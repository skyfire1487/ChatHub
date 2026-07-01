# ChatHub 領域知識入門（給完全新手）

> 這份文件用**白話 + 生活比喻**解釋 ChatHub 用到的網路與並發名詞（Socket、loopback、TCP、埠、NAT、執行緒、心跳…），
> 而且每個概念都對應到專案裡**實際的程式位置**，讓你看得懂也找得到。
> 只想「把它跑起來」看 [TEACHING.md](TEACHING.md)；想看協定規格看 [PROTOCOL.md](PROTOCOL.md)。

---

## 0. 先建立一個全貌比喻

把一場聊天室想成**一通電話會議**：

- 有人先「開一條專線並公布電話號碼」→ 這是 **伺服器（Server / Host）**。
- 其他人「打這個號碼進來」→ 這是 **客戶端（Client）**。
- 電話號碼 = **IP 位址**；分機號碼 = **埠（Port）**；接通後的那條線 = **Socket**。
- 主持人把任何人講的話「轉播給所有人聽」= **廣播（broadcast）**。
- 每隔一下問一聲「還在嗎？」確認沒斷線 = **心跳（heartbeat）**。

下面把每個名詞拆開講。

---

## 1. 主從式架構（Client–Server）

網路程式最常見的分工：一邊「等別人來連」，一邊「主動去連別人」。

- **Server（伺服器）**：先啟動、待命，等待連線。ChatHub 的 Host 就是 Server。
- **Client（客戶端）**：主動發起連線去找 Server。ChatHub 的 Join 端就是 Client。

> ChatHub 特別之處：**同一支 App 兩種角色都能當**（進來選 Host / Join）。
> 對應：[`ChatServer`](../app/src/main/java/com/example/chathub/net/ChatServer.java)（伺服器）、
> [`ChatClient`](../app/src/main/java/com/example/chathub/net/ChatClient.java)（客戶端）。

---

## 2. IP 位址、localhost 與 **loopback**（最常被搞混）

**IP 位址**是一台機器在網路上的「門牌號碼」，例如 `192.168.0.12`。別人要連你，得知道你的 IP。

### localhost / loopback = `127.0.0.1`（「自己」）

`127.0.0.1` 是一個特別的 IP，叫 **loopback（回送位址）**，代表「**這台機器自己**」。
你連 `127.0.0.1` 時，封包根本不會出網路卡，會在系統內部**繞回自己**——就像**打電話給自己家的分機**，訊號沒出門。

- 用途：同一台機器上的兩個程式互連、本機測試很方便。
- **陷阱（本專案很重要）**：`127.0.0.1` 對「別人」沒有意義。你叫朋友連你的 `127.0.0.1`，
  他連到的是**他自己**，不是你。所以要讓別人連進來，必須給的是**區網 IP（如 `192.168.x.x`）**，不是 loopback。

> 這就是為什麼 ChatHub 的 Host 設定頁**不顯示 127.0.0.1**，而是用
> [`NetworkUtils.getLocalIpAddress()`](../app/src/main/java/com/example/chathub/util/NetworkUtils.java)
> 掃出「非 loopback 的區網 IPv4」給別人連。程式裡也**刻意排除 loopback**（`!addr.isLoopbackAddress()`）。

### 模擬器的特例：`10.0.2.2`

Android 模擬器裡有一組保留位址，其中 **`10.0.2.2` = 「模擬器眼中的『開發電腦』的 loopback」**。
也就是：在模擬器 App 裡連 `10.0.2.2`，等於連到「你正在打字的這台電腦本身」。第 6 節會說明它怎麼用。

---

## 3. 埠（Port）

一台機器上會同時跑很多網路程式（瀏覽器、遊戲、聊天室…），IP 只指到「哪台機器」，
**埠**再指「這台機器上的哪個程式」——像大樓的**門牌是 IP、分機/房號是 Port**。

- 範圍 `0~65535`。其中 `0~1023` 是系統保留（HTTP 80、HTTPS 443…），一般程式用 **`1024~65535`**。
- ChatHub 預設用 **8888**（好記、不易衝突）。

> 對應：[`Constants.DEFAULT_PORT = 8888`](../app/src/main/java/com/example/chathub/Constants.java)；
> 設定頁也會驗證輸入的埠落在 1024~65535。

---

## 4. Socket 是什麼？（本專案的主角）

**Socket（通訊端點）**就是「一條已經接通的網路管線的兩端」。程式透過它**讀（收）**和**寫（送）**資料，
就像水管的一端——你從這頭倒水（送資料），對方那頭流出來（收資料）。

Java 有兩個關鍵類別：

| 類別 | 角色 | 白話 |
|---|---|---|
| `ServerSocket` | 伺服器的「總機」 | 綁在某個埠上**等電話**。`accept()` 會一直**阻塞等待**，直到有人打進來，然後給你一條專屬的 `Socket` |
| `Socket` | 一條「已接通的線」 | 客戶端用它主動撥號連 Server；Server 端每接到一個客戶端也會得到一條 `Socket` 代表「這條連線」 |

