package org.ocean.admin.gis.ogc;

import lombok.extern.slf4j.Slf4j;
import org.geotools.api.coverage.grid.GridEnvelope;
import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.geotools.api.referencing.datum.PixelInCell;
import org.geotools.api.referencing.operation.MathTransform;
import org.geotools.gce.geotiff.GeoTiffReader;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.referencing.CRS;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 按 WMS GetMap 的视口参数，把 GeoTIFF 源数据实时重采样成位图。
 *
 * <p>与切片链路的区别是：瓦片是固定 256×256 的静态文件，这里要按客户端任意 BBOX、
 * 任意宽高重新采样。实现上只把视口对应的那一块源像素读进内存——源影像是
 * strip 布局、无 overview 的大幅面 GeoTIFF（常见上亿像素），走 GeoTools 的
 * {@code GridCoverageRenderer} 会对每张输出图做整幅 warp/mosaic 解码；一个视口内
 * 并发十几张瓦片时内存与 I/O 被打满，线程会长时间卡在 TIFF 解码里，
 * 进而占满工作线程让后续请求（包括 GetFeatureInfo）全部排队。</p>
 *
 * <p>因此这里和 {@link WmsPixelSampler} 使用同一条路径：用 GeoTools 只读坐标系、
 * 网格变换与波段信息，再交给 ImageIO 按 {@code SourceRegion} + 源抽样解码局部窗口，
 * 读出像素数与输出尺寸同量级，内存有确定上限。</p>
 */
@Slf4j
@Component
public class WmsRasterRenderer {

    /** 单张输出位图上限，避免超大 WIDTH/HEIGHT 把堆打满。 */
    private static final int MAX_PIXELS = 4096 * 4096;
    /** 单个源窗口读取上限：约 800 万像素 ARGB（32MB），并发叠加时的硬顶。 */
    private static final long MAX_READ_PIXELS = 8_000_000L;
    /** 网格坐标取整容差，抵消投影往返的浮点尾差。 */
    private static final double GRID_EPSILON = 1e-6;

    /**
     * 渲染是 CPU 与 I/O 密集操作，限制并发避免拖垮接口线程与堆。
     *
     * <p>一个视口内切片式客户端会同时发起十几到二十几张 GetMap，许可给得太小会让这些请求
     * 串成多轮、整屏出图明显变慢；上限由单次读取的像素预算兜底，不做无界并发。</p>
     */
    private final Semaphore renderPermits = new Semaphore(8);
    private static final long RENDER_ACQUIRE_SECONDS = 30L;

    /**
     * 渲染一个 GetMap 请求。
     *
     * @param sources     该图层的 GeoTIFF 源文件（可能多个分块）
     * @param layerBounds 图层在请求坐标系下的地理范围
     * @param request     已校验的请求参数
     * @return WIDTH×HEIGHT 的位图；无数据区域保持透明，由调用方统一铺背景
     */
    public BufferedImage render(List<Path> sources, ReferencedEnvelope layerBounds,
            WmsMapRequest request) throws Exception {
        long pixels = (long) request.width() * request.height();
        if (pixels > MAX_PIXELS) {
            throw new IllegalArgumentException(
                    "WIDTH 与 HEIGHT 的乘积不能超过 " + MAX_PIXELS + " 像素");
        }

        // 始终输出透明画布：背景由 WmsService 在合成多图层时统一填充，
        // 否则每个图层的白底会覆盖掉先前绘制的图层。
        BufferedImage canvas = new BufferedImage(request.width(), request.height(),
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = canvas.createGraphics();
        boolean acquired = false;
        try {
            acquired = renderPermits.tryAcquire(RENDER_ACQUIRE_SECONDS, TimeUnit.SECONDS);
            if (!acquired) {
                throw new IllegalStateException("渲染队列繁忙，请稍后重试");
            }
            paintSources(graphics, sources, layerBounds, request);
        } finally {
            if (acquired) {
                renderPermits.release();
            }
            graphics.dispose();
        }
        return canvas;
    }

    private void paintSources(Graphics2D graphics, List<Path> sources,
            ReferencedEnvelope layerBounds, WmsMapRequest request) throws Exception {
        CoordinateReferenceSystem targetCrs = CRS.decode(request.crs(), true);
        ReferencedEnvelope requestEnvelope = new ReferencedEnvelope(
                request.bbox()[0], request.bbox()[2],
                request.bbox()[1], request.bbox()[3], targetCrs);
        ReferencedEnvelope dataEnvelope = layerBounds.intersection(requestEnvelope);
        if (dataEnvelope == null || dataEnvelope.isEmpty()) {
            // 请求视口完全落在数据范围外，返回纯背景图（OGC 允许）
            return;
        }

        // 请求视口到画布的映射按 WMS 的 BBOX↔像素约定直接计算：
        // 像素 (i, j) 覆盖 [minX + i·Δx, minY + (height-j-1)·Δy] 起的一个像元。
        AffineTransform worldToScreen = worldToScreen(request);
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);

        for (Path source : sources) {
            if (!Files.isReadable(source)) {
                continue;
            }
            paintOneSource(graphics, source, requestEnvelope, targetCrs, worldToScreen);
        }
    }

