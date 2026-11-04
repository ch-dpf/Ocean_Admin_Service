package org.ocean.admin.gis.service;
import java.io.IOException;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.geotools.referencing.CRS;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.io.WKTWriter;
import org.ocean.admin.gis.entity.GisFileMeta;
import org.ocean.admin.gis.entity.GisFileGroup;
import org.ocean.admin.gis.entity.GisDataSet;
import org.ocean.admin.gis.entity.GisFileSpatialMetadata;
import org.ocean.admin.gis.mapper.GisFileMetaMapper;
import org.ocean.admin.gis.mapper.GisFileGroupMapper;
import org.ocean.admin.gis.mapper.GisDataSetMapper;
import org.ocean.admin.gis.mapper.GisFileSpatialMetadataMapper;
import org.ocean.admin.gis.mapper.GisVectorMapper;
import org.ocean.admin.gis.processing.input.ControlledWorkspaceResolver;
import org.ocean.admin.gis.util.GeoJsonFeatureReader;
import org.ocean.admin.gis.util.RasterFootprintBuilder;
import org.ocean.admin.gis.vo.GisFileMetaVO;
import org.ocean.admin.gis.progress.ProgressStage;
import org.ocean.admin.gis.progress.ProgressUnit;
import org.springframework.stereotype.Service;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;
import java.util.*;
import java.util.stream.Collectors;
import java.util.function.Consumer;

/** 文件空间元数据及矢量内容提取生命周期；文件读取不占用数据库事务。 */
@Service
@Slf4j
@RequiredArgsConstructor
public class FileSpatialMetadataService {
    private final VectorResourceService vectorResources;
    private static final GeometryFactory GEOMETRIES = new GeometryFactory(new PrecisionModel(), 4326);
    private final GisFileSpatialMetadataMapper spatialMapper;
    private final RasterSpatialMetadataExtractor rasterExtractor;
    private final FileGroupService fileGroups;
    private final GdalDatasetReader datasetReader;
    private final VectorLayerService vectorLayers;
    private final GeoJsonFeatureReader geoJsonReader;
    private final GisFileMetaMapper fileMapper;
    private final GisFileGroupMapper groupMapper;
    private final GisDataSetMapper dataSetMapper;
    private final ControlledWorkspaceResolver sources;
    private final GisVectorMapper vectorMapper;
    private final VectorDataSetStyleService vectorDataSetStyles;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;
    private final ApplicationEventPublisher events;

    /** null表示领取或心跳，true/false表示已提交的解析或删除结果。事件不创建业务任务。 */
    public record MetadataProgress(List<Long> fileIds, Boolean succeeded) {
        public MetadataProgress { fileIds = List.copyOf(fileIds); }
    }

    public record MetadataReady(List<Long> fileIds) {
        public MetadataReady { fileIds = List.copyOf(fileIds); }
    }

    public record MetadataWork(Long fileId, long version, ProgressStage stage) { }

    /** 扫描结束后一次放行；过期或已被删除的等待文件不再获得执行资格。 */
    public void releaseIngestion(String progressId) {
        List<Long> released = fileGroups.assembleIngestion(progressId);
        if (!released.isEmpty()) events.publishEvent(new MetadataReady(released));

    }

    public void renewIngestionWaiting(String progressId) {
        spatialMapper.renewIngestionWaiting(progressId, LocalDateTime.now().plusMinutes(15));
    }

    /** 接入前置阶段失去活动后明确失败，保留文件供删除后重新录入。 */
    public void expireIngestionWaiting() {
        transactions.executeWithoutResult(status -> {
            List<Long> expired = spatialMapper.expireIngestionWaiting();
            if (!expired.isEmpty()) events.publishEvent(new MetadataProgress(expired, false));
        });
    }

