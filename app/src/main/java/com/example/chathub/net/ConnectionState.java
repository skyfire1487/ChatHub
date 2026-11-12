package com.example.chathub.net;

/**
 * 描述 Client 連線與重連狀態，並提供 UI 顯示標籤。
 */
public enum ConnectionState {

    CONNECTING("連線中…"),   // 正在嘗試建立 socket
    CONNECTED("已連線"),     // 已連上且 JOIN 完成
    RECONNECTING("重連中…"), // 連線中斷，正在指數退避重試
    DISCONNECTED("已斷線");  // 已停止（使用者離開或伺服器關閉）

    private final String label; // 給 UI 顯示的中文字

    ConnectionState(String label) {
        this.label = label;
    }

    /** 取得可直接顯示在狀態 banner 的中文標籤。 */
    public String label() {
        return label;
    }
}
