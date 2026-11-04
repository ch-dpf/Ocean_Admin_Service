package org.ocean.admin.gis.ogc;

import lombok.extern.slf4j.Slf4j;
import org.geotools.api.coverage.grid.GridEnvelope;
import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.geotools.api.referencing.datum.PixelInCell;
import org.geotools.api.referencing.operation.MathTransform;
import org.geotools.gce.geotiff.GeoTiffReader;
import org.geotools.referencing.CRS;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.Raster;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 按 WMS GetFeatureInfo 的查询点，从源 GeoTIFF 读取原始像元值。
 *
 * <p>与 {@link WmsRasterRenderer} 的区别：渲染链路必然经过重采样与符号化，数值已不是像元原值。
 * 这里用 GeoTools 读取坐标系、网格变换与波段定义，再用 ImageIO 的 {@code SourceRegion}
 * 只解码点击位置周围 {@code (2 × buffer + 1)²} 的极小窗口——GeoTools 的网格范围读取参数
 * 对普通 strip 布局的 GeoTIFF 会把整幅读入内存，不能用于按点查询。</p>
 */
@Slf4j
@Component
public class WmsPixelSampler {

    private static final CoordinateReferenceSystem WGS84 = decode();
    private static final long ACQUIRE_SECONDS = 30L;

    /** 读取是 I/O 与解码操作，限制并发避免拖垮接口线程。 */
    private final Semaphore readPermits = new Semaphore(4);

    /**
     * 在多个源分块中查询同一个世界坐标。
     *
     * @param sources   图层的源 GeoTIFF 列表
     * @param queryCrs  请求坐标系
     * @param worldX    查询点横坐标，与 {@code queryCrs} 同单位
     * @param worldY    查询点纵坐标，与 {@code queryCrs} 同单位
     * @param buffer    以输出像素为单位的查询半径；0 表示只取点击像元
     * @return 最近的有效像元；查询点不在任何源范围内时返回 null
     */
    public BandSample sample(List<Path> sources, CoordinateReferenceSystem queryCrs,
            double worldX, double worldY, int buffer) {
        BandSample best = null;
        double bestPixelArea = Double.POSITIVE_INFINITY;
        for (Path source : sources) {
            if (!Files.isReadable(source)) {
                continue;
            }
            Candidate candidate = readCandidate(source, queryCrs, worldX, worldY, buffer);
            if (candidate == null) {
                continue;
            }
            // 多源叠压时与切片链路一致：分辨率最高（源像元面积最小）的源优先
            if (candidate.pixelArea() < bestPixelArea) {
                best = candidate.sample();
                bestPixelArea = candidate.pixelArea();
            }
        }
        return best;
    }

    private Candidate readCandidate(Path source, CoordinateReferenceSystem queryCrs,
            double worldX, double worldY, int buffer) {
        boolean acquired = false;
        GeoTiffReader metadata = null;
        ImageInputStream stream = null;
        ImageReader imageReader = null;
        try {
            acquired = readPermits.tryAcquire(ACQUIRE_SECONDS, TimeUnit.SECONDS);
            if (!acquired) {
                throw new IllegalStateException("查询队列繁忙，请稍后重试");
            }
            metadata = new GeoTiffReader(source.toFile());
            CoordinateReferenceSystem sourceCrs = metadata.getCoordinateReferenceSystem();
            MathTransform gridToWorld = metadata.getOriginalGridToWorld(PixelInCell.CELL_CORNER);
            double[] point = toSourcePoint(queryCrs, sourceCrs, worldX, worldY);
            double[] grid = {point[0], point[1]};
            gridToWorld.inverse().transform(grid, 0, grid, 0, 1);

            GridEnvelope range = metadata.getOriginalGridRange();
            int centerCol = (int) Math.floor(grid[0]);
            int centerRow = (int) Math.floor(grid[1]);
            int minCol = range.getLow(0);
            int maxCol = range.getHigh(0);
            int minRow = range.getLow(1);
            int maxRow = range.getHigh(1);
            if (centerCol < minCol - buffer || centerCol > maxCol + buffer
                    || centerRow < minRow - buffer || centerRow > maxRow + buffer) {
                // 查询点与源范围的距离超过查询半径，该源不参与本次查询
                return null;
            }
            int col0 = Math.max(minCol, centerCol - buffer);
            int col1 = Math.min(maxCol, centerCol + buffer);
            int row0 = Math.max(minRow, centerRow - buffer);
            int row1 = Math.min(maxRow, centerRow + buffer);

            stream = ImageIO.createImageInputStream(source.toFile());
            if (stream == null) {
                return null;
            }
            imageReader = firstReader(stream);
            if (imageReader == null) {
                return null;
            }
            imageReader.setInput(stream, true, true);
            ImageReadParam readParam = imageReader.getDefaultReadParam();
            readParam.setSourceRegion(new Rectangle(col0, row0, col1 - col0 + 1, row1 - row0 + 1));
            BufferedImage window = imageReader.read(0, readParam);

            int[] chosen = pickPixel(window, centerCol - col0, centerRow - row0, buffer == 0);
            if (chosen == null) {
                return null;
            }
            double[] values = readValues(window, chosen[0], chosen[1]);
            double[] pixelCenter = {col0 + chosen[0] + 0.5, row0 + chosen[1] + 0.5};
            gridToWorld.transform(pixelCenter, 0, pixelCenter, 0, 1);
            double[] wgs84 = {pixelCenter[0], pixelCenter[1]};
            if (!CRS.equalsIgnoreMetadata(sourceCrs, WGS84)) {
                CRS.findMathTransform(sourceCrs, WGS84, true).transform(wgs84, 0, wgs84, 0, 1);
            }
            return new Candidate(new BandSample(bandNames(values.length), values,
                    wgs84[0], wgs84[1]), pixelArea(gridToWorld));
        } catch (Exception ex) {
            log.warn("[OGC][WMS] 查询源像元失败，已跳过: {}", source, ex);
            return null;
        } finally {
            if (imageReader != null) {
                imageReader.dispose();
            }
            if (stream != null) {
                try {
                    stream.close();
                } catch (Exception ignored) {
                    // 关闭失败不影响已读取的结果
                }
            }
            if (metadata != null) {
                metadata.dispose();
            }
            if (acquired) {
                readPermits.release();
            }
        }
    }