    public void extract(Long fileId) {
        GisFileMeta claim = transactions.execute(status -> spatialMapper.claim(fileId, LocalDateTime.now().plusMinutes(15)));
        if (claim == null) return;
        GisFileGroup group = fileGroups.startExtraction(claim);
        if (group == null) return;
        List<GisFileMeta> members = fileGroups.members(group.getId());
        List<Long> memberIds = members.stream().map(GisFileMeta::getId).toList();
        events.publishEvent(new MetadataProgress(memberIds, null));
        Consumer<ProgressStage> progress = metadataProgress(claim);
        Runnable heartbeat = leaseHeartbeat(claim);
        Runnable claimGuard = () -> {
            // 与删除和任务提交保持同一锁顺序，避免建层外键回锁数据集时形成环路。
            if (dataSetMapper.selectActiveByIdForUpdate(claim.getDataSetId()) == null
                    || !fileGroups.lockCurrent(group) || spatialMapper.lockClaim(fileId, claim.getMetadataVersion()) == null)
                throw new MetadataException("LEASE_LOST", "文件组输入版本或解析租约已失效");
        };
        List<GisFileSpatialMetadata> metadata = new ArrayList<>();
        VectorLayerService.VectorExtraction vectorResult = null;
        String errorCode = null;
        String errorMessage = null;
        try (var context = prepareWithSnapshot(claim, group, members, claimGuard, heartbeat, progress)) {
            progress.accept(ProgressStage.determinate("METADATA_READING", "读取文件组空间信息", 0, 1, ProgressUnit.OPERATION));
            List<VectorLayerService.ImportLayer> vectorInputs = new ArrayList<>();
            for (var source : context.vectors()) {
                heartbeat.run();
                GisFileSpatialMetadata item = newMetadata(claim, source.key(), source.name());
                vector(source.geoJsonPath(), item, heartbeat, progress);
                preserveSource(item, source.sourceCrs(), source.sourceExtent(), source.metadata(), group.getFormat());
                metadata.add(item);
                long count = objectMapper.readTree(item.getExtraMetadata()).path("featureCount").asLong();
                vectorInputs.add(new VectorLayerService.ImportLayer(source.key(), source.name(), source.geoJsonPath(), count));
            }
            if (!vectorInputs.isEmpty()) {
                vectorResult = vectorLayers.importSource(claim, vectorInputs, claimGuard, heartbeat, progress);
                for (GisFileSpatialMetadata item : metadata) {
                    var extra = (tools.jackson.databind.node.ObjectNode) objectMapper.readTree(item.getExtraMetadata());
                    extra.set("vectorImport", objectMapper.valueToTree(vectorResult));
                    extra.put("vectorPlacement", claim.getVectorPlacement());
                    extra.put("vectorDatasetStyleId", claim.getVectorDatasetStyleId().toString());
                    item.setExtraMetadata(objectMapper.writeValueAsString(extra));
                }
            }
            GisDataSet dataSet = dataSetMapper.selectById(claim.getDataSetId());
            if (dataSet == null) throw new MetadataException("MISSING_DATASET", "文件所属数据集不存在");
            for (var source : context.rasters()) {
                heartbeat.run();
                GisFileSpatialMetadata item = newMetadata(claim, source.key(), source.name());
                rasterExtractor.extract(source.geoTiffPath(), item, Long.valueOf(1).equals(dataSet.getCategoryId()), heartbeat, progress);
                preserveSource(item, source.sourceCrs(), source.sourceExtent(), source.metadata(), group.getFormat());
                metadata.add(item);
            }
            if (metadata.isEmpty()) throw new MetadataException("EMPTY_DATASET", "文件组中没有可定位的空间图层");
            context.verifySources();
        } catch (ControlledWorkspaceResolver.SourceException ex) {
            errorCode = ex.code(); errorMessage = ex.getMessage();
        } catch (GdalDatasetReader.ReaderException ex) {
            errorCode = ex.code(); errorMessage = ex.getMessage();
        } catch (VectorLayerService.ImportException ex) {
            errorCode = ex.code(); errorMessage = ex.getMessage();
        } catch (MetadataException ex) {
            if ("LEASE_LOST".equals(ex.code)) return;
            errorCode = ex.code; errorMessage = ex.getMessage();
        } catch (RasterSpatialMetadataExtractor.ExtractionException ex) {
            errorCode = ex.code(); errorMessage = ex.getMessage();
        } catch (RasterFootprintBuilder.FootprintException ex) {
            errorCode = ex.code(); errorMessage = ex.getMessage();
        } catch (Exception ex) {
            errorCode = "EXTRACTION_FAILED"; errorMessage = ex.getMessage();
            log.warn("文件组空间元数据提取失败: groupId={}, fileId={}", group.getId(), fileId, ex);
        }
        List<GisFileSpatialMetadata> extracted = errorCode == null ? List.copyOf(metadata) : List.of();
        VectorLayerService.VectorExtraction extractedVector = errorCode == null ? vectorResult : null;
        String code = errorCode;
        String message = errorMessage;
        if (code == null) progress.accept(ProgressStage.determinate("COMMITTING", "提交文件组空间信息与导入结果", 0, 1, ProgressUnit.OPERATION));
        transactions.executeWithoutResult(status -> {
            if (dataSetMapper.selectActiveByIdForUpdate(claim.getDataSetId()) == null || !fileGroups.lockCurrent(group)) return;
            GisFileMeta locked = spatialMapper.lockClaim(fileId, claim.getMetadataVersion());
            if (locked == null) return;
            if (code != null) vectorMapper.deleteExtractingLayersBySource(fileId);
            spatialMapper.deleteByFileId(fileId);
            for (GisFileSpatialMetadata item : extracted) spatialMapper.insert(item);
            GisFileMeta point = extracted.isEmpty() ? null : spatialMapper.selectRepresentativePoint(fileId);
            if (!extracted.isEmpty() && point == null) throw new IllegalStateException("空间元数据缺少代表点: " + fileId);
            if (extractedVector != null) {
                vectorDataSetStyles.require(claim.getDataSetId(), true);
                for (Long layerId : extractedVector.layerIds()) {
                    if (vectorMapper.refreshLayer(layerId) != 1 || vectorMapper.setLayerStatus(layerId, "READY") != 1)
                        throw new IllegalStateException("矢量图层提取结果提交失败: " + fileId);
                }
                vectorMapper.bumpDataSetRevision(claim.getDataSetId(), true, true);
            }
            String result = code == null ? "COMPLETED" : "FAILED";
            fileMapper.update(null, new LambdaUpdateWrapper<GisFileMeta>()
                    .eq(GisFileMeta::getId, fileId)
                    .set(GisFileMeta::getMetadataStatus, result)
                    .set(GisFileMeta::getMetadataLeaseExpiresAt, null)
                    .set(GisFileMeta::getMetadataErrorCode, code)
                    .set(GisFileMeta::getMetadataErrorMessage, abbreviate(message))
                    .set(GisFileMeta::getMetadataExtractedAt, LocalDateTime.now())
                    .set(GisFileMeta::getRepresentativeLongitude, point == null ? null : point.getRepresentativeLongitude())
                    .set(GisFileMeta::getRepresentativeLatitude, point == null ? null : point.getRepresentativeLatitude())
                    .set(GisFileMeta::getRepresentativeLayerKey, point == null ? null : point.getRepresentativeLayerKey())
                    .set(GisFileMeta::getRepresentativeExtentKind, point == null ? null : point.getRepresentativeExtentKind())
                    .set(GisFileMeta::getUpdateTime, LocalDateTime.now()));
            fileGroups.finishExtraction(group, result, code, message);
            events.publishEvent(new MetadataProgress(memberIds, code == null));
        });
        progress.accept(code == null ? ProgressStage.determinate("COMMITTING", "文件组导入完成", 1, 1, ProgressUnit.OPERATION)
                : ProgressStage.indeterminate("FAILED", message));
    }

