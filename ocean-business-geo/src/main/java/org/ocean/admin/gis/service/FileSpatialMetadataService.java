package org.ocean.admin.gis.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.geotools.api.coverage.grid.GridEnvelope;
import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.geotools.api.referencing.datum.PixelInCell;
import org.geotools.api.referencing.operation.MathTransform;
import org.geotools.gce.geotiff.GeoTiffReader;
import org.geotools.referencing.CRS;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.geom.util.AffineTransformation;
import org.locationtech.jts.io.WKTWriter;
import org.ocean.admin.gis.dto.TaskProgressUnit;
import org.ocean.admin.gis.dto.TaskProgressUpdate;
import org.ocean.admin.gis.dto.TaskStage;
import org.ocean.admin.gis.dto.TaskStatus;
import org.ocean.admin.gis.entity.GisFileMeta;
import org.ocean.admin.gis.entity.GisFileSpatialMetadata;
import org.ocean.admin.gis.entity.GisFileTransferRecord;
import org.ocean.admin.gis.imagery.ImageryTileOptions;
import org.ocean.admin.gis.mapper.GisFileMetaMapper;
import org.ocean.admin.gis.mapper.GisDataSetMapper;
import org.ocean.admin.gis.mapper.GisFileSpatialMetadataMapper;
import org.ocean.admin.gis.util.FileUploadUtil;
import org.ocean.admin.gis.terrain.engine.TerrainOptions;
import org.geotools.geometry.jts.ReferencedEnvelope;
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
            if (!notApplicable) metadata = read(claim);
        } catch (MetadataException ex) {
            errorCode = ex.code;
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

    private GisFileSpatialMetadata read(GisFileMeta file) throws Exception {
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
                raster(path, metadata, Long.valueOf(1).equals(dataSet.getCategoryId()));
            }
            case "geojson", "json" -> vector(path, metadata);
            default -> throw new MetadataException("UNSUPPORTED_FORMAT", "首期空间元数据提取仅支持 GeoTIFF、GeoJSON");
        }
        return metadata;
    }

    private void raster(Path path, GisFileSpatialMetadata metadata, boolean terrain) throws Exception {
        GeoTiffReader reader = new GeoTiffReader(path.toFile());
        try {
            // GeoTools 会为缺失 CRS 的 TIFF 提供默认坐标系，必须先检查文件自身的 GeoKey。
            if (reader.getMetadata() == null || !reader.getMetadata().hasGeoKey()) {
                throw new MetadataException("MISSING_CRS", "GeoTIFF 缺少源坐标系定义");
            }
            if (reader.getGroundControlPoints() != null) {
                throw new MetadataException("UNSUPPORTED_GEOREFERENCING", "首期不支持仅由地面控制点定位的栅格");
            }
            CoordinateReferenceSystem source = reader.getCoordinateReferenceSystem();
            if (source == null) throw new MetadataException("MISSING_CRS", "GeoTIFF 缺少源坐标系");
            MathTransform toGeographic = CRS.findMathTransform(source, CRS.decode("EPSG:4326", true), false);
            MathTransform gridToSource = reader.getOriginalGridToWorld(PixelInCell.CELL_CORNER);
            GridEnvelope grid = reader.getOriginalGridRange();
            if (grid == null || grid.getSpan(0) < 1 || grid.getSpan(1) < 1) {
                throw new MetadataException("INVALID_RASTER", "GeoTIFF 栅格尺寸无效");
            }
            int width = grid.getSpan(0);
            int height = grid.getSpan(1);
            double left = grid.getLow(0);
            double top = grid.getLow(1);
            List<Coordinate> ring = new ArrayList<>();
            for (int edge = 0; edge < 4; edge++) {
                for (int sample = 0; sample < 64; sample++) {
                    double t = sample / 64d;
                    double x = switch (edge) { case 0 -> left + t * width; case 1 -> left + width; case 2 -> left + (1 - t) * width; default -> left; };
                    double y = switch (edge) { case 0 -> top; case 1 -> top + t * height; case 2 -> top + height; default -> top + (1 - t) * height; };
                    double[] geographic = transform(toGeographic, transform(gridToSource, new double[]{x, y}));
                    if (!Double.isFinite(geographic[0]) || !Double.isFinite(geographic[1])
                            || geographic[1] < -90.0000001 || geographic[1] > 90.0000001) {
                        throw new MetadataException("INVALID_EXTENT", "GeoTIFF 转换后的经纬度范围无效");
                    }
                    ring.add(new Coordinate(geographic[0], Math.clamp(geographic[1], -90d, 90d)));
                }
            }
            ring.add(new Coordinate(ring.getFirst()));
            Geometry footprint = normalizeLongitudes(ring);
            metadata.setDataKind("RASTER");
            metadata.setExtentKind("RASTER_FOOTPRINT");
            metadata.setGeographicExtent(new WKTWriter().write(footprint));
            try { metadata.setSourceCrsCode(CRS.lookupIdentifier(source, true)); }
            catch (Exception ex) { log.debug("无法识别源坐标系权威编码，保留完整 WKT: {}", path, ex); }
            metadata.setSourceCrsWkt(source.toWKT());
            String unit = source.getCoordinateSystem().getAxis(0).getUnit().toString();
            metadata.setSourceUnit(unit);
            metadata.setResolutionUnit(unit);
            var envelope = reader.getOriginalEnvelope();
            metadata.setSourceExtent(objectMapper.writeValueAsString(Map.of(
                    "lowerCorner", new double[]{envelope.getMinimum(0), envelope.getMinimum(1)},
                    "upperCorner", new double[]{envelope.getMaximum(0), envelope.getMaximum(1)})));
            metadata.setRasterWidth(width);
            metadata.setRasterHeight(height);
            metadata.setBandCount(reader.getImageLayout().getSampleModel(null).getNumBands());
            double[] center = {left + width / 2d, top + height / 2d};
            double[] p = transform(gridToSource, center);
            double[] px = transform(gridToSource, new double[]{center[0] + 1, center[1]});
            double[] py = transform(gridToSource, new double[]{center[0], center[1] + 1});
            metadata.setResolutionX(Math.hypot(px[0] - p[0], px[1] - p[1]));
            metadata.setResolutionY(Math.hypot(py[0] - p[0], py[1] - p[1]));
            if (terrain) calculateTerrainZoom(metadata, reader, source, width);
            else calculateZoom(metadata, gridToSource, toGeographic, left, top, width, height);
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("extentEdgeSamples", 64);
            if (reader.getMetadata().hasNoData()) {
                double noData = reader.getMetadata().getNoData();
                extra.put("noData", Double.isFinite(noData) ? noData : Double.toString(noData));
            }
            metadata.setExtraMetadata(objectMapper.writeValueAsString(extra));
        } finally {
            reader.dispose();
        }
    }

    private void calculateTerrainZoom(GisFileSpatialMetadata metadata, GeoTiffReader reader,
            CoordinateReferenceSystem source, int width) throws Exception {
        var bounds = new ReferencedEnvelope(reader.getOriginalEnvelope());
        double pixelSizeMeters;
        if (CRS.equalsIgnoreMetadata(source, CRS.decode("EPSG:4326", true))) {
            double latitude = (bounds.getMinY() + bounds.getMaxY()) / 2d;
            double sin = Math.sin(Math.toRadians(latitude));
            double radius = 6378137d / Math.sqrt(1d - 0.0066943799901413165d * sin * sin);
            pixelSizeMeters = Math.toRadians(bounds.getWidth()) * radius / width;
        } else {
            // mago 的默认可用范围分析直接采用源包围范围的 X 像素间距（米）。
            String unit = source.getCoordinateSystem().getAxis(0).getUnit().toString();
            if (!Set.of("m", "metre", "meter").contains(unit)) {
                metadata.setCalculationDetails(objectMapper.writeValueAsString(
                        Map.of("reason", "TERRAIN_SOURCE_UNIT_NOT_SUPPORTED")));
                return;
            }
            pixelSizeMeters = bounds.getWidth() / width;
        }
        int zoom = TerrainOptions.calculateRecommendedMaxZoom(pixelSizeMeters);
        metadata.setTileMatrixSetId("WorldCRS84Quad");
        metadata.setTileWidth(256);
        metadata.setTileHeight(256);
        metadata.setRecommendedMaxZoom(zoom);
        metadata.setCalculationDetails(objectMapper.writeValueAsString(Map.of(
                "method", "MAGO_DEFAULT_AVAILABLE_DEPTH", "engine", "mago-3d-terrainer-1.14.2-release",
                "resolutionMeters", pixelSizeMeters, "includesFinalSubdivision", true, "systemMaxZoom", 22)));
    }

    private void calculateZoom(GisFileSpatialMetadata metadata, MathTransform gridToSource,
            MathTransform toGeographic, double left, double top, int width, int height) throws Exception {
        MathTransform mercator = CRS.findMathTransform(CRS.decode("EPSG:4326", true), CRS.decode("EPSG:3857", true), false);
        double resolution = 0;
        for (double fx : new double[]{0.1, 0.5, 0.9}) {
            for (double fy : new double[]{0.1, 0.5, 0.9}) {
                double x = left + fx * width;
                double y = top + fy * height;
                double[][] sample = new double[3][];
                sample[0] = transform(toGeographic, transform(gridToSource, new double[]{x, y}));
                sample[1] = transform(toGeographic, transform(gridToSource, new double[]{x + 1, y}));
                sample[2] = transform(toGeographic, transform(gridToSource, new double[]{x, y + 1}));
                if (Arrays.stream(sample).anyMatch(c -> Math.abs(c[1]) > 85.0511287798066)) continue;
                for (int i = 0; i < 3; i++) sample[i] = transform(mercator, sample[i]);
                for (int i = 1; i < 3; i++) {
                    double dx = Math.abs(sample[i][0] - sample[0][0]);
                    dx = Math.min(dx, Math.abs(40075016.68557849 - dx));
                    resolution = Math.max(resolution, Math.hypot(dx, sample[i][1] - sample[0][1]));
                }
            }
        }
        if (!Double.isFinite(resolution) || resolution <= 0) {
            metadata.setCalculationDetails(objectMapper.writeValueAsString(Map.of("reason", "OUTSIDE_WEB_MERCATOR_OR_INVALID_RESOLUTION")));
            return;
        }
        int nativeZoom = ImageryTileOptions.calculateNativeMaxZoom(resolution, 40075016.68557849, 256);
        metadata.setTileMatrixSetId("WebMercatorQuad");
        metadata.setTileWidth(256);
        metadata.setTileHeight(256);
        metadata.setRecommendedMaxZoom(Math.clamp(nativeZoom, 0, 22));
        metadata.setCalculationDetails(objectMapper.writeValueAsString(Map.of(
                "method", "MAX_PIXEL_SPACING_3X3_SAMPLES_IN_EPSG_3857", "resolutionMeters", resolution,
                "unconstrainedMaxZoom", nativeZoom, "rounding", "FLOOR_WITH_INTEGER_TOLERANCE",
                "zoomBoundaryTolerance", ImageryTileOptions.ZOOM_BOUNDARY_TOLERANCE, "systemMaxZoom", 22)));
    }

    private Geometry normalizeLongitudes(List<Coordinate> ring) {
        for (int i = 1; i < ring.size(); i++) {
            double previous = ring.get(i - 1).x;
            while (ring.get(i).x - previous > 180) ring.get(i).x -= 360;
            while (ring.get(i).x - previous < -180) ring.get(i).x += 360;
        }
        ring.set(ring.size() - 1, new Coordinate(ring.getFirst()));
        Geometry polygon = GEOMETRIES.createPolygon(ring.toArray(Coordinate[]::new));
        if (!polygon.isValid() || polygon.isEmpty()) throw new MetadataException("INVALID_EXTENT", "栅格空间范围不是有效几何");
        Envelope envelope = polygon.getEnvelopeInternal();
        if (envelope.getWidth() > 360.0000001) throw new MetadataException("INVALID_EXTENT", "栅格经度跨度超过360度");
        int first = (int) Math.floor((envelope.getMinX() + 180) / 360);
        int last = (int) Math.floor((envelope.getMaxX() + 180) / 360);
        if (last - first > 2) throw new MetadataException("INVALID_EXTENT", "栅格经度范围超过全球范围");
        List<Geometry> parts = new ArrayList<>();
        for (int offset = first; offset <= last; offset++) {
            Geometry clipped = polygon.intersection(GEOMETRIES.toGeometry(new Envelope(-180 + offset * 360d, 180 + offset * 360d, -90, 90)));
            if (!clipped.isEmpty() && clipped.getDimension() == 2) {
                parts.add(AffineTransformation.translationInstance(-offset * 360d, 0).transform(clipped));
            }
        }
        Geometry result = GEOMETRIES.buildGeometry(parts);
        if (result.isEmpty()) throw new MetadataException("INVALID_EXTENT", "栅格空间范围为空");
        return result;
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

    private double[] transform(MathTransform transform, double[] point) throws Exception {
        double[] result = new double[2];
        transform.transform(point, 0, result, 0, 1);
        return result;
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
