# ChatHub 教學文件（從零跑通 + 程式講解 + 錯誤排查）

> 這份文件讓沒參與開發的人也能**自己跑通、看懂、接手**這個專案。
> **完全新手請先讀 [CONCEPTS.md](CONCEPTS.md)**（用白話 + 生活比喻介紹 Socket、loopback、TCP、埠、NAT、執行緒、心跳等領域知識）。
> 協定規格見 [PROTOCOL.md](PROTOCOL.md)；功能總覽見 [../README.md](../README.md)。

---

## 1. 這是什麼、為什麼這樣設計

**ChatHub** 是一個 Android 區域網路即時群播聊天室。它**不用** WebSocket / Socket.IO / Firebase，
刻意以**原生 TCP `Socket` / `ServerSocket`** 搭配**自訂 JSON 協定**實作，目的是完整展示
「Socket 連線 → 多執行緒並發 → 訊息分幀 → 廣播 → 心跳保活 → 優雅關閉」這一整條技術流程。

### 三層職責

| 層 | 角色 | 負責 |
|---|---|---|
| **UI 層**（Activity/Adapter） | 畫面 | 收集輸入、顯示訊息/成員/狀態；完全不碰 socket 細節 |
| **網路層**（`net/`） | 通訊 | 開/連 socket、收送 JSON、廣播、心跳、重連 |
| **協定層**（`ChatProtocol`） | 約定 | 定義每種訊息長怎樣、怎麼打包/解析 |

### 資料怎麼流動（一句話版）

> Host 開 `ServerSocket` 監聽 → Client `Socket` 連入並送 `JOIN` → 任何人送 `MSG`，
> Server 廣播給所有人 → 各端把 JSON 轉成畫面泡泡。連線狀態用 `PING/PONG` 心跳維持與量測。