    private GdalDatasetReader.ReadContext prepareWithSnapshot(GisFileMeta claim, GisFileGroup group,
            List<GisFileMeta> members, Runnable guard, Runnable heartbeat, Consumer<ProgressStage> progress) throws IOException {
        if (org.ocean.admin.gis.entity.GisFileFormat.valueOf(group.getFormat()).isVector())
            vectorResources.capture(claim, group, members, guard, heartbeat, progress);
        return datasetReader.prepare(group, members, heartbeat, progress);
    }

    private GisFileSpatialMetadata newMetadata(GisFileMeta source, String key, String name) {
        GisFileSpatialMetadata result = new GisFileSpatialMetadata();
        result.setId(IdWorker.getId()); result.setFileMetaId(source.getId());
        result.setLayerKey(key); result.setLayerName(name);
        result.setCalculationDetails("{}"); result.setExtraMetadata("{}");
        return result;
    }

    /** 标准化输入仅用于执行，来源坐标与原始描述必须单独保留。 */
    private void preserveSource(GisFileSpatialMetadata metadata, String sourceCrs,
            JsonNode sourceExtent, JsonNode sourceDetails, String format) throws Exception {
        if (sourceDetails == null || sourceDetails.isNull()) return;
        if (sourceCrs != null && !sourceCrs.isBlank()) {
            var crs = sourceCrs.startsWith("EPSG:") || sourceCrs.startsWith("OGC:")
                    ? CRS.decode(sourceCrs, true) : CRS.parseWKT(sourceCrs);
            metadata.setSourceCrsWkt(crs.toWKT());
            try { metadata.setSourceCrsCode(CRS.lookupIdentifier(crs, true)); }
            catch (Exception ignored) { metadata.setSourceCrsCode(null); }
            metadata.setSourceUnit(crs.getCoordinateSystem().getAxis(0).getUnit().toString());
        }
        // 未知源范围用 JSON null 表示，不能保留标准化数据的经纬度范围并标成源投影。
        metadata.setSourceExtent(sourceExtent == null ? "null" : objectMapper.writeValueAsString(sourceExtent));
        var extra = (tools.jackson.databind.node.ObjectNode) objectMapper.readTree(metadata.getExtraMetadata());
        extra.put("sourceFormat", format);
        extra.set("sourceMetadata", sourceDetails);
        metadata.setExtraMetadata(objectMapper.writeValueAsString(extra));
    }
    /** 按文件来源发现不可用时立即使空间查询失效，同时撤销旧提取任务的提交资格。 */
    public void markSourceUnavailable(Long fileId, String code, String message) {
        transactions.executeWithoutResult(status -> {
            GisFileMeta located = fileMapper.selectById(fileId);
            if (located == null || !"WORKSPACE".equals(located.getSourceType())) return;
            if (dataSetMapper.selectActiveByIdForUpdate(located.getDataSetId()) == null) return;
            located = fileMapper.selectById(fileId);
            if (located == null) return;
            GisFileGroup group = located.getFileGroupId() == null ? null : groupMapper.lock(located.getFileGroupId());
            Long entryId = group == null || group.getEntryFileId() == null ? fileId : group.getEntryFileId();
            GisFileMeta source = fileMapper.selectActiveByIdForUpdate(entryId);
            if (source == null) return;
            if (group != null) {
                boolean hadSuccess = "COMPLETED".equals(group.getParseStatus())
                        || "REIMPORT_REQUIRED".equals(group.getParseStatus()) || vectorMapper.countSourceReferences(entryId) > 0;
                group.setInputRevision(group.getInputRevision() + 1);
                group.setFingerprint(null);
                group.setAssemblyStatus("BLOCKED");
                group.setParseStatus(hadSuccess ? "REIMPORT_REQUIRED" : "PARKED");
                group.setErrorCode(code); group.setErrorMessage(abbreviate(message));
                groupMapper.updateState(group);
            }
            fileMapper.update(null, new LambdaUpdateWrapper<GisFileMeta>()
                    .eq(GisFileMeta::getId, entryId)
                    .set(GisFileMeta::getMetadataStatus, "FAILED")
                    .setSql("metadata_version = metadata_version + 1")
                    .set(GisFileMeta::getMetadataLeaseExpiresAt, null)
                    .set(GisFileMeta::getRepresentativeLongitude, null)
                    .set(GisFileMeta::getRepresentativeLatitude, null)
                    .set(GisFileMeta::getRepresentativeLayerKey, null)
                    .set(GisFileMeta::getRepresentativeExtentKind, null)
                    .set(GisFileMeta::getMetadataErrorCode, code)
                    .set(GisFileMeta::getMetadataErrorMessage, abbreviate(message))
                    .set(GisFileMeta::getUpdateTime, LocalDateTime.now()));
            List<Long> affected = group == null ? List.of(fileId)
                    : fileGroups.members(group.getId()).stream().map(GisFileMeta::getId).toList();
            events.publishEvent(new MetadataProgress(affected, false));
        });
    }
    private Consumer<ProgressStage> metadataProgress(GisFileMeta claim) {
        return ProgressStage.throttled(work -> {
            try {
                GisFileMeta current = fileMapper.selectById(claim.getId());
                if (current == null || !Objects.equals(current.getMetadataVersion(), claim.getMetadataVersion())
                        || !"RUNNING".equals(current.getMetadataStatus())
                        || current.getMetadataLeaseExpiresAt() == null
                        || !current.getMetadataLeaseExpiresAt().isAfter(LocalDateTime.now())) return;
                events.publishEvent(new MetadataWork(claim.getId(), claim.getMetadataVersion(), work));
            } catch (RuntimeException ex) {
                log.warn("文件解析进度通知失败: fileId={}, reason={}", claim.getId(), ex.getMessage());
            }
        });
    }

