# ChatHub 網路與連線架構

> 本文件說明 ChatHub 使用的 IP 定址、TCP Socket、訊息分幀、NAT、並發處理與心跳機制，
> 並將各項網路概念對應至實際程式模組。建置與裝置連線步驟見 [OPERATIONS.md](OPERATIONS.md)；
> 訊息格式與生命週期見 [PROTOCOL.md](PROTOCOL.md)。

---

## 0. 系統通訊模型

ChatHub 採用 Host／Client 架構。Host 建立 TCP 監聽端點、管理連入的 Client 連線並負責訊息廣播；Client 以 Host 的 IP 與埠號建立連線。雙方以 newline-delimited JSON 交換事件，並以心跳監測連線狀態。

```text
Client Socket ── JSON Lines over TCP ──> Host ServerSocket
                                             ├─ ClientHandler
                                             ├─ Member Roster
                                             └─ Room Broadcast
```

---

## 1. 主從式架構（Client–Server）

Client–Server 架構將連線責任分為服務端與客戶端：服務端建立監聽端點，客戶端主動建立連線。

- **Server（伺服器端）**：建立 TCP 監聽端點並接受連線；ChatHub 的 Host 承擔此角色。
- **Client（客戶端）**：以伺服器位址與埠號發起連線；ChatHub 的 Join 端承擔此角色。

> **同一個 Android App 支援 Host 與 Client 兩種角色**，使用者可在首頁選擇建立或加入聊天室。
> 對應：[`ChatServer`](../app/src/main/java/com/example/chathub/net/ChatServer.java)（伺服器）、
> [`ChatClient`](../app/src/main/java/com/example/chathub/net/ChatClient.java)（客戶端）。

---

## 2. IP 位址、localhost 與 **loopback**

**IP 位址**是網路端點的位址，例如區域網路中的 `192.168.0.12`。Client 需要 Host 可連線的 IP 與埠號，才能建立 TCP 連線。

### localhost 與 loopback 位址 `127.0.0.1`

`127.0.0.1` 是 **loopback（回送位址）**，指向目前裝置的網路堆疊。傳送至此位址的封包會在本機迴送，不會抵達其他裝置。

- 用途：同一台裝置上的程式互連與本機測試。
- **連線範圍**：另一台裝置若連至 `127.0.0.1`，會連到該裝置本身，而非 Host。區域網路 Client 必須使用 Host 的區網 IP。

> 因此 ChatHub 的 Host 設定頁**不顯示 127.0.0.1**，而是透過
> [`NetworkUtils.getLocalIpAddress()`](../app/src/main/java/com/example/chathub/util/NetworkUtils.java)
> 選取非 loopback 的區域網路 IPv4 位址供 Client 連線。實作也會**排除 loopback**（`!addr.isLoopbackAddress()`）。

### 模擬器的特例：`10.0.2.2`

Android Emulator 保留 `10.0.2.2` 作為模擬器連至開發主機 loopback 的位址。
在 Android Emulator 中連線至 `10.0.2.2`，會抵達執行模擬器的開發主機。第 6 節說明此位址在 ChatHub 模擬器連線中的用途。

---

## 3. 埠（Port）

一台裝置可同時提供多個網路服務；IP 位址識別主機，**埠（Port）**則識別主機上提供服務的應用程式端點。ChatHub 預設監聽 TCP 埠 8888，Client 必須使用相同埠號。

- 範圍 `0~65535`。其中 `0~1023` 是系統保留（HTTP 80、HTTPS 443…），一般程式用 **`1024~65535`**。
- ChatHub 預設監聽埠為 **8888**。

> 對應：[`Constants.DEFAULT_PORT = 8888`](../app/src/main/java/com/example/chathub/Constants.java)；
> 設定頁也會驗證輸入的埠落在 1024~65535。

---

## 4. Socket 連線端點

**Socket（通訊端點）**代表已建立的網路連線端點。程式透過 input/output stream 讀取與寫入資料；TCP 連線兩端各自持有對應的 Socket。

Java 有兩個關鍵類別：

| 類別 | 角色 | 行為 |
|---|---|---|
| `ServerSocket` | 伺服器監聽端點 | 綁定埠號並等待連線；`accept()` 阻塞至新連線建立，之後回傳該連線的 `Socket` |
| `Socket` | TCP 連線端點 | Client 使用 `Socket` 連至 Host；Host 接受連線後也取得對應的 `Socket`，供該連線收送資料 |

流程：

```
Server: ServerSocket(8888) → accept() waits → accepted Socket → InputStream/OutputStream
Client: new Socket(hostIp, 8888) → connected Socket → InputStream/OutputStream
```

