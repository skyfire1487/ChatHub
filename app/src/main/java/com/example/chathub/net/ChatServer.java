package com.example.chathub.net;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.example.chathub.Constants;
import com.example.chathub.util.NetworkUtils;

import org.json.JSONObject;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * ChatServer —「聊天室伺服器」核心（新建，Socket 核心檔）。
 *
 * 解決什麼問題：
 *   Host 端要開一個 ServerSocket 監聽埠，接受多個客戶端連線，並負責「把任一個人說的話
 *   廣播給所有人」「維護在線成員清單」「心跳偵測掉線」「優雅關閉不讓客戶端報錯」。
 *   這支就是這整套伺服器邏輯的中樞；每條連線的「讀」交給各自的 ClientHandler。
 *
 * 整個 Socket 生命週期（面試主線，從這裡看最清楚）：
 *   start() 開 ServerSocket → accept() 迴圈每接到一個 Socket 就建一個 ClientHandler 起讀執行緒
 *   → 客戶端送 JOIN，登錄成員、廣播 ROSTER+系統訊息、補歷史
 *   → 任何人送 MSG，伺服器廣播給所有連線（fan-out）
 *   → 定時 PING 心跳，逾時沒回應就移除該成員
 *   → shutdown() 廣播 SERVER_CLOSING、關閉所有連線與 ServerSocket。
 *
 * 並發模型：
 *   - 1 條 accept 執行緒（阻塞在 accept()）。
 *   - 每個客戶端 1 條讀執行緒（ClientHandler）。
 *   - 1 個排程器跑心跳。
 *   成員清單用 CopyOnWriteArrayList（讀多寫少、遍歷時不怕別執行緒在改，廣播很安全）。
 *   所有「給 UI 的回呼」都用主執行緒 Handler 轉送，Activity 端不必再自己 runOnUiThread。
 */
public class ChatServer {

    private static final String TAG = "ChatServer";

    /**
     * 伺服器事件回呼（一律在「主執行緒」被呼叫，UI 可直接更新畫面）。
     * Host 自己也是聊天室一員，所以它也透過這些回呼看到訊息與成員變化。
     */
    public interface Listener {
        void onStarted(String ip, int port);        // 成功開始監聽
        void onStartFailed(String reason);          // 開埠失敗（如埠被占用）
        void onMessage(String name, String body, long ts); // 有人發話（含 Host 自己）
        void onSystem(String body, long ts);        // 系統訊息（加入/離開/逾時）
        void onRoster(List<String> memberNames);    // 在線成員更新
        void onTyping(String name, boolean typing); // 有人打字中
        void onStopped();                           // 伺服器已關閉
    }

    private final String hostName;        // Host 自己的暱稱（也算一位成員）
    private final int port;               // 監聽埠
    private final Listener listener;      // UI 回呼
    private final Handler ui = new Handler(Looper.getMainLooper()); // 主執行緒派送器

    private final CopyOnWriteArrayList<ClientHandler> clients = new CopyOnWriteArrayList<>();
    private final List<JSONObject> history = new ArrayList<>(); // 近期訊息（給新加入者補前情）

    private volatile boolean running = false;   // 伺服器是否運轉中
    private ServerSocket serverSocket;          // 監聽用 socket
    private Thread acceptThread;                // accept 迴圈執行緒
    private ScheduledExecutorService heartbeat; // 心跳排程器
    // 專一的「送出」執行緒：所有對 socket 的寫出都丟到這裡，確保
    // (1) 不在 UI 主執行緒做網路 IO（否則 NetworkOnMainThreadException）；
    // (2) 所有廣播/歷史/關閉訊息序列化、順序一致（單一執行緒 FIFO）。
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    public ChatServer(String hostName, int port, Listener listener) {
        this.hostName = hostName;
        this.port = port;
        this.listener = listener;
    }

    // ── 啟動 / 關閉 ────────────────────────────────────────────────────────

    /**
     * 啟動伺服器：在背景執行緒開 ServerSocket 並進入 accept 迴圈。
     * 為何放背景執行緒：開埠與 accept() 都是阻塞操作，放主執行緒會卡住 UI（ANR）。
     * 為何開埠失敗要回呼而非丟例外：埠被占用是使用者層級的可預期錯誤，
     *   用 onStartFailed 讓 UI 友善提示「換個埠」，比 crash 好。
     */
    public void start() {
        running = true;
        acceptThread = new Thread(this::acceptLoop, "chat-accept");
        acceptThread.start();
    }

