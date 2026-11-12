package com.example.chathub.model;

/**
 * 聊天室成員名稱資料模型。
 */
public class Member {

    // 成員暱稱；用 final 表示建立後不可變（一個 Member 物件就代表某個固定名稱的人）。
    private final String name;

    /**
     * 建構一個成員。
     * @param name 使用者暱稱（呼叫端需自行確保非空）
     */
    public Member(String name) {
        this.name = name;
    }

    /** 取得暱稱。 */
    public String getName() {
        return name;
    }
}
