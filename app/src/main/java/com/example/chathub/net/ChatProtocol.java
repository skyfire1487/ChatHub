package com.example.chathub.net;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

/**
 * ChatProtocol —「應用層通訊協定」單一事實來源（新建，核心檔）。
 *
 * 解決什麼問題：
 *   TCP socket 本身只搬「位元組串流」，沒有「訊息」概念。聊天室要區分「這是加入、那是聊天、
 *   還是心跳」，就必須自己在 TCP 之上定義一套格式。這支把「每一種訊息長怎樣、欄位叫什麼、
 *   怎麼打包/解析」全部集中，伺服器與客戶端共用同一份定義，避免兩邊各寫各的而對不起來。
 *
 * 設計（這段是面試/教學要能講清楚的重點）：
 *   1. 每一則訊息 = 一個 JSON 物件，必含 "type" 欄位標明種類（JOIN/MSG/PING…）。
 *   2. 傳輸時「一行一則」：把 JSON 字串後面接一個 '\n' 當作訊息邊界，
 *      收端用 BufferedReader.readLine() 一次讀一整則。這就是所謂的「newline 分幀」。
 *   3. 為什麼 newline 分幀安全？因為 JSONObject.toString() 會把字串內的換行字元
 *      轉義成字面的「\n」(反斜線+n)，輸出永遠是「單一物理行」，不會有真正的換行去破壞分幀。
 *      → 我們唯一要守的規則是：送出前一律用 toString() 序列化，不要自己手拼帶換行的字串。
 *
 * 為何用 org.json：
 *   它內建於 Android（免額外依賴），符合「不引入非必要套件」的原則，且 API 簡單。
 */
public final class ChatProtocol {

    private ChatProtocol() {} // 純工具類，禁止 new。

    // ── 訊息種類（type 欄位的值）────────────────────────────────────────────
    public static final String TYPE_JOIN = "JOIN";                 // 客→伺：連上後自報暱稱/房間
    public static final String TYPE_ROSTER = "ROSTER";             // 伺→客：目前在線成員清單
    public static final String TYPE_MSG = "MSG";                   // 雙向：一般聊天訊息
    public static final String TYPE_SYSTEM = "SYSTEM";             // 伺→客：系統訊息（加入/離開/公告）
    public static final String TYPE_TYPING = "TYPING";             // 雙向：打字中狀態
    public static final String TYPE_PING = "PING";                 // 雙向：心跳探測
    public static final String TYPE_PONG = "PONG";                 // 雙向：心跳回應
    public static final String TYPE_SERVER_CLOSING = "SERVER_CLOSING"; // 伺→客：伺服器即將關閉
    public static final String TYPE_ERROR = "ERROR";              // 伺→客：錯誤（如暱稱重複）

    // ── 欄位鍵名（集中定義，避免到處打錯字串）──────────────────────────────
    public static final String K_TYPE = "type";
    public static final String K_NAME = "name";
    public static final String K_BODY = "body";
    public static final String K_TS = "ts";        // timestamp（毫秒）
    public static final String K_ROOM = "room";
    public static final String K_MEMBERS = "members";
    public static final String K_TYPING = "typing"; // boolean
    public static final String K_REASON = "reason";
    public static final String K_CODE = "code";
    public static final String K_MSG = "msg";

    // ── 解析端：把收到的一行字串轉回 JSON，並安全取欄位 ──────────────────────

    /**
     * 把 readLine() 讀到的一行轉成 JSONObject。
     * @param line 一整則訊息（不含結尾換行）
     * @return 解析後的 JSON 物件
     * @throws JSONException 格式不對時拋出，由呼叫端決定忽略或記錄（避免一筆壞封包就讓整條連線掛掉）
     */
    public static JSONObject parse(String line) throws JSONException {
        return new JSONObject(line);
    }

    /** 取出 type 欄位；沒有就回空字串（呼叫端的 switch 會落入 default 而忽略）。 */
    public static String typeOf(JSONObject json) {
        return json.optString(K_TYPE, "");
    }

