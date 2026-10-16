package org.ocean.admin.gis.util;

import org.geotools.api.referencing.operation.MathTransform;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.geom.util.AffineTransformation;
import org.locationtech.jts.operation.polygonize.Polygonizer;

import java.util.*;

/** 按行保存有效像元的边界，避免为每个像元创建多边形。像元坐标指向像元外沿。 */
public final class RasterFootprintBuilder {
    private static final double GEOGRAPHIC_BOUNDARY_TOLERANCE = 1e-6;
    private static final GeometryFactory PIXELS = new GeometryFactory();
    private static final GeometryFactory GEOGRAPHIC = new GeometryFactory(new PrecisionModel(), 4326);
    private final int width;
    private final int maximumSegments;
    private final List<LineString> segments = new ArrayList<>();
    private final Map<Long, Integer> verticalStarts = new HashMap<>();
    private BitSet previous = new BitSet();
    private int rowIndex;
    private boolean finished;

    public RasterFootprintBuilder(int width, int maximumSegments) {
        if (width < 1 || maximumSegments < 4) throw new IllegalArgumentException("轮廓尺寸或线段限额无效");
        this.width = width;
        this.maximumSegments = maximumSegments;
    }

    public void acceptRow(BitSet current) {
        if (finished || current.length() > width) throw new IllegalStateException("轮廓行状态或宽度无效");
        BitSet changed = (BitSet) previous.clone();
        changed.xor(current);
        for (int start = changed.nextSetBit(0); start >= 0;) {
            int orientationEnd = previous.get(start) ? previous.nextClearBit(start) : previous.nextSetBit(start);
            int end = Math.min(changed.nextClearBit(start), orientationEnd < 0 ? width : orientationEnd);
            addSegment(start, rowIndex, end, rowIndex);
            start = changed.nextSetBit(end);
        }
        Set<Long> active = new HashSet<>();
        for (int start = current.nextSetBit(0); start >= 0;) {
            int end = Math.min(width, current.nextClearBit(start));
            // 区分左右边界，避免将对角接触像元的线段错误合并。
            active.add(start * 2L);
            active.add(end * 2L + 1);
            start = current.nextSetBit(end);
        }
        for (var iterator = verticalStarts.entrySet().iterator(); iterator.hasNext();) {
            var segment = iterator.next();
            if (!active.contains(segment.getKey())) {
                double x = segment.getKey() / 2;
                addSegment(x, segment.getValue(), x, rowIndex);
                iterator.remove();
            }
        }
        for (Long key : active) verticalStarts.putIfAbsent(key, rowIndex);
        previous = (BitSet) current.clone();
        rowIndex++;
    }

    public Geometry buildGeometry() {
        if (finished) throw new IllegalStateException("轮廓已构建");
        acceptRow(new BitSet());
        finished = true;
        if (segments.isEmpty()) throw failure("EMPTY_GEOMETRY", "栅格不包含有效像元");
        Polygonizer polygonizer = new Polygonizer(true);
        polygonizer.add(segments);
        Geometry geometry = polygonizer.getGeometry();
        if (!geometry.isValid() || !polygonizer.getDangles().isEmpty()
                || !polygonizer.getCutEdges().isEmpty() || !polygonizer.getInvalidRingLines().isEmpty()) {
            throw failure("INVALID_EXTENT", "有效像元边界无法构成有效轮廓");
        }
        return geometry;
    }

    private void addSegment(double x1, double y1, double x2, double y2) {
        if (segments.size() >= maximumSegments) {
            throw failure("FOOTPRINT_TOO_COMPLEX", "有效数据轮廓超出线段限额：" + maximumSegments);
        }
        segments.add(PIXELS.createLineString(new Coordinate[]{new Coordinate(x1, y1), new Coordinate(x2, y2)}));
    }