    private void acceptLoop() {
        try {
            serverSocket = new ServerSocket(port); // 綁定並開始監聽；埠被占用會丟 IOException
            String ip = NetworkUtils.getLocalIpAddress();
            post(() -> listener.onStarted(ip, port));
            startHeartbeat(); // 開始心跳排程

            // accept() 阻塞等下一個連線；shutdown() 關閉 serverSocket 會讓它丟例外而跳出。
            while (running) {
                Socket socket = serverSocket.accept();
                try {
                    ClientHandler handler = new ClientHandler(this, socket);
                    clients.add(handler);
                    // 每條連線一條讀執行緒，彼此獨立（多人同時講話互不阻塞）。
                    new Thread(handler, "chat-client").start();
                } catch (IOException e) {
                    Log.w(TAG, "建立連線處理器失敗: " + e);
                }
            }
        } catch (IOException e) {
            // 若仍在運轉中卻丟例外 = 開埠/監聽失敗；若是 shutdown 造成的，running 已是 false。
            if (running) {
                Log.e(TAG, "伺服器啟動或監聽失敗: " + e);
                post(() -> listener.onStartFailed("無法開啟埠 " + port + "：" + e.getMessage()));
                running = false;
            }
        }
    }

    /**
     * 優雅關閉伺服器。
     * 在幹嘛：先廣播 SERVER_CLOSING 通知客戶端「我要關了，請自行收尾」，
     *        再關閉每條客戶端連線與 ServerSocket，停掉心跳。
     * 為何先廣播再關：讓客戶端「先知道是正常關閉」再斷線，這樣對端 readLine() 收 null 時
     *               能顯示「伺服器已關閉」而不是當成異常錯誤（對應原作業：關閉不可報錯）。
     */
    public void shutdown() {
        if (!running) return;
        running = false;
        // 1) 先停心跳（不要再對即將關閉的連線送 PING）。
        if (heartbeat != null) heartbeat.shutdownNow();
        // 2) 在 io 執行緒上「先廣播 SERVER_CLOSING、再關閉每條連線」：
        //    放同一個 io 任務確保「關閉通知一定在斷線之前送出」（順序正確），
        //    且整段在背景執行緒跑，不在 UI 主執行緒做網路 IO。
        io.submit(() -> {
            JSONObject closing = ChatProtocol.serverClosing("伺服器已關閉聊天室");
            for (ClientHandler h : clients) h.send(closing);
            for (ClientHandler h : clients) h.close();
            clients.clear();
            // 關閉監聽 socket（會讓 accept() 解除阻塞而結束 accept 迴圈）。
            try {
                if (serverSocket != null) serverSocket.close();
            } catch (IOException ignored) {}
        });
        // 3) 讓 io 跑完上面已排入的關閉任務後終止（不再接受新任務）。
        io.shutdown();
        // 4) 通知 UI。
        post(listener::onStopped);
    }

    // ── 心跳保活 ──────────────────────────────────────────────────────────

    /**
     * 啟動心跳排程：每隔 HEARTBEAT_INTERVAL_MS 對所有客戶端送 PING，
     * 並檢查誰太久沒回應（超過 HEARTBEAT_TIMEOUT_MS）就視為掉線移除。
     * 為何需要：客戶端如果是被系統殺掉、網路硬斷，socket 不一定會即時回 null，
     *          只靠 readLine 偵測可能拖很久；主動心跳能在固定時間內清掉死連線（在線狀態才準）。
     */
    private void startHeartbeat() {
        heartbeat = Executors.newSingleThreadScheduledExecutor();
        heartbeat.scheduleAtFixedRate(this::heartbeatTick,
                Constants.HEARTBEAT_INTERVAL_MS, Constants.HEARTBEAT_INTERVAL_MS,
                TimeUnit.MILLISECONDS);
    }

    private void heartbeatTick() {
        long now = System.currentTimeMillis();
        JSONObject ping = ChatProtocol.ping(now);
        for (ClientHandler h : clients) {
            if (now - h.getLastSeenMs() > Constants.HEARTBEAT_TIMEOUT_MS) {
                // 太久沒消息：判定掉線，關閉並當作離線處理。
                Log.d(TAG, "心跳逾時，移除成員: " + h.getName());
                h.close();
                onClientDisconnected(h);
            } else {
                h.send(ping); // 還活著：送一次心跳探測，請對端回 PONG
            }
        }
    }

    // ── 來自 ClientHandler 的事件（在各客戶端的讀執行緒上被呼叫）─────────────

    /** 某客戶端送了 JOIN：登錄成員、補歷史給他、廣播最新 roster 與「X 加入」系統訊息。 */
    void onClientJoined(ClientHandler handler) {
        // 1) 先把近期歷史補送給「剛加入的這個人」，讓他看得到前情。
        sendHistoryTo(handler);
        // 2) 廣播系統訊息與最新成員清單給所有人（含 Host UI）。
        long ts = System.currentTimeMillis();
        String body = handler.getName() + " 加入了聊天室";
        broadcastSystem(body, ts);
        broadcastRoster();
    }

