package com.example.chathub.ui;

import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.chathub.Constants;
import com.example.chathub.R;
import com.example.chathub.adapter.MemberAdapter;
import com.example.chathub.adapter.MessageAdapter;
import com.example.chathub.model.ChatMessage;
import com.example.chathub.net.ChatClient;
import com.example.chathub.net.ChatServer;
import com.example.chathub.net.ConnectionState;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * ChatActivity —「聊天主畫面」，Host 與 Client 共用（新建，核心 UI）。
 *
 * 解決什麼問題：
 *   這頁同時負責「網路生命週期」與「畫面呈現」：依進來的 mode 決定要開伺服器(ChatServer)
 *   還是當客戶端(ChatClient)，並把網路層回呼來的事件畫到畫面上（訊息、成員、打字中、連線狀態、RTT）。
 *
 * 為何同時 implements 兩個 Listener：
 *   ChatServer.Listener 與 ChatClient.Listener 有四個同名回呼（onMessage/onSystem/onRoster/onTyping），
 *   共用同一份實作即可；其餘各自獨有的回呼在另一模式下不會被呼叫。這樣 Host/Client 共用大部分 UI 邏輯。
 *
 * 執行緒：網路層所有回呼都已切回「主執行緒」（ChatServer/ChatClient 內用 Handler 轉送），
 *        所以這裡可以直接更新 UI，不需再 runOnUiThread。
 */