流程：

```
Server: ServerSocket(8888) → accept() 等待… → 有人連 → 得到一條 Socket（代表那個人）
Client: new Socket(對方IP, 8888) → 接通 → 得到一條 Socket（代表這條連線）
接通後，雙方各自從 socket 拿 InputStream/OutputStream 來收/送資料
```

> 對應：[`ChatServer.acceptLoop()`](../app/src/main/java/com/example/chathub/net/ChatServer.java) 的
> `serverSocket.accept()`；每條連線交給一個
> [`ClientHandler`](../app/src/main/java/com/example/chathub/net/ClientHandler.java) 處理；
> 客戶端在 [`ChatClient.openConnection()`](../app/src/main/java/com/example/chathub/net/ChatClient.java) 建立 `Socket`。

---

## 5. TCP vs UDP，以及「訊息沒有邊界」的問題

網路傳輸有兩種主要方式：

| | **TCP**（本專案用） | UDP |
|---|---|---|
| 特性 | 可靠、**有序**、像打電話（先接通再講，保證送到、順序不亂） | 快但不保證送到、不保證順序，像寄明信片 |
| 適合 | 聊天訊息（不能掉字、不能亂序） | 直播串流、遊戲即時位置（掉一點沒差） |

ChatHub 要「訊息不掉、順序正確」，所以用 **TCP**。

### 但 TCP 有個坑：它是「位元組串流」，**沒有訊息邊界**

TCP 只保證「位元組照順序到」，但**不告訴你一則訊息從哪開始到哪結束**。
你送三句話，對方可能一次全收到、或分五次才收齊——像**水管裡的水連成一條，沒有分裝**。

**解法：分幀（framing）。** ChatHub 的規則：**每則訊息 = 一行 JSON，結尾加一個換行 `\n`**；
收端用 `readLine()` 一次讀「一整行 = 一則訊息」。

