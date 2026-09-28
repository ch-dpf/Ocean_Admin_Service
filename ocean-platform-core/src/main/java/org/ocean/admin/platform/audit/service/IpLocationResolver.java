package org.ocean.admin.platform.audit.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 将可信客户端 IP 转换为稳定、可展示的登录地点。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IpLocationResolver {

    static final String LOCAL = "本机";
    static final String PRIVATE_NETWORK = "内网";
    static final String UNKNOWN = "未知";

    private final LocalIpLocationProvider locationProvider;

    public String resolve(String ipAddress) {
        InetAddress address = parseLiteralAddress(ipAddress);
        if (address == null) {
            return UNKNOWN;
        }
        if (address.isLoopbackAddress()) {
            return LOCAL;
        }
        if (isPrivateAddress(address)) {
            return PRIVATE_NETWORK;
        }

        try {
            return formatRegion(locationProvider.search(address.getHostAddress()));
        } catch (Exception exception) {
            log.warn("IP归属地解析失败, ip={}: {}", ipAddress, exception.getMessage());
            return UNKNOWN;
        }
    }

    private InetAddress parseLiteralAddress(String ipAddress) {
        if (ipAddress == null || ipAddress.isBlank()) {
            return null;
        }

        String normalized = normalize(ipAddress.trim());
        if (!isIpv4Literal(normalized) && !normalized.contains(":")) {
            return null;
        }
        try {
            return InetAddress.getByName(normalized);
        } catch (UnknownHostException exception) {
            return null;
        }
    }

    private String normalize(String address) {
        if (address.startsWith("[") && address.endsWith("]")) {
            return address.substring(1, address.length() - 1);
        }
        return address;
    }

    private boolean isIpv4Literal(String address) {
        String[] segments = address.split("\\.", -1);
        if (segments.length != 4) {
            return false;
        }
        for (String segment : segments) {
            if (segment.isEmpty() || segment.length() > 3) {
                return false;
            }
            for (int index = 0; index < segment.length(); index++) {
                if (!Character.isDigit(segment.charAt(index))) {
                    return false;
                }
            }
            if (Integer.parseInt(segment) > 255) {
                return false;
            }
        }
        return true;
    }

    private boolean isPrivateAddress(InetAddress address) {
        return address.isAnyLocalAddress()
                || address.isSiteLocalAddress()
                || address.isLinkLocalAddress()
                || isUniqueLocalIpv6(address)
                || isCarrierGradeNat(address);
    }

    private boolean isUniqueLocalIpv6(InetAddress address) {
        if (!(address instanceof Inet6Address)) {
            return false;
        }
        return (address.getAddress()[0] & 0xFE) == 0xFC;
    }

    private boolean isCarrierGradeNat(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 4
                && Byte.toUnsignedInt(bytes[0]) == 100
                && (Byte.toUnsignedInt(bytes[1]) & 0xC0) == 0x40;
    }

    private String formatRegion(String region) {
        if (region == null || region.isBlank()) {
            return UNKNOWN;
        }

        String[] fields = region.split("\\|", -1);
        List<String> candidates = new ArrayList<>(3);
        for (int index = 0; index < Math.min(fields.length, 3); index++) {
            candidates.add(fields[index]);
        }

        Set<String> parts = new LinkedHashSet<>();
        for (String candidate : candidates) {
            if (candidate != null) {
                String value = candidate.trim();
                if (!value.isEmpty() && !"0".equals(value)) {
                    parts.add(value);
                }
            }
        }
        return parts.isEmpty() ? UNKNOWN : String.join(" ", parts);
    }
}
