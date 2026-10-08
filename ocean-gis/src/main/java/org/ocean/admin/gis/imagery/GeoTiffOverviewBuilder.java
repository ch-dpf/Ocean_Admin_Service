package org.ocean.admin.gis.imagery;

import org.geotools.api.parameter.GeneralParameterValue;
import org.geotools.api.parameter.ParameterValue;
import org.geotools.coverage.grid.GridCoverage2D;
import org.geotools.coverage.grid.GridEnvelope2D;
import org.geotools.coverage.grid.GridGeometry2D;
import org.geotools.coverage.grid.io.AbstractGridFormat;
import org.geotools.coverage.grid.io.OverviewPolicy;
import org.geotools.gce.geotiff.GeoTiffReader;
import org.ocean.admin.gis.processing.GisProcessingProgress;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.RenderedImage;
import java.awt.image.SampleModel;
import java.awt.image.DataBuffer;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.WritableRaster;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** 使用标准 ImageIO 接口生成 GeoTools 可识别的外部多页 TIFF overview。 */
public class GeoTiffOverviewBuilder {

    private static final int OVERVIEW_FACTOR = 2;
    private static final int TIFF_TILE_SIZE = 256;
    private static final long BIG_TIFF_THRESHOLD = 3_500_000_000L;

    public int build(Path baseTiff, int minimumSize,
            Consumer<GisProcessingProgress> progressListener) throws IOException {
        Path source = baseTiff.toAbsolutePath().normalize();
        Path overview = overviewPath(source);
        Path temporary = overview.resolveSibling(overview.getFileName() + ".tmp");
        Files.deleteIfExists(temporary);

        GeoTiffReader reader = null;
        ImageWriter writer = null;
        try {
            reader = new GeoTiffReader(source.toFile());
            int width = reader.getOriginalGridRange().getSpan(0);
            int height = reader.getOriginalGridRange().getSpan(1);
            List<OverviewSize> levels = overviewSizes(width, height, minimumSize);
            if (levels.isEmpty()) {
                Files.deleteIfExists(overview);
                return 0;
            }

            writer = tiffSequenceWriter();
            try (ImageOutputStream output = ImageIO.createImageOutputStream(temporary.toFile())) {
                if (output == null) {
                    throw new IOException("无法创建外部 overview 输出流: " + temporary);
                }
                writer.setOutput(output);
                writer.prepareWriteSequence(null);
                boolean forceBigTiff = estimatedUncompressedSize(reader, levels)
                        >= BIG_TIFF_THRESHOLD;
                for (int index = 0; index < levels.size(); index++) {
                    OverviewSize size = levels.get(index);
                    GridCoverage2D coverage = null;
                    try {
                        coverage = reader.read(readParameters(reader, size));
                        if (coverage == null) {
                            throw new IOException("GeoTIFF overview 读取结果为空: " + size);
                        }
                        BufferedImage image = materialize(coverage.getRenderedImage());
                        writer.writeToSequence(new IIOImage(image, null, null),
                                writeParameters(writer, image, forceBigTiff));
                    } finally {
                        if (coverage != null) {
                            coverage.dispose(true);
                        }
                    }
                    progressListener.accept(GisProcessingProgress.determinate(
                            "building-overviews", index + 1L, levels.size(),
                            "正在构建影像概览图：" + (index + 1) + "/" + levels.size()));
                }
                writer.endWriteSequence();
                output.flush();
            }
            moveAtomically(temporary, overview);
            return levels.size();
        } catch (Exception ex) {
            Files.deleteIfExists(temporary);
            if (ex instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException("构建外部 GeoTIFF overview 失败: " + ex.getMessage(), ex);
        } finally {
            if (writer != null) {
                writer.dispose();
            }
            if (reader != null) {
                reader.dispose();
            }
        }
    }

    public int validate(Path baseTiff, int minimumSize) throws IOException {
        GeoTiffReader reader = null;
        try {
            reader = new GeoTiffReader(baseTiff.toFile());
            int width = reader.getOriginalGridRange().getSpan(0);
            int height = reader.getOriginalGridRange().getSpan(1);
            int expected = overviewSizes(width, height, minimumSize).size();
            double[][] resolutions = reader.getResolutionLevels();
            int actual = resolutions == null ? 0 : Math.max(0, resolutions.length - 1);
            if (actual < expected) {
                throw new IOException("GeoTIFF overview 数量不足，期望" + expected + "层，实际" + actual + "层");
            }
            return actual;
        } finally {
            if (reader != null) {
                reader.dispose();
            }
        }
    }

    public Path overviewPath(Path baseTiff) {
        return baseTiff.resolveSibling(baseTiff.getFileName() + ".ovr");
    }

    private GeneralParameterValue[] readParameters(GeoTiffReader reader, OverviewSize size) {
        ParameterValue<GridGeometry2D> geometry = AbstractGridFormat.READ_GRIDGEOMETRY2D.createValue();
        geometry.setValue(new GridGeometry2D(
                new GridEnvelope2D(0, 0, size.width(), size.height()),
                reader.getOriginalEnvelope()));
        ParameterValue<OverviewPolicy> overview = AbstractGridFormat.OVERVIEW_POLICY.createValue();
        overview.setValue(OverviewPolicy.IGNORE);
        ParameterValue<Boolean> useImageN = AbstractGridFormat.USE_IMAGEN_IMAGEREAD.createValue();
        useImageN.setValue(Boolean.TRUE);
        return new GeneralParameterValue[]{geometry, overview, useImageN};
    }

    private List<OverviewSize> overviewSizes(int width, int height, int minimumSize) {
        List<OverviewSize> levels = new ArrayList<>();
        int currentWidth = width;
        int currentHeight = height;
        while (Math.max(currentWidth, currentHeight) > minimumSize) {
            currentWidth = Math.max(1, (currentWidth + OVERVIEW_FACTOR - 1) / OVERVIEW_FACTOR);
            currentHeight = Math.max(1, (currentHeight + OVERVIEW_FACTOR - 1) / OVERVIEW_FACTOR);
            levels.add(new OverviewSize(currentWidth, currentHeight));
        }
        return levels;
    }

    private ImageWriter tiffSequenceWriter() throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("TIFF");
        ImageWriter fallback = null;
        while (writers.hasNext()) {
            ImageWriter candidate = writers.next();
            if (!candidate.canWriteSequence()) {
                candidate.dispose();
                continue;
            }
            if (candidate.getClass().getName().toLowerCase(Locale.ROOT).contains("geosolutions")) {
                if (fallback != null) {
                    fallback.dispose();
                }
                return candidate;
            }
            if (fallback == null) {
                fallback = candidate;
            } else {
                candidate.dispose();
            }
        }
        if (fallback != null) {
            return fallback;
        }
        throw new IOException("当前 JVM 没有支持多页写入的 TIFF ImageWriter");
    }