完整時序圖見 [PROTOCOL.md 第 4 節](PROTOCOL.md#4-完整連線生命週期時序)。

---

## 2. 檔案清單（**新建** vs **改預設**）

> 「改預設」= Android Studio 建專案時自動產生、我們改寫內容的檔；「新建」= 我們從零加的檔。

### Java（`app/src/main/java/com/example/chathub/`）

| 檔案 | 新建/改預設 | 職責 |
|---|---|---|
| `MainActivity.java` | **改預設** | 首頁：輸入暱稱、選 Host/Join |
| `Constants.java` | 新建 | 全專案設定常數（埠、心跳、退避…） |
| `net/ChatProtocol.java` | 新建 | **協定單一事實來源**：型別常數 + JSON 打包/解析 |
| `net/ChatServer.java` | 新建 | 伺服器：accept 迴圈、廣播、roster、歷史、心跳、優雅關閉 |
| `net/ClientHandler.java` | 新建 | 伺服器端「單一連線」的讀執行緒與寫出 |
| `net/ChatClient.java` | 新建 | 客戶端：連線、讀迴圈、送出、心跳量 RTT、自動重連 |
| `net/ConnectionState.java` | 新建 | 連線狀態列舉（連線中/已連線/重連中/已斷線） |
| `model/ChatMessage.java` | 新建 | UI 訊息模型（自己/他人/系統三型別） |
| `model/Member.java` | 新建 | 成員模型 |
| `adapter/MessageAdapter.java` | 新建 | 訊息清單轉接器（3 種泡泡 view type） |
| `adapter/MemberAdapter.java` | 新建 | 成員清單轉接器 |
| `ui/HostSetupActivity.java` | 新建 | 建立聊天室設定頁（顯示本機 IP、設埠） |
| `ui/JoinSetupActivity.java` | 新建 | 加入聊天室設定頁（輸入 IP/埠） |
| `ui/ChatActivity.java` | 新建 | 聊天主畫面（Host/Client 共用） |
| `util/NetworkUtils.java` | 新建 | 取本機 IP、host 檢查 |
| `util/TimeFormat.java` | 新建 | 時間格式化（`SimpleDateFormat`，相容 minSdk 25） |
| `util/AvatarUtils.java` | 新建 | 暱稱 → 一致色 / 首字頭像 |

### 資源與設定

| 檔案 | 新建/改預設 | 說明 |
|---|---|---|
| `res/layout/activity_main.xml` | **改預設** | 首頁版面（保留 root id `main` 供 inset） |
| `res/layout/activity_host_setup.xml`、`activity_join_setup.xml`、`activity_chat.xml` | 新建 | 三頁版面 |
| `res/layout/item_message_self/other/system.xml`、`item_member.xml` | 新建 | 清單項目版面 |
| `res/values/colors.xml`、`themes.xml`、`values-night/themes.xml` | **改預設** | 套 ChatHub 品牌色 |
| `res/values/strings.xml` | **改預設** | 集中文字 |
| `res/values/styles.xml` | 新建 | 按鈕樣式（`backgroundTint` 避 MaterialButton 雷） |
| `res/drawable/*.xml` | 新建 | 泡泡/頭像/輸入框/狀態點/向量圖示 |
| `AndroidManifest.xml` | **改預設** | 加網路權限 + 註冊三頁 Activity |
| `gradle/libs.versions.toml`、`app/build.gradle.kts` | **改預設** | 加 `recyclerview` 依賴 |

---

## 3. 從零跑通（分階段步驟）

### 前置

- 已安裝 Android Studio（含內建 JDK 與 SDK）。
- 本機 `JAVA_HOME` 可能是空的，命令列建置要指向 Android Studio 內建 jbr。

### 階段 A — 編譯出 APK

在專案根目錄開 PowerShell：

```powershell
# 此機 JAVA_HOME 為空，指向 Android Studio 內建 JDK
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:assembleDebug --console=plain
```

**務必看到 `BUILD SUCCESSFUL`** 才算過。編譯失敗時 APK 不會更新，裝到模擬器的會是舊版（常見雷）。
產物在 `app\build\outputs\apk\debug\app-debug.apk`。

### 階段 B — 兩台模擬器互連的關鍵設定（**最容易卡的地方**）

> **為什麼要特別處理**：Android 模擬器各自在自己的 NAT 後面，**彼此不能直接用 IP 互連**。
> 解法：用 `adb forward` 把「開發機的某個埠」轉接到「當 Server 的模擬器」，
> 另一台模擬器再用特殊位址 `10.0.2.2`（模擬器看開發機的 loopback）連進來。

```powershell
$adb = "C:\Users\User\AppData\Local\Android\Sdk\platform-tools\adb.exe"

# 1) 開兩台模擬器（可在 Android Studio 的 Device Manager 啟動，或命令列）
& "C:\Users\User\AppData\Local\Android\Sdk\emulator\emulator.exe" -avd Client01
& "C:\Users\User\AppData\Local\Android\Sdk\emulator\emulator.exe" -avd Client02

# 2) 查出兩台的序號（通常 emulator-5554 / emulator-5556）
& $adb devices

# 3) 兩台都安裝同一支 APK
& $adb -s emulator-5554 install -r app\build\outputs\apk\debug\app-debug.apk
& $adb -s emulator-5556 install -r app\build\outputs\apk\debug\app-debug.apk

# 4) 把「開發機:8888」轉接到「當 Server 的模擬器(5554):8888」
& $adb -s emulator-5554 forward tcp:8888 tcp:8888
```

> **懶人一鍵版**：上面的第 3、4 步（裝 APK 到所有模擬器 + 對 Host 設 forward）已包成腳本，
> 建置完 APK 後直接執行即可（`emulator-5554` 換成你要當 Host 的那台序號）：
>
> ```powershell
> scripts\emu_connect.bat emulator-5554 8888
> ```
>
> 註：`.bat` 內容刻意用純英文（cmd 讀 `.bat` 是系統 ANSI 編碼，含中文會亂碼閃退，這是本機踩過的雷）。

### 階段 C — 在 App 裡操作

**Host（emulator-5554）**
1. App 首頁中央有暱稱欄，輸入例如 `Alice`。
2. 點下方第一張卡片 **建立聊天室**（畫面中段，藍色 Wi-Fi 圖示那張）。
3. 進入設定頁，會看到 **本機 IP** 與 **埠號 8888**，點藍色大鈕 **開始聊天室**。
4. 進入聊天室，頂部顯示 `本機IP:8888`、右上狀態點轉綠、在線人數 1。

**Client（emulator-5556）**
1. 首頁輸入暱稱 `Bob`。
2. 點第二張卡片 **加入聊天室**（藍色登入箭頭那張）。
3. 設定頁 **伺服器 IP** 填 `10.0.2.2`（預設已填）、埠號 `8888`，點 **連線**。
4. 連上後雙方在線人數變 2，互打訊息即時出現在兩台上。

> 真機測試（同一 Wi-Fi）不需要 `adb forward`：Client 的伺服器 IP 直接填 **Host 手機的區網 IP**
> （Host 設定頁上顯示的那個）即可。

---

## 4. 重點函式講解（搭配原始碼註解閱讀）

> 想最快看懂「整個 Socket 流程」，照這個順序讀：
> `ChatProtocol` → `ChatServer` → `ClientHandler` → `ChatClient`。

### 4.1 `ChatServer.acceptLoop()`（伺服器主迴圈）
- 開 `ServerSocket(port)` → 取本機 IP 回呼 UI → 啟動心跳。
- `while(running) { socket = serverSocket.accept(); … }`：`accept()` 阻塞等連線；
  每來一個就 `new ClientHandler(this, socket)` 並起一條讀執行緒。
- 關閉時 `serverSocket.close()` 會讓 `accept()` 丟例外而跳出迴圈（這是讓阻塞迴圈優雅結束的標準手法）。

### 4.2 `ClientHandler.run()` / `handleLine()`（伺服器端讀一條連線）
- `run()`：`while((line = in.readLine()) != null) handleLine(line)`；
  `readLine()` 回 `null` = 客戶端關閉，`finally` 一定關連線並通知伺服器移除成員。
- `handleLine()`：解析 JSON、依 `type` 分派（JOIN/MSG/TYPING/PING/PONG）。
  解析失敗只記 log 不中斷連線（一筆壞封包不該弄垮整條連線）。

### 4.3 `ChatServer.broadcast()`（fan-out 廣播）
- 把一則 JSON 送給所有 `ClientHandler`。
- **實作關鍵**：寫出丟到單一 `io` 執行緒。因為 `broadcast` 可能被 UI 主執行緒呼叫
  （Host 發話/打字），直接在主執行緒做 socket 寫會丟 `NetworkOnMainThreadException`；
  交給 `io` 既避開此問題，又保證所有人收到的順序一致。

### 4.4 `ChatServer.heartbeatTick()`（心跳保活）
- 定時對所有連線送 `PING`；若某連線超過逾時沒任何消息，判定掉線並移除。
- 為什麼需要：App 被系統殺掉/網路硬斷時，`readLine` 不一定即時回 `null`，
  靠主動心跳能在固定時間內把死連線清掉，讓「在線狀態」準確。

### 4.5 `ChatClient.connectionLoop()`（客戶端連線/重連循環）
- `連線 → 讀迴圈 → (斷線) → 等待退避 → 重連`，直到使用者離開或收到 `SERVER_CLOSING`。
- **指數退避**：每次重連失敗等待時間加倍（到上限），避免伺服器沒開時瘋狂重連；連上就重置。

### 4.6 `ChatClient.handleLine()` 的 `PONG` 分支（量 RTT）
- 客戶端定時送 `PING(ts=現在)`；伺服器原樣回 `PONG(ts)`；
  客戶端收到時 `RTT = 現在 - ts`，顯示「延遲 x ms」。這是雙向往返時間的最簡實作。

### 4.7 優雅關閉的兩條路徑
- **客戶端離開**：`ChatClient.leave()` 關 socket → 伺服器 `readLine()` 收 `null` → 移除成員、廣播「X 離開」+ 新 roster。
- **伺服器關閉**：`ChatServer.shutdown()` 在 `io` 執行緒上「**先**廣播 `SERVER_CLOSING`、**再**關所有連線」，
  客戶端收到後顯示「伺服器已關閉」並優雅離開——**不報錯、不崩潰**。

---

## 5. 錯誤排查（先判斷卡在哪一層）

| 症狀 | 先看哪裡 | 常見原因與處理 |
|---|---|---|
| 改了程式但模擬器行為沒變 | 建置輸出 | 沒看到 `BUILD SUCCESSFUL` → 編譯失敗、APK 沒更新。修好再 `install -r`。 |
| Client 一直「連線中…」連不上 | 是否做了 `adb forward` | 模擬器互連必須 `adb forward tcp:8888 tcp:8888` 到 Server 模擬器；Client IP 要填 `10.0.2.2`。 |
| Client 連不上（真機） | 網路與 IP | 兩機要同一 Wi-Fi；IP 要填 Host 設定頁顯示的區網 IP；公司/宿舍 Wi-Fi 可能隔離客戶端（AP isolation）。 |
| App 一發訊息就閃退 | Logcat | 看 `adb logcat -b crash`。本專案曾因「在主執行緒做 socket 寫」出現 `NetworkOnMainThreadException`，已用 io 執行緒修正。 |
| Host 顯示不出本機 IP | Logcat（NetworkUtils） | 模擬器/裝置沒有可用區網介面；確認已連 Wi-Fi。 |
| 中文訊息變亂碼 | 編碼 | 收送串流必須 UTF-8（本專案已固定）。 |

**快速自我診斷指令**

```powershell
$adb = "C:\Users\User\AppData\Local\Android\Sdk\platform-tools\adb.exe"
# 確認 Server 模擬器內 8888 有在 LISTEN
& $adb -s emulator-5554 shell "netstat -an | findstr 8888"
# 看當前最上層 Activity（確認是否還在 ChatActivity 或已被踢回上一頁）
& $adb -s emulator-5554 shell dumpsys activity activities | findstr ResumedActivity
# 看崩潰堆疊
& $adb -s emulator-5556 logcat -d -b crash
```

---

## 6. 截圖 → Demo PPT 引導

`Docs/screenshots/` 已附 7 張驗證截圖，建議簡報順序與每張要證明的重點：

| 檔案 | 放哪頁 | 證明什麼 |
|---|---|---|
| `01_home.png` | 開場 | 單一 APK，進來選 Host/Join；Material3 品牌 UI |
| `02_host_setup.png` | 架構說明 | Host 顯示本機 IP/埠（給別人連） |
| `03_join_setup.png` | 架構說明 | Client 填 IP/埠連線 |
| `04_host_chat.png` + `05_client_chat.png` | **核心（左右並排）** | **同一段對話在兩台裝置上**：自己藍泡泡靠右、對方白泡泡靠左、頭像/時間；Client 上方還顯示「延遲 x ms」(RTT) → 證明 socket 雙向即時 + 心跳 |
| `06_host_member_left.png` | 在線狀態 | Client 離開後 Host 在線數變 1、出現「X 離開」→ presence/roster 正常 |
| `07_server_closed.png` | 優雅關閉 | Host 關閉後 Client 跳「伺服器已關閉」、狀態點轉紅、**不報錯** |

> 最有說服力的是 `04`+`05` **左右並排**那組：一眼看出「同一則訊息即時出現在兩台、且自他視角正確」，
> 這就是 end-to-end 的即時 Socket 通訊證據。
>
> 截圖怎麼取：`adb -s <序號> exec-out screencap -p > 檔名.png`。
> 解析度 1080×2424 偏大，要放 PPT 可先縮到約 600px 寬。