    // ── 組裝端：各種訊息的建構器，回傳 JSONObject ───────────────────────────
    // 註：這裡用 optString/put 的順序固定欄位，方便對照 Docs/PROTOCOL.md。

    /** JOIN：客戶端連上後自報暱稱與房間。 */
    public static JSONObject join(String name, String room) {
        return obj(K_TYPE, TYPE_JOIN, K_NAME, name, K_ROOM, room);
    }

    /** ROSTER：伺服器推送目前在線成員（每次有人進出就全量送一份，客戶端直接覆蓋顯示）。 */
    public static JSONObject roster(List<String> memberNames) {
        // JSONArray 用來承載字串陣列；逐一塞入成員名稱。
        JSONArray arr = new JSONArray();
        for (String n : memberNames) {
            arr.put(n);
        }
        return obj(K_TYPE, TYPE_ROSTER, K_MEMBERS, arr);
    }

    /** MSG：一般聊天訊息（誰、說什麼、何時）。 */
    public static JSONObject msg(String name, String body, long ts) {
        return obj(K_TYPE, TYPE_MSG, K_NAME, name, K_BODY, body, K_TS, ts);
    }

    /** SYSTEM：系統訊息（伺服器產生，例如「X 加入了聊天室」）。 */
    public static JSONObject system(String body, long ts) {
        return obj(K_TYPE, TYPE_SYSTEM, K_BODY, body, K_TS, ts);
    }

    /** TYPING：打字中狀態（誰、現在是否正在打字）。 */
    public static JSONObject typing(String name, boolean isTyping) {
        return obj(K_TYPE, TYPE_TYPING, K_NAME, name, K_TYPING, isTyping);
    }

    /** PING：心跳探測，帶送出當下時間，方便對端原樣回拋以量 RTT。 */
    public static JSONObject ping(long ts) {
        return obj(K_TYPE, TYPE_PING, K_TS, ts);
    }

    /** PONG：心跳回應，把 PING 帶來的 ts 原樣回傳，送方相減即得來回延遲（RTT）。 */
    public static JSONObject pong(long ts) {
        return obj(K_TYPE, TYPE_PONG, K_TS, ts);
    }

    /** SERVER_CLOSING：伺服器主動關閉前廣播，讓客戶端「優雅收尾」而非報錯崩潰。 */
    public static JSONObject serverClosing(String reason) {
        return obj(K_TYPE, TYPE_SERVER_CLOSING, K_REASON, reason);
    }

    /** ERROR：伺服器回報錯誤（例如暱稱重複），帶錯誤碼與訊息。 */
    public static JSONObject error(String code, String msg) {
        return obj(K_TYPE, TYPE_ERROR, K_CODE, code, K_MSG, msg);
    }

    /**
     * obj —— 共用建構器：用「鍵, 值, 鍵, 值…」的成對參數組出 JSON。
     * 在幹嘛：把所有建構器共用的「逐欄 put」集中在這裡，順便把 JSONObject.put() 宣告的
     *        checked JSONException 一次處理掉，讓上面每個建構器都能乾淨地 return（不必各自 throws）。
     * 為何能安全吞掉例外：key 與 value 都是本程式自己給的合法資料（字串/數字/布林/JSONArray），
     *        org.json 在這種情況不會丟 JSONException；萬一真的發生代表程式寫錯，用 RuntimeException 立即暴露。
     * @param kv 必須是偶數長度：kv[0]=鍵, kv[1]=值, kv[2]=鍵 …
     */
    private static JSONObject obj(Object... kv) {
        try {
            JSONObject json = new JSONObject();
            for (int i = 0; i < kv.length; i += 2) {
                json.put((String) kv[i], kv[i + 1]);
            }
            return json;
        } catch (JSONException e) {
            throw new RuntimeException("不可能發生：建立封包失敗", e);
        }
    }
}
