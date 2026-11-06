package org.ocean.admin.gis.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.ocean.admin.gis.entity.GisFileMeta;
import org.ocean.admin.gis.entity.GisVectorFeature;
import org.ocean.admin.gis.entity.GisVectorGroup;
import org.ocean.admin.gis.entity.GisVectorLayer;
import org.ocean.admin.gis.mapper.GisVectorMapper;
import org.ocean.admin.gis.progress.ProgressStage;
import org.ocean.admin.gis.progress.ProgressUnit;
import org.ocean.admin.gis.util.GeoJsonFeatureReader;
import org.ocean.admin.gis.util.VectorTemplateRules;
import org.ocean.admin.gis.vo.GisVectorVO;
import org.ocean.admin.kernel.common.PageResult;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/** 矢量来源导入及图层查询、编辑；写入时保持要素与汇总版本一致。 */
@Service
@RequiredArgsConstructor
public class VectorLayerService {
    private static final long MAX_PAGE_BYTES = 8L * 1024 * 1024;
    private final GisVectorMapper mapper;
    private final ObjectMapper json;
    private final GeoJsonFeatureReader reader;
    private final VectorTemplateRules templateRules;
    private final VectorDataSetStyleService dataSetStyles;
    private final TransactionTemplate transactions;
    private final VectorResourceService resources;

    /** 一个源图层对应一份已转换为 WGS84 的标准 GeoJSON，源键须在同一主入口内唯一且稳定。 */
    public record ImportLayer(String sourceLayerKey, String sourceLayerName, Path geoJsonPath, long featureCount) {
        public ImportLayer {
            if (sourceLayerKey == null || sourceLayerKey.isBlank()) throw new IllegalArgumentException("源图层键不能为空");
            Objects.requireNonNull(geoJsonPath, "源图层 GeoJSON 路径不能为空");
            if (featureCount < 0) throw new IllegalArgumentException("源图层要素数量不能为负数");
        }
    }

    /** 提取结果由文件元数据生命周期统一提交，导入服务不提前将图层标记为 READY。 */
    public record VectorExtraction(List<Long> layerIds, long sourceFeatures, long parts, long unknownParts,
            long warnings, long emptyGeometryFeatures, Map<String, Long> groupCounts) {
        public VectorExtraction {
            layerIds = List.copyOf(layerIds);
            groupCounts = Map.copyOf(groupCounts);
        }
    }

    /** 向文件元数据生命周期保留矢量领域的明确失败原因。 */
    public static final class ImportException extends RuntimeException {
        private final String code;

        private ImportException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() { return code; }
    }

