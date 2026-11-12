package com.example.chathub.util;

import android.util.Log;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

/**
 * 擷取區域網路 IPv4 位址並檢查 Host 欄位。
 */
public final class NetworkUtils {

    private static final String TAG = "NetworkUtils";

    private NetworkUtils() {} // 純工具類，禁止 new。

    /**
     * 擷取可供區域網路連線的 IPv4 位址。
     *
     * 掃描裝置網路介面，排除 loopback 與 link-local 位址；若未找到符合條件的位址則回傳 null。
     */
    public static String getLocalIpAddress() {
        try {
            // 取得所有網路介面的列舉器。
            for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
                 en.hasMoreElements(); ) {
                NetworkInterface intf = en.nextElement();
                // 略過沒啟用或是 loopback 的介面，省得白掃。
                if (!intf.isUp() || intf.isLoopback()) {
                    continue;
                }
                // 一個介面可能綁多個位址，逐一檢查。
                for (Enumeration<InetAddress> addrs = intf.getInetAddresses();
                     addrs.hasMoreElements(); ) {
                    InetAddress addr = addrs.nextElement();
                    // 條件：非 loopback、且確實是 IPv4。
                    if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ex) {
            // 取不到就記 log 回 null，呼叫端顯示「無法取得 IP」即可，不應讓 App 崩潰。
            Log.e(TAG, "取得本機 IP 失敗: " + ex);
        }
        return null;
    }

    /**
     * 檢查伺服器位址欄位是否有輸入內容。實際連線狀態由 Socket 連線結果與逾時機制判定。
     */
    public static boolean looksLikeHost(String host) {
        return host != null && !host.trim().isEmpty();
    }
}
