# ChatHub 應用層通訊協定（JSON over TCP）

> 這份文件規格化 ChatHub 在「原生 TCP Socket」之上自訂的訊息協定。
> 本文件定義 Host 與 Client 間的訊息格式、連線生命週期與並發傳輸約定。
> 對應程式：[`ChatProtocol.java`](../app/src/main/java/com/example/chathub/net/ChatProtocol.java)（單一事實來源）。

## 1. 為什麼需要自訂協定

TCP 提供可靠、有序的**位元組串流**，但不保留應用程式每次 `write()` 呼叫的邊界。多次寫入的資料可能由接收端一次讀取，也可能分批到達。
因此，應用層必須自行定義一則訊息的起點與終點。ChatHub 採用以下規則：

1. **每一則訊息 = 一個 JSON 物件**，必含 `type` 欄位標明種類。
2. **以換行字元 `\n` 分幀（framing）**：送出時在 JSON 字串後接一個 `\n`；
   收端用 `BufferedReader.readLine()` 一次讀「一整行 = 一則訊息」。

### 為什麼 newline 分幀是安全的

JSON 字串內若含有換行，`JSONObject.toString()` 會把它**轉義**成字面的 `\n`（反斜線 + n），
輸出永遠是**單一物理行**。因此只要遵守「送出前一律 `toString()` 序列化」，
訊息內容裡的換行不會破壞分幀。這是本協定唯一要守的紀律。

## 2. 共同欄位

| 欄位 | 型別 | 說明 |
|---|---|---|
| `type` | string | 訊息種類，必填。值見下表。 |
| `ts` | number | 毫秒時間戳（部分訊息攜帶）。 |

## 3. 訊息種類

| `type` | 方向 | 欄位 | 用途 |
|---|---|---|---|
| `JOIN` | 客 → 伺 | `name`, `room` | 連上後自報暱稱與房間（核心為單一房間 `main`）。 |
| `ROSTER` | 伺 → 客 | `members`(string[]) | 在線成員清單；每次有人進出就**全量**推送，客戶端直接覆蓋顯示。 |
| `MSG` | 雙向 | `name`, `body`, `ts` | 聊天訊息。客戶端送給伺服器，伺服器**廣播**給所有人（含原送者）。 |
| `SYSTEM` | 伺 → 客 | `body`, `ts` | 系統訊息（X 加入/離開、公告）。 |
| `TYPING` | 雙向 | `name`, `typing`(bool) | 打字中狀態。 |
| `PING` | 雙向 | `ts` | 心跳探測。攜帶送出當下時間。 |
| `PONG` | 雙向 | `ts` | 心跳回應。把 `PING` 的 `ts` 原樣回傳，送方相減即得 RTT。 |
| `SERVER_CLOSING` | 伺 → 客 | `reason` | 伺服器即將關閉，請客戶端優雅收尾（非錯誤）。 |
| `ERROR` | 伺 → 客 | `code`, `msg` | 錯誤（保留欄位，例如未來的暱稱重複）。 |

### 範例（每行一則，實際傳輸時各行尾接 `\n`）

```json
{"type":"JOIN","name":"Bob","room":"main"}
{"type":"ROSTER","members":["Alice（主持）","Bob"]}
{"type":"MSG","name":"Bob","body":"Hi Alice","ts":1751340000000}
{"type":"SYSTEM","body":"Bob 加入了聊天室","ts":1751340000001}
{"type":"TYPING","name":"Bob","typing":true}
{"type":"PING","ts":1751340005000}
{"type":"PONG","ts":1751340005000}
{"type":"SERVER_CLOSING","reason":"伺服器已關閉聊天室"}
```

## 4. 完整連線生命週期（時序）

```
Client                                   Server (Host)
  │                                         │  ServerSocket(port).accept() 阻塞中
  │  TCP connect ──────────────────────────▶│  accept() 返回 → new ClientHandler → 起讀執行緒
  │  {JOIN, name, room} ───────────────────▶│  記下暱稱
  │◀──────────── (補送近 N 筆歷史 MSG/SYSTEM)│  sendHistoryTo()
  │◀──────────── {SYSTEM "X 加入"} ──(廣播)──│  broadcastSystem()
  │◀──────────── {ROSTER [...]} ────(廣播)──│  broadcastRoster()
  │                                         │
  │  {MSG body} ───────────────────────────▶│  存歷史 → 廣播給所有連線（fan-out）
  │◀──────────── {MSG body} ────────(廣播)──│  （含原送者，確保所有人順序一致）
  │                                         │
  │  {PING ts} ────────────────────────────▶│  回 {PONG ts}
  │◀──────────── {PONG ts} ─────────────────│  Client 算 RTT 顯示「延遲 x ms」
  │◀──────────── {PING ts}  (伺服器心跳)─────│  Server 定時探測；逾時沒回應→移除該成員
  │  {PONG ts} ────────────────────────────▶│  更新該連線 lastSeen
  │                                         │
  │  (使用者離開) TCP close ─────────────────▶│  readLine() 回 null → 移除成員
  │                                         │  → 廣播 {SYSTEM "X 離開"} + {ROSTER}
  │                                         │
  │◀──────────── {SERVER_CLOSING} ──(廣播)──│  Host 關閉 → 先廣播再關所有連線
  │  顯示「伺服器已關閉」，優雅離開（不報錯）  │
```

## 5. 並發與執行緒模型

**伺服器（Host）**
- 1 條 `accept` 執行緒，阻塞在 `ServerSocket.accept()`。
- 每個客戶端 1 條讀執行緒（`ClientHandler.run` 的 `readLine` 迴圈）。
- 1 個 `ScheduledExecutorService` 跑心跳。
- 1 個單執行緒 `io` 負責**所有 socket 寫出**（廣播/歷史/關閉）：
  既避免在 UI 主執行緒做網路 IO，又保證送出順序一致（FIFO）。
- 成員清單用 `CopyOnWriteArrayList`：遍歷廣播時不怕別執行緒同時增刪。

**客戶端（Client）**
- 1 條管理執行緒：`連線 → 讀迴圈 → (斷線) → 指數退避 → 重連` 的循環。
- 送出走專一的 `sender` 單執行緒（不在 UI 主執行緒做網路 IO）。
- 1 個排程器跑客戶端心跳（量 RTT 兼保活）。

**共同紀律**
- `readLine()` 是阻塞函式，一定獨立執行緒；回 `null` = 對端關閉，當作正常事件處理。
- 同一條連線的寫出用 `writeLock` 同步，避免兩則訊息字元交錯破壞分幀。
- 串流以集中的 `closeQuietly`/`try-with` 關閉，例外中途也保證關閉、不洩漏。
- 一律 UTF-8 編碼，中文不亂碼。

## 6. 對應原始碼

| 概念 | 檔案 |
|---|---|
| 協定定義/打包/解析 | [`net/ChatProtocol.java`](../app/src/main/java/com/example/chathub/net/ChatProtocol.java) |
| 伺服器（accept/廣播/心跳/關閉） | [`net/ChatServer.java`](../app/src/main/java/com/example/chathub/net/ChatServer.java) |
| 伺服器端單一連線的讀寫 | [`net/ClientHandler.java`](../app/src/main/java/com/example/chathub/net/ClientHandler.java) |
| 客戶端（連線/讀/送/心跳/重連） | [`net/ChatClient.java`](../app/src/main/java/com/example/chathub/net/ChatClient.java) |
