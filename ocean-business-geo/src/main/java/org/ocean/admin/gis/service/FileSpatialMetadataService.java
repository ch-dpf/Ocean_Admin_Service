package org.ocean.admin.gis.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.geotools.referencing.CRS;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.io.WKTWriter;
import org.ocean.admin.gis.dto.TaskProgressUnit;
import org.ocean.admin.gis.dto.TaskProgressUpdate;
import org.ocean.admin.gis.dto.TaskStage;
import org.ocean.admin.gis.dto.TaskStatus;
import org.ocean.admin.gis.entity.GisFileMeta;
import org.ocean.admin.gis.entity.GisFileSpatialMetadata;
import org.ocean.admin.gis.entity.GisFileTransferRecord;
import org.ocean.admin.gis.mapper.GisFileMetaMapper;
import org.ocean.admin.gis.mapper.GisDataSetMapper;
import org.ocean.admin.gis.mapper.GisFileSpatialMetadataMapper;
import org.ocean.admin.gis.util.FileUploadUtil;
import org.ocean.admin.gis.util.RasterFootprintBuilder;
import org.ocean.admin.gis.vo.GisFileMetaVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/** 空间元数据提取及其持久化生命周期；文件读取不占用数据库事务。 */
@Service
@Slf4j
@RequiredArgsConstructor
public class FileSpatialMetadataService {
    private static final GeometryFactory GEOMETRIES = new GeometryFactory(new PrecisionModel(), 4326);
    private static final Set<String> ATTACHMENTS = Set.of("prj", "tfw", "aux", "xml", "shx", "dbf", "cpg", "sbn", "sbx");
    private final GisFileSpatialMetadataMapper spatialMapper;
    private final RasterSpatialMetadataExtractor rasterExtractor;
    private final GisFileMetaMapper fileMapper;
    private final GisDataSetMapper dataSetMapper;
    private final FileTransferRecordService transferService;
    private final FileUploadUtil files;
    private final AsyncTaskService tasks;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;

    public void extract(Long fileId) {
        GisFileMeta claim = transactions.execute(status -> spatialMapper.claim(fileId, LocalDateTime.now().plusMinutes(15)));
        if (claim == null) return;
        GisFileSpatialMetadata metadata = null;
        String errorCode = null;
        String errorMessage = null;
        boolean notApplicable = ATTACHMENTS.contains(claim.getExtension());
        try {
            if (!notApplicable) metadata = read(claim, leaseHeartbeat(claim));
        } catch (MetadataException ex) {
            if ("LEASE_LOST".equals(ex.code)) return;
            errorCode = ex.code;
            errorMessage = ex.getMessage();
        } catch (RasterSpatialMetadataExtractor.ExtractionException ex) {
            errorCode = ex.code();
            errorMessage = ex.getMessage();
        } catch (RasterFootprintBuilder.FootprintException ex) {
            errorCode = ex.code();
            errorMessage = ex.getMessage();
        } catch (Exception ex) {
            errorCode = "EXTRACTION_FAILED";
            errorMessage = ex.getMessage();
            log.warn("空间元数据提取失败: fileId={}", fileId, ex);
        }
        GisFileSpatialMetadata extracted = metadata;
        String code = errorCode;
        String message = errorMessage;
        GisFileTransferRecord record = transactions.execute(status -> {
            // 超时重新领取、文件变更或删除都会使本次结果失效。
            GisFileMeta locked = spatialMapper.lockClaim(fileId, claim.getMetadataVersion());
            if (locked == null) return null;
            spatialMapper.deleteByFileId(fileId);
            if (extracted != null) spatialMapper.insert(extracted);
            String result = code != null ? "FAILED" : notApplicable ? "NOT_APPLICABLE" : "COMPLETED";
            fileMapper.update(null, new LambdaUpdateWrapper<GisFileMeta>()
                    .eq(GisFileMeta::getId, fileId)
                    .set(GisFileMeta::getMetadataStatus, result)
                    .set(GisFileMeta::getMetadataLeaseExpiresAt, null)
                    .set(GisFileMeta::getMetadataErrorCode, code)
                    .set(GisFileMeta::getMetadataErrorMessage, abbreviate(message))
                    .set(GisFileMeta::getMetadataExtractedAt, LocalDateTime.now())
                    .set(GisFileMeta::getUpdateTime, LocalDateTime.now()));
            locked.setMetadataStatus(result);
            locked.setMetadataErrorMessage(abbreviate(message));
            return transferService.completeMetadataExtraction(locked, code == null);
        });
        if (record != null) publish(record);
    }

