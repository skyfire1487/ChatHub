package com.example.chathub.model;

/**
 * Member —「聊天室在線成員」資料模型（新建）。
 *
 * 解決什麼問題：
 *   伺服器與客戶端都需要一份「目前誰在線」的清單（roster）。為了讓 UI 與網路層
 *   傳遞成員資訊時有一致的型別，把單一成員抽成這個極簡模型，目前只需要名稱。
 * 輸入 / 輸出：
 *   建構時給暱稱字串；對外用 getName() 取回。
 * 為何這樣寫：
 *   成員在本專案只靠「暱稱」識別（單房間、教學用途），不需要 id / 連線物件等欄位，
 *   故刻意保持最小，避免過度設計（符合全域規則：只實作目前真正需要的最小功能）。
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