> 對應：[`ChatServer.acceptLoop()`](../app/src/main/java/com/example/chathub/net/ChatServer.java) 的
> `serverSocket.accept()`；每條連線交給一個
> [`ClientHandler`](../app/src/main/java/com/example/chathub/net/ClientHandler.java) 處理；
> 客戶端在 [`ChatClient.openConnection()`](../app/src/main/java/com/example/chathub/net/ChatClient.java) 建立 `Socket`。

---

## 5. TCP 與 UDP，以及訊息分幀

網路傳輸有兩種主要方式：

| | **TCP**（本專案用） | UDP |
|---|---|---|
| 特性 | 可靠、有序的位元組串流；連線正常時依序交付資料 | 無連線資料報；不保證交付與順序 |
| 適合 | 需要可靠、有序傳輸的雙向聊天訊息 | 可容忍遺失且重視低延遲的即時資料 |

ChatHub 使用 **TCP** 傳送聊天資料，利用可靠、有序的連線語意維持訊息流。

### TCP 是位元組串流，**沒有訊息邊界**

TCP 維持位元組順序，但不保留應用程式每次 `write()` 呼叫的邊界。多次寫入的資料可能由接收端一次讀取，也可能分批到達，因此應用層必須定義訊息邊界。

**解法：分幀（framing）。** ChatHub 的規則：**每則訊息 = 一行 JSON，結尾加一個換行 `\n`**；
收端用 `readLine()` 一次讀「一整行 = 一則訊息」。