    /** 加密在像元空间完成，保留全球覆盖的方向及投影转换后弯曲的边界。 */
    public static Geometry toGeographic(Geometry pixels, MathTransform gridToSource,
            MathTransform toGeographic, double left, double top, int width, int height,
            int maximumCoordinates, Runnable heartbeat) throws Exception {
        List<Polygon> polygons = new ArrayList<>();
        CoordinateBudget budget = new CoordinateBudget(maximumCoordinates, heartbeat);
        double maximumStep = Math.max(width, height) / 128d;
        for (int i = 0; i < pixels.getNumGeometries(); i++) {
            Polygon pixelPolygon = (Polygon) pixels.getGeometryN(i);
            LinearRing shell = transformRing(pixelPolygon.getExteriorRing(), gridToSource, toGeographic,
                    left, top, maximumStep, null, budget);
            LinearRing[] holes = new LinearRing[pixelPolygon.getNumInteriorRing()];
            for (int h = 0; h < holes.length; h++) {
                holes[h] = transformRing(pixelPolygon.getInteriorRingN(h), gridToSource, toGeographic,
                        left, top, maximumStep, shell.getEnvelopeInternal().centre().x, budget);
            }
            Polygon unwrapped = GEOGRAPHIC.createPolygon(shell, holes);
            if (!unwrapped.isValid()) throw failure("INVALID_EXTENT", "坐标转换后的轮廓无效");
            Envelope bounds = unwrapped.getEnvelopeInternal();
            if (bounds.getWidth() > 360 + GEOGRAPHIC_BOUNDARY_TOLERANCE) {
                throw failure("INVALID_EXTENT", "栅格经度跨度超过360度");
            }
            int first = (int) Math.floor((bounds.getMinX() + 180) / 360);
            int last = (int) Math.floor((bounds.getMaxX() + 180) / 360);
            for (int offset = first; offset <= last; offset++) {
                heartbeat.run();
                Geometry clipped = unwrapped.intersection(GEOGRAPHIC.toGeometry(
                        new Envelope(-180 + offset * 360d, 180 + offset * 360d, -90, 90)));
                Geometry shifted = AffineTransformation.translationInstance(-offset * 360d, 0).transform(clipped);
                collectPolygons(shifted, polygons);
            }
        }
        Geometry result = GEOGRAPHIC.createMultiPolygon(polygons.toArray(Polygon[]::new));
        if (result.isEmpty() || !result.isValid()) throw failure("INVALID_EXTENT", "归一化后的栅格轮廓无效");
        if (result.getNumPoints() > maximumCoordinates) throw failure("FOOTPRINT_TOO_COMPLEX", "地理轮廓超出坐标限额");
        return result;
    }

    private static void collectPolygons(Geometry geometry, List<Polygon> polygons) {
        if (geometry instanceof Polygon polygon) {
            if (!polygon.isEmpty()) polygons.add(polygon);
        } else if (geometry instanceof GeometryCollection collection) {
            for (int i = 0; i < collection.getNumGeometries(); i++) collectPolygons(collection.getGeometryN(i), polygons);
        }
    }

    private static LinearRing transformRing(LineString ring, MathTransform gridToSource,
            MathTransform toGeographic, double left, double top, double maximumStep,
            Double anchor, CoordinateBudget budget) throws Exception {
        List<Coordinate> coordinates = new ArrayList<>();
        Coordinate[] original = ring.getCoordinates();
        Coordinate first = transform(original[0], gridToSource, toGeographic, left, top);
        budget.add(coordinates, first);
        for (int i = 1; i < original.length; i++) {
            Coordinate end = transform(original[i], gridToSource, toGeographic, left, top);
            densify(original[i - 1], original[i], coordinates.getLast(), end, gridToSource, toGeographic,
                    left, top, maximumStep, 0, coordinates, budget);
        }
        for (int i = 1; i < coordinates.size(); i++) {
            double previousX = coordinates.get(i - 1).x;
            coordinates.get(i).x = nearestLongitude(coordinates.get(i).x, previousX);
        }
        if (Math.abs(coordinates.getLast().x - coordinates.getFirst().x) > GEOGRAPHIC_BOUNDARY_TOLERANCE) {
            throw failure("UNSUPPORTED_POLAR_FOOTPRINT", "轮廓环绕极点，无法构成普通经纬度多边形");
        }
        coordinates.set(coordinates.size() - 1, new Coordinate(coordinates.getFirst()));
        LinearRing result = GEOGRAPHIC.createLinearRing(coordinates.toArray(Coordinate[]::new));
        if (anchor != null) {
            double offset = Math.rint((anchor - result.getEnvelopeInternal().centre().x) / 360) * 360;
            result = (LinearRing) AffineTransformation.translationInstance(offset, 0).transform(result);
        }
        return result;
    }

