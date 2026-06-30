package com.example.chathub.util;

import android.util.Log;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

/**
 * NetworkUtils —「網路相關小工具」（新建）。
 *
 * 解決什麼問題：
 *   Host 啟動聊天室後，要把「本機 IP」顯示給別人，別人才知道要連哪裡。Android 沒有單一 API
 *   直接給「對外可用的區網 IP」，得自己掃所有網路介面挑一個合適的。
 *   本檔的 getLocalIpAddress() 是把原暑期 pptx 第 11 頁的範例清理、補註解、修正而成。
 */
public final class NetworkUtils {

    private static final String TAG = "NetworkUtils";

    private NetworkUtils() {} // 純工具類，禁止 new。

    /**
     * 取得本機區網 IPv4 位址（字串）。
     *
     * 在幹嘛：列舉裝置上所有網路介面（wifi、行動網路…），逐一看它們的位址，
     *        挑出「不是 loopback(127.x)、不是 link-local(169.254.x)、且是 IPv4」的那一個回傳。
     * 輸出：成功回 IP 字串（如 "192.168.0.12"）；找不到回 null。
     * 為何這樣寫：
     *   - 只要 IPv4：聊天室填 IP 用 IPv4 直覺好懂，且避免 IPv6 一長串。原範例用 isLinkLocalAddress
     *     會把 IPv4 也誤判，這裡改成「先確認是 Inet4Address」更精準。
     *   - loopback(127.0.0.1) 是自己連自己、別人連不到，必須排除。
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
     * 粗略檢查使用者輸入的 IP/host 是否「看起來可連」。
     * 在幹嘛：只做最基本的非空檢查；真正能不能連，交給 socket 連線時的逾時去判斷
     *        （不在這裡做嚴格 regex，避免擋掉 "10.0.2.2"、主機名等合法輸入）。
     */
    public static boolean looksLikeHost(String host) {
        return host != null && !host.trim().isEmpty();
    }
}