    /** 请求坐标系 → 画布像素坐标（y 轴向下）。 */
    private AffineTransform worldToScreen(WmsMapRequest request) {
        double spanX = request.bbox()[2] - request.bbox()[0];
        double spanY = request.bbox()[3] - request.bbox()[1];
        AffineTransform transform = new AffineTransform();
        transform.scale(request.width() / spanX, -request.height() / spanY);
        transform.translate(-request.bbox()[0], -request.bbox()[3]);
        return transform;
    }

    private void paintOneSource(Graphics2D graphics, Path source,
            ReferencedEnvelope requestEnvelope, CoordinateReferenceSystem targetCrs,
            AffineTransform worldToScreen) throws Exception {
        GeoTiffReader metadata = null;
        ImageInputStream stream = null;
        ImageReader reader = null;
        try {
            metadata = new GeoTiffReader(source.toFile());
            CoordinateReferenceSystem sourceCrs = metadata.getCoordinateReferenceSystem();
            MathTransform gridToWorld = metadata.getOriginalGridToWorld(PixelInCell.CELL_CORNER);
            GridEnvelope range = metadata.getOriginalGridRange();

            ReferencedEnvelope sourceView = transformEnvelope(
                    requestEnvelope, targetCrs, sourceCrs);
            if (sourceView == null) {
                return;
            }
            // 视口四角换到源栅格坐标后取包围盒：与轴序、坐标轴方向、仿射旋转都无关。
            // 取整带容差：投影往返会引入 1e-6 量级的尾差，直接 floor/ceil 会白读一整行/列，
            // 并让窗口与画布不再严格对齐。
            double[] corners = {
                    sourceView.getMinX(), sourceView.getMinY(),
                    sourceView.getMaxX(), sourceView.getMinY(),
                    sourceView.getMaxX(), sourceView.getMaxY(),
                    sourceView.getMinX(), sourceView.getMaxY()};
            double[] gridCorners = new double[8];
            gridToWorld.inverse().transform(corners, 0, gridCorners, 0, 4);
            double minGridX = Double.POSITIVE_INFINITY;
            double maxGridX = Double.NEGATIVE_INFINITY;
            double minGridY = Double.POSITIVE_INFINITY;
            double maxGridY = Double.NEGATIVE_INFINITY;
            for (int corner = 0; corner < 4; corner++) {
                minGridX = Math.min(minGridX, gridCorners[corner * 2]);
                maxGridX = Math.max(maxGridX, gridCorners[corner * 2]);
                minGridY = Math.min(minGridY, gridCorners[corner * 2 + 1]);
                maxGridY = Math.max(maxGridY, gridCorners[corner * 2 + 1]);
            }
            int col0 = clamp((int) Math.floor(minGridX + GRID_EPSILON),
                    range.getLow(0), range.getHigh(0));
            int col1 = clamp((int) Math.ceil(maxGridX - GRID_EPSILON),
                    range.getLow(0), range.getHigh(0));
            int row0 = clamp((int) Math.floor(minGridY + GRID_EPSILON),
                    range.getLow(1), range.getHigh(1));
            int row1 = clamp((int) Math.ceil(maxGridY - GRID_EPSILON),
                    range.getLow(1), range.getHigh(1));
            if (col1 <= col0 || row1 <= row0) {
                // 视口与源范围没有交集
                return;
            }

            Rectangle2D screen = screenRect(worldToScreen, gridToWorld, sourceCrs, targetCrs,
                    col0, row0, col1, row1);
            if (screen.isEmpty()) {
                return;
            }
            int[] steps = subsampling(col1 - col0, row1 - row0,
                    screen.getWidth(), screen.getHeight());

            stream = ImageIO.createImageInputStream(source.toFile());
            if (stream == null) {
                return;
            }
            reader = firstReader(stream);
            if (reader == null) {
                return;
            }
            reader.setInput(stream, true, true);
            ImageReadParam readParam = reader.getDefaultReadParam();
            readParam.setSourceRegion(new Rectangle(col0, row0, col1 - col0, row1 - row0));
            if (steps[0] > 1 || steps[1] > 1) {
                readParam.setSourceSubsampling(steps[0], steps[1], 0, 0);
            }
            BufferedImage window = reader.read(0, readParam);
            int left = (int) Math.floor(screen.getMinX());
            int top = (int) Math.floor(screen.getMinY());
            int width = Math.max(1, (int) Math.ceil(screen.getMaxX()) - left);
            int height = Math.max(1, (int) Math.ceil(screen.getMaxY()) - top);
            graphics.drawImage(window, left, top, width, height, null);
        } catch (Exception ex) {
            log.warn("[OGC][WMS] 渲染源文件失败，已跳过: {}", source, ex);
            throw ex;
        } finally {
            if (reader != null) {
                reader.dispose();
            }
            closeQuietly(stream);
            if (metadata != null) {
                metadata.dispose();
            }
        }
    }

