package com.example.chathub.ui;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.chathub.Constants;
import com.example.chathub.R;
import com.example.chathub.util.NetworkUtils;

/**
 * 收集 Host 建立聊天室所需埠號並顯示區域網路位址。
 */
public class HostSetupActivity extends AppCompatActivity {

    private EditText etPort;
    private String nickname;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_host_setup);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.hostSetupRoot), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        // 從上一頁帶來的暱稱。
        nickname = getIntent().getStringExtra(ChatActivity.EXTRA_NICKNAME);

        // 顯示本機 IP（取不到就提示確認 Wi-Fi）。
        TextView tvLocalIp = findViewById(R.id.tvLocalIp);
        String ip = NetworkUtils.getLocalIpAddress();
        tvLocalIp.setText(ip != null ? ip : getString(R.string.ip_unavailable));

        etPort = findViewById(R.id.etHostPort);

        ((Button) findViewById(R.id.btnStartServer)).setOnClickListener(v -> startChat());
    }

    /** 驗證埠號後，帶 mode=HOST 進入 ChatActivity（真正開伺服器在那裡）。 */
    private void startChat() {
        int port = parsePort(etPort.getText().toString());
        if (port < 0) {
            Toast.makeText(this, "埠號需介於 1024~65535", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(this, ChatActivity.class);
        intent.putExtra(ChatActivity.EXTRA_MODE, ChatActivity.MODE_HOST);
        intent.putExtra(ChatActivity.EXTRA_NICKNAME, nickname);
        intent.putExtra(ChatActivity.EXTRA_PORT, port);
        startActivity(intent);
    }

    /** 解析並驗證埠號；非法回 -1。1024~65535（0~1023 為系統保留）。 */
    private int parsePort(String s) {
        try {
            int p = Integer.parseInt(s.trim());
            return (p >= 1024 && p <= 65535) ? p : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
