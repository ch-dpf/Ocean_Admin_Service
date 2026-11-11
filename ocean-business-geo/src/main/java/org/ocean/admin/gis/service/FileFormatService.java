package org.ocean.admin.gis.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ocean.admin.gis.entity.GisFileFormat;
import org.ocean.admin.gis.vo.GisFileGroupVO;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/** 统一格式目录、接收条件与读取参数规则；驱动探测不进入文件登记事务。 */
@Service
@Slf4j
@RequiredArgsConstructor
public class FileFormatService {
    private final GdalDatasetReader reader;
    private final EpsgService epsg;
    @Resource(name = "taskExecutor")
    private Executor executor;
    private final AtomicBoolean refreshing = new AtomicBoolean();
    private volatile Map<GisFileFormat, GdalDatasetReader.DriverCapability> capabilities = Map.of();

    /** 原生环境只在受限工作进程中检查，失败时明确关闭接收并定期恢复探测。 */
    @PostConstruct
    public synchronized void refreshCapabilities() {
        try {
            capabilities = Map.copyOf(reader.probeCapabilities());
        } catch (RuntimeException ex) {
            var failed = new EnumMap<GisFileFormat, GdalDatasetReader.DriverCapability>(GisFileFormat.class);
            String reason = "空间读取环境不可用，请检查服务配置后重试。";
            for (GisFileFormat format : GisFileFormat.values())
                failed.put(format, new GdalDatasetReader.DriverCapability(false, reason));
            capabilities = Map.copyOf(failed);
            log.warn("文件格式能力探测失败，暂停接收空间文件", ex);
        }
    }

    /** 探测可能等待原生进程，不能阻塞进度刷新和租约续期的调度线程。 */
    @Scheduled(fixedDelay = 60000, initialDelay = 60000)
    public void scheduleCapabilityRefresh() {
        if (!refreshing.compareAndSet(false, true)) return;
        try {
            executor.execute(() -> {
                try { refreshCapabilities(); }
                finally { refreshing.set(false); }
            });
        } catch (RuntimeException ex) {
            refreshing.set(false);
            log.warn("格式能力探测暂未调度，将在下一周期重试", ex);
        }
    }

    public List<GisFileGroupVO.FormatCapability> listCapabilities(Long categoryId) {
        var snapshot = capabilities;
        return Arrays.stream(GisFileFormat.values())
                .filter(format -> categoryId == null || format.supportsCategory(categoryId))
                .map(format -> {
                    var runtime = snapshot.getOrDefault(format,
                            new GdalDatasetReader.DriverCapability(false, "格式能力尚未就绪，请稍后重试。"));
                    return new GisFileGroupVO.FormatCapability(format.name(), format.categoryIds(),
                            format.entryExtensions().stream().sorted().toList(),
                            format.requiredExtensions().stream().sorted().toList(),
                            format.optionalExtensions().stream().sorted().toList(),
                            runtime.available(), runtime.reason(), format.readOptions(), format.notices());
                }).toList();
    }

    /** 返回拒绝原因；null 表示主文件或配套文件属于当前可解析格式。 */
    public String checkAcceptance(Long categoryId, String fileName) {
        String reason = "当前数据集不支持接收此格式：" + fileName;
        var snapshot = capabilities;
        for (GisFileFormat format : GisFileFormat.values()) {
            if (!format.supportsCategory(categoryId) || !format.acceptsMember(fileName)) continue;
            var runtime = snapshot.get(format);
            if (runtime != null && runtime.available()) return null;
            reason = runtime == null ? "格式能力尚未就绪，请稍后重试。" : runtime.reason();
        }
        return reason;
    }

    public String availabilityError(GisFileFormat format) {
        var runtime = capabilities.get(format);
        if (runtime == null) return "格式能力尚未就绪，请稍后重试。";
        return runtime.available() ? null : runtime.reason();
    }

    /** 保存阶段允许暂缺必填项，入队阶段才要求读取配置完整。 */
    public String validateReadOptions(GisFileFormat format, JsonNode options, boolean requireComplete) {
        if (options == null || !options.isObject()) return "读取配置必须是对象";
        for (var option : format.readOptions()) {
            var value = options.get(option.key());
            if (value != null && !value.isNull() && !value.isString()) return option.label() + "必须是文本";
            boolean supplied = value != null && !value.isNull() && !value.asText("").isBlank();
            if (supplied && option.requirement() == GisFileFormat.Requirement.FORBIDDEN)
                return format.name() + "不支持设置" + option.label();
            if (!supplied && requireComplete && option.requirement() == GisFileFormat.Requirement.REQUIRED)
                return "请明确" + option.label();
        }
        String crs = options.path("sourceCrs").asText("").trim();
        if (!crs.isEmpty() && !crs.matches("(?i)EPSG:[1-9][0-9]*|OGC:CRS84")
                && !crs.matches("(?is)(GEOGCS|PROJCS|GEOGCRS|PROJCRS|COMPOUNDCRS|BOUNDCRS|VERTCRS)\\s*\\[.*"))
            return "源坐标系必须是EPSG编码、OGC:CRS84或明确WKT，禁止文件和URL引用";
        if (crs.matches("(?i)EPSG:[1-9][0-9]*")) epsg.validateCode(crs);
        String x = options.path("xField").asText("").trim();
        String y = options.path("yField").asText("").trim();
        if (!x.isEmpty() && x.equals(y)) return "X、Y字段必须不同";
        return null;
    }
}
