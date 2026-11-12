package com.example.chathub.net;

import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * 處理一條已接受 TCP 連線的訊息讀取、事件派發與送出。
 */
public class ClientHandler implements Runnable {

    private static final String TAG = "ClientHandler";

    private final ChatServer server;          // 回呼用：把收到的訊息交給伺服器統一處理
    private final Socket socket;              // 這條連線的 socket
    private final BufferedReader in;          // 從客戶端讀（一行一則 JSON）
    private final BufferedWriter out;         // 寫回客戶端
    private final Object writeLock = new Object(); // 寫出同步鎖（廣播 vs 點對點寫不交錯）

    private volatile String name = "";        // 客戶端暱稱；JOIN 後才有，故用 volatile 跨執行緒可見
    private volatile long lastSeenMs;         // 最後一次收到此客戶端任何訊息的時間（心跳逾時判斷用）
    private volatile boolean closed = false;  // 是否已關閉，避免重複清理

    /**
     * 建構並備妥讀寫串流。
     * @param server 所屬伺服器（收到訊息會回呼它）
     * @param socket accept() 取得的連線
     * 為何在建構子就包好 Buffered*：streams 取得一次、重複使用，避免每次收送都重建。
     * 一律指定 UTF-8：中文訊息不會變亂碼（不指定會用平台預設編碼，跨機器易出包）。
     */
    public ClientHandler(ChatServer server, Socket socket) throws IOException {
        this.server = server;
        this.socket = socket;
        this.in = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        this.out = new BufferedWriter(
                new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        this.lastSeenMs = System.currentTimeMillis();
    }

    public String getName() {
        return name;
    }

    public long getLastSeenMs() {
        return lastSeenMs;
    }

    /**
     * 讀迴圈（在自己的執行緒上跑）。
     * 處理方式：不斷 readLine() 讀客戶端送來的每一行，交給 handleLine() 派發，直到對端關閉或出錯。
     * 資源管理：不論正常結束(null)或 IOException，finally 都會關連線並通知伺服器移除此成員，
     *                      確保成員清單與資源一定被清理（優雅關閉的關鍵）。
     */
    @Override
    public void run() {
        try {
            String line;
            // readLine() 阻塞等待一行；回 null 代表客戶端關閉了輸出（連線結束）。
            while ((line = in.readLine()) != null) {
                lastSeenMs = System.currentTimeMillis(); // 收到任何東西都算「還活著」
                handleLine(line);
            }
        } catch (IOException e) {
            // 連線被中斷（網路斷、對端 crash…）：屬正常情況，記 debug 即可。
            Log.d(TAG, "連線讀取結束: " + e);
        } finally {
            close();                       // 關閉本連線資源
            server.onClientDisconnected(this); // 通知伺服器把此成員移除並廣播更新
        }
    }

    /**
     * 處理單一行訊息：解析 JSON、依 type 分派。
     * 為何單獨抽出來：讓讀迴圈乾淨，也方便閱讀「伺服器看到各種封包時各做什麼」。
     * 解析失敗只記錄、不中斷連線：一筆壞封包不該讓整條連線掛掉（健壯性）。
     */
    private void handleLine(String line) {
        JSONObject json;
        try {
            json = ChatProtocol.parse(line);
        } catch (JSONException e) {
            Log.w(TAG, "收到無法解析的封包，已忽略: " + line);
            return;
        }

        switch (ChatProtocol.typeOf(json)) {
            case ChatProtocol.TYPE_JOIN:
                // 客戶端自報暱稱：記下名字，交給伺服器做「登錄成員＋廣播 roster＋補歷史」。
                this.name = json.optString(ChatProtocol.K_NAME, "匿名");
                server.onClientJoined(this);
                break;

            case ChatProtocol.TYPE_MSG:
                // 一般聊天：交給伺服器轉廣播給所有人（含發話者，確保大家順序一致）。
                server.onClientMessage(
                        json.optString(ChatProtocol.K_NAME, name),
                        json.optString(ChatProtocol.K_BODY, ""),
                        json.optLong(ChatProtocol.K_TS, System.currentTimeMillis()));
                break;

            case ChatProtocol.TYPE_TYPING:
                // 打字中狀態：轉給其他人顯示「X 正在輸入…」。
                server.onClientTyping(
                        json.optString(ChatProtocol.K_NAME, name),
                        json.optBoolean(ChatProtocol.K_TYPING, false),
                        this);
                break;

            case ChatProtocol.TYPE_PING:
                // 客戶端探測伺服器是否活著：原樣把 ts 回拋成 PONG，讓對方算 RTT。
                send(ChatProtocol.pong(json.optLong(ChatProtocol.K_TS, 0)));
                break;

            case ChatProtocol.TYPE_PONG:
                // Client 回應伺服器心跳時，lastSeenMs 已更新，無需額外處理。
                break;

            default:
                // 未知或不需伺服器處理的 type：忽略（向前相容，新增 type 不會讓舊端崩潰）。
                break;
        }
    }

    /**
     * 把一則 JSON 訊息寫回這個客戶端。
     * 處理方式：序列化成單行 JSON、補上 '\n' 當分幀邊界、flush 立即送出。
     * 為何 synchronized(writeLock)：廣播時多條執行緒可能同時寫同一個 out，
     *   不同步會讓兩則訊息的字元交錯、毀掉分幀。鎖住確保「一則寫完才換下一則」。
     * 寫失敗的處理：通常代表連線已斷，關閉並讓伺服器移除此成員。
     */
    public void send(JSONObject json) {
        synchronized (writeLock) {
            try {
                out.write(json.toString()); // JSONObject.toString() 保證單行（內含換行會被轉義）
                out.write("\n");            // 分幀：收端 readLine() 以此切出一則
                out.flush();                // 立即送出，不要卡在緩衝區
            } catch (IOException e) {
                Log.d(TAG, "寫出失敗（連線可能已斷）: " + e);
                close();
                server.onClientDisconnected(this);
            }
        }
    }

    /**
     * 集中關閉本連線的資源（可重複呼叫，靠 closed 旗標防止重入）。
     * 為何集中：socket 關了，底層的 in/out 也會跟著失效；逐一 try 關閉並吞掉例外，
     *          確保「關閉」這件事本身不會再丟例外出來（closeQuietly 模式）。
     */
    public void close() {
        if (closed) return;
        closed = true;
        try { socket.close(); } catch (IOException ignored) {}
    }
}