    /**
     * 源栅格窗口在输出画布上覆盖的矩形。
     *
     * <p>四角先在源坐标系里求包围盒，再换到请求坐标系做屏幕变换。源与请求坐标系不同时
     * 这是一次「矩形到矩形」的近似（不做逐像素重投影），对城市级范围的高斯投影差异可以忽略，
     * 但换来的是读取量与输出像素数同量级——真正的逐像素重投影需要整幅 warp，正是这里要避免的。</p>
     */
    private Rectangle2D screenRect(AffineTransform worldToScreen, MathTransform gridToWorld,
            CoordinateReferenceSystem sourceCrs, CoordinateReferenceSystem targetCrs,
            int col0, int row0, int col1, int row1) throws Exception {
        double[] grid = {col0, row0, col1, row0, col1, row1, col0, row1};
        double[] world = new double[8];
        gridToWorld.transform(grid, 0, world, 0, 4);
        if (!CRS.equalsIgnoreMetadata(sourceCrs, targetCrs)) {
            CRS.findMathTransform(sourceCrs, targetCrs, true).transform(world, 0, world, 0, 4);
        }
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (int corner = 0; corner < 4; corner++) {
            double[] point = {world[corner * 2], world[corner * 2 + 1]};
            worldToScreen.transform(point, 0, point, 0, 1);
            minX = Math.min(minX, point[0]);
            minY = Math.min(minY, point[1]);
            maxX = Math.max(maxX, point[0]);
            maxY = Math.max(maxY, point[1]);
        }
        return new Rectangle2D.Double(minX, minY, maxX - minX, maxY - minY);
    }

    /**
     * 计算源抽样步长，使读取的像素数与输出尺寸同量级。
     *
     * <p>缩小时若不抽样，一帧视口就要把上亿像素的源整幅解码进内存。步长取缩放倍数的二分之一，
     * 让双线性缩放仍有约 2×2 的邻域可取：取整倍数直接抽样会退化成最近邻，
     * 细节丢失比适度多读一点更明显。放大时步长保持 1，不丢细节。</p>
     *
     * <p>读取像素数再由 {@link #MAX_READ_PIXELS} 兜底，保证视口内十几张瓦片并发时
     * 内存不会叠加到不可控。</p>
     */
    private int[] subsampling(int sourceWidth, int sourceHeight,
            double targetWidth, double targetHeight) {
        double scale = Math.sqrt(((double) sourceWidth * sourceHeight)
                / Math.max(1.0, targetWidth * targetHeight));
        int stepX = Math.max(1, (int) Math.round(scale / 2));
        int stepY = Math.max(1, (int) Math.round(scale / 2));
        while (readPixels(sourceWidth, sourceHeight, stepX, stepY) > MAX_READ_PIXELS) {
            stepX++;
            stepY++;
        }
        return new int[]{stepX, stepY};
    }

    private long readPixels(int sourceWidth, int sourceHeight, int stepX, int stepY) {
        long columns = (sourceWidth + stepX - 1L) / stepX;
        long rows = (sourceHeight + stepY - 1L) / stepY;
        return columns * rows;
    }

    private ReferencedEnvelope transformEnvelope(ReferencedEnvelope envelope,
            CoordinateReferenceSystem from, CoordinateReferenceSystem to) {
        if (CRS.equalsIgnoreMetadata(from, to)) {
            return envelope;
        }
        try {
            return envelope.transform(to, true);
        } catch (Exception ex) {
            log.warn("[OGC][WMS] 无法把请求范围转换到源坐标系，跳过该源: {}", to.getName(), ex);
            return null;
        }
    }

    private ImageReader firstReader(ImageInputStream stream) {
        Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
        return readers.hasNext() ? readers.next() : null;
    }

    private void closeQuietly(ImageInputStream stream) {
        if (stream == null) {
            return;
        }
        try {
            stream.close();
        } catch (Exception ignored) {
            // 关闭失败不影响已读取的结果
        }
    }

    private int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
