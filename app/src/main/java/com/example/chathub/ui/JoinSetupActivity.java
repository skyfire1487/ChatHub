package com.example.chathub.ui;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.chathub.R;
import com.example.chathub.util.NetworkUtils;

/**
 * 收集 Client 連線所需的 Host 位址與埠號。
 */
public class JoinSetupActivity extends AppCompatActivity {

    private EditText etIp, etPort;
    private String nickname;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_join_setup);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.joinSetupRoot), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        nickname = getIntent().getStringExtra(ChatActivity.EXTRA_NICKNAME);
        etIp = findViewById(R.id.etServerIp);
        etPort = findViewById(R.id.etJoinPort);

        ((Button) findViewById(R.id.btnConnect)).setOnClickListener(v -> startChat());
    }

    /** 驗證 IP/埠號後，帶 mode=CLIENT 進入 ChatActivity（真正連線在那裡）。 */
    private void startChat() {
        String host = etIp.getText().toString().trim();
        if (!NetworkUtils.looksLikeHost(host)) {
            Toast.makeText(this, R.string.need_host, Toast.LENGTH_SHORT).show();
            return;
        }
        int port = parsePort(etPort.getText().toString());
        if (port < 0) {
            Toast.makeText(this, "埠號需介於 1024~65535", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(this, ChatActivity.class);
        intent.putExtra(ChatActivity.EXTRA_MODE, ChatActivity.MODE_CLIENT);
        intent.putExtra(ChatActivity.EXTRA_NICKNAME, nickname);
        intent.putExtra(ChatActivity.EXTRA_HOST, host);
        intent.putExtra(ChatActivity.EXTRA_PORT, port);
        startActivity(intent);
    }

    private int parsePort(String s) {
        try {
            int p = Integer.parseInt(s.trim());
            return (p >= 1024 && p <= 65535) ? p : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
