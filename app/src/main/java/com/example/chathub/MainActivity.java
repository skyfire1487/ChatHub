package com.example.chathub;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.EditText;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.chathub.ui.ChatActivity;
import com.example.chathub.ui.HostSetupActivity;
import com.example.chathub.ui.JoinSetupActivity;
import com.google.android.material.card.MaterialCardView;

/**
 * MainActivity —「首頁 / 角色選擇」（改預設：Android Studio 產生的進入點，內容已改寫）。
 *
 * 解決什麼問題：
 *   單一 APK 同時能當伺服器或客戶端，所以一進來先讓使用者輸入暱稱，再選「建立聊天室(Host)」
 *   或「加入聊天室(Join)」，分別導去對應的設定頁。
 *
 * 共用常數：暱稱透過 Intent extra 一路帶到後面的頁面（key 定義在 ChatActivity.EXTRA_*）。
 */
public class MainActivity extends AppCompatActivity {

    private EditText etNickname; // 暱稱輸入框

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);              // 啟用 edge-to-edge（內容延伸到系統列後方）
        setContentView(R.layout.activity_main);

        // 用 WindowInsets 動態加 padding：避免內容被狀態列/導航列/瀏海切到（API 35 起強制 edge-to-edge）。
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        etNickname = findViewById(R.id.etNickname);

        // 兩張卡片：點擊先驗證暱稱，再導去對應設定頁。
        ((MaterialCardView) findViewById(R.id.cardHost))
                .setOnClickListener(v -> goSetup(HostSetupActivity.class));
        ((MaterialCardView) findViewById(R.id.cardJoin))
                .setOnClickListener(v -> goSetup(JoinSetupActivity.class));
    }

    /**
     * 驗證暱稱後，帶著暱稱導向指定的設定頁。
     * 為何在這裡擋空暱稱：暱稱是聊天室識別「誰說的」的關鍵，必須先有，後面頁面才不用各自再檢查。
     */
    private void goSetup(Class<?> target) {
        String nickname = etNickname.getText().toString().trim();
        if (TextUtils.isEmpty(nickname)) {
            Toast.makeText(this, R.string.need_nickname, Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(this, target);
        intent.putExtra(ChatActivity.EXTRA_NICKNAME, nickname);
        startActivity(intent);
    }
}
