# ChatHub 執行與維護指南

> 本文件整理開發環境建置、裝置連線、操作流程、診斷方式與關鍵模組責任。
> 網路與連線架構見 [ARCHITECTURE.md](ARCHITECTURE.md)；通訊事件與欄位見 [PROTOCOL.md](PROTOCOL.md)。
> 功能總覽見 [../README.md](../README.md)。

---

## 1. 系統定位與架構原則

**ChatHub** 是一款以 Android 原生 TCP `Socket` / `ServerSocket` 和自訂 JSON 協定為核心的區域網路群組通訊應用。
其網路服務涵蓋多客戶端連線、newline 訊息分幀、事件廣播、連線心跳與關閉狀態管理。

### 三層職責

| 層 | 角色 | 負責 |
|---|---|---|
| **UI 層**（Activity/Adapter） | 互動與呈現 | 收集使用者輸入，呈現訊息、成員與連線狀態 |
| **網路層**（`net/`） | 連線與傳輸 | 管理 Socket 連線、JSON 傳輸、事件廣播、心跳與重連 |
| **協定層**（`ChatProtocol`） | 訊息格式 | 定義訊息型別、欄位、序列化與解析規則 |

### 訊息處理流程

> Host 以 `ServerSocket` 監聽連線 → Client 連線並送出 `JOIN` → Host 接收 `MSG` 並廣播至目前連線的成員。
> `PING/PONG` 用於連線存活偵測與往返延遲（RTT）估算；各端依事件更新聊天室畫面。

