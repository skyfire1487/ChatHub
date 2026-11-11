# ChatHub — Android 即時群播通訊系統

> 在 Android 上以**原生 TCP Socket** 自建一套 **JSON 應用層協定**的即時群播聊天系統，
> 具**多執行緒並發**、**在線狀態（presence）管理**與**心跳保活**。
> 刻意不使用 WebSocket / Socket.IO / Firebase，凸顯底層網路與並發能力。

單一 APK 同時能當**伺服器（Host）**或**客戶端（Client）**：進來輸入暱稱、選「建立聊天室」或「加入聊天室」。

| 首頁（選角色） | Host 聊天 | Client 聊天（顯示 RTT） |
|---|---|---|
| ![home](Docs/screenshots/01_home.png) | ![host](Docs/screenshots/04_host_chat.png) | ![client](Docs/screenshots/05_client_chat.png) |

## Host / Client 設定

Host 會顯示聊天室的 IP 與埠號；Client 輸入同一組位址即可加入。兩台真機請連上同一個 Wi-Fi，並使用 Host 顯示的區網 IP。

| Host 顯示可供連線的位址 | Client 輸入 Host 位址 |
|---|---|
| ![Host 設定頁](Docs/screenshots/02_host_setup.png) | ![Client 加入設定頁](Docs/screenshots/03_join_setup.png) |

兩台 Android 模擬器在同一台電腦上互連時，先執行 `scripts\emu_connect.bat <HOST_SERIAL> [PORT]` 設定 port forwarding，再於 Client 填入 `10.0.2.2`；完整步驟見 [Docs/TEACHING.md](Docs/TEACHING.md)。

## 正常離開與關閉

Client 離開後，Host 會即時更新成員名單；Host 關閉聊天室時，Client 會顯示伺服器已關閉並平順離開。

| Client 離開後的 Host 名單 | Host 關閉後的 Client 畫面 |
|---|---|
| ![Host 成員名單更新](Docs/screenshots/06_host_member_left.png) | ![Client 顯示伺服器已關閉](Docs/screenshots/07_server_closed.png) |

## 功能

**核心（對應原 Socket 聊天室作業）**
- Android `ServerSocket` 多人連線、每連線一條讀執行緒
- 自訂 JSON 協定 + newline 分幀
- 在線成員清單（presence）、加入/離開系統訊息
- 訊息廣播（fan-out），自己/他人/系統三型別泡泡、暱稱頭像、時間戳
- 顯示本機 Server IP/埠
- **優雅關閉**：Client 離開→成員表更新不報錯；Host 關伺服器→Client 顯示「已關閉」不崩潰

**加值亮點**
- **心跳保活**（`PING/PONG`）：逾時自動移除死連線
- **連線延遲（RTT）顯示**：用心跳量來回時間
- **斷線自動重連**（指數退避）+ 連線狀態 banner / 狀態點
- **打字中**指示
- 加入時補送**近期訊息歷史**

## 技術棧

- **語言/UI**：Java、XML layout、Material 3（不使用 Kotlin / Compose）
- **網路**：`java.net.Socket` / `ServerSocket` 原生 TCP、`org.json` JSON、多執行緒（`Thread` + `ExecutorService`）
- **minSdk 25 / targetSdk 36**、Gradle Kotlin DSL

## 快速開始

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:assembleDebug --console=plain   # 需看到 BUILD SUCCESSFUL
```

**完全新手先讀 [Docs/CONCEPTS.md](Docs/CONCEPTS.md)** — 白話介紹 Socket、loopback、TCP、埠、NAT、執行緒、心跳等領域知識，並對應到程式位置。
兩台模擬器互連、完整操作與錯誤排查見 **[Docs/TEACHING.md](Docs/TEACHING.md)**。
通訊協定規格見 **[Docs/PROTOCOL.md](Docs/PROTOCOL.md)**。

## 專案結構

```
app/src/main/java/com/example/chathub/
├─ MainActivity.java          首頁/角色選擇
├─ Constants.java             設定常數
├─ net/                       ★ Socket 核心
│  ├─ ChatProtocol.java       協定（單一事實來源）
│  ├─ ChatServer.java         伺服器（accept/廣播/心跳/關閉）
│  ├─ ClientHandler.java      伺服器端單一連線
│  ├─ ChatClient.java         客戶端（連線/讀/送/心跳/重連）
│  └─ ConnectionState.java    連線狀態列舉
├─ model/  ChatMessage, Member
├─ adapter/ MessageAdapter, MemberAdapter
├─ ui/     HostSetupActivity, JoinSetupActivity, ChatActivity
└─ util/   NetworkUtils, TimeFormat, AvatarUtils
```
