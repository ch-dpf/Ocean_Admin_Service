package org.ocean.admin.platform.audit.utils;

/**
 * 从 User-Agent 字符串中解析浏览器与操作系统名称，供登录/审计日志等场景复用。
 * <p>
 * 基于关键词的粗粒度识别，不保证版本级精度；匹配顺序对 Edge/Chrome、Safari 等交叉 UA 有影响。
 */
public final class UserAgentParser {

    private UserAgentParser() {
    }

    /**
     * 解析浏览器名称。
     *
     * @param userAgent 原始 User-Agent，可为 null
     * @return 浏览器标识；无法识别时返回 {@code Unknown}
     */
    public static String parseBrowser(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return "Unknown";
        }
        // Edge 的 UA 通常同时包含 Chrome/Safari，需优先于 Chrome 判断
        if (userAgent.contains("Edg/") || userAgent.contains("Edge")) {
            return "Edge";
        }
        if (userAgent.contains("Chrome") || userAgent.contains("CriOS")) {
            return "Chrome";
        }
        if (userAgent.contains("Firefox") || userAgent.contains("FxiOS")) {
            return "Firefox";
        }
        // Safari 常出现在 Chrome/Edge UA 中，需在排除上述浏览器后再判断
        if (userAgent.contains("Safari")) {
            return "Safari";
        }
        if (userAgent.contains("MSIE") || userAgent.contains("Trident")) {
            return "IE";
        }
        return "Unknown";
    }

    /**
     * 解析操作系统名称。
     *
     * @param userAgent 原始 User-Agent，可为 null
     * @return 操作系统标识；无法识别时返回 {@code Unknown}
     */
    public static String parseOS(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return "Unknown";
        }
        // Android/iOS 的 UA 也可能含 Linux/Mac 片段，移动端优先判断
        if (userAgent.contains("Android")) {
            return "Android";
        }
        if (userAgent.contains("iPhone") || userAgent.contains("iPad")) {
            return "iOS";
        }
        // Windows 11 的 UA 仍多为 Windows NT 10.0，此处与历史日志保持同一标签
        if (userAgent.contains("Windows NT 10.0")) {
            return "Windows 10";
        }
        if (userAgent.contains("Windows NT 6.3")) {
            return "Windows 8.1";
        }
        if (userAgent.contains("Windows NT 6.1")) {
            return "Windows 7";
        }
        if (userAgent.contains("Mac OS X") || userAgent.contains("Macintosh")) {
            return "Mac OS";
        }
        if (userAgent.contains("Linux")) {
            return "Linux";
        }
        return "Unknown";
    }
}
