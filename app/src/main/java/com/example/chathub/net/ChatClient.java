package com.example.chathub.net;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.example.chathub.Constants;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 管理 Client TCP 連線、協定訊息、心跳與自動重連。
 */
public class ChatClient {

    private static final String TAG = "ChatClient";

    /** 客戶端事件回呼（一律在主執行緒被呼叫）。 */
    public interface Listener {
        void onState(ConnectionState state);          // 連線狀態變化（驅動狀態 banner）
        void onMessage(String name, String body, long ts);
        void onSystem(String body, long ts);
        void onRoster(List<String> memberNames);
        void onTyping(String name, boolean typing);
        void onRtt(long rttMs);                        // 量到的來回延遲（毫秒）
        void onServerClosing(String reason);           // 伺服器正常關閉
    }

    private final String name;        // 本機暱稱
    private final String host;        // 要連的伺服器位址
    private final int port;           // 要連的埠
    private final Listener listener;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final Object writeLock = new Object();
    private final ExecutorService sender = Executors.newSingleThreadExecutor(); // 序列化送出
    private ScheduledExecutorService pinger; // 客戶端心跳排程

    private volatile boolean active = true;  // 是否還要維持連線（false=使用者離開或伺服器關閉，停止重連）
    private volatile Socket socket;
    private volatile BufferedWriter out;
    private Thread managerThread;

    public ChatClient(String name, String host, int port, Listener listener) {
        this.name = name;
        this.host = host;
        this.port = port;
        this.listener = listener;
    }

    // ── 啟動 / 停止 ────────────────────────────────────────────────────────

    /** 啟動客戶端：在管理執行緒上跑「連線→讀→重連」循環。 */
    public void start() {
        managerThread = new Thread(this::connectionLoop, "chat-client-mgr");
        managerThread.start();
    }

    /**
     * 使用者主動離開聊天室。
     * 處理方式：把 active 設 false（停止重連），關閉 socket（解除 readLine 阻塞），停心跳與 sender。
     * 為何這樣：正常離開時 socket 一關，伺服器端 readLine() 會收到 null → 伺服器把我移出成員清單
     *          並廣播離開事件，使在線成員狀態保持同步。
     */
    public void leave() {
        active = false;
        closeSocket();
        // 中斷管理執行緒：若它正卡在重連退避的 sleep()，interrupt 會讓它立即醒來、
        // 看到 active=false 而收尾，不用乾等最多 15 秒（sleep 內會捕捉 InterruptedException）。
        if (managerThread != null) managerThread.interrupt();
        if (pinger != null) pinger.shutdownNow();
        sender.shutdownNow();
        post(() -> listener.onState(ConnectionState.DISCONNECTED));
    }

    /**
     * 連線循環（管理執行緒）：負責建立連線、跑讀迴圈、斷線後指數退避重連。
     * 退避（backoff）：每次重連失敗，等待時間加倍（base→2x→4x…）到上限為止，
     *   避免伺服器沒開時瘋狂重連灌爆 CPU/網路；連線成功後把等待時間重置回 base。
     */
    private void connectionLoop() {
        long delay = Constants.RECONNECT_BASE_DELAY_MS;
        boolean firstAttempt = true;

        while (active) {
            // 第一次叫「連線中」，之後的循環叫「重連中」（UI banner 顏色不同）。
            // 取成 final 區域變數再給 lambda 用：lambda 只能捕捉 effectively final 的變數，
            // 而 firstAttempt 在迴圈尾端會被改寫，故不能直接放進 lambda。
            final ConnectionState attemptState =
                    firstAttempt ? ConnectionState.CONNECTING : ConnectionState.RECONNECTING;
            post(() -> listener.onState(attemptState));
            try {
                openConnection(); // 建立 socket、備妥串流、送 JOIN
                post(() -> listener.onState(ConnectionState.CONNECTED));
                delay = Constants.RECONNECT_BASE_DELAY_MS; // 連上就重置退避
                startPinger();    // 開始量 RTT/保活
                readLoop();       // 阻塞直到連線中斷
            } catch (IOException e) {
                Log.d(TAG, "連線/讀取結束: " + e);
            } finally {
                stopPinger();
                closeSocket();
            }
            firstAttempt = false;

            if (!active) break; // 使用者離開或伺服器關閉 → 不再重連

            // 斷線且仍要維持 → 顯示重連中、等待退避時間後再試。
            post(() -> listener.onState(ConnectionState.RECONNECTING));
            sleep(delay);
            delay = Math.min(delay * 2, Constants.RECONNECT_MAX_DELAY_MS);
        }
        post(() -> listener.onState(ConnectionState.DISCONNECTED));
    }