    private static void densify(Coordinate startPixel, Coordinate endPixel, Coordinate start, Coordinate end,
            MathTransform gridToSource, MathTransform toGeographic, double left, double top, double maximumStep,
            int depth, List<Coordinate> coordinates, CoordinateBudget budget) throws Exception {
        Coordinate middlePixel = new Coordinate((startPixel.x + endPixel.x) / 2, (startPixel.y + endPixel.y) / 2);
        Coordinate middle = transform(middlePixel, gridToSource, toGeographic, left, top);
        double endX = nearestLongitude(end.x, start.x);
        double middleX = nearestLongitude(middle.x, (start.x + endX) / 2);
        boolean split = startPixel.distance(endPixel) > maximumStep
                || Math.abs(middleX - (start.x + endX) / 2) > GEOGRAPHIC_BOUNDARY_TOLERANCE
                || Math.abs(middle.y - (start.y + end.y) / 2) > GEOGRAPHIC_BOUNDARY_TOLERANCE;
        if (split && depth < 24) {
            densify(startPixel, middlePixel, start, middle, gridToSource, toGeographic,
                    left, top, maximumStep, depth + 1, coordinates, budget);
            densify(middlePixel, endPixel, middle, end, gridToSource, toGeographic,
                    left, top, maximumStep, depth + 1, coordinates, budget);
        } else {
            if (split) throw failure("INVALID_EXTENT", "轮廓转换未收敛");
            budget.add(coordinates, end);
        }
    }

    private static Coordinate transform(Coordinate pixel, MathTransform gridToSource,
            MathTransform toGeographic, double left, double top) throws Exception {
        double[] point = {left + pixel.x, top + pixel.y};
        gridToSource.transform(point, 0, point, 0, 1);
        toGeographic.transform(point, 0, point, 0, 1);
        double longitude = point[0], latitude = point[1];
        if (!Double.isFinite(longitude) || !Double.isFinite(latitude)
                || latitude < -90 - GEOGRAPHIC_BOUNDARY_TOLERANCE || latitude > 90 + GEOGRAPHIC_BOUNDARY_TOLERANCE) {
            throw failure("INVALID_EXTENT", "GeoTIFF 转换后的经纬度范围无效");
        }
        if (Math.abs(longitude - 180) <= GEOGRAPHIC_BOUNDARY_TOLERANCE) longitude = 180;
        else if (Math.abs(longitude + 180) <= GEOGRAPHIC_BOUNDARY_TOLERANCE) longitude = -180;
        return new Coordinate(longitude, Math.clamp(latitude, -90d, 90d));
    }

    private static double nearestLongitude(double longitude, double anchor) {
        return longitude + Math.rint((anchor - longitude) / 360) * 360;
    }

    private static final class CoordinateBudget {
        private final int maximum;
        private final Runnable heartbeat;
        private int count;
        private CoordinateBudget(int maximum, Runnable heartbeat) { this.maximum = maximum; this.heartbeat = heartbeat; }
        private void add(List<Coordinate> coordinates, Coordinate coordinate) {
            if (++count > maximum) throw failure("FOOTPRINT_TOO_COMPLEX", "轮廓转换超出坐标限额：" + maximum);
            if ((count & 4095) == 0) heartbeat.run();
            coordinates.add(coordinate);
        }
    }

    private static FootprintException failure(String code, String message) { return new FootprintException(code, message); }

    public static final class FootprintException extends IllegalArgumentException {
        private final String code;
        private FootprintException(String code, String message) { super(message); this.code = code; }
        public String code() { return code; }
    }
}