    public void populate(List<GisFileMetaVO> records, boolean includeGeometry) {
        if (records.isEmpty()) return;
        Map<Long, List<GisFileSpatialMetadata>> grouped = spatialMapper.selectByFileIds(
                        records.stream().map(GisFileMetaVO::getId).toList(), includeGeometry).stream()
                .collect(Collectors.groupingBy(GisFileSpatialMetadata::getFileMetaId));
        for (GisFileMetaVO record : records) {
            record.setSpatialReferences(grouped.getOrDefault(record.getId(), List.of()).stream()
                    .map(item -> new GisFileMetaVO.SpatialReference(item.getLayerKey(), item.getLayerName(),
                            item.getDataKind(), item.getSourceCrsCode(), item.getSourceCrsWkt(), item.getSourceUnit(),
                            json(item.getSourceExtent()), item.getGeographicExtent(), item.getExtentKind(),
                            item.getWest(), item.getSouth(), item.getEast(), item.getNorth(),
                            item.getRasterWidth(), item.getRasterHeight(), item.getBandCount(),
                            item.getResolutionX(), item.getResolutionY(), item.getResolutionUnit(),
                            item.getTileMatrixSetId(), item.getTileWidth(), item.getTileHeight(),
                            item.getRecommendedMaxZoom(), json(item.getCalculationDetails()), json(item.getExtraMetadata())))
                    .toList());
        }
    }