    /**
     * 建立一條新連線並完成 JOIN。
     * 為何用 connect(SocketAddress, timeout)：填錯 IP 時不會無限期卡住，逾時就丟例外進重連流程。
     * 一律 UTF-8：與伺服器端一致，中文不亂碼。
     */
    private void openConnection() throws IOException {
        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, port), Constants.CONNECT_TIMEOUT_MS);
        this.socket = s;
        this.out = new BufferedWriter(
                new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
        // 連上的第一件事：自報暱稱與房間，伺服器才知道我是誰、把我列入成員。
        rawWrite(ChatProtocol.join(name, Constants.DEFAULT_ROOM));
    }

    /** 讀迴圈：持續讀伺服器送來的每一行並派發，直到 readLine() 回 null 或出錯。 */
    private void readLoop() throws IOException {
        BufferedReader in = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            handleLine(line);
        }
    }

    /** 解析並派發伺服器送來的單行訊息。 */
    private void handleLine(String line) {
        JSONObject json;
        try {
            json = ChatProtocol.parse(line);
        } catch (JSONException e) {
            Log.w(TAG, "收到無法解析的封包，已忽略: " + line);
            return;
        }

        switch (ChatProtocol.typeOf(json)) {
            case ChatProtocol.TYPE_MSG:
                post(() -> listener.onMessage(
                        json.optString(ChatProtocol.K_NAME, "匿名"),
                        json.optString(ChatProtocol.K_BODY, ""),
                        json.optLong(ChatProtocol.K_TS, System.currentTimeMillis())));
                break;

            case ChatProtocol.TYPE_SYSTEM:
                post(() -> listener.onSystem(
                        json.optString(ChatProtocol.K_BODY, ""),
                        json.optLong(ChatProtocol.K_TS, System.currentTimeMillis())));
                break;

            case ChatProtocol.TYPE_ROSTER:
                post(() -> listener.onRoster(parseMembers(json)));
                break;

            case ChatProtocol.TYPE_TYPING:
                post(() -> listener.onTyping(
                        json.optString(ChatProtocol.K_NAME, ""),
                        json.optBoolean(ChatProtocol.K_TYPING, false)));
                break;

            case ChatProtocol.TYPE_PING:
                // 伺服器探測我是否活著：原樣回 PONG。
                rawWrite(ChatProtocol.pong(json.optLong(ChatProtocol.K_TS, 0)));
                break;

            case ChatProtocol.TYPE_PONG:
                // 這是伺服器回應「我的 PING」：用當下時間減去當初送出的 ts = 來回延遲(RTT)。
                long rtt = System.currentTimeMillis() - json.optLong(ChatProtocol.K_TS, 0);
                post(() -> listener.onRtt(rtt));
                break;

            case ChatProtocol.TYPE_SERVER_CLOSING:
                // 伺服器正常關閉：停止重連、通知 UI 優雅離開（不是錯誤）。
                active = false;
                post(() -> listener.onServerClosing(
                        json.optString(ChatProtocol.K_REASON, "伺服器已關閉")));
                closeSocket(); // 解除 readLine 阻塞，讓循環收尾
                break;

            default:
                break; // 未知 type 忽略（向前相容）
        }
    }

    /** 從 ROSTER 封包的 members 陣列取出成員名稱清單。 */
    private List<String> parseMembers(JSONObject json) {
        List<String> names = new ArrayList<>();
        JSONArray arr = json.optJSONArray(ChatProtocol.K_MEMBERS);
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                names.add(arr.optString(i));
            }
        }
        return names;
    }

    // ── 對外送出（UI 呼叫）─────────────────────────────────────────────────

    /** 送出一則聊天訊息（交給 sender 執行緒，避免在 UI 執行緒做網路 IO）。 */
    public void sendMessage(String body) {
        send(ChatProtocol.msg(name, body, System.currentTimeMillis()));
    }

    /** 送出打字中狀態。 */
    public void sendTyping(boolean typing) {
        send(ChatProtocol.typing(name, typing));
    }

    /** 把送出工作丟到 sender 執行緒序列化執行。 */
    private void send(JSONObject json) {
        if (sender.isShutdown()) return;
        try {
            sender.submit(() -> rawWrite(json));
        } catch (RejectedExecutionException ignored) {
            // 剛好在檢查後、submit 前被 leave() 關掉：丟棄這次送出即可，不需報錯。
        }
    }

    /**
     * 真正寫出到 socket（永遠在 sender 執行緒或管理執行緒上跑，不在 UI 執行緒）。
     * synchronized(writeLock)：心跳 PING 與使用者訊息可能同時要寫，鎖住避免字元交錯破壞分幀。
     * out 可能在重連空窗為 null：此時直接略過（聊天訊息在斷線時丟失可接受，不阻塞）。
     */
    private void rawWrite(JSONObject json) {
        synchronized (writeLock) {
            BufferedWriter w = out;
            if (w == null) return;
            try {
                w.write(json.toString());
                w.write("\n");
                w.flush();
            } catch (IOException e) {
                Log.d(TAG, "寫出失敗（連線可能已斷）: " + e);
                closeSocket(); // 讓 readLoop 結束、進入重連
            }
        }
    }

    // ── 客戶端心跳（量 RTT 兼保活）─────────────────────────────────────────

    /** 啟動客戶端心跳：定時送 PING(帶現在時間)，伺服器回 PONG 後即可算出 RTT。 */
    private void startPinger() {
        pinger = Executors.newSingleThreadScheduledExecutor();
        pinger.scheduleAtFixedRate(
                () -> send(ChatProtocol.ping(System.currentTimeMillis())),
                Constants.HEARTBEAT_INTERVAL_MS, Constants.HEARTBEAT_INTERVAL_MS,
                TimeUnit.MILLISECONDS);
    }

    private void stopPinger() {
        if (pinger != null) {
            pinger.shutdownNow();
            pinger = null;
        }
    }

    // ── 小工具 ────────────────────────────────────────────────────────────

    /** 關閉目前 socket（可重複呼叫）；同時把 out 清掉，避免對死連線寫入。 */
    private void closeSocket() {
        synchronized (writeLock) {
            out = null;
        }
        Socket s = socket;
        socket = null;
        if (s != null) {
            try { s.close(); } catch (IOException ignored) {}
        }
    }

    /** 睡眠指定毫秒；被中斷時安靜返回（用於重連退避等待）。 */
    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 把工作丟回主執行緒執行。 */
    private void post(Runnable r) {
        ui.post(r);
    }
}