> 分幀正確性：`JSONObject.toString()` 會將字串中的換行**轉義**為字面 `\n`，使每個 JSON 物件維持在單一物理行。
> 對應：[`ChatProtocol`](../app/src/main/java/com/example/chathub/net/ChatProtocol.java) 的說明與各 `send()`。
> 更完整見 [PROTOCOL.md 第 1 節](PROTOCOL.md#1-為什麼需要自訂協定)。

---

## 6. 區域網路（LAN）、NAT 與 Android 模擬器連線轉送

**區域網路（LAN）**：連接至同一區域網路的裝置，可透過區網 IP（`192.168.x.x`）彼此通訊。
→ **真機連線**：Host 與 Client 連上同一個 Wi-Fi 後，Client 使用 Host 設定頁顯示的區網 IP 與埠號。

**NAT（網路位址轉換）**：路由器讓內網多台機器共用一個對外 IP 的機制。
由於每個 Android Emulator 都位於獨立 NAT 網段，**兩台模擬器無法直接透過彼此的內部 IP 建立連線**。

模擬器互連時，使用開發主機作為 TCP forwarding 中繼；真機同網路連線不需要此設定：

```
Client 模擬器 ──連 10.0.2.2:8888──▶ 開發主機:8888 ──adb forward──▶ Host 模擬器:8888
        (10.0.2.2 指向開發主機)              (adb 將主機埠轉送至 Host 模擬器)
```

- `10.0.2.2`：Android Emulator 預留給 Client 存取開發主機的位址（見第 2 節）。
- `adb forward tcp:8888 tcp:8888`：將開發主機的 TCP 8888 轉送至 Host 模擬器的 TCP 8888。
- 設定完成後，Client 模擬器連至開發主機的 8888，即可到達 Host 模擬器上的服務。

> 完整設定步驟見 [OPERATIONS.md](OPERATIONS.md)。

---

## 7. 執行緒（Thread）與阻塞式 I/O

**執行緒**是程式可獨立排程的執行單位。Android App 的 **UI 主執行緒（main thread）**負責繪製畫面與處理互動，因此不能執行長時間阻塞工作，否則可能觸發無回應（ANR）。

**阻塞（blocking）**：呼叫端會等待外部事件或資料的操作。例如 `accept()` 等候新連線、`readLine()` 等候下一則訊息；若在主執行緒呼叫，畫面更新與互動也會一併停滯。

ChatHub 將網路操作配置在背景執行緒，並透過主執行緒 Handler 更新 UI。

- Android 對主執行緒的同步網路 I/O 會拋出 **`NetworkOnMainThreadException`**。

> 對應：伺服器 accept、每條連線的讀都在各自執行緒；**所有送出**走
> [`ChatServer`](../app/src/main/java/com/example/chathub/net/ChatServer.java) 的 `io` 執行緒、
> [`ChatClient`](../app/src/main/java/com/example/chathub/net/ChatClient.java) 的 `sender` 執行緒；
> 更新畫面則用主執行緒 `Handler` 轉回（Activity 收到回呼時已在主執行緒）。

---

## 8. 訊息廣播（broadcast / fan-out）

聊天室的核心傳輸模式是**一對多訊息分送**。伺服器收到 `MSG` 後，依目前連線名單逐一送出；此模式稱為 **fan-out**。

> ChatHub 讓伺服器把訊息廣播給**所有人（含原發話者）**，這樣每台裝置看到的順序都一致
> （由伺服器當「唯一順序來源」）。對應：[`ChatServer.broadcast()`](../app/src/main/java/com/example/chathub/net/ChatServer.java)。

---

## 9. 心跳（heartbeat）與延遲（RTT）

**連線中斷偵測**：對端 App 結束或網路中斷時，TCP Socket 不一定能立即回報失效，因此需以週期性心跳監測連線狀態。
若不處理，「在線名單」就會顯示已經離線的人。

**心跳**：連線端依排程傳送 `PING`，接收端以 `PONG` 回應。逾時未收到預期回應時，伺服器判定連線失效並更新成員名單。

**RTT（Round-Trip Time，來回延遲）**：`PING` 帶出「送出當下的時間」，對方原樣回傳；
收到回應時以目前時間減去原始時間戳，估算封包往返所需時間；ChatHub 以「延遲 x ms」顯示 RTT。

> 對應：伺服器
> [`ChatServer.heartbeatTick()`](../app/src/main/java/com/example/chathub/net/ChatServer.java)（逾時移除）；
> 客戶端 [`ChatClient`](../app/src/main/java/com/example/chathub/net/ChatClient.java) 的 `PONG` 分支算 RTT。

---

## 10. JSON 與序列化

**JSON** 是一種通用的資料文字格式，例如 `{"type":"MSG","name":"Bob","body":"hi"}`。
**序列化**＝把「程式裡的物件」變成「可以在網路上傳的文字」；**解析**＝反過來把文字變回物件。

Host 與 Client 依同一組 JSON 訊息格式交換事件；序列化與解析使用 Android 內建的 `org.json`。

> 對應：[`ChatProtocol`](../app/src/main/java/com/example/chathub/net/ChatProtocol.java) 集中所有打包/解析。

---

## 11. 優雅關閉（graceful shutdown）

**優雅關閉**會先通知連線對端，再關閉 Socket 並完成成員狀態清理，避免將預期中的結束流程呈現為連線錯誤。

- **Client 離開**：關閉 Socket → 伺服器 `readLine()` 收到 `null`（正常結束訊號）→ 移除成員並廣播離開事件。
- **Host 關閉**：**先廣播 `SERVER_CLOSING`** → 再關閉所有連線。Client 收到事件後顯示伺服器關閉狀態並結束聊天室流程。

> 對應：[`ChatClient.leave()`](../app/src/main/java/com/example/chathub/net/ChatClient.java)、
> [`ChatServer.shutdown()`](../app/src/main/java/com/example/chathub/net/ChatServer.java)。

---

## 12. 名詞速查表

| 名詞 | 說明 |
|---|---|
| Client / Server | 主動建立連線 / 接受連線 |
| IP 位址 | 網路端點的位址 |
| **loopback / 127.0.0.1** | 指向目前主機本身的回送位址；其他裝置以此位址連線時會指向自身 |
| **10.0.2.2** | Android Emulator 提供給模擬器使用、指向開發主機的特殊位址 |
| Port（埠） | 指定主機上的服務端點；應用程式通常使用 1024–65535 範圍 |
| Socket | 已建立的 TCP 連線端點，用來收送資料 |
| ServerSocket | 伺服器的 TCP 監聽端點，`accept()` 接受新連線 |
| TCP | 可靠、有序的位元組串流；本專案的傳輸層 |
| 分幀 framing | 因 TCP 不保留訊息邊界，以 `\n` 定義應用層訊息界線 |
| LAN / NAT | 區域網路 / 讓內網共用對外 IP 的位址轉換機制 |
| 執行緒 Thread | 可獨立執行的工作單位；阻塞網路 I/O 置於背景執行緒 |
| 阻塞 blocking | 等待連線或資料完成的操作，例如 `accept()` 與 `readLine()` |
| 廣播 fan-out | 將一則訊息分送給所有連線 |
| 心跳 heartbeat | 週期性 PING/PONG，用於偵測失效連線 |
| RTT | 訊息往返的延遲時間 |
| JSON / 序列化 | 傳輸用的文字格式 / 物件⇄文字的轉換 |
| 優雅關閉 | 關閉連線前通知對端，並完成必要的狀態清理 |

---

搭配 [PROTOCOL.md](PROTOCOL.md) 的生命週期與 `net/` 模組實作，可追蹤從 TCP 連線建立、JSON 交換至資源關閉的完整流程。
