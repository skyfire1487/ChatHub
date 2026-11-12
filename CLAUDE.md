# CLAUDE.md — ChatHub 專案工程指南

> 記錄 ChatHub 的系統邊界、建置方式、模組職責與工程維護慣例。
> 網路架構見 [Docs/ARCHITECTURE.md](Docs/ARCHITECTURE.md)；執行與診斷見 [Docs/OPERATIONS.md](Docs/OPERATIONS.md)；
> 協定規格見 [Docs/PROTOCOL.md](Docs/PROTOCOL.md)；功能總覽見 [README.md](README.md)。

## 1. 專案概述

**ChatHub** 是 Android 區域網路即時群組通訊應用。單一 APK 支援 Host 與 Client 角色，
以**原生 TCP `Socket`/`ServerSocket` 與自訂 JSON 協定**處理連線生命週期、並發通訊與訊息分幀。

- **平台與語言**：Android、Java、XML layout。
- 套件 `com.example.chathub`，minSdk 25 / compileSdk 36，Gradle Kotlin DSL（`libs.versions.toml` 版本目錄）。

## 2. 本機建置與裝置連線（驗證改動前先讀）

**建置**：若終端機未設定 `JAVA_HOME`，請指向 Android Studio 內建的 JBR。
```powershell
if (-not $env:JAVA_HOME) {
    $env:JAVA_HOME = Join-Path $env:ProgramFiles 'Android\Android Studio\jbr'
}
.\gradlew.bat :app:assembleDebug --console=plain
```
建置結果必須顯示 `BUILD SUCCESSFUL`。未成功時不得將 APK 視為目前原始碼版本。

**模擬器互連**：透過 `adb forward` 將開發主機埠轉送至 Host 模擬器；Client 使用 `10.0.2.2` 與相同埠號連線。裝置序號依 `adb devices` 輸出指定，操作步驟見 [OPERATIONS.md](Docs/OPERATIONS.md) 第 3 節。
Android SDK 路徑由 `ANDROID_HOME` 指定，`adb` 位於其 `platform-tools` 子目錄；AVD 名稱依 Android Studio Device Manager 的設定。

**真機**：兩機同一 Wi-Fi，Client 填 Host 設定頁顯示的區網 IP，不需 adb forward。

## 3. 架構與資料流

```
Host: ChatActivity ── ChatServer(ServerSocket, accept迴圈, 廣播, 心跳, io執行緒)
                          │  每連線一個 ClientHandler(讀執行緒)
                          ▼  TCP, 每行一則 JSON(\n 分幀)
Client: ChatActivity ── ChatClient(Socket, 讀迴圈, sender執行緒, 心跳量RTT, 自動重連)
```
- 一則訊息 = 一個含 `type` 的 JSON 物件，後接 `\n`；收端 `readLine()` 一次讀一則。
- 完整生命週期/協定型別見 [Docs/PROTOCOL.md](Docs/PROTOCOL.md)。

## 4. 檔案地圖（`app/src/main/java/com/example/chathub/`）

| 檔案 | 職責 |
|---|---|
| `MainActivity` | 首頁：暱稱輸入與 Host/Join 角色選擇 |
| `Constants` | 埠/心跳/逾時/退避/房間等常數 |
| `net/ChatProtocol` | **協定單一事實來源**：型別常數 + JSON 打包/解析 |
| `net/ChatServer` | 伺服器：accept、廣播 fan-out、roster、歷史、心跳、優雅關閉；所有寫出走單一 `io` 執行緒 |
| `net/ClientHandler` | 伺服器端單一連線的讀執行緒/寫出 |
| `net/ChatClient` | 客戶端：連線、讀迴圈、`sender` 送出、客戶端心跳量 RTT、指數退避重連 |
| `net/ConnectionState` | 連線狀態列舉 |
| `model/ChatMessage`,`Member` | 資料模型 |
| `adapter/MessageAdapter` | 訊息清單（3 種泡泡 view type） |
| `adapter/MemberAdapter` | 成員清單 |
| `ui/ChatActivity` | 聊天主畫面，Host/Client 共用；同時 implements 兩個 Listener |
| `ui/HostSetupActivity`,`JoinSetupActivity` | 兩個設定頁 |
| `util/NetworkUtils`,`TimeFormat`,`AvatarUtils` | 取本機 IP、時間格式、暱稱配色 |

**工程設計約定**：網路操作使用背景執行緒；網路層以主執行緒 Handler 派送 UI 回呼；搜尋與排序在 App 端處理。

## 5. 實作約束與相容性注意事項

1. **不可在 UI 主執行緒做 socket 寫出** → `NetworkOnMainThreadException`。
   伺服器端所有寫出走 `ChatServer.io` 單執行緒；客戶端走 `ChatClient.sender`。新增送出路徑時務必沿用。
2. **`readLine()` 是阻塞函式**，必在獨立執行緒；回 `null` = 對端關閉，要當**正常事件**處理（移除成員/重連），不可當錯誤崩潰。
3. **TCP 無訊息邊界**：送出一律 `JSONObject.toString()`（會把內含換行轉義成單行）後接 `\n`；不要自己手拼帶換行字串。
4. **同一連線的寫出要同步**（`writeLock`），避免兩則訊息字元交錯破壞分幀。
5. **返回鍵用 `OnBackPressedDispatcher`**，不要覆寫 `onBackPressed()`（targetSdk 36 predictive back 下不被呼叫）。
6. **MaterialButton 會忽略 `android:background` 自訂 drawable**：按鈕底色用 `styles.xml` 的 `AppButton.*`（`backgroundTint`）。
7. **edge-to-edge（API 35 強制）**：root 給 id，用 `WindowInsets` 動態加 systemBars padding，不被系統列/瀏海切到。
8. **minSdk 25**：時間用 `SimpleDateFormat`，不用 `java.time`（要 API 26+）。
9. **編譯失敗 → APK 不更新 → 模擬器跑舊版**：install/截圖前先確認 `BUILD SUCCESSFUL`。

## 6. 版本控制慣例

每個可獨立驗證階段（協定/伺服器/客戶端/UI/驗證/文檔）一個 commit 當回檔點；
commit 前確認 `BUILD SUCCESSFUL` 且不含 build 產物（`.gitignore` 已含 `/build`、`/app/build`、`.gradle`、`local.properties`）。
本專案**無機密**（埠非機密），故不需 `.env`。

## 7. 當前功能邊界

- 核心為**單一房間**（`Constants.DEFAULT_ROOM = "main"`）；多房間為未實作的延伸。
- 暱稱**不防重複**（兩人同名仍可進，協定保留了 `ERROR` 型別供未來擴充）。
- 訊息歷史只存在 Host 記憶體（重啟即清空），非持久化。