    private ImageWriteParam writeParameters(ImageWriter writer, RenderedImage image,
            boolean forceBigTiff) {
        ImageWriteParam parameters = writer.getDefaultWriteParam();
        if (parameters.canWriteCompressed()) {
            String compression = preferredCompression(parameters.getCompressionTypes());
            if (compression != null) {
                parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                parameters.setCompressionType(compression);
            }
        }
        if (parameters.canWriteTiles()) {
            parameters.setTilingMode(ImageWriteParam.MODE_EXPLICIT);
            parameters.setTiling(
                    Math.min(TIFF_TILE_SIZE, image.getWidth()),
                    Math.min(TIFF_TILE_SIZE, image.getHeight()), 0, 0);
        }
        if (forceBigTiff) {
            enableBigTiff(parameters);
        }
        return parameters;
    }

    private BufferedImage materialize(RenderedImage source) throws IOException {
        ColorModel colorModel = source.getColorModel();
        if (colorModel == null) {
            throw new IOException("GeoTIFF overview 缺少颜色模型");
        }
        WritableRaster raster = colorModel.createCompatibleWritableRaster(
                source.getWidth(), source.getHeight());
        source.copyData(raster);
        return new BufferedImage(colorModel, raster,
                colorModel.isAlphaPremultiplied(), null);
    }

    private long estimatedUncompressedSize(GeoTiffReader reader, List<OverviewSize> levels)
            throws IOException {
        SampleModel sampleModel = reader.getImageLayout().getSampleModel(null);
        int bands = sampleModel == null ? 4 : sampleModel.getNumBands();
        int dataType = sampleModel == null ? DataBuffer.TYPE_BYTE : sampleModel.getDataType();
        long bytesPerPixel = Math.max(1L,
                ((long) bands * DataBuffer.getDataTypeSize(dataType) + 7L) / 8L);
        long total = 0L;
        try {
            for (OverviewSize level : levels) {
                long pixels = Math.multiplyExact((long) level.width(), level.height());
                total = Math.addExact(total, Math.multiplyExact(pixels, bytesPerPixel));
            }
            return total;
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }

    private String preferredCompression(String[] compressionTypes) {
        if (compressionTypes == null) {
            return null;
        }
        for (String preferred : List.of("Deflate", "LZW")) {
            for (String available : compressionTypes) {
                if (preferred.equalsIgnoreCase(available)) {
                    return available;
                }
            }
        }
        return null;
    }

    private void enableBigTiff(ImageWriteParam parameters) {
        try {
            Method method = parameters.getClass().getMethod("setForceToBigTIFF", boolean.class);
            method.invoke(parameters, true);
        } catch (ReflectiveOperationException ignored) {
            // 标准 ImageIO writer 没有 BigTIFF 扩展时保持其默认行为。
        }
    }

    private void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private record OverviewSize(int width, int height) { }
}