    private ImageReader firstReader(ImageInputStream stream) {
        Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
        return readers.hasNext() ? readers.next() : null;
    }

    private double[] toSourcePoint(CoordinateReferenceSystem queryCrs,
            CoordinateReferenceSystem sourceCrs, double worldX, double worldY) throws Exception {
        double[] point = {worldX, worldY};
        if (!CRS.equalsIgnoreMetadata(queryCrs, sourceCrs)) {
            CRS.findMathTransform(queryCrs, sourceCrs, true).transform(point, 0, point, 0, 1);
        }
        return point;
    }

    /**
     * 选择返回的像元。
     *
     * <p>buffer 为 0 时严格返回点击像元，与 GeoServer 栅格查询一致；存在容差时取距离最近的有效像元，
     * 半径内没有有效像元时仍返回点击像元，由调用方按原始值呈现。</p>
     *
     * @return 窗口内的列行坐标；点击像元不在窗口内时返回 null
     */
    private int[] pickPixel(BufferedImage window, int centerCol, int centerRow, boolean exact) {
        if (centerCol < 0 || centerCol >= window.getWidth()
                || centerRow < 0 || centerRow >= window.getHeight()) {
            return null;
        }
        if (exact) {
            return new int[]{centerCol, centerRow};
        }
        int[] nearestValid = null;
        double nearestValidDistance = Double.POSITIVE_INFINITY;
        for (int col = 0; col < window.getWidth(); col++) {
            for (int row = 0; row < window.getHeight(); row++) {
                double distance = Math.hypot(col - centerCol, row - centerRow);
                if (distance >= nearestValidDistance) {
                    continue;
                }
                if (isValid(readValues(window, col, row))) {
                    nearestValidDistance = distance;
                    nearestValid = new int[]{col, row};
                }
            }
        }
        return nearestValid != null ? nearestValid : new int[]{centerCol, centerRow};
    }

    /** 按数据缓冲类型读取，避免浮点栅格被整数截断。 */
    private double[] readValues(BufferedImage window, int col, int row) {
        Raster raster = window.getRaster();
        double[] values = new double[raster.getNumBands()];
        boolean floating = switch (raster.getDataBuffer().getDataType()) {
            case DataBuffer.TYPE_FLOAT, DataBuffer.TYPE_DOUBLE -> true;
            default -> false;
        };
        for (int band = 0; band < values.length; band++) {
            values[band] = floating
                    ? raster.getSampleDouble(col, row, band)
                    : raster.getSample(col, row, band);
        }
        return values;
    }

    /**
     * 有效像元判定：存在 Alpha 波段时透明像素无效；否则要求至少一个波段不是全零背景值。
     *
     * <p>源 GeoTIFF 未声明 Nodata 时无从区分真实零值与空白，这里只把整行全零的空白区视为无效，
     * 与切片链路的透明填充语义一致。</p>
     */
    private boolean isValid(double[] values) {
        if (values.length == 0) {
            return false;
        }
        if (values.length >= 4 && values[3] == 0) {
            return false;
        }
        for (double value : values) {
            if (value != 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * 波段名与 GeoTools 对影像色带的默认命名保持一致：单波段为 GRAY_INDEX，
     * 三/四波段为 RED_BAND、GREEN_BAND、BLUE_BAND、ALPHA_BAND，其余按序号命名。
     */
    private String[] bandNames(int bandCount) {
        String[] names = new String[bandCount];
        for (int band = 0; band < bandCount; band++) {
            if (bandCount == 1) {
                names[band] = "GRAY_INDEX";
                continue;
            }
            names[band] = switch (band) {
                case 0 -> "RED_BAND";
                case 1 -> "GREEN_BAND";
                case 2 -> "BLUE_BAND";
                case 3 -> "ALPHA_BAND";
                default -> "band" + (band + 1);
            };
        }
        return names;
    }

    /** 源栅格单像元在世界坐标下的面积，用于在多个源之间挑最高分辨率。 */
    private double pixelArea(MathTransform gridToWorld) throws Exception {
        double[] axes = {0, 0, 1, 0, 0, 1};
        gridToWorld.transform(axes, 0, axes, 0, 3);
        double dx1 = axes[2] - axes[0];
        double dy1 = axes[3] - axes[1];
        double dx2 = axes[4] - axes[0];
        double dy2 = axes[5] - axes[1];
        return Math.abs(dx1 * dy2 - dy1 * dx2);
    }

    private static CoordinateReferenceSystem decode() {
        try {
            return CRS.decode("EPSG:4326", true);
        } catch (Exception ex) {
            throw new IllegalStateException("无法初始化 EPSG:4326 坐标系", ex);
        }
    }

    private record Candidate(BandSample sample, double pixelArea) {
    }

    /** 一个查询点命中的原始像元值。 */
    public record BandSample(
            String[] bandNames,
            double[] values,
            double longitude,
            double latitude) {
    }
}
