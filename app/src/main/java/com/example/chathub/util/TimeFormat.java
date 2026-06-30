package com.example.chathub.util;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * TimeFormat —「時間格式化」工具（新建）。
 *
 * 解決什麼問題：
 *   訊息要顯示「幾點幾分」，日期分隔要顯示「今天/昨天/月日」。把格式化集中一處，
 *   避免每個 Adapter 各自 new SimpleDateFormat 造成不一致或重複建立。
 * 為何用 SimpleDateFormat 而非 java.time：
 *   本專案 minSdk=25，java.time（LocalDateTime 等）要 API 26+ 才保證可用，
 *   為相容低版本一律用 SimpleDateFormat（沿用 AgendaTracker 既有踩雷結論）。
 */
public final class TimeFormat {

    private TimeFormat() {} // 純工具類，禁止 new。

    // SimpleDateFormat 非執行緒安全；這裡只在 UI 主執行緒的 Adapter 用，故共用一份即可。
    private static final SimpleDateFormat HM =
            new SimpleDateFormat("HH:mm", Locale.getDefault());
    private static final SimpleDateFormat MD =
            new SimpleDateFormat("M月d日", Locale.getDefault());

    /** 把毫秒時間戳格式化成「HH:mm」，給訊息泡泡角落顯示。 */
    public static String hhmm(long ts) {
        return HM.format(new Date(ts));
    }

    /**
     * 給「日期分隔列」用的標籤：今天 / 昨天 / M月d日。
     * 在幹嘛：把某則訊息的時間戳換成人看得懂的日期分組標籤，讓聊天串依日期分段。
     */
    public static String dayLabel(long ts) {
        Calendar target = dayStart(ts);                       // 該訊息所屬「那一天」的 00:00
        Calendar today = dayStart(System.currentTimeMillis());// 今天的 00:00
        long diffDays = (today.getTimeInMillis() - target.getTimeInMillis()) / 86_400_000L; // 相差幾天
        if (diffDays == 0) return "今天";
        if (diffDays == 1) return "昨天";
        return MD.format(new Date(ts));
    }

    /** 把時間戳歸一到「當天 00:00:00」，方便比較是不是同一天。 */
    private static Calendar dayStart(long ts) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ts);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c;
    }
}
