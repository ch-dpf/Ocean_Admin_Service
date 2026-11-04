package org.ocean.admin.gis.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.RequiredArgsConstructor;
import org.ocean.admin.gis.entity.*;
import org.ocean.admin.gis.mapper.GisVectorMapper;
import org.ocean.admin.gis.progress.ProgressStage;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/** 原始集合、工作存储转换与发布快照的生命周期；文件安装完成后才登记可读资源。 */
@Service
@RequiredArgsConstructor
public class VectorResourceService {
    private final GisVectorMapper mapper;
    private final GdalDatasetReader reader;
    private final GeoPackageStore store;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;
    private final VectorDataSetStyleService styles;
    private final VectorRenderPlanner planner;
    private final org.ocean.admin.gis.util.VectorTileEncoder encoder;

    public byte[] tile(Long dataSetId, GisVectorResource frozen, int z, int x, int y, int maxBytes) throws IOException {
        JsonNode definition = frozen == null ? null : json.readTree(frozen.getDefinitionJson());
        if (frozen != null) return store.tile(frozen.getStorageKey(),definition,z,x,y,maxBytes);
        JsonNode policy = planner.policy(styles.require(dataSetId, false));
        var tile = encoder.begin(z, x, y, policy, maxBytes);
        List<GisVectorLayer> layers = mapper.dataSetLayers(dataSetId);
        if (!layers.isEmpty() && layers.getFirst().getResourceId() != null
                && layers.stream().allMatch(layer -> layers.getFirst().getResourceId().equals(layer.getResourceId()))) {
            var shared = require(layers.getFirst().getResourceId());
            if ("GPKG".equals(shared.getStorageType())) {
                var current = json.createObjectNode();
                current.set("tilePolicy", policy);
                current.set("layers", json.valueToTree(layers));
                current.set("groups", json.valueToTree(mapper.groups(dataSetId)));
                return store.tile(shared.getStorageKey(), current, z, x, y, maxBytes);
            }
        }
        var groups = new java.util.HashMap<Long,String>();
        mapper.groups(dataSetId).forEach(group -> groups.put(group.getId(),group.getGroupKey()));
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
        for (var layer : layers) {
            long after=0;
            while (true) {
                if (System.nanoTime()>deadline) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"文件瓦片生成超时，请缩小数据层级范围");
                List<GisVectorFeature> batch;
                if (layer.getResourceId()!=null && "GPKG".equals(require(layer.getResourceId()).getStorageType()))
                    batch=features(layer,after,100,tile.bbox(),null);
                else batch=mapper.tileFeatures(layer.getId(),after,tile.bbox());
                if (batch.isEmpty()) break;
                for (var feature : batch) tile.add(feature,layer,groups.get(layer.getGroupId()));
                after=batch.getLast().getId();
            }
        }
        return tile.finish();
    }

    public void capture(GisFileMeta source, GisFileGroup group, List<GisFileMeta> members,
                        Runnable guard, Runnable heartbeat, Consumer<ProgressStage> progress) throws IOException {
        if (mapper.sourceResource(source.getId()) != null) return;
        try (var context = reader.prepareSnapshot(group, members, heartbeat, progress)) {
            context.verifySources();
            long id = IdWorker.getId();
            store.install(context.snapshotPath(), id);
            GisVectorResource resource = resource(id, source.getDataSetId(), source.getId(), "SOURCE", context.packageResult());
            try {
                transactions.executeWithoutResult(tx -> {
                    guard.run();
                    if (mapper.sourceResource(source.getId()) != null) throw new IllegalStateException("原始快照已创建");
                    rollbackCleanup(id);
                    mapper.insertResource(resource);
                    for (JsonNode node : context.packageResult().path("layers")) {
                        var layer = new GisVectorSourceLayer();
                        layer.setId(IdWorker.getId()); layer.setResourceId(id);
                        layer.setLayerKey(node.path("key").asText()); layer.setLayerName(node.path("name").asText());
                        layer.setTableName(node.path("table").asText()); layer.setFeatureCount(node.path("featureCount").asLong());
                        layer.setSourceCrs(node.path("sourceCrs").asText(null)); layer.setSchemaJson(json.writeValueAsString(node));
                        mapper.insertSourceLayer(layer);
                    }
                });
            } catch (RuntimeException ex) { Files.deleteIfExists(store.path(id + ".gpkg")); throw ex; }
        }
    }

    public List<GisVectorResource> list(Long dataSetId) {
        styles.require(dataSetId, false);
        return mapper.resources(dataSetId);
    }

    public List<GisVectorSourceLayer> sourceLayers(Long fileId) {
        var resource = mapper.sourceResource(fileId);
        if (resource == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "原始快照尚未生成");
        return mapper.sourceLayers(resource.getId());
    }

    public JsonNode sourceFeatures(Long sourceLayerId, long after, int limit, Long fid) throws IOException {
        var layer = mapper.sourceLayer(sourceLayerId);
        if (layer == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "原始要素集合不存在");
        var resource = require(layer.getResourceId());
        if (!"SOURCE".equals(resource.getResourceKind())) throw new IllegalArgumentException("非原始要素集合");
        if (limit < 1 || limit > 1000 || after < 0 || fid != null && fid < 1) throw new IllegalArgumentException("分页参数无效");
        JsonNode rows = store.query(resource.getStorageKey(), layer.getTableName(), after, limit + 1, null, fid);
        ObjectNode result = json.createObjectNode().put("sourceLayerId", sourceLayerId.toString())
                .put("sourceCrs", layer.getSourceCrs()).put("dataVersion", resource.getDataVersion())
                .put("hasMore", rows.path("items").size() > limit);
        var features = result.putArray("features"); long last = after;
        for (JsonNode row : rows.path("items")) {
            if (features.size() == limit) break;
            last = row.path("fid").asLong();
            // 文本供浏览器无损查看原始属性中的大整数，避免 JavaScript 数值精度截断。
            features.addObject().put("sourceRowId", Long.toString(last))
                    .put("featureJson", json.writerWithDefaultPrettyPrinter().writeValueAsString(row.path("value")))
                    .set("feature", row.path("value"));
        }
        if (rows.path("items").size() > limit) result.put("nextAfter", Long.toString(last));
        return result;
    }

    public GisVectorResource require(Long id) {
        var resource = mapper.resource(id);
        if (resource == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "矢量资源版本不存在");
        return resource;
    }

    public void verifySourceSnapshot(Long fileId) throws IOException {
        var resource=mapper.sourceResource(fileId);
        if(resource==null || !resource.getChecksum().equals(store.checksum(resource.getStorageKey())))
            throw new IllegalStateException("原始快照缺失或校验失败，禁止释放原文件");
    }

    @Transactional(rollbackFor=Exception.class)
    public void retire(Long id) {
        var resource=require(id);styles.require(resource.getDataSetId(),true);
        if("SOURCE".equals(resource.getResourceKind())) throw new IllegalArgumentException("原始快照保留来源追溯，不通过工作资源接口删除");
        if(mapper.resourceReferences(id)>0) throw new ResponseStatusException(HttpStatus.CONFLICT,"资源仍被工作图层或发布记录引用");
        mapper.retireResource(id);
    }

    public void cleanupRetired() {
        for(var resource:mapper.retiredResources()) {
            if(mapper.resourceReferences(resource.getId())>0) continue;
            try {Files.deleteIfExists(store.path(resource.getStorageKey()));mapper.markResourceCleaned(resource.getId());}
            catch(IOException ex) {org.slf4j.LoggerFactory.getLogger(VectorResourceService.class).warn("矢量旧版本清理待重试: {}",resource.getId(),ex);}
        }
    }

    public List<GisVectorFeature> features(GisVectorLayer layer, long after, int limit, double[] bbox, Long fid) throws IOException {
        if (layer.getResourceId() == null || "POSTGIS".equals(require(layer.getResourceId()).getStorageType())) {
            if (fid != null) {
                var feature = mapper.feature(layer.getId(), fid);
                return feature == null ? List.of() : List.of(feature);
            }
            if (bbox != null) return mapper.features(layer.getId(), after, limit, bbox[0],bbox[1],bbox[2],bbox[3]);
            return mapper.exportFeatures(layer.getId(), after, limit);
        }
        var resource = require(layer.getResourceId());
        return java.util.stream.StreamSupport.stream(store.query(resource.getStorageKey(), "l_" + layer.getId(),
                        after, limit, bbox, fid).path("items").spliterator(), false)
                .map(row -> json.treeToValue(row.path("value"), GisVectorFeature.class)).toList();
    }

    public void requireEditable(GisVectorLayer layer) {
        if (layer.getResourceId() != null && "GPKG".equals(require(layer.getResourceId()).getStorageType()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前为只读GPKG，请先显式转入PostGIS编辑存储");
    }

    /** 所有编辑与转换均持有数据集样式行锁，复制期间不会混入不同的数据版本。 */
    @Transactional(rollbackFor = Exception.class)
    public GisVectorResource convert(Long dataSetId, String target, String ifMatch) throws IOException {
        var state = styles.require(dataSetId, true);
        VectorDataSetStyleService.requireMatch(ifMatch, "\"storage-" + dataSetId + "-" + state.getDataRevision() + "\"");
        if (!Set.of("GPKG", "POSTGIS").contains(target)) throw new IllegalArgumentException("存储类型须为GPKG或POSTGIS");
        List<GisVectorLayer> layers = mapper.dataSetLayers(dataSetId);
        if (layers.isEmpty()) throw new IllegalArgumentException("没有可转换的工作图层");
        GisVectorResource resource;
        if ("GPKG".equals(target)) {
            // 要素覆盖采用独立样式方案后才能移出外键约束的PostGIS要素表。
            if (mapper.hasFeatureCartography(dataSetId)) throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "请先将要素配图保存为独立样式方案并移除工作区要素覆盖，再转换存储");
            resource = freeze(dataSetId, "WORKING");
            for (var layer : layers) {
                mapper.assignResource(layer.getId(), resource.getId());
                mapper.purgeLayerFeatures(layer.getId());
            }
        } else {
            resource = new GisVectorResource(); resource.setId(IdWorker.getId()); resource.setDataSetId(dataSetId);
            resource.setResourceKind("WORKING"); resource.setStorageType("POSTGIS"); resource.setDataVersion(state.getDataRevision());
            resource.setSizeBytes(0L); resource.setDefinitionJson("{}"); mapper.insertResource(resource);
            var packaged = layers.stream().filter(layer -> layer.getResourceId() != null
                    && "GPKG".equals(require(layer.getResourceId()).getStorageType())).toList();
            forEachBatch(packaged, mapper::insertFeatures);
            for (var layer : layers) {
                mapper.assignResource(layer.getId(), resource.getId());
                mapper.refreshLayer(layer.getId());
            }
        }
        mapper.bumpDataSetRevision(dataSetId, true, false);
        for(var layer:layers) if(layer.getResourceId()!=null && mapper.resourceReferences(layer.getResourceId())==0)
            mapper.retireResource(layer.getResourceId());
        return require(resource.getId());
    }

    /** 固定整个数据集的要素及分类契约；样式单独保存在发布绑定中。 */
    @Transactional(rollbackFor = Exception.class)
    public GisVectorResource freeze(Long dataSetId, String kind) throws IOException {
        var state = styles.require(dataSetId, true);
        var definition = json.createObjectNode();
        definition.set("map", json.valueToTree(planner.definition(state)));
        definition.set("tilePolicy", planner.policy(state));
        definition.set("groups", json.valueToTree(mapper.groups(dataSetId)));
        var layers = mapper.dataSetLayers(dataSetId);
        if (layers.isEmpty()) throw new IllegalArgumentException("数据集没有可发布的图层");
        var layerNodes = definition.putArray("layers");
        for (var layer : layers) {
            var node = (ObjectNode) json.valueToTree(layer); node.put("table", "l_" + layer.getId()); layerNodes.add(node);
        }
        long id = IdWorker.getId();
        rollbackCleanup(id);
        try (var writer = Files.newBufferedWriter(store.path(id + ".ndjson"))) {
            forEachBatch(layers, batch -> {
                for (var feature : batch) { writer.write(json.writeValueAsString(feature)); writer.newLine(); }
            });
        }
        store.build(id, layerNodes, () -> { });
        var resource = resource(id, dataSetId, null, kind, definition);
        resource.setDataVersion(state.getDataRevision()); mapper.insertResource(resource);
        return require(id);
    }

    @FunctionalInterface
    private interface FeatureBatchConsumer { void accept(List<GisVectorFeature> batch) throws IOException; }

    /** 批量复制始终有界内存；每个包只打开一次，PostGIS按主键游标读取。 */
    private void forEachBatch(List<GisVectorLayer> layers, FeatureBatchConsumer consumer) throws IOException {
        var packages = new java.util.LinkedHashMap<Long, List<Long>>();
        for (var layer : layers) {
            if (layer.getResourceId() != null && "GPKG".equals(require(layer.getResourceId()).getStorageType())) {
                packages.computeIfAbsent(layer.getResourceId(), key -> new java.util.ArrayList<>()).add(layer.getId());
                continue;
            }
            long after = 0;
            while (true) {
                var batch = mapper.exportFeatures(layer.getId(), after, 100);
                if (batch.isEmpty()) break;
                consumer.accept(batch);
                after = batch.getLast().getId();
            }
        }
        for (var entry : packages.entrySet()) {
            long temporaryId = IdWorker.getId();
            var output = store.path(temporaryId + ".ndjson");
            try {
                store.export(require(entry.getKey()).getStorageKey(), entry.getValue(), temporaryId);
                try (var input = Files.newBufferedReader(output)) {
                    var batch = new java.util.ArrayList<GisVectorFeature>(100);
                    String line;
                    while ((line = input.readLine()) != null) {
                        batch.add(json.readValue(line, GisVectorFeature.class));
                        if (batch.size() == 100) { consumer.accept(batch); batch.clear(); }
                    }
                    if (!batch.isEmpty()) consumer.accept(batch);
                }
            } finally { Files.deleteIfExists(output); }
        }
    }

    private GisVectorResource resource(long id, Long dataSetId, Long fileId, String kind, JsonNode definition) throws IOException {
        var resource = new GisVectorResource(); resource.setId(id); resource.setDataSetId(dataSetId);
        resource.setSourceFileId(fileId); resource.setResourceKind(kind); resource.setStorageType("GPKG");
        resource.setStorageKey(id + ".gpkg"); resource.setDataVersion(1L); resource.setStatus("READY");
        resource.setChecksum(store.checksum(resource.getStorageKey())); resource.setSizeBytes(Files.size(store.path(resource.getStorageKey())));
        resource.setDefinitionJson(json.writeValueAsString(definition)); return resource;
    }

    private void rollbackCleanup(long id) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) throw new IllegalStateException("资源登记必须在事务中执行");
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                if (status == STATUS_COMMITTED) return;
                for (String suffix : List.of(".gpkg", ".partial", ".ndjson")) {
                    try { Files.deleteIfExists(store.path(id + suffix)); }
                    catch (IOException ex) { org.slf4j.LoggerFactory.getLogger(VectorResourceService.class).warn("回滚后矢量临时资源清理失败: {}", id, ex); }
                }
            }
        });
    }
}
