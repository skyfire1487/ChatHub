package com.example.chathub.model;

/**
 * ChatMessage —「聊天頁要顯示的一則內容」UI 資料模型（新建）。
 *
 * 解決什麼問題：
 *   網路層收到的是「協定封包（帶 type 的 JSON）」，但畫面上要顯示的只是三種泡泡：
 *   自己說的、別人說的、系統訊息。這個模型把「要怎麼畫」需要的欄位收斂起來，
 *   讓 RecyclerView 的 Adapter 只依賴它，不必認識網路協定細節（關注點分離）。
 * 輸入 / 輸出：
 *   由 Activity 在收到網路回呼後建立；Adapter 讀 kind/name/body/timestamp 來決定版型與內容。
 * 為何這樣寫：
 *   把「傳輸用的協定物件」與「顯示用的 UI 物件」分開，是讓「整個 Socket 流程好解釋」的關鍵——
 *   網路層只管收送 JSON，UI 層只管畫泡泡，兩者用這個簡單模型銜接。
 */
public class ChatMessage {

    /** 泡泡型別：決定版型（自己靠右、別人靠左、系統置中）。 */
    public enum Kind {
        SELF,    // 自己發出的訊息（靠右、主色泡泡）
        OTHER,   // 其他成員的訊息（靠左、淺色泡泡 + 頭像 + 暱稱）
        SYSTEM   // 系統訊息（置中膠囊，如「X 加入/離開」「伺服器已關閉」）
    }

    private final Kind kind;        // 此則屬於哪一種泡泡
    private final String name;      // 發話者暱稱；系統訊息可為 null
    private final String body;      // 內文（聊天文字，或系統訊息文字）
    private final long timestamp;   // 產生時間（毫秒，本機時鐘）；用於顯示時間與日期分隔

    /**
     * 建構一則待顯示訊息。
     * @param kind      泡泡型別
     * @param name      發話者暱稱（系統訊息傳 null 即可）
     * @param body      內文
     * @param timestamp 毫秒時間戳（通常 System.currentTimeMillis() 或封包帶來的 ts）
     */
    public ChatMessage(Kind kind, String name, String body, long timestamp) {
        this.kind = kind;
        this.name = name;
        this.body = body;
        this.timestamp = timestamp;
    }

    /** 便利建構：系統訊息（自動帶當下時間、無發話者）。 */
    public static ChatMessage system(String body) {
        return new ChatMessage(Kind.SYSTEM, null, body, System.currentTimeMillis());
    }

    public Kind getKind() {
        return kind;
    }

    public String getName() {
        return name;
    }

    public String getBody() {
        return body;
    }

    public long getTimestamp() {
        return timestamp;
    }
}