    /** 来源变更及人工重试共用同一失效操作；旧空间记录立即退出查询。 */
    public void invalidate(Long fileId) {
        fileMapper.update(null, new LambdaUpdateWrapper<GisFileMeta>()
                .eq(GisFileMeta::getId, fileId)
                .set(GisFileMeta::getMetadataStatus, "QUEUED")
                .setSql("metadata_version = metadata_version + 1")
                .set(GisFileMeta::getMetadataLeaseExpiresAt, null)
                .set(GisFileMeta::getMetadataErrorCode, null)
                .set(GisFileMeta::getMetadataErrorMessage, null)
                .set(GisFileMeta::getMetadataExtractedAt, null)
                .set(GisFileMeta::getUpdateTime, LocalDateTime.now()));
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

    private void publish(GisFileTransferRecord completedSnapshot) {
        synchronized (FileUploadService.requestLock(completedSnapshot.getRequestId())) {
            // 多文件并行完成时，按批次串行读取最新结果，不能用提交先后不定的旧快照回退计数。
            GisFileTransferRecord record = transferService.findImportRecordByRequestId(completedSnapshot.getRequestId());
            if (record == null) return;
            publishCurrent(record);
        }
    }

    private void publishCurrent(GisFileTransferRecord record) {
        try {
            if (tasks.getTaskInfo(record.getRecordNo()) == null) {
                if (record.getRecordStatus() != TaskStatus.RUNNING) return;
                tasks.registerTask(record.getRecordNo(), record.getRequestId(), "GIS数据导入",
                        record.getTotalCount(), "GIS_UPLOAD", TaskProgressUnit.FILE);
            }
            tasks.updateProgress(record.getRecordNo(),
                    TaskProgressUpdate.resultChanged(record.getCompletedCount(), record.getFailedCount()),
                    TaskProgressUpdate.stageChanged(TaskStage.indeterminate(record.getCurrentStage(),
                            record.getRecordStatus() == TaskStatus.RUNNING ? "正在导入GIS数据" : "GIS数据导入结束")));
            if (record.getRecordStatus() != TaskStatus.RUNNING) {
                files.deleteEmptyBatchDirectory(record.getRecordNo());
                tasks.finalizeTaskResult(record.getRecordNo(), "导入结束，成功" + record.getCompletedCount()
                        + "个，失败" + record.getFailedCount() + "个");
            }
        } catch (RuntimeException ex) {
            // 实时通知失败不回滚已提交的导入结果，查询接口以数据库为准。
            log.warn("发布空间元数据提取结果失败: recordNo={}", record.getRecordNo(), ex);
        }
    }

    private Runnable leaseHeartbeat(GisFileMeta claim) {
        long[] renewedAt = {System.nanoTime()};
        return () -> {
            long now = System.nanoTime();
            if (now - renewedAt[0] < java.util.concurrent.TimeUnit.SECONDS.toNanos(30)) return;
            if (spatialMapper.renewLease(claim.getId(), claim.getMetadataVersion(), LocalDateTime.now().plusMinutes(15)) != 1) {
                throw new MetadataException("LEASE_LOST", "解析任务租约已失效");
            }
            renewedAt[0] = now;
        };
    }

    private GisFileSpatialMetadata read(GisFileMeta file, Runnable heartbeat) throws Exception {
        if (!"LOCAL".equals(file.getStorageType())) throw new MetadataException("UNSUPPORTED_STORAGE", "首期空间解析仅支持本地文件存储");
        Path path = files.resolveStoredPath(file.getStorageKey());
        GisFileSpatialMetadata metadata = new GisFileSpatialMetadata();
        metadata.setId(IdWorker.getId());
        metadata.setFileMetaId(file.getId());
        metadata.setLayerKey("default");
        metadata.setLayerName(file.getOriginalName());
        metadata.setCalculationDetails("{}");
        metadata.setExtraMetadata("{}");
        switch (file.getExtension()) {
            case "tif", "tiff" -> {
                var dataSet = dataSetMapper.selectById(file.getDataSetId());
                if (dataSet == null) throw new MetadataException("MISSING_DATASET", "文件所属数据集不存在");
                rasterExtractor.extract(path, metadata, Long.valueOf(1).equals(dataSet.getCategoryId()), heartbeat);
            }
            case "geojson", "json" -> vector(path, metadata);
            default -> throw new MetadataException("UNSUPPORTED_FORMAT", "首期空间元数据提取仅支持 GeoTIFF、GeoJSON");
        }
        return metadata;
    }

    private void vector(Path path, GisFileSpatialMetadata metadata) throws Exception {
        VectorBounds bounds = new VectorBounds();
        try (JsonParser parser = objectMapper.createParser(path.toFile())) {
            if (parser.nextToken() != JsonToken.START_OBJECT) throw new MetadataException("INVALID_GEOJSON", "GeoJSON 根节点必须为对象");
            readGeoJsonObject(parser, bounds, 0);
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
        metadata.setExtraMetadata(objectMapper.writeValueAsString(Map.of("featureCount", bounds.features, "geometryTypes", bounds.types)));
    }

    /** 流式读取几何坐标，跳过属性，不把整个大型 GeoJSON 加载进内存。 */
    private void readGeoJsonObject(JsonParser parser, VectorBounds bounds, int depth) throws Exception {
        if (depth > 64) throw new MetadataException("INVALID_GEOJSON", "GeoJSON 嵌套层级过深");
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
                case "crs" -> throw new MetadataException("UNSUPPORTED_CRS", "首期仅支持 RFC 7946 GeoJSON，请先转换为 WGS84 经度/纬度坐标");
                case "features", "geometries" -> {
                    members.add(name);
                    if (value != JsonToken.START_ARRAY) throw new MetadataException("INVALID_GEOJSON", name + " 必须为数组");
                    while (parser.nextToken() != JsonToken.END_ARRAY) {
                        if (parser.currentToken() != JsonToken.START_OBJECT) throw new MetadataException("INVALID_GEOJSON", "GeoJSON 数组成员必须为对象");
                        readGeoJsonObject(parser, bounds, depth + 1);
                    }
                }
                case "geometry" -> {
                    members.add(name);
                    if (value == JsonToken.START_OBJECT) readGeoJsonObject(parser, bounds, depth + 1);
                    else if (value != JsonToken.VALUE_NULL) throw new MetadataException("INVALID_GEOJSON", "geometry 必须为对象或 null");
                }
                case "coordinates" -> {
                    members.add(name);
                    if (value != JsonToken.START_ARRAY) throw new MetadataException("INVALID_GEOJSON", "coordinates 必须为数组");
                    readCoordinates(parser, bounds.extent, 0);
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

    private void readCoordinates(JsonParser parser, Envelope extent, int depth) throws Exception {
        if (depth > 8) throw new MetadataException("INVALID_GEOJSON", "坐标数组嵌套过深");
        int count = 0;
        double longitude = 0;
        double latitude = 0;
        boolean nested = false;
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            if (parser.currentToken() == JsonToken.START_ARRAY) {
                if (count > 0) throw new MetadataException("INVALID_GEOJSON", "坐标数组结构不一致");
                nested = true;
                readCoordinates(parser, extent, depth + 1);
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
    }

    private static final class MetadataException extends IllegalArgumentException {
        private final String code;
        private MetadataException(String code, String message) { super(message); this.code = code; }
    }
}