完整時序圖見 [PROTOCOL.md 第 4 節](PROTOCOL.md#4-完整連線生命週期時序)。

---

## 2. 系統模組與元件責任

| 模組 | 主要責任 |
|---|---|
| UI Activities | Host／Client 角色選擇、連線設定、聊天室與成員狀態呈現 |
| Network services | TCP 連線接受、Client 連線處理、訊息傳輸、廣播、心跳與重連 |
| Protocol | JSON 訊息型別、序列化、解析與換行分幀 |
| Models and adapters | 聊天訊息與成員資料模型、列表呈現 |
| Utilities | 區域網路 IP 擷取、時間格式化與頭像識別 |

實際 Java 模組與原始碼路徑見 [ARCHITECTURE.md](ARCHITECTURE.md) 與專案根目錄的 [README.md](../README.md)。

## 3. 本機建置與雙裝置連線

### 開發環境

- 安裝 Android Studio，並在 SDK Manager 安裝專案所需的 Android SDK。
- 建置使用 Java 11；若 `JAVA_HOME` 尚未設定，可使用 Android Studio 內建的 JBR。若 Android Studio 安裝在其他位置，請改填實際 JBR 路徑。
- 模擬器名稱與裝置序號依本機 Android Studio Device Manager 設定。

### 建置 Debug APK

在專案根目錄的 PowerShell 執行：

```powershell
if (-not $env:JAVA_HOME) {
    $env:JAVA_HOME = Join-Path $env:ProgramFiles 'Android\Android Studio\jbr'
}
.\gradlew.bat :app:assembleDebug --console=plain
```

以建置輸出中的 `BUILD SUCCESSFUL` 確認成功。APK 位於 `app\build\outputs\apk\debug\app-debug.apk`。

### Android 模擬器互連設定

Android Emulator 使用各自的虛擬網路環境。兩台模擬器互連時，透過開發主機及 `adb forward` 將 Host 模擬器的監聽埠轉送至主機；Client 模擬器再以 `10.0.2.2` 存取該主機埠。真機位於同一 Wi-Fi 時，Client 直接使用 Host 的區域網路位址，不需此轉送。

先在 Android Studio Device Manager 啟動兩台 AVD，再於專案根目錄執行：

```powershell
$sdk = $env:ANDROID_HOME
if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
if (-not $sdk) { throw 'Set ANDROID_HOME to the Android SDK location.' }
$adb = Join-Path $sdk 'platform-tools\adb.exe'
$hostSerial = '<HOST_SERIAL>'
$clientSerial = '<CLIENT_SERIAL>'
$port = 8888

& $adb devices
& $adb -s $hostSerial install -r app\build\outputs\apk\debug\app-debug.apk
& $adb -s $clientSerial install -r app\build\outputs\apk\debug\app-debug.apk
& $adb -s $hostSerial forward ('tcp:{0}' -f $port) ('tcp:{0}' -f $port)
```

將 `<HOST_SERIAL>` 與 `<CLIENT_SERIAL>` 換成 `adb devices` 列出的裝置序號。也可在建置 APK 後執行自動化腳本；該腳本會安裝 APK 至已連線裝置，並在指定 Host 上設定埠轉送：

```powershell
scripts\emu_connect.bat <HOST_SERIAL> [PORT]
```

### 連線流程

**Host**：設定暱稱，選擇「建立聊天室」，確認顯示的區域網路位址與埠號後啟動聊天室。

**Client**：設定暱稱並選擇「加入聊天室」。模擬器互連時，先確認已設定 `adb forward`，再使用 `10.0.2.2` 與相同埠號；真機則輸入 Host 畫面顯示的區域網路位址與埠號。

連線後，Host 與 Client 的成員清單會同步更新，雙方可互傳訊息。真機連線需使用相同 Wi-Fi 網路；部分網路可能啟用用戶端隔離，阻止裝置彼此通訊。

---

## 4. 核心執行路徑

從 `ChatProtocol` 的訊息定義開始，可依序追蹤 `ChatServer` 的連線管理、`ClientHandler` 的單一連線處理，以及 `ChatClient` 的客戶端生命週期。

### 4.1 `ChatServer.acceptLoop()`（伺服器連線接受迴圈）
- 建立 `ServerSocket(port)`、取得本機 IP 並啟動心跳排程。
- `accept()` 等待新連線；每條已接受連線交由 `ClientHandler` 的讀取執行緒處理。
- 關閉 `ServerSocket` 會解除 `accept()` 的阻塞，讓連線接受迴圈結束。

### 4.2 `ClientHandler.run()` / `handleLine()`（單一連線訊息處理）
- `run()` 逐行讀取 JSON；`readLine()` 回傳 `null` 表示對端已關閉輸出串流。`finally` 區塊負責關閉資源並通知伺服器移除成員。
- `handleLine()` 解析 JSON，依 `type` 派送 `JOIN`、`MSG`、`TYPING`、`PING` 與 `PONG` 事件；解析錯誤記錄後繼續處理後續輸入。

### 4.3 `ChatServer.broadcast()`（訊息廣播）
- 將事件送至目前連線的 `ClientHandler`。
- Socket 寫入集中由單一 `io` 執行緒執行，避免在 UI 主執行緒進行網路 I/O，並依佇列順序送出廣播事件。

### 4.4 `ChatServer.heartbeatTick()`（心跳與逾時處理）
- 定期對連線傳送 `PING`。逾時未收到該連線的訊息或回應時，伺服器將連線判定為失效並更新成員名單。
- 心跳提供 Socket 關閉事件以外的失效偵測機制，適用於非正常結束或網路中斷情況。

### 4.5 `ChatClient.connectionLoop()`（客戶端連線與重連）
- 管理連線建立、訊息讀取、斷線處理及重連流程；使用者離開或收到 `SERVER_CLOSING` 後停止重連。
- 重連採指數退避並設置等待上限；成功連線後重設退避時間。

### 4.6 `ChatClient.handleLine()` 的 `PONG` 分支（RTT 估算）
- Client 傳送含時間戳的 `PING`，伺服器在 `PONG` 中回傳該時間戳。Client 以接收時間減去原始時間戳估算往返延遲，並顯示 RTT 毫秒值。

### 4.7 連線結束與資源回收
- **Client 離開**：`ChatClient.leave()` 關閉 Socket；伺服器讀取迴圈結束後移除成員，並更新系統訊息與名單。
- **Host 關閉**：`ChatServer.shutdown()` 先廣播 `SERVER_CLOSING`，再關閉所有連線及監聽端點；Client 依關閉事件更新畫面並結束連線流程。

---

## 5. 連線診斷

| 症狀 | 檢查項目 | 處理方式 |
|---|---|---|
| 模擬器執行內容未反映最新修改 | Gradle 建置輸出與 APK 安裝結果 | 確認建置輸出包含 `BUILD SUCCESSFUL`，再將最新 APK 安裝至目標裝置。 |
| 模擬器 Client 無法連線 | Host 序號、埠轉送與 Client 位址 | 確認 Host 已啟動、`adb forward` 指向 Host 模擬器，並在 Client 使用 `10.0.2.2` 與相同埠號。 |
| 真機 Client 無法連線 | Wi-Fi 與 Host 區域網路位址 | 確認兩台裝置位於同一網路，並使用 Host 設定頁顯示的位址；路由器可能啟用用戶端隔離（AP isolation）。 |
| 傳送訊息時 App 結束 | Android Logcat 崩潰紀錄 | 使用 `adb logcat -d -b crash` 檢視堆疊資訊。 |
| Host 未顯示區域網路位址 | 網路介面與 Logcat | 確認裝置有可用的區域網路介面，再檢視 `NetworkUtils` 相關紀錄。 |
| 中文訊息顯示異常 | 字元編碼 | 確認傳輸串流以 UTF-8 編碼。 |

**裝置狀態診斷指令**

```powershell
$sdk = $env:ANDROID_HOME
if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
if (-not $sdk) { throw 'Set ANDROID_HOME to the Android SDK location.' }
$adb = Join-Path $sdk 'platform-tools\adb.exe'
$hostSerial = '<HOST_SERIAL>'
$clientSerial = '<CLIENT_SERIAL>'
& $adb forward --list
& $adb -s $hostSerial logcat -d -b crash
& $adb -s $clientSerial logcat -d -b crash
```

---

## 6. 產品畫面與功能狀態

`Docs/screenshots/` 收錄 7 張主要操作畫面，對應下列功能狀態：

| 畫面 | 顯示狀態 | 功能對應 |
|---|---|---|
| `01_home.png` | 角色選擇 | 單一 APK 提供 Host 與 Client 入口，並呈現品牌介面 |
| `02_host_setup.png` | Host 連線設定 | 顯示區域網路 IP 與監聽埠 |
| `03_join_setup.png` | Client 連線設定 | 輸入伺服器 IP 與連線埠 |
| `04_host_chat.png` + `05_client_chat.png` | 雙端聊天室 | 對照同一段對話在 Host 與 Client 的呈現，包括訊息方向、頭像、時間及 Client RTT 狀態 |
| `06_host_member_left.png` | 成員狀態同步 | Client 離開後，Host 更新在線人數與系統訊息 |
| `07_server_closed.png` | 伺服器關閉通知 | Host 關閉後，Client 顯示關閉狀態並結束連線 |

> `04` 與 `05` 並列呈現相同聊天內容在兩個角色介面的顯示方式。更新畫面素材時，可使用 Android Debug Bridge 的 `screencap` 擷取實際裝置畫面。