    /**
     * 主入口文件保留来源身份，各源图层独立分类，避免重复的要素序号和几何路径发生冲突。
     * claimGuard 必须在当前事务内锁定并验证文件组和文件租约；长时间读取仅通过心跳续期。
     */
    public VectorExtraction importSource(GisFileMeta source, List<ImportLayer> inputLayers, Runnable claimGuard,
            Runnable heartbeat, Consumer<ProgressStage> progress) throws IOException {
        Objects.requireNonNull(source, "主入口文件不能为空");
        Objects.requireNonNull(claimGuard, "导入租约校验不能为空");
        Objects.requireNonNull(heartbeat, "导入心跳不能为空");
        Objects.requireNonNull(progress, "导入进度不能为空");
        List<ImportLayer> inputs = List.copyOf(inputLayers);
        Set<String> sourceKeys = new HashSet<>();
        long totalFeatures = 0;
        for (ImportLayer input : inputs) {
            if (!sourceKeys.add(input.sourceLayerKey())) throw new IllegalArgumentException("源图层键重复: " + input.sourceLayerKey());
            totalFeatures = Math.addExact(totalFeatures, input.featureCount());
        }
        long progressTotal = Math.max(1, totalFeatures);
        progress.accept(ProgressStage.determinate("VECTOR_IMPORTING", "源要素处理与入库", 0, progressTotal, ProgressUnit.FEATURE));
        var snapshot = dataSetStyles.require(source.getDataSetId(), false);
        if (!Objects.equals(snapshot.getId(), source.getVectorDatasetStyleId())
                || source.getVectorPlacement() == null || !Set.of("AUTO", "UNKNOWN", "X_LIGHT", "X_DARK").contains(source.getVectorPlacement())) {
            throw new ImportException("MISSING_VECTOR_IMPORT_CONFIG", "文件未固定样式与归组方式，禁止猜测或重导存量数据");
        }
        var template = dataSetStyles.compile(snapshot);
        Map<String, GisVectorGroup> targets = mapper.groups(source.getDataSetId()).stream()
                .collect(Collectors.toMap(GisVectorGroup::getGroupKey, group -> group));
        transactions.executeWithoutResult(status -> {
            claimGuard.run();
            if (mapper.layersBySource(source.getId()).stream().anyMatch(layer -> "READY".equals(layer.getLayerStatus()))) {
                throw new ImportException("VECTOR_ALREADY_EXTRACTED", "已完成图层不可被元数据重试覆盖");
            }
            mapper.deleteExtractingLayersBySource(source.getId());
            mapper.retireUnusedWorkingResources(source.getId());
        });
        Map<String, GisVectorLayer> layers = new LinkedHashMap<>();
        Map<String, Long> counts = new LinkedHashMap<>();
        List<GisVectorFeature> batch = new ArrayList<>();
        long[] statistics = {0, 0, 0, 0};
        long[] committedParts = {0};
        long[] processedFeatures = {0};
        long[] emptyGeometryFeatures = {0};
        long sourceFeatures = 0;
        long resourceId = IdWorker.getId();
        Path inputPath = resources.workingInput(resourceId);
        try (var output = java.nio.file.Files.newBufferedWriter(inputPath)) {
            for (ImportLayer input : inputs) {
                String layerName = importLayerName(source, input);
                Long sourceLayerId = resources.sourceLayers(source.getId()).stream()
                        .filter(layer -> input.sourceLayerKey().equals(layer.getLayerKey())).map(layer -> layer.getId())
                        .findFirst().orElseThrow(() -> new IllegalStateException("原始集合身份缺失"));
                sourceFeatures += reader.read(input.geoJsonPath(), parsed -> {
                    JsonNode properties = json.readTree(parsed.properties());
                    JsonNode geometry = json.readTree(parsed.geometry());
                    if (geometry.isNull()) {
                        emptyGeometryFeatures[0]++;
                        statistics[3]++;
                    }
                    for (var part : geometry.isNull() ? List.<VectorTemplateRules.GeometryPart>of() : templateRules.parts(geometry)) {
                        var match = templateRules.classify(template, properties, part.kind(), source.getVectorPlacement(), layerName);
                        var group = targets.get(match.groupKey());
                        if (group == null) throw new IllegalStateException("数据集缺少模板分类: " + match.groupKey());
                        String layerKey = importLayerKey(input.sourceLayerKey(), match.groupKey(), part.kind());
                        GisVectorLayer layer = layers.get(layerKey);
                        if (layer == null) {
                            layer = new GisVectorLayer();
                            layer.setId(IdWorker.getId()); layer.setDataSetId(source.getDataSetId()); layer.setSourceFileId(source.getId());
                            layer.setSourceLayerId(sourceLayerId);
                            layer.setGroupId(group.getId()); layer.setOriginalGroupId(group.getId()); layer.setGeometryType(part.kind());
                            layer.setAssignmentMode("AUTO");
                            boolean unknown = "UNKNOWN_CLASSIFICATION".equals(group.getNodeType());
                            layer.setEnabled(!unknown && template.groups().get(match.groupKey()).path("defaultLayerEnabled").asBoolean());
                            layer.setStyleMode(unknown ? "UNCONFIGURED" : "INHERIT_TARGET_GROUP");
                            layer.setLayerKey(layerKey); layer.setLayerName(layerName);
                            GisVectorLayer created = layer;
                            transactions.executeWithoutResult(status -> {
                                claimGuard.run();
                                if (mapper.insertLayer(created) != 1) throw new IllegalStateException("分类图层创建失败");
                            });
                            layers.put(layerKey, layer);
                        }
                        GisVectorFeature feature = new GisVectorFeature();
                        feature.setId(IdWorker.getId()); feature.setLayerId(layer.getId());
                        feature.setSourceLayerId(sourceLayerId);
                        feature.setGeometryJson(json.writeValueAsString(part.geometry())); feature.setPropertiesJson(parsed.properties());
                        feature.setSourceFeatureId(parsed.sourceId()); feature.setSourceFeatureIndex(parsed.index());
                        feature.setSourceGeometryPath(part.path()); feature.setClassificationRuleKey(match.ruleKey());
                        feature.setVersion(1L);
                        feature.setCreateTime(java.time.LocalDateTime.now()); feature.setUpdateTime(feature.getCreateTime());
                        batch.add(feature);
                        statistics[0] += feature.getGeometryJson().length() + parsed.properties().length();
                        statistics[1]++;
                        if ("UNKNOWN_CLASSIFICATION".equals(group.getNodeType())) statistics[2]++;
                        if (match.warning()) statistics[3]++;
                        counts.merge(match.groupKey(), 1L, Long::sum);
                        if (batch.size() >= 100 || statistics[0] >= 2 * 1024 * 1024) {
                            committedParts[0] += flushImportBatch(batch, claimGuard, output);
                            statistics[0] = 0;
                        }
                    }
                    processedFeatures[0]++;
                    progress.accept(ProgressStage.determinate("VECTOR_IMPORTING", "源要素处理与入库；已提交几何 " + committedParts[0] + " 个",
                            Math.min(processedFeatures[0], progressTotal), progressTotal, ProgressUnit.FEATURE));
                }, heartbeat);
            }
            committedParts[0] += flushImportBatch(batch, claimGuard, output);
        } catch (IOException | RuntimeException ex) {
            java.nio.file.Files.deleteIfExists(inputPath);
            throw ex;
        }
        try {
            progress.accept(ProgressStage.determinate("VECTOR_IMPORTING", "源要素处理结束；已提交几何 " + committedParts[0] + " 个",
                    sourceFeatures, Math.max(progressTotal, sourceFeatures), ProgressUnit.FEATURE));
            if (sourceFeatures == 0 || layers.isEmpty()) throw new ImportException("EMPTY_GEOMETRY", "来源不包含可制图要素");
            resources.importWorking(resourceId, source, List.copyOf(layers.values()), claimGuard, heartbeat);
            return new VectorExtraction(layers.values().stream().map(GisVectorLayer::getId).toList(),
                    sourceFeatures, statistics[1], statistics[2], statistics[3], emptyGeometryFeatures[0], counts);
        } finally { java.nio.file.Files.deleteIfExists(inputPath); }
    }

