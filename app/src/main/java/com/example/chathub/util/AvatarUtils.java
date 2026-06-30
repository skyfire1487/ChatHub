package com.example.chathub.util;

import android.graphics.Color;

/**
 * AvatarUtils —「暱稱 → 一致顏色 / 首字頭像」工具（新建，加值 B）。
 *
 * 解決什麼問題：
 *   聊天室裡每個人若有固定代表色與首字色塊頭像，視覺上更好辨識、也更像成熟的通訊 App。
 *   重點是「同一個暱稱永遠對應同一個顏色」，這樣同一人在不同裝置/不同訊息都長一樣。
 * 為何這樣寫：
 *   用「暱稱字串的雜湊」對調色盤取餘數來決定顏色——純函式、零狀態、跨裝置一致，
 *   不需要伺服器分配顏色（最小成本達成一致性）。
 */
public final class AvatarUtils {

    private AvatarUtils() {} // 純工具類，禁止 new。

    // 預先挑好的一組對比明確、彩度適中的顏色，當作頭像/暱稱色盤。
    private static final int[] PALETTE = {
            Color.parseColor("#E57373"), // 紅
            Color.parseColor("#9575CD"), // 紫
            Color.parseColor("#4FC3F7"), // 藍
            Color.parseColor("#4DB6AC"), // 青綠
            Color.parseColor("#81C784"), // 綠
            Color.parseColor("#FFB74D"), // 橘
            Color.parseColor("#A1887F"), // 棕
            Color.parseColor("#F06292"), // 粉
    };

    /**
     * 由暱稱決定代表色。
     * 在幹嘛：把暱稱的 hashCode 取絕對值後對色盤長度取餘數，得到固定的索引。
     * 為何 Math.abs：hashCode 可能為負，負數當索引會越界，先轉正。
     */
    public static int colorFor(String name) {
        if (name == null || name.isEmpty()) {
            return PALETTE[0];
        }
        int idx = Math.abs(name.hashCode()) % PALETTE.length;
        return PALETTE[idx];
    }

    /**
     * 取暱稱首字當頭像文字（取第一個字元，轉大寫）。
     * 中文就是第一個字、英文是首字母；空字串回 "?"。
     */
    public static String initialOf(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "?";
        }
        String trimmed = name.trim();
        return trimmed.substring(0, 1).toUpperCase();
    }
}