> 為什麼安全？`JSONObject.toString()` 會把內容裡的換行**轉義**成字面 `\n`，輸出永遠是單一行，不會誤切。
> 對應：[`ChatProtocol`](../app/src/main/java/com/example/chathub/net/ChatProtocol.java) 的說明與各 `send()`。
> 更完整見 [PROTOCOL.md 第 1 節](PROTOCOL.md#1-為什麼需要自訂協定)。

---

## 6. 區域網路（LAN）、NAT，與「模擬器為何要 10.0.2.2 + adb forward」

**區域網路（LAN）**：同一個 Wi-Fi / 路由器底下的機器，彼此能用區網 IP（`192.168.x.x`）直接互連。
→ **真機測試**：兩支手機連同一 Wi-Fi，Client 填 Host 手機顯示的區網 IP 就能連，很單純。

**NAT（網路位址轉換）**：路由器讓內網多台機器共用一個對外 IP 的機制。
它造成一個結果：**Android 模擬器各自躲在自己的 NAT 後面，彼此無法直接用 IP 互連**（這是模擬器的天生限制）。

**所以模擬器互測要繞一下**（真機不用）：

```
Client 模擬器 ──連 10.0.2.2:8888──▶ 開發電腦:8888 ──adb forward──▶ Host 模擬器:8888
        (模擬器看電腦的 loopback)          (adb 把電腦的埠轉給 Host 模擬器)
```

- `10.0.2.2`：模擬器內連它 = 連到「開發電腦本身」（見第 2 節）。
- `adb forward tcp:8888 tcp:8888`：把「開發電腦的 8888」接到「Host 模擬器的 8888」。
- 兩者合起來，Client 模擬器就能「經過電腦中轉」連到 Host 模擬器。

> 指令步驟見 [TEACHING.md 第 3 節階段 B](TEACHING.md#階段-b--兩台模擬器互連的關鍵設定最容易卡的地方)。

---

## 7. 執行緒（Thread）、阻塞，與「網路為何一定要背景執行緒」

**執行緒**是程式裡「同時在跑的一條工作線」。App 有一條特別的 **UI 主執行緒（main thread）**，
專門負責畫面與回應點擊。**規則：主執行緒不能被卡住**，卡超過幾秒 App 就會「無回應（ANR）」。

**阻塞（blocking）**：有些操作會「停在那裡等」。例如 `accept()` 等連線、`readLine()` 等對方送資料——
它們會**一直卡住直到有結果**。如果把它們放在主執行緒，畫面就凍住了。

**所以網路操作一律放背景執行緒**，收到結果再切回主執行緒改畫面。

- Android 甚至直接禁止在主執行緒做網路 IO，違反會丟 **`NetworkOnMainThreadException`**（本專案開發時真的踩過、已修）。

> 對應：伺服器 accept、每條連線的讀都在各自執行緒；**所有送出**走
> [`ChatServer`](../app/src/main/java/com/example/chathub/net/ChatServer.java) 的 `io` 執行緒、
> [`ChatClient`](../app/src/main/java/com/example/chathub/net/ChatClient.java) 的 `sender` 執行緒；
> 更新畫面則用主執行緒 `Handler` 轉回（Activity 收到回呼時已在主執行緒）。

---

## 8. 廣播（broadcast / fan-out）

聊天室的核心動作：**一個人說的話，要送給所有在線的人**。
伺服器收到某人的 `MSG`，就對「每一條連線」各送一份——這種「一對多分送」叫 **fan-out**。

> ChatHub 讓伺服器把訊息廣播給**所有人（含原發話者）**，這樣每台裝置看到的順序都一致
> （由伺服器當「唯一順序來源」）。對應：[`ChatServer.broadcast()`](../app/src/main/java/com/example/chathub/net/ChatServer.java)。

---

## 9. 心跳（heartbeat）與延遲（RTT）

**問題**：連線可能「悄悄斷掉」——對方 App 被系統殺掉、Wi-Fi 突然斷，socket 不一定會馬上通知你。
若不處理，「在線名單」就會顯示已經離線的人。

**心跳**：每隔幾秒互相送一個小訊號（`PING`），對方回一個（`PONG`）確認「我還活著」。
超過一段時間沒回應，就判定掉線、把他移出名單。

**RTT（Round-Trip Time，來回延遲）**：`PING` 帶出「送出當下的時間」，對方原樣回傳；
收到回應時用「現在時間 − 當初時間」＝一來一回花了多久，就是**延遲**（ChatHub 顯示「延遲 x ms」）。

> 對應：伺服器
> [`ChatServer.heartbeatTick()`](../app/src/main/java/com/example/chathub/net/ChatServer.java)（逾時移除）；
> 客戶端 [`ChatClient`](../app/src/main/java/com/example/chathub/net/ChatClient.java) 的 `PONG` 分支算 RTT。

---

## 10. JSON 與序列化

**JSON** 是一種通用的資料文字格式，例如 `{"type":"MSG","name":"Bob","body":"hi"}`。
**序列化**＝把「程式裡的物件」變成「可以在網路上傳的文字」；**解析**＝反過來把文字變回物件。

ChatHub 用 JSON 當「訊息的共同語言」：Host 和 Client 各寫各的 Java，但只要都遵守同一份 JSON 約定就能溝通。
用的是 Android 內建的 `org.json`（免額外套件）。

> 對應：[`ChatProtocol`](../app/src/main/java/com/example/chathub/net/ChatProtocol.java) 集中所有打包/解析。

---

## 11. 優雅關閉（graceful shutdown）

「關掉」不能只是「拔線」，否則對方會收到一堆錯誤。**優雅關閉**＝離開前**先好好說再見**。

- **Client 離開**：關閉自己的 socket → 伺服器 `readLine()` 收到 `null`（正常結束訊號）→ 把他移出名單、廣播「X 離開」。
- **Host 關閉**：**先廣播 `SERVER_CLOSING`**（告訴大家「我要關了」）→ 再關所有連線 →
  Client 收到後顯示「伺服器已關閉」並平順離開，**不跳錯誤、不崩潰**。

> 對應：[`ChatClient.leave()`](../app/src/main/java/com/example/chathub/net/ChatClient.java)、
> [`ChatServer.shutdown()`](../app/src/main/java/com/example/chathub/net/ChatServer.java)。

---

## 12. 名詞速查表

| 名詞 | 一句話 |
|---|---|
| Client / Server | 主動連別人 / 待命等別人連 |
| IP 位址 | 機器在網路上的門牌號碼 |
| **loopback / 127.0.0.1** | 「自己」的特殊 IP，封包繞回本機；**別人連不到你的 loopback** |
| **10.0.2.2** | 模擬器眼中「開發電腦」的位址（模擬器測試用） |
| Port（埠） | 指到機器上的哪個程式；一般用 1024~65535 |
| Socket | 一條接通的網路管線的一端，用來收送資料 |
| ServerSocket | 伺服器的總機，`accept()` 等連線 |
| TCP | 可靠、有序的傳輸（像打電話）；本專案用它 |
| 分幀 framing | 因 TCP 無訊息邊界，用 `\n` 切出「一則訊息」 |
| LAN / NAT | 區域網路 / 讓內網共用對外 IP 的機制（造成模擬器不能直連） |
| 執行緒 Thread | 同時在跑的一條工作線；網路要放背景、別卡 UI |
| 阻塞 blocking | 停在那裡等結果的操作（如 `accept`/`readLine`） |
| 廣播 fan-out | 把一則訊息分送給所有連線 |
| 心跳 heartbeat | 定時互探「還在嗎」，偵測掉線 |
| RTT | 訊息一來一回的延遲時間 |
| JSON / 序列化 | 傳輸用的文字格式 / 物件⇄文字的轉換 |
| 優雅關閉 | 離開前先通知對方，不讓對端報錯 |

---

看完這份，再回頭讀 [PROTOCOL.md](PROTOCOL.md) 的時序圖與 `net/` 底下的原始碼註解，
「整個 Socket 技術流程」就能從頭到尾串起來。
