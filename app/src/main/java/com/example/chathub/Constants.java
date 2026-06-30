package com.example.chathub;

/**
 * Constants —「全專案共用的設定常數」集中處（新建）。
 *
 * 解決什麼問題：
 *   port、心跳間隔、逾時、重連退避、預設房間名…這些數字若散落各檔，日後要調很痛苦，
 *   也容易兩處不一致。集中在這裡當「單一事實來源」，要調參數只改這一支。
 * 為何這樣寫：
 *   全部用 public static final（編譯期常數）。沒有任何機密（port 不是機密），
 *   所以不需要 .env；若日後真的有金鑰再另立機制（符合全域規則：機密不寫進可提交程式碼）。
 */
public final class Constants {

    // 私有建構子：這是純常數類別，禁止被 new（工具類慣例）。
    private Constants() {}

    /** 預設聊天室埠號。1024~65535 之間（0~1023 系統保留）；8888 好記又不易衝突。 */
    public static final int DEFAULT_PORT = 8888;

    /** 預設房間名（核心為單房間，先固定 "main"；多房間延伸時才會用到別的值）。 */
    public static final String DEFAULT_ROOM = "main";

    /** 伺服器每隔多久對所有客戶端送一次心跳 PING（毫秒）。 */
    public static final long HEARTBEAT_INTERVAL_MS = 5_000L;

    /**
     * 心跳逾時（毫秒）：某客戶端超過這段時間沒有任何訊息/PONG，
     * 伺服器就視為已斷線並移除。設為間隔的 ~3 倍，容忍偶發延遲。
     */
    public static final long HEARTBEAT_TIMEOUT_MS = 16_000L;

    /** 連線 socket 的 connect 逾時（毫秒）：避免填錯 IP 時 UI 卡死太久。 */
    public static final int CONNECT_TIMEOUT_MS = 8_000;

    /** 客戶端斷線後自動重連的起始等待（毫秒），之後指數退避。 */
    public static final long RECONNECT_BASE_DELAY_MS = 1_000L;

    /** 自動重連的最大等待上限（毫秒），避免退避到無限長。 */
    public static final long RECONNECT_MAX_DELAY_MS = 15_000L;

    /** 伺服器在記憶體保留的近期訊息筆數；新成員加入時補送這些，讓他看得到前情。 */
    public static final int HISTORY_SIZE = 50;

    /** 模擬器測試時，客戶端要連的「主機 loopback」位址（連到開發機，再由 adb forward 轉給 Server 模擬器）。 */
    public static final String EMULATOR_HOST_LOOPBACK = "10.0.2.2";
}