    private int flushImportBatch(List<GisVectorFeature> batch, Runnable claimGuard, java.io.BufferedWriter output) {
        if (batch.isEmpty()) return 0;
        int size = batch.size();
        transactions.executeWithoutResult(status -> {
            claimGuard.run();
            var error = mapper.firstGeometryError(batch);
            if (error != null) throw new GeoJsonFeatureReader.FeatureReadException(error.featureIndex(), error.reason());
        });
        try {
            for (var feature : batch) { output.write(json.writeValueAsString(feature)); output.newLine(); }
        } catch (IOException ex) { throw new IllegalStateException("工作包要素写入失败", ex); }
        batch.clear();
        return size;
    }

    private String importLayerKey(String sourceKey, String groupKey, String kind) {
        String classificationKey = groupKey + ":" + kind;
        if ("default".equals(sourceKey)) return classificationKey;
        String key = "source:" + sourceKey + ":" + classificationKey;
        if (key.length() <= 255) return key;
        // 长源键只压缩来源部分，分类和几何类别保留可读；独立前缀避免与原始短键冲突。
        try {
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sourceKey.getBytes(StandardCharsets.UTF_8)));
            return "source-sha256:" + digest + ":" + classificationKey;
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("运行环境缺少 SHA-256", ex);
        }
    }

    private String importLayerName(GisFileMeta source, ImportLayer input) {
        String name = input.sourceLayerName();
        if ("default".equals(input.sourceLayerKey()) || name == null || name.isBlank()) {
            name = source.getOriginalName();
            if (name != null) {
                int suffix = name.lastIndexOf('.');
                if (suffix > 0) name = name.substring(0, suffix);
            }
        }
        name = name == null ? "矢量图层" : name.trim();
        if (name.isBlank()) name = "矢量图层";
        return name.substring(0, name.offsetByCodePoints(0, Math.min(100, name.codePointCount(0, name.length()))));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<List<GisVectorVO.Layer>> page(Long dataSetId, Long groupId, Long sourceFileId, int current, int size) {
        if (current < 1 || size < 1 || size > 100 || (dataSetId != null && dataSetId < 1)) throw badRequest("页码须大于0，每页1至100条");
        if (groupId != null && (dataSetId == null || groupId < 1 || mapper.group(dataSetId, groupId) == null))
            throw badRequest("分组查询须指定正确的数据集及分组");
        if (sourceFileId != null && sourceFileId < 1) throw badRequest("来源文件ID无效");
        Page<GisVectorLayer> page = mapper.pageLayers(new Page<>(current, size), dataSetId, groupId, sourceFileId);
        return new PageResult<>(page.getCurrent(), page.getSize(), page.getTotal(), page.getRecords().stream().map(this::toVO).toList());
    }

    public GisVectorVO.Layer detail(Long id) { return toVO(requireLayer(id, false)); }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public GisVectorVO.FeatureCollection features(Long layerId, String bbox, int limit, String cursor) {
        if (limit < 1 || limit > 1000) throw badRequest("limit 须为1至1000");
        GisVectorLayer layer = ready(layerId, false);
        Double[] box = parseBox(bbox);
        String queryKey = Arrays.toString(box);
        long after = decodeCursor(cursor, layer, queryKey);
        List<GisVectorFeature> rows;
        try {
            rows = resources.features(layer, after, limit + 1, new double[]{box[0], box[1], box[2], box[3]}, null);
        } catch (IOException ex) { throw new IllegalStateException("工作要素读取失败", ex); }
        List<GisVectorVO.Feature> features = new ArrayList<>();
        long bytes = 0;
        boolean more = false;
        long last = after;
        for (GisVectorFeature row : rows) {
            long rowBytes = (row.getGeometryJson() == null ? 0 : row.getGeometryJson().getBytes(StandardCharsets.UTF_8).length)
                    + (row.getPropertiesJson() == null ? 4 : row.getPropertiesJson().getBytes(StandardCharsets.UTF_8).length) + 256L;
            if (features.size() == limit || (bytes + rowBytes > MAX_PAGE_BYTES && !features.isEmpty())) { more = true; break; }
            features.add(toFeature(row));
            bytes += rowBytes;
            last = row.getId();
        }
        String next = more ? encodeCursor(layer, last, queryKey) : null;
        return new GisVectorVO.FeatureCollection("FeatureCollection", features, features.size(), more, next, layer.getDataVersion());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public GisVectorVO.Feature feature(Long layerId, Long id) {
        ready(layerId, false);
        return toFeature(requireFeature(layerId, id));
    }

    @Transactional(rollbackFor = Exception.class)
    public GisVectorVO.Feature createFeature(Long layerId, JsonNode body) {
        GisVectorLayer layer = ready(layerId, true);
        resources.requireEditable(layer);
        GisVectorFeature feature = parsedFeature(layerId, body);
        requireKind(layer, body);
        feature.setId(IdWorker.getId());
        feature.setVersion(1L); feature.setCreateTime(java.time.LocalDateTime.now()); feature.setUpdateTime(feature.getCreateTime());
        validateTopology(feature);
        boolean packaged = packaged(layer);
        if (!packaged) mapper.insertFeatures(List.of(feature));
        reclassify(layer,feature,!packaged);
        resources.edit(layer, null, feature);
        if (!packaged) mapper.refreshLayer(layerId);
        mapper.bumpDataSetRevision(layer.getDataSetId(), true, false);
        return toFeature(feature);
    }

    @Transactional(rollbackFor = Exception.class)
    public GisVectorVO.Feature updateFeature(Long layerId, Long id, String ifMatch, JsonNode body) {
        GisVectorLayer layer = ready(layerId, true);
        resources.requireEditable(layer);
        GisVectorFeature existing = requireFeature(layerId, id);
        requireMatch(ifMatch, featureEtag(id, existing.getVersion()));
        if (body != null && body.has("id") && !id.toString().equals(body.get("id").asText())) throw badRequest("Feature.id 与路径不一致");
        GisVectorFeature feature = parsedFeature(layerId, body);
        requireKind(layer, body);
        feature.setId(id);
        feature.setVersion(existing.getVersion() + 1);
        feature.setSourceLayerId(existing.getSourceLayerId()); feature.setSourceFeatureId(existing.getSourceFeatureId());
        feature.setSourceFeatureIndex(existing.getSourceFeatureIndex()); feature.setSourceGeometryPath(existing.getSourceGeometryPath());
        feature.setClassificationRuleKey(existing.getClassificationRuleKey());
        feature.setCreateTime(existing.getCreateTime()); feature.setUpdateTime(java.time.LocalDateTime.now());
        // XYZ 编辑须显式提交高程，防止二维拖动误删已有 Z。
        JsonNode oldGeometry = json.readTree(existing.getGeometryJson());
        if (coordinateDimension(oldGeometry) != coordinateDimension(body.get("geometry"))) throw badRequest("编辑须保留原要素的 XY/XYZ 坐标维度");
        validateTopology(feature);
        boolean packaged = packaged(layer);
        if (!packaged && mapper.updateFeature(feature, existing.getVersion()) != 1) throw preconditionFailed();
        reclassify(layer,feature,!packaged);
        resources.edit(layer, existing, feature);
        if (!packaged) mapper.refreshLayer(layerId);
        mapper.bumpDataSetRevision(layer.getDataSetId(), true, false);
        return toFeature(feature);
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteFeature(Long layerId, Long id, String ifMatch) {
        GisVectorLayer layer = ready(layerId, true);
        resources.requireEditable(layer);
        GisVectorFeature existing = requireFeature(layerId, id);
        requireMatch(ifMatch, featureEtag(id, existing.getVersion()));
        boolean packaged = packaged(layer);
        if (!packaged && mapper.deleteFeature(layerId, id, existing.getVersion()) != 1) throw preconditionFailed();
        resources.edit(layer, existing, null);
        if (!packaged) mapper.refreshLayer(layerId);
        mapper.bumpDataSetRevision(layer.getDataSetId(), true, false);
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteLayer(Long id, String ifMatch) {
        GisVectorLayer layer = requireLayer(id, true);
        if ("IMPORTING".equals(layer.getLayerStatus())) throw new ResponseStatusException(HttpStatus.CONFLICT, "正在导入的图层不能删除");
        requireMatch(ifMatch, layerEtag(id, layer.getDataVersion(), layer.getPresentationVersion()));
        mapper.deleteLayer(id);
        mapper.purgeLayerFeatures(id);
        if (layer.getResourceId() != null && mapper.resourceReferences(layer.getResourceId()) == 0)
            mapper.retireResource(layer.getResourceId());
        mapper.bumpDataSetRevision(layer.getDataSetId(), true, true);
    }

    public GisVectorVO.Layer toVO(GisVectorLayer layer) {
        String storageType = layer.getResourceId() == null ? "POSTGIS" : resources.require(layer.getResourceId()).getStorageType();
        return new GisVectorVO.Layer(layer.getId().toString(), layer.getDataSetId().toString(),
                layer.getSourceFileId() == null ? null : layer.getSourceFileId().toString(),
                layer.getLayerKey(), layer.getLayerName(), layer.getLayerStatus(), layer.getFeatureCount(),
                tree(layer.getGeographicExtent()), tree(layer.getGeometryTypes()), layer.getDataVersion(), layer.getCreateTime(), layer.getUpdateTime(),
                layer.getGroupId().toString(), layer.getOriginalGroupId().toString(), layer.getGeometryType(), layer.getAssignmentMode(),
                Boolean.TRUE.equals(layer.getEnabled()), layer.getStyleMode(), layer.getPresentationVersion(),
                layer.getSourceLayerId() == null ? null : layer.getSourceLayerId().toString(),
                layer.getResourceId() == null ? null : layer.getResourceId().toString(), storageType,
                layer.getResourceId() != null && !"WORKING".equals(resources.require(layer.getResourceId()).getResourceKind()));
    }

    public static String featureEtag(Long id, long version) { return "\"feature-" + id + "-v" + version + "\""; }
    public static String layerEtag(Long id, long version, long presentation) { return "\"layer-" + id + "-d" + version + "-p" + presentation + "\""; }

    private GisVectorLayer ready(Long id, boolean lock) {
        GisVectorLayer layer = requireLayer(id, lock);
        if (lock) resources.finishPending(layer.getDataSetId());
        if (!"READY".equals(layer.getLayerStatus())) throw new ResponseStatusException(HttpStatus.CONFLICT, "图层尚未完成导入");
        return layer;
    }

    private GisVectorLayer requireLayer(Long id, boolean lock) {
        if (id == null || id < 1) throw badRequest("图层ID须大于0");
        if (lock) {
            var existing = mapper.layer(id);
            if (existing == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "图层不存在或已删除");
            if (mapper.lockDataSetStyle(existing.getDataSetId()) == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "数据集尚未初始化样式");
        }
        GisVectorLayer layer = lock ? mapper.lockLayer(id) : mapper.layer(id);
        if (layer == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "图层不存在或已删除");
        if (layer.getGroupId() == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "存量图层尚未分类，须单独迁移");
        return layer;
    }

    private boolean packaged(GisVectorLayer layer) {
        return layer.getResourceId() != null && "GPKG".equals(resources.require(layer.getResourceId()).getStorageType());
    }

    private Long reclassify(GisVectorLayer source, GisVectorFeature feature, boolean persist) {
        if (!"AUTO".equals(source.getAssignmentMode())) return source.getId();
        var original = mapper.group(source.getDataSetId(),source.getOriginalGroupId());
        if (original != null && "UNKNOWN_CLASSIFICATION".equals(original.getNodeType())) return source.getId();
        var template = dataSetStyles.compile(dataSetStyles.require(source.getDataSetId(),false));
        String placement = original == null ? "AUTO" : template.groups().get(original.getGroupKey()).path("placement").asText("AUTO");
        var classification = templateRules.classify(template, json.readTree(feature.getPropertiesJson()), source.getGeometryType(), placement, source.getLayerName());
        var group = mapper.groups(source.getDataSetId()).stream()
                .filter(g -> g.getGroupKey().equals(classification.groupKey())).findFirst().orElseThrow();
        var target = source;
        if (!group.getId().equals(source.getGroupId())) {
            target = mapper.layersBySource(source.getSourceFileId()).stream()
                    .filter(l -> group.getId().equals(l.getGroupId()) && "AUTO".equals(l.getAssignmentMode())
                        && Objects.equals(l.getSourceLayerId(), source.getSourceLayerId()) && Objects.equals(l.getResourceId(), source.getResourceId())
                        && source.getGeometryType().equals(l.getGeometryType()) && "READY".equals(l.getLayerStatus()))
                    .findFirst().orElse(null);
            if (target == null) {
                target = new GisVectorLayer();
                target.setId(IdWorker.getId()); target.setDataSetId(source.getDataSetId());
                target.setSourceFileId(source.getSourceFileId()); target.setGroupId(group.getId());
                target.setResourceId(source.getResourceId()); target.setSourceLayerId(source.getSourceLayerId());
                target.setOriginalGroupId(group.getId()); target.setGeometryType(source.getGeometryType());
                target.setAssignmentMode("AUTO"); target.setLayerKey("edited-" + target.getId());
                target.setLayerName(source.getLayerName());
                boolean unknown = "UNKNOWN_CLASSIFICATION".equals(group.getNodeType());
                target.setEnabled(!unknown);
                target.setStyleMode(unknown ? "UNCONFIGURED" : "INHERIT_TARGET_GROUP");
                mapper.insertLayer(target);
                mapper.setLayerStatus(target.getId(),"READY");
            }
        }
        if (persist && mapper.reclassifyFeature(feature.getId(),source.getId(),target.getId(),classification.ruleKey()) != 1)
            throw preconditionFailed();
        feature.setLayerId(target.getId()); feature.setClassificationRuleKey(classification.ruleKey());
        if (!target.getId().equals(source.getId())) {
            if (persist) mapper.refreshLayer(target.getId());
            mapper.bumpDataSetRevision(source.getDataSetId(),false,true);
        }
        return target.getId();
    }

    private void requireKind(GisVectorLayer layer, JsonNode body) {
        try {
            if (!layer.getGeometryType().equals(templateRules.kind(body.get("geometry")))) throw badRequest("几何类别与图层不一致");
        } catch (IllegalArgumentException ex) { throw badRequest(ex.getMessage()); }
    }

    private GisVectorFeature requireFeature(Long layerId, Long id) {
        if (id == null || id < 1) throw badRequest("要素ID须大于0");
        GisVectorFeature feature;
        try { feature = resources.features(ready(layerId, false), 0, 1, null, id).stream().findFirst().orElse(null); }
        catch (IOException ex) { throw new IllegalStateException("工作要素读取失败", ex); }
        if (feature == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "图层内不存在该要素");
        return feature;
    }

    private GisVectorFeature parsedFeature(Long layerId, JsonNode body) {
        try {
            var parsed = reader.parseFeature(body, 0);
            GisVectorFeature feature = new GisVectorFeature();
            feature.setLayerId(layerId);
            feature.setGeometryJson(parsed.geometry());
            feature.setPropertiesJson(parsed.properties());
            return feature;
        } catch (IllegalArgumentException ex) { throw badRequest(ex.getMessage()); }
    }

    private void validateTopology(GisVectorFeature feature) {
        var error = mapper.firstGeometryError(List.of(feature));
        if (error != null) throw badRequest("无效几何: " + error.reason());
    }

    private GisVectorVO.Feature toFeature(GisVectorFeature feature) {
        return new GisVectorVO.Feature("Feature", feature.getId().toString(), tree(feature.getGeometryJson()),
                tree(feature.getPropertiesJson()), feature.getVersion(), feature.getLayerId().toString(),
                feature.getSourceFeatureIndex() == null ? null : new GisVectorVO.SourceReference(feature.getSourceFeatureIndex(),
                        tree(feature.getSourceFeatureId()), feature.getSourceGeometryPath(), feature.getClassificationRuleKey(),
                        feature.getSourceLayerId() == null ? null : feature.getSourceLayerId().toString()));
    }

    private JsonNode tree(String value) { return value == null ? null : json.readTree(value); }

    private void requireMatch(String value, String expected) {
        if (value == null || value.isBlank()) throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED, "编辑或删除必须携带详情响应中的 If-Match/ETag");
        if (!expected.equals(value.trim())) throw preconditionFailed();
    }

    private ResponseStatusException preconditionFailed() {
        return new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "数据版本已变化，请重新加载后编辑");
    }

    private Double[] parseBox(String value) {
        if (value == null) throw badRequest("属性分页查询必须指定bbox，地图显示请使用数据集瓦片");
        try {
            String[] parts = value.split(",", -1);
            if (parts.length != 4) throw new IllegalArgumentException();
            Double[] box = new Double[4];
            for (int i = 0; i < 4; i++) {
                box[i] = Double.parseDouble(parts[i]);
                if (!Double.isFinite(box[i])) throw new IllegalArgumentException();
            }
            if (Math.abs(box[0]) > 180 || Math.abs(box[2]) > 180 || Math.abs(box[1]) > 90
                    || Math.abs(box[3]) > 90 || box[1] >= box[3] || box[0].doubleValue() == box[2].doubleValue()) throw new IllegalArgumentException();
            return box;
        } catch (IllegalArgumentException ex) { throw badRequest("bbox 须为 west,south,east,north；west>east 表示跨日期变更线"); }
    }

    private String encodeCursor(GisVectorLayer layer, long lastId, String queryKey) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                (layer.getId() + ":" + layer.getDataVersion() + ":" + lastId + ":" + queryKey).getBytes(StandardCharsets.UTF_8));
    }

    private long decodeCursor(String value, GisVectorLayer layer, String queryKey) {
        if (value == null) return 0;
        if (value.length() > 1024) throw badRequest("cursor 无效");
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8).split(":", 4);
            if (parts.length != 4 || Long.parseLong(parts[0]) != layer.getId() || !queryKey.equals(parts[3])) throw new IllegalArgumentException();
            if (Long.parseLong(parts[1]) != layer.getDataVersion()) throw new ResponseStatusException(HttpStatus.CONFLICT, "图层已编辑，请从首页重新查询");
            long after = Long.parseLong(parts[2]);
            if (after < 1) throw new IllegalArgumentException();
            return after;
        } catch (IllegalArgumentException ex) { throw badRequest("cursor 与图层或查询范围不匹配"); }
    }

    private int coordinateDimension(JsonNode geometry) {
        if ("GeometryCollection".equals(geometry.path("type").asText())) return coordinateDimension(geometry.path("geometries").get(0));
        JsonNode position = geometry.get("coordinates");
        while (position.get(0).isArray()) position = position.get(0);
        return position.size();
    }

    private ResponseStatusException badRequest(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}
