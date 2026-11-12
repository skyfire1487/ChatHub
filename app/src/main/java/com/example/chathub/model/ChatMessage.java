package com.example.chathub.model;

/**
 * 聊天訊息與系統事件的呈現資料模型。
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
