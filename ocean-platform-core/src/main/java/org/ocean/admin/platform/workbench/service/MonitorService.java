package org.ocean.admin.platform.workbench.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sun.management.OperatingSystemMXBean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ocean.admin.platform.audit.entity.SysLoginLog;
import org.ocean.admin.platform.audit.entity.SysOperationLog;
import org.ocean.admin.platform.audit.mapper.SysLoginLogMapper;
import org.ocean.admin.platform.audit.mapper.SysOperationLogMapper;
import org.ocean.admin.platform.workbench.dto.ApiDailyFrequencyDTO;
import org.ocean.admin.platform.workbench.dto.DiskUsageDTO;
import org.ocean.admin.platform.workbench.dto.SystemMetricsDTO;
import org.ocean.admin.platform.workbench.dto.TrendFrequencyDTO;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 监控服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MonitorService {

    private final SysOperationLogMapper sysOperationLogMapper;
    private final SysLoginLogMapper sysLoginLogMapper;


    private static final DateTimeFormatter MONTH_LABEL_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM");

    public SystemMetricsDTO getRealtimeMetrics() {
        MemorySnapshot memorySnapshot = resolveMemoryUsage();
        return new SystemMetricsDTO(
                roundTwoDecimals(resolveCpuUsage()),
                roundTwoDecimals(resolveGpuUsage()),
                roundTwoDecimals(memorySnapshot.usagePercent()),
                roundTwoDecimals(bytesToGb(memorySnapshot.totalBytes())),
                roundTwoDecimals(bytesToGb(memorySnapshot.usedBytes())),
                roundTwoDecimals(bytesToGb(memorySnapshot.freeBytes())),
                System.currentTimeMillis()
        );
    }

    public DiskUsageDTO getDiskUsage() {
        try {
//            File uploadsRoot = resolveUploadsRoot();
//            Path path = uploadsRoot.toPath();
//            FileStore store = Files.getFileStore(path);
//            double totalGb = bytesToGb(store.getTotalSpace());
//            double freeGb = bytesToGb(store.getUsableSpace());
//            double usedGb = Math.max(0D, totalGb - freeGb);
//            String diskName = (store.name() == null || store.name().isBlank())
//                    ? String.valueOf(path.getRoot())
//                    : store.name();
//            return new DiskUsageDTO(diskName, roundOneDecimal(totalGb), roundOneDecimal(usedGb), roundOneDecimal(freeGb));
            return new DiskUsageDTO();
        } catch (Exception e) {
            log.warn("获取磁盘使用情况失败: {}", e.getMessage());
            return new DiskUsageDTO("服务磁盘", 0D, 0D, 0D);
        }
    }

    public List<ApiDailyFrequencyDTO> getApiDailyFrequency(int days) {
        int validDays = Math.max(1, Math.min(days, 31));
        LocalDate today = LocalDate.now();
        LocalDate startDate = today.minusDays(validDays - 1L);

        LambdaQueryWrapper<SysOperationLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysOperationLog::getDeleted, 0)
                .ge(SysOperationLog::getCreateTime, startDate.atStartOfDay())
                .likeRight(SysOperationLog::getRequestUrl, "/api/")
                .notLike(SysOperationLog::getRequestUrl, "/api/monitor/")
                .orderByAsc(SysOperationLog::getCreateTime);

        List<SysOperationLog> logs = sysOperationLogMapper.selectList(wrapper);
        Map<LocalDate, Long> counter = new LinkedHashMap<>();
        for (int i = 0; i < validDays; i++) {
            counter.put(startDate.plusDays(i), 0L);
        }
        for (SysOperationLog log : logs) {
            LocalDateTime createTime = log.getCreateTime();
            if (createTime == null) {
                continue;
            }
            LocalDate day = createTime.toLocalDate();
            if (counter.containsKey(day)) {
                counter.put(day, counter.get(day) + 1L);
            }
        }

        List<ApiDailyFrequencyDTO> result = new ArrayList<>();
        counter.forEach((date, count) -> result.add(new ApiDailyFrequencyDTO(date.toString(), count)));
        return result;
    }

    public List<TrendFrequencyDTO> getActivityTrend(String metric, String period, int size) {
        String safeMetric = metric == null ? "api" : metric.trim().toLowerCase();
        String safePeriod = period == null ? "daily" : period.trim().toLowerCase();

        if ("monthly".equals(safePeriod)) {
            int validMonths = Math.max(1, Math.min(size, 12));
            return "login".equals(safeMetric)
                    ? getLoginMonthlyFrequency(validMonths)
                    : getApiMonthlyFrequency(validMonths);
        }

        int validDays = Math.max(1, Math.min(size, 31));
        return "login".equals(safeMetric)
                ? getLoginDailyFrequency(validDays)
                : getApiDailyTrend(validDays);
    }

    private List<TrendFrequencyDTO> getApiDailyTrend(int days) {
        LocalDate today = LocalDate.now();
        LocalDate startDate = today.minusDays(days - 1L);

        LambdaQueryWrapper<SysOperationLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysOperationLog::getDeleted, 0)
                .ge(SysOperationLog::getCreateTime, startDate.atStartOfDay())
                .likeRight(SysOperationLog::getRequestUrl, "/api/")
                .notLike(SysOperationLog::getRequestUrl, "/api/monitor/")
                .orderByAsc(SysOperationLog::getCreateTime);

        List<SysOperationLog> logs = sysOperationLogMapper.selectList(wrapper);
        Map<LocalDate, Long> counter = new LinkedHashMap<>();
        for (int i = 0; i < days; i++) {
            counter.put(startDate.plusDays(i), 0L);
        }
        for (SysOperationLog log : logs) {
            LocalDateTime createTime = log.getCreateTime();
            if (createTime == null) {
                continue;
            }
            LocalDate day = createTime.toLocalDate();
            if (counter.containsKey(day)) {
                counter.put(day, counter.get(day) + 1L);
            }
        }

        List<TrendFrequencyDTO> result = new ArrayList<>();
        counter.forEach((date, count) -> result.add(new TrendFrequencyDTO(date.toString(), count)));
        return result;
    }

    private List<TrendFrequencyDTO> getApiMonthlyFrequency(int months) {
        YearMonth currentMonth = YearMonth.now();
        YearMonth startMonth = currentMonth.minusMonths(months - 1L);

        LambdaQueryWrapper<SysOperationLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysOperationLog::getDeleted, 0)
                .ge(SysOperationLog::getCreateTime, startMonth.atDay(1).atStartOfDay())
                .likeRight(SysOperationLog::getRequestUrl, "/api/")
                .notLike(SysOperationLog::getRequestUrl, "/api/monitor/")
                .orderByAsc(SysOperationLog::getCreateTime);

        List<SysOperationLog> logs = sysOperationLogMapper.selectList(wrapper);
        Map<YearMonth, Long> counter = new LinkedHashMap<>();
        for (int i = 0; i < months; i++) {
            counter.put(startMonth.plusMonths(i), 0L);
        }
        for (SysOperationLog log : logs) {
            LocalDateTime createTime = log.getCreateTime();
            if (createTime == null) {
                continue;
            }
            YearMonth month = YearMonth.from(createTime);
            if (counter.containsKey(month)) {
                counter.put(month, counter.get(month) + 1L);
            }
        }

        List<TrendFrequencyDTO> result = new ArrayList<>();
        counter.forEach((month, count) -> result.add(new TrendFrequencyDTO(month.format(MONTH_LABEL_FORMATTER), count)));
        return result;
    }

    private List<TrendFrequencyDTO> getLoginDailyFrequency(int days) {
        LocalDate today = LocalDate.now();
        LocalDate startDate = today.minusDays(days - 1L);

        LambdaQueryWrapper<SysLoginLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysLoginLog::getDeleted, 0)
                .eq(SysLoginLog::getStatus, 1)
                .ge(SysLoginLog::getLoginTime, startDate.atStartOfDay())
                .orderByAsc(SysLoginLog::getLoginTime);

        List<SysLoginLog> logs = sysLoginLogMapper.selectList(wrapper);
        Map<LocalDate, Long> counter = new LinkedHashMap<>();
        for (int i = 0; i < days; i++) {
            counter.put(startDate.plusDays(i), 0L);
        }
        for (SysLoginLog log : logs) {
            LocalDateTime loginTime = log.getLoginTime();
            if (loginTime == null) {
                continue;
            }
            LocalDate day = loginTime.toLocalDate();
            if (counter.containsKey(day)) {
                counter.put(day, counter.get(day) + 1L);
            }
        }

        List<TrendFrequencyDTO> result = new ArrayList<>();
        counter.forEach((date, count) -> result.add(new TrendFrequencyDTO(date.toString(), count)));
        return result;
    }

    private List<TrendFrequencyDTO> getLoginMonthlyFrequency(int months) {
        YearMonth currentMonth = YearMonth.now();
        YearMonth startMonth = currentMonth.minusMonths(months - 1L);

        LambdaQueryWrapper<SysLoginLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysLoginLog::getDeleted, 0)
                .eq(SysLoginLog::getStatus, 1)
                .ge(SysLoginLog::getLoginTime, startMonth.atDay(1).atStartOfDay())
                .orderByAsc(SysLoginLog::getLoginTime);

        List<SysLoginLog> logs = sysLoginLogMapper.selectList(wrapper);
        Map<YearMonth, Long> counter = new LinkedHashMap<>();
        for (int i = 0; i < months; i++) {
            counter.put(startMonth.plusMonths(i), 0L);
        }
        for (SysLoginLog log : logs) {
            LocalDateTime loginTime = log.getLoginTime();
            if (loginTime == null) {
                continue;
            }
            YearMonth month = YearMonth.from(loginTime);
            if (counter.containsKey(month)) {
                counter.put(month, counter.get(month) + 1L);
            }
        }

        List<TrendFrequencyDTO> result = new ArrayList<>();
        counter.forEach((month, count) -> result.add(new TrendFrequencyDTO(month.format(MONTH_LABEL_FORMATTER), count)));
        return result;
    }

    private double resolveCpuUsage() {
        try {
            OperatingSystemMXBean bean = ManagementFactory.getPlatformMXBean(OperatingSystemMXBean.class);
            if (bean == null) {
                return 0D;
            }
            double cpuLoad = bean.getCpuLoad();
            if (Double.isNaN(cpuLoad) || cpuLoad < 0D) {
                return 0D;
            }
            return Math.clamp(cpuLoad * 100D, 0D, 100D);
        } catch (Exception e) {
            log.debug("读取 CPU 占用率失败: {}", e.getMessage());
            return 0D;
        }
    }

    private double resolveGpuUsage() {
        Process process = null;
        try {
            process = new ProcessBuilder("nvidia-smi", "--query-gpu=utilization.gpu", "--format=csv,noheader,nounits")
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(2, TimeUnit.SECONDS);
            if (!finished || process.exitValue() != 0) {
                return 0D;
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                List<Double> values = new ArrayList<>();
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty()) {
                        continue;
                    }
                    try {
                        values.add(Double.parseDouble(trimmed));
                    } catch (NumberFormatException ignored) {
                        // ignore invalid line
                    }
                }
                if (values.isEmpty()) {
                    return 0D;
                }
                double sum = 0D;
                for (Double value : values) {
                    sum += value;
                }
                return Math.clamp(sum / values.size(), 0D, 100D);
            }
        } catch (Exception e) {
            log.debug("读取 GPU 占用率失败: {}", e.getMessage());
            return 0D;
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    private MemorySnapshot resolveMemoryUsage() {
        return resolvePhysicalMemoryUsage();
    }

    private MemorySnapshot resolvePhysicalMemoryUsage() {
        try {
            OperatingSystemMXBean bean = ManagementFactory.getPlatformMXBean(OperatingSystemMXBean.class);
            if (bean == null) {
                return MemorySnapshot.empty();
            }
            long totalBytes = Math.max(0L, bean.getTotalMemorySize());
            if (totalBytes <= 0L) {
                return MemorySnapshot.empty();
            }
            long freeBytes = Math.max(0L, Math.min(bean.getFreeMemorySize(), totalBytes));
            long usedBytes = Math.max(0L, totalBytes - freeBytes);
            return new MemorySnapshot(totalBytes, usedBytes, freeBytes);
        } catch (Exception e) {
            log.debug("读取系统内存占用率失败: {}", e.getMessage());
            return MemorySnapshot.empty();
        }
    }

    private long calculateDirectorySize(Path path) {
        if (path == null || !Files.exists(path)) {
            return 0L;
        }
        try (var stream = Files.walk(path)) {
            return stream
                    .filter(Files::isRegularFile)
                    .mapToLong(p -> {
                        try {
                            return Files.size(p);
                        } catch (IOException e) {
                            return 0L;
                        }
                    })
                    .sum();
        } catch (IOException e) {
            log.debug("统计目录大小失败, path={}: {}", path, e.getMessage());
            return 0L;
        }
    }

    private String resolveLeafDirectoryName(String rawPath, String fallback) {
        if (rawPath == null || rawPath.isBlank()) {
            return fallback;
        }
        String normalized = rawPath.replace('\\', '/');
        int idx = normalized.lastIndexOf('/');
        if (idx >= 0 && idx < normalized.length() - 1) {
            return normalized.substring(idx + 1);
        }
        return normalized.isBlank() ? fallback : normalized;
    }

    private double bytesToGb(long bytes) {
        return bytes / 1024D / 1024D / 1024D;
    }

    private double roundOneDecimal(double value) {
        return Math.round(value * 10D) / 10D;
    }

    private double roundTwoDecimals(double value) {
        return Math.round(value * 100D) / 100D;
    }


    private record MemorySnapshot(long totalBytes, long usedBytes, long freeBytes) {
        static MemorySnapshot empty() {
            return new MemorySnapshot(0L, 0L, 0L);
        }

        double usagePercent() {
            if (totalBytes <= 0L) {
                return 0D;
            }
            return (usedBytes * 100D) / totalBytes;
        }
    }
}