    private JsonNode json(String value) {
        return value == null ? null : objectMapper.readTree(value);
    }

    private Runnable leaseHeartbeat(GisFileMeta claim) {
        long[] renewedAt = {System.nanoTime()};
        return () -> {
            long now = System.nanoTime();
            if (now - renewedAt[0] < TimeUnit.SECONDS.toNanos(30)) return;
            if (spatialMapper.renewLease(claim.getId(), claim.getMetadataVersion(), LocalDateTime.now().plusMinutes(15)) != 1) {
                throw new MetadataException("LEASE_LOST", "解析任务租约已失效");
            }
            renewedAt[0] = now;
            events.publishEvent(new MetadataProgress(List.of(claim.getId()), null));
        };
    }

    private void vector(Path path, GisFileSpatialMetadata metadata, Runnable heartbeat, Consumer<ProgressStage> progress) throws Exception {
        VectorBounds bounds = new VectorBounds();
        long totalBytes = Files.size(path);
        progress.accept(ProgressStage.determinate("GEOJSON_PARSING", "解析 GeoJSON 空间范围", 0, totalBytes, ProgressUnit.BYTE));
        try (JsonParser parser = objectMapper.createParser(path.toFile())) {
            if (parser.nextToken() != JsonToken.START_OBJECT) throw new MetadataException("INVALID_GEOJSON", "GeoJSON 根节点必须为对象");
            readGeoJsonObject(parser, bounds, () -> {
                heartbeat.run();
                long offset = parser.currentLocation().getByteOffset();
                if (offset >= 0) progress.accept(ProgressStage.determinate("GEOJSON_PARSING", "解析 GeoJSON 空间范围",
                        Math.min(offset, totalBytes - 1), totalBytes, ProgressUnit.BYTE));
            }, 0);
            if (parser.nextToken() != null) throw new MetadataException("INVALID_GEOJSON", "GeoJSON 根节点之后存在额外内容");
        }
        if (bounds.extent.isNull()) throw new MetadataException("EMPTY_GEOMETRY", "GeoJSON 不包含可定位的几何坐标");
        metadata.setDataKind("VECTOR");
        metadata.setExtentKind("VECTOR_ENVELOPE");
        metadata.setSourceCrsCode("OGC:CRS84");
        metadata.setSourceCrsWkt(CRS.decode("EPSG:4326", true).toWKT());
        metadata.setSourceUnit("degree");
        metadata.setSourceExtent(objectMapper.writeValueAsString(Map.of(
                "lowerCorner", new double[]{bounds.extent.getMinX(), bounds.extent.getMinY()},
                "upperCorner", new double[]{bounds.extent.getMaxX(), bounds.extent.getMaxY()})));
        metadata.setGeographicExtent(new WKTWriter().write(GEOMETRIES.toGeometry(bounds.extent)));
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("featureCount", bounds.features);
        extra.put("geometryTypes", bounds.types);
        if (bounds.declaredCrs != null) extra.put("declaredCrs", bounds.declaredCrs);
        metadata.setExtraMetadata(objectMapper.writeValueAsString(extra));
        progress.accept(ProgressStage.determinate("GEOJSON_PARSING", "GeoJSON 空间范围解析完成", totalBytes, totalBytes, ProgressUnit.BYTE));
    }