public class ChatActivity extends AppCompatActivity
        implements ChatServer.Listener, ChatClient.Listener {

    // Intent extra 的 key 與 mode 值（給其他頁面引用，集中定義在此）。
    public static final String EXTRA_MODE = "mode";
    public static final String EXTRA_NICKNAME = "nickname";
    public static final String EXTRA_HOST = "host";
    public static final String EXTRA_PORT = "port";
    public static final String MODE_HOST = "host";
    public static final String MODE_CLIENT = "client";

    private String mode;        // host 或 client
    private String nickname;    // 本機暱稱
    private String host;        // client 模式要連的位址
    private int port;

    private ChatServer server;  // host 模式才會建立
    private ChatClient client;  // client 模式才會建立

    // 視圖
    private TextView tvTitle, tvSubtitle, tvOnlineCount, banner, tvTyping;
    private View statusDot, cardMembers;
    private EditText etMessage;
    private RecyclerView rvMessages;

    // 清單轉接器
    private final MessageAdapter messageAdapter = new MessageAdapter();
    private final MemberAdapter memberAdapter = new MemberAdapter();

    // 打字中：記錄「目前有誰正在打字」（不含自己），以及自己是否已送出 typing(true)。
    private final Set<String> typingUsers = new LinkedHashSet<>();
    private boolean selfTyping = false;
    private final Handler typingHandler = new Handler(Looper.getMainLooper());
    // 自己停止打字的延遲任務：停止輸入 1.5 秒後送 typing(false)。
    private final Runnable stopTypingTask = () -> setSelfTyping(false);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_chat);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.chatRoot), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        // 讀取上一頁帶來的參數。
        mode = getIntent().getStringExtra(EXTRA_MODE);
        nickname = getIntent().getStringExtra(EXTRA_NICKNAME);
        host = getIntent().getStringExtra(EXTRA_HOST);
        port = getIntent().getIntExtra(EXTRA_PORT, Constants.DEFAULT_PORT);

        bindViews();
        setupRecyclerViews();
        setupInput();

        // 依模式啟動網路。
        if (MODE_HOST.equals(mode)) {
            startAsHost();
        } else {
            startAsClient();
        }
    }

    // ── 視圖綁定與設定 ─────────────────────────────────────────────────────

    private void bindViews() {
        tvTitle = findViewById(R.id.tvChatTitle);
        tvSubtitle = findViewById(R.id.tvChatSubtitle);
        tvOnlineCount = findViewById(R.id.tvOnlineCount);
        banner = findViewById(R.id.banner);
        tvTyping = findViewById(R.id.tvTyping);
        statusDot = findViewById(R.id.statusDot);
        cardMembers = findViewById(R.id.cardMembers);
        etMessage = findViewById(R.id.etMessage);
        rvMessages = findViewById(R.id.rvMessages);

        // 送出鈕。
        ((ImageButton) findViewById(R.id.btnSend)).setOnClickListener(v -> sendCurrentInput());
        // 在線徽章：切換成員面板展開/收合。
        findViewById(R.id.badgeOnline).setOnClickListener(v -> toggleMembers());
        // 選單：依模式顯示「關閉伺服器 / 離開聊天室」。
        findViewById(R.id.btnMenu).setOnClickListener(this::showMenu);
    }

    private void setupRecyclerViews() {
        // 訊息清單：stackFromEnd=true 讓內容貼齊底部，新訊息出現在最下方。
        LinearLayoutManager msgLm = new LinearLayoutManager(this);
        msgLm.setStackFromEnd(true);
        rvMessages.setLayoutManager(msgLm);
        rvMessages.setAdapter(messageAdapter);

        // 成員清單。
        RecyclerView rvMembers = findViewById(R.id.rvMembers);
        rvMembers.setLayoutManager(new LinearLayoutManager(this));
        rvMembers.setAdapter(memberAdapter);
    }

    private void setupInput() {
        // 監看輸入內容，推斷「打字中」狀態並通知對方。
        etMessage.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override
            public void afterTextChanged(Editable s) {
                if (s.length() > 0) {
                    setSelfTyping(true);
                    // 每打一個字就重設「停止打字」倒數，停手 1.5 秒後才送 typing(false)。
                    typingHandler.removeCallbacks(stopTypingTask);
                    typingHandler.postDelayed(stopTypingTask, 1500);
                } else {
                    setSelfTyping(false);
                }
            }
        });
    }

    // ── 啟動網路 ──────────────────────────────────────────────────────────

    /** Host 模式：建立並啟動伺服器，本機也是聊天室一員。 */
    private void startAsHost() {
        tvTitle.setText("聊天室（主持）");
        tvSubtitle.setText("啟動中…");
        setStatusColor(R.color.status_warn);
        server = new ChatServer(nickname, port, this);
        server.start();
    }

    /** Client 模式：建立並啟動客戶端，連到指定伺服器。 */
    private void startAsClient() {
        tvTitle.setText(host + ":" + port);
        tvSubtitle.setText(ConnectionState.CONNECTING.label());
        setStatusColor(R.color.status_warn);
        client = new ChatClient(nickname, host, port, this);
        client.start();
    }

    // ── 送出訊息 / 打字中 ──────────────────────────────────────────────────

    /** 讀取輸入框文字並送出（依模式呼叫伺服器或客戶端）。 */
    private void sendCurrentInput() {
        String text = etMessage.getText().toString().trim();
        if (TextUtils.isEmpty(text)) return;
        if (server != null) {
            server.sendHostMessage(text);   // Host 發話：自己 UI 會透過 onMessage 回呼顯示
        } else if (client != null) {
            client.sendMessage(text);       // Client 發話：伺服器廣播回來時才顯示（單一順序來源）
        }
        etMessage.setText("");              // 清空輸入框
        setSelfTyping(false);               // 送出後即停止打字中
    }

    /** 設定自己的打字中狀態，並只在「狀態有變化」時才通知對方（避免狂送）。 */
    private void setSelfTyping(boolean typing) {
        if (selfTyping == typing) return;
        selfTyping = typing;
        if (server != null) {
            server.sendHostTyping(typing);
        } else if (client != null) {
            client.sendTyping(typing);
        }
    }

    // ── 共用回呼（ChatServer 與 ChatClient 都會呼叫；已在主執行緒）────────────

    @Override
    public void onMessage(String name, String body, long ts) {
        // 判斷是不是自己說的：是→靠右藍泡泡，否→靠左白泡泡。
        ChatMessage.Kind kind = name.equals(nickname) ? ChatMessage.Kind.SELF : ChatMessage.Kind.OTHER;
        addMessage(new ChatMessage(kind, name, body, ts));
        // 對方一旦發話，代表他打完了，把他從「打字中」移除。
        if (typingUsers.remove(name)) refreshTyping();
    }

    @Override
    public void onSystem(String body, long ts) {
        addMessage(new ChatMessage(ChatMessage.Kind.SYSTEM, null, body, ts));
    }

    @Override
    public void onRoster(List<String> memberNames) {
        memberAdapter.setMembers(memberNames);
        tvOnlineCount.setText(String.valueOf(memberNames.size()));
    }

    @Override
    public void onTyping(String name, boolean typing) {
        if (name == null || name.equals(nickname)) return; // 不顯示自己的打字中
        if (typing) {
            typingUsers.add(name);
        } else {
            typingUsers.remove(name);
        }
        refreshTyping();
    }

    // ── 伺服器(Host)專屬回呼 ──────────────────────────────────────────────

    @Override
    public void onStarted(String ip, int port) {
        // 顯示「本機 IP:埠」讓 Host 知道要請別人連哪裡。
        tvSubtitle.setText((ip != null ? ip : "本機") + ":" + port);
        setStatusColor(R.color.status_ok);
    }

    @Override
    public void onStartFailed(String reason) {
        // 開埠失敗（多半是埠被占用）：跳提示後關閉本頁。
        new AlertDialog.Builder(this)
                .setTitle("無法建立聊天室")
                .setMessage(reason)
                .setPositiveButton(R.string.confirm, (d, w) -> finish())
                .setCancelable(false)
                .show();
    }

    @Override
    public void onStopped() {
        // 伺服器已關閉（由我們自己觸發 shutdown），結束本頁。
        finish();
    }

    // ── 客戶端(Client)專屬回呼 ────────────────────────────────────────────

    @Override
    public void onState(ConnectionState state) {
        switch (state) {
            case CONNECTED:
                banner.setVisibility(View.GONE);   // 連上就把 banner 收起來
                setStatusColor(R.color.status_ok);
                break;
            case CONNECTING:
            case RECONNECTING:
                showBanner(state.label(), R.color.status_warn);
                setStatusColor(R.color.status_warn);
                break;
            case DISCONNECTED:
                setStatusColor(R.color.status_bad);
                break;
        }
    }

    @Override
    public void onRtt(long rttMs) {
        // 顯示來回延遲（RTT），順帶當作「目前連線品質」指標。
        tvSubtitle.setText("延遲 " + rttMs + " ms");
    }

    @Override
    public void onServerClosing(String reason) {
        // 伺服器正常關閉：這是「正常事件」不是錯誤，提示後離開（對應原作業：關閉不報錯）。
        new AlertDialog.Builder(this)
                .setTitle("聊天室已關閉")
                .setMessage(getString(R.string.server_closed))
                .setPositiveButton(R.string.confirm, (d, w) -> finish())
                .setCancelable(false)
                .show();
    }

    // ── UI 小工具 ─────────────────────────────────────────────────────────

    /** 新增一則訊息並把清單捲到最底（讓最新訊息保持可見）。 */
    private void addMessage(ChatMessage m) {
        messageAdapter.add(m);
        rvMessages.scrollToPosition(messageAdapter.size() - 1);
    }

    /** 依目前「正在打字的人」更新底部提示文字；沒人打字就隱藏。 */
    private void refreshTyping() {
        if (typingUsers.isEmpty()) {
            tvTyping.setVisibility(View.GONE);
            return;
        }
        String names = TextUtils.join("、", typingUsers);
        tvTyping.setText(names + " 正在輸入…");
        tvTyping.setVisibility(View.VISIBLE);
    }

    /** 切換成員面板展開/收合。 */
    private void toggleMembers() {
        cardMembers.setVisibility(
                cardMembers.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
    }

    /** 顯示連線狀態 banner（文字 + 底色）。 */
    private void showBanner(String text, int colorRes) {
        banner.setText(text);
        banner.setBackgroundTintList(
                ColorStateList.valueOf(ContextCompat.getColor(this, colorRes)));
        banner.setVisibility(View.VISIBLE);
    }

    /** 設定 AppBar 連線狀態點顏色（綠/黃/紅）。 */
    private void setStatusColor(int colorRes) {
        statusDot.setBackgroundTintList(
                ColorStateList.valueOf(ContextCompat.getColor(this, colorRes)));
    }

    /** 顯示右上選單：Host 顯示「關閉伺服器」、Client 顯示「離開聊天室」。 */
    private void showMenu(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        boolean isHost = server != null;
        String label = isHost ? getString(R.string.close_server) : getString(R.string.leave_room);
        popup.getMenu().add(label);
        popup.setOnMenuItemClickListener(item -> {
            confirmLeave();
            return true;
        });
        popup.show();
    }

    /** 離開前的確認對話框（依模式文案不同）。 */
    private void confirmLeave() {
        boolean isHost = server != null;
        new AlertDialog.Builder(this)
                .setMessage(isHost ? R.string.close_server_confirm : R.string.leave_confirm)
                .setPositiveButton(R.string.confirm, (d, w) -> finish()) // finish 觸發 onDestroy 收網路
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** 系統返回鍵：等同點選單離開（先確認，避免誤觸直接斷線）。 */
    @Override
    public void onBackPressed() {
        confirmLeave();
    }

    /**
     * 頁面結束時一定要關閉網路資源，避免執行緒/連線洩漏。
     * Host → shutdown()（會廣播 SERVER_CLOSING 讓客戶端優雅離開）；Client → leave()。
     */
    @Override
    protected void onDestroy() {
        super.onDestroy();
        typingHandler.removeCallbacks(stopTypingTask);
        if (server != null) server.shutdown();
        if (client != null) client.leave();
    }
}