    /** 某客戶端送了 MSG：存入歷史、廣播給所有連線、並讓 Host UI 也看到。 */
    void onClientMessage(String name, String body, long ts) {
        JSONObject msg = ChatProtocol.msg(name, body, ts);
        addHistory(msg);
        broadcast(msg);                              // fan-out 給所有客戶端
        post(() -> listener.onMessage(name, body, ts)); // Host 端 UI 顯示
    }

    /** 某客戶端打字中：轉給「其他」客戶端，並通知 Host UI（自己不用收到自己的打字中）。 */
    void onClientTyping(String name, boolean typing, ClientHandler from) {
        JSONObject t = ChatProtocol.typing(name, typing);
        for (ClientHandler h : clients) {
            if (h != from) h.send(t);
        }
        post(() -> listener.onTyping(name, typing));
    }

    /** 某客戶端離線（讀迴圈結束或心跳逾時）：移除成員、廣播「X 離開」與最新 roster。 */
    void onClientDisconnected(ClientHandler handler) {
        // remove 回傳 true 代表「這次才真的移除」；避免讀迴圈與心跳重複觸發而重覆廣播。
        if (clients.remove(handler)) {
            long ts = System.currentTimeMillis();
            String who = handler.getName().isEmpty() ? "有人" : handler.getName();
            broadcastSystem(who + " 離開了聊天室", ts);
            broadcastRoster();
        }
    }

    // ── Host 自己發話 / 打字（Host 也是成員）──────────────────────────────

    /** Host 在自己的輸入框送出訊息：等同一位成員發話，存歷史、廣播、本地顯示。 */
    public void sendHostMessage(String body) {
        onClientMessage(hostName, body, System.currentTimeMillis());
    }

    /** Host 打字中：廣播給所有客戶端（Host 自己 UI 不需顯示自己的打字中）。 */
    public void sendHostTyping(boolean typing) {
        broadcast(ChatProtocol.typing(hostName, typing));
    }

    // ── 廣播 / 成員 / 歷史 的小工具 ────────────────────────────────────────

    /**
     * 把一則訊息送給「所有」已連線客戶端（fan-out 的實作核心）。
     * 實際寫出丟到 io 執行緒：呼叫端可能是 UI 主執行緒（Host 發話/打字）或背景執行緒，
     * 統一交給 io 序列化送出，既不會在主執行緒做網路 IO，也保證廣播順序一致。
     */
    private void broadcast(JSONObject json) {
        enqueue(() -> {
            for (ClientHandler h : clients) {
                h.send(json);
            }
        });
    }

    /** 把送出工作排入 io 執行緒；若 io 已在關閉流程中則安靜略過。 */
    private void enqueue(Runnable task) {
        if (io.isShutdown()) return;
        try {
            io.submit(task);
        } catch (RejectedExecutionException ignored) {
            // 正在關閉，丟棄這次送出即可。
        }
    }

    /** 廣播系統訊息，並讓 Host UI 也顯示。 */
    private void broadcastSystem(String body, long ts) {
        broadcast(ChatProtocol.system(body, ts));
        post(() -> listener.onSystem(body, ts));
    }

    /** 組出目前在線成員名單（Host 排第一，後面接所有已 JOIN 的客戶端），廣播並更新 Host UI。 */
    private void broadcastRoster() {
        List<String> names = currentRoster();
        broadcast(ChatProtocol.roster(names));
        post(() -> listener.onRoster(names));
    }

    /** 計算目前成員名單。空名稱（連上但還沒 JOIN）暫不列入。 */
    private List<String> currentRoster() {
        List<String> names = new ArrayList<>();
        names.add(hostName + "（主持）"); // Host 自己
        for (ClientHandler h : clients) {
            if (!h.getName().isEmpty()) names.add(h.getName());
        }
        return names;
    }

    /** 把訊息加入歷史並裁切到上限（只留最近 HISTORY_SIZE 筆，避免無限長）。 */
    private void addHistory(JSONObject json) {
        synchronized (history) {
            history.add(json);
            while (history.size() > Constants.HISTORY_SIZE) {
                history.remove(0); // 超量就丟掉最舊的一筆
            }
        }
    }

    /** 把歷史逐筆補送給剛加入的客戶端（讓他補看前面的對話）。 */
    private void sendHistoryTo(ClientHandler handler) {
        List<JSONObject> snapshot;
        synchronized (history) {
            snapshot = new ArrayList<>(history); // 複製一份再送，縮短鎖定時間
        }
        // 同樣經 io 序列化送出，確保「歷史」一定排在後續的 roster/system 之前。
        enqueue(() -> {
            for (JSONObject json : snapshot) {
                handler.send(json);
            }
        });
    }

    /** 把工作丟回主執行緒執行（所有 UI 回呼統一走這裡）。 */
    private void post(Runnable r) {
        ui.post(r);
    }
}