    /** 流式读取几何坐标，跳过属性，不把整个大型 GeoJSON 加载进内存。 */
    private void readGeoJsonObject(JsonParser parser, VectorBounds bounds, Runnable heartbeat, int depth) throws Exception {
        if (depth > 64) throw new MetadataException("INVALID_GEOJSON", "GeoJSON 嵌套层级过深");
        heartbeat.run();
        String type = null;
        Set<String> members = new HashSet<>();
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            if (parser.currentToken() != JsonToken.PROPERTY_NAME) throw new MetadataException("INVALID_GEOJSON", "GeoJSON 对象结构无效");
            String name = parser.currentName();
            JsonToken value = parser.nextToken();
            switch (name) {
                case "type" -> {
                    if (value != JsonToken.VALUE_STRING) throw new MetadataException("INVALID_GEOJSON", "GeoJSON type 必须为字符串");
                    type = parser.getString();
                }
                case "crs" -> {
                    try {
                        if (depth == 0 && bounds.declaredCrs != null) throw new IllegalArgumentException("文件顶层 crs 不能重复");
                        bounds.declaredCrs = geoJsonReader.readSourceCrs(parser, depth);
                    } catch (IllegalArgumentException ex) {
                        throw new MetadataException("UNSUPPORTED_CRS", ex.getMessage());
                    }
                }
                case "features", "geometries" -> {
                    members.add(name);
                    if (value != JsonToken.START_ARRAY) throw new MetadataException("INVALID_GEOJSON", name + " 必须为数组");
                    while (parser.nextToken() != JsonToken.END_ARRAY) {
                        if (parser.currentToken() != JsonToken.START_OBJECT) throw new MetadataException("INVALID_GEOJSON", "GeoJSON 数组成员必须为对象");
                        readGeoJsonObject(parser, bounds, heartbeat, depth + 1);
                    }
                }
                case "geometry" -> {
                    members.add(name);
                    if (value == JsonToken.START_OBJECT) readGeoJsonObject(parser, bounds, heartbeat, depth + 1);
                    else if (value != JsonToken.VALUE_NULL) throw new MetadataException("INVALID_GEOJSON", "geometry 必须为对象或 null");
                }
                case "coordinates" -> {
                    members.add(name);
                    if (value != JsonToken.START_ARRAY) throw new MetadataException("INVALID_GEOJSON", "coordinates 必须为数组");
                    readCoordinates(parser, bounds.extent, heartbeat, 0);
                }
                default -> parser.skipChildren();
            }
        }
        if (type == null) throw new MetadataException("INVALID_GEOJSON", "GeoJSON 缺少 type");
        switch (type) {
            case "FeatureCollection" -> requireMember(members, "features");
            case "Feature" -> { requireMember(members, "geometry"); bounds.features++; }
            case "GeometryCollection" -> { requireMember(members, "geometries"); bounds.types.add(type); }
            case "Point", "MultiPoint", "LineString", "MultiLineString", "Polygon", "MultiPolygon" -> {
                requireMember(members, "coordinates"); bounds.types.add(type);
            }
            default -> throw new MetadataException("INVALID_GEOJSON", "未知 GeoJSON 类型: " + type);
        }
    }

    private void requireMember(Set<String> members, String member) {
        if (members.size() != 1 || !members.contains(member)) throw new MetadataException("INVALID_GEOJSON", "GeoJSON 几何成员与 type 不匹配");
    }

    private void readCoordinates(JsonParser parser, Envelope extent, Runnable heartbeat, int depth) throws Exception {
        if (depth > 8) throw new MetadataException("INVALID_GEOJSON", "坐标数组嵌套过深");
        heartbeat.run();
        int count = 0;
        double longitude = 0;
        double latitude = 0;
        boolean nested = false;
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            if (parser.currentToken() == JsonToken.START_ARRAY) {
                if (count > 0) throw new MetadataException("INVALID_GEOJSON", "坐标数组结构不一致");
                nested = true;
                readCoordinates(parser, extent, heartbeat, depth + 1);
            } else if (parser.currentToken() != null && parser.currentToken().isNumeric() && !nested) {
                double coordinate = parser.getDoubleValue();
                if (!Double.isFinite(coordinate)) throw new MetadataException("INVALID_GEOJSON", "坐标必须为有限数值");
                if (count == 0) longitude = coordinate;
                if (count == 1) latitude = coordinate;
                count++;
            } else throw new MetadataException("INVALID_GEOJSON", "坐标数组必须只包含坐标或子数组");
        }
        if (count == 0) return;
        if (count < 2 || longitude < -180 || longitude > 180 || latitude < -90 || latitude > 90) {
            throw new MetadataException("INVALID_GEOJSON", "GeoJSON 坐标必须为合法的经度/纬度");
        }
        extent.expandToInclude(longitude, latitude);
    }

    private String abbreviate(String message) {
        return message == null ? null : message.substring(0, Math.min(message.length(), 1000));
    }

    private static final class VectorBounds {
        private final Envelope extent = new Envelope();
        private final Set<String> types = new TreeSet<>();
        private long features;
        private String declaredCrs;
    }

    private static final class MetadataException extends RuntimeException {
        private final String code;
        private MetadataException(String code, String message) { super(message); this.code = code; }
    }
}
