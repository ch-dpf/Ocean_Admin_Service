package org.ocean.admin.platform.audit.service;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.lionsoul.ip2region.service.Config;
import org.lionsoul.ip2region.service.ConfigBuilder;
import org.lionsoul.ip2region.service.Ip2Region;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;

/**
 * 管理 ip2region 离线数据库查询服务。
 */
@Slf4j
@Component
public class LocalIpLocationProvider {

    private final Ip2Region ip2Region;

    public LocalIpLocationProvider(
            @Value("${ocean.audit.ip-location.ipv4-xdb-path:}") String ipv4XdbPath,
            @Value("${ocean.audit.ip-location.ipv6-xdb-path:}") String ipv6XdbPath) {
        Ip2Region queryService = null;
        try {
            Config ipv4Config = createConfig(ipv4XdbPath, false);
            Config ipv6Config = createConfig(ipv6XdbPath, true);
            if (ipv4Config != null || ipv6Config != null) {
                queryService = Ip2Region.create(ipv4Config, ipv6Config);
            }
        } catch (Exception exception) {
            log.error("初始化 ip2region 离线库失败，公网 IP 登录地点将记录为未知: {}", exception.getMessage());
        }
        this.ip2Region = queryService;

        if (ip2Region == null) {
            log.info("未配置可用的 ip2region 离线库，公网 IP 登录地点将记录为未知");
        }
    }

    public String search(String ipAddress) throws Exception {
        return ip2Region == null ? null : ip2Region.search(ipAddress);
    }

    @PreDestroy
    public void close() throws Exception {
        if (ip2Region != null) {
            ip2Region.close();
        }
    }

    private Config createConfig(String configuredPath, boolean ipv6) throws Exception {
        if (configuredPath == null || configuredPath.isBlank()) {
            return null;
        }

        File xdbFile = new File(configuredPath.trim());
        if (!xdbFile.isFile()) {
            log.warn("ip2region {} 离线库不存在或不是文件: {}",
                    ipv6 ? "IPv6" : "IPv4", xdbFile.getAbsolutePath());
            return null;
        }

        ConfigBuilder config = Config.custom()
                .setCachePolicy(Config.VIndexCache)
                .setSearchers(10)
                .setXdbFile(xdbFile);
        return ipv6 ? config.asV6() : config.asV4();
    }
}
