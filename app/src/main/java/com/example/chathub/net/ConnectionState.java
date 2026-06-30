package com.example.chathub.net;

/**
 * ConnectionState —「客戶端連線狀態」列舉（新建，加值 A 12）。
 *
 * 解決什麼問題：
 *   客戶端不是只有「連上/沒連上」兩種，還有「連線中」「重連中」「已斷線」。把這些狀態
 *   收成一個列舉，UI 的狀態 banner 就能依狀態切顏色與文字（綠/黃/紅），邏輯清楚不易亂。
 * 為何用 enum：
 *   狀態是「有限且互斥」的集合，enum 最貼切；附帶中文標籤方便 UI 直接顯示。
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
