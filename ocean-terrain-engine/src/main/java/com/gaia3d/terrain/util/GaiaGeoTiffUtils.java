/*
 * SPDX-License-Identifier: MPL-2.0
 * 基于 Gaia3D/mago-3d-terrainer v1.14.2-release，已为本库同 JVM 执行改造。
 */
package com.gaia3d.terrain.util;

import com.gaia3d.terrain.structure.GeographicExtension;
import com.gaia3d.util.CelestialBody;
import com.gaia3d.util.GlobeUtils;
import lombok.experimental.UtilityClass;
import org.geotools.api.coverage.grid.GridGeometry;
import org.geotools.api.geometry.Position;
import org.geotools.api.referencing.FactoryException;
import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.geotools.api.referencing.operation.MathTransform;
import org.geotools.api.referencing.operation.TransformException;
import org.geotools.coverage.grid.GridCoordinates2D;
import org.geotools.coverage.grid.GridCoverage2D;
import org.geotools.geometry.jts.JTS;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.referencing.CRS;
import org.joml.Vector2d;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;

@UtilityClass
public class GaiaGeoTiffUtils {
    public static Vector2d getLongitudeLatitudeDegree(GridCoverage2D coverage, int coordX, int coordY, GeometryFactory gf, MathTransform targetToWgs) throws TransformException {
        GridCoordinates2D coord = new GridCoordinates2D(coordX, coordY);
        Position p = coverage.getGridGeometry().gridToWorld(coord);
        Point point = gf.createPoint(new Coordinate(p.getOrdinate(0), p.getOrdinate(1)));
        Geometry wgsP = JTS.transform(point, targetToWgs);
        Point centroid = wgsP.getCentroid();
        Coordinate coordinate = centroid.getCoordinate();
        return new Vector2d(coordinate.x, coordinate.y);
    }

    public static boolean isGridCoverageInTargetCRS(GridCoverage2D coverage) throws FactoryException {
        CoordinateReferenceSystem crsTarget = coverage.getCoordinateReferenceSystem2D();
        CoordinateReferenceSystem crsOutput = org.geotools.referencing.crs.DefaultGeographicCRS.WGS84;
        MathTransform targetToOutput = CRS.findMathTransform(crsTarget, crsOutput, true);
        return targetToOutput.isIdentity();
    }

    public static boolean isGridCoverage2DWGS84(GridCoverage2D coverage) throws FactoryException {
        return isGridCoverageInTargetCRS(coverage);
    }

    public static void getEnvelopeSpanInMetersOfGridCoverage2D(GridCoverage2D coverage, double[] resultEnvelopeSpanMeters) throws FactoryException {
        CelestialBody body = com.gaia3d.util.CelestialBody.EARTH;
        if (isGridCoverageInTargetCRS(coverage)) {
            ReferencedEnvelope envelope = coverage.getEnvelope2D();
            double minLat = envelope.getMinimum(1);
            double maxLat = envelope.getMaximum(1);
            double midLat = (minLat + maxLat) / 2.0;
            double radius = GlobeUtils.getRadiusAtLatitude(midLat, body);
            double degToRadFactor = GlobeUtils.DEGREE_TO_RADIAN_FACTOR;

            ReferencedEnvelope envelopeOriginal = coverage.getEnvelope2D();
            double envelopeSpanX = envelopeOriginal.getSpan(0);
            double envelopeSpanY = envelopeOriginal.getSpan(1);
            resultEnvelopeSpanMeters[0] = (envelopeSpanX * degToRadFactor) * radius;
            resultEnvelopeSpanMeters[1] = (envelopeSpanY * degToRadFactor) * radius;
        } else {
            ReferencedEnvelope envelopeOriginal = coverage.getEnvelope2D();
            resultEnvelopeSpanMeters[0] = envelopeOriginal.getSpan(0);
            resultEnvelopeSpanMeters[1] = envelopeOriginal.getSpan(1);
        }
    }

    public static Vector2d getPixelSizeMeters(GridCoverage2D coverage) throws FactoryException {
        double[] envelopeSpanInMeters = new double[2];
        getEnvelopeSpanInMetersOfGridCoverage2D(coverage, envelopeSpanInMeters);
        GridGeometry gridGeometry = coverage.getGridGeometry();
        int gridSpanX = gridGeometry.getGridRange().getSpan(0);
        int gridSpanY = gridGeometry.getGridRange().getSpan(1);
        double pixelSizeX = envelopeSpanInMeters[0] / gridSpanX;
        double pixelSizeY = envelopeSpanInMeters[1] / gridSpanY;
        return new Vector2d(pixelSizeX, pixelSizeY);
    }

    public static GeographicExtension getGeographicExtension(GridCoverage2D coverage, GeometryFactory gf, MathTransform targetToWgs, GeographicExtension resultGeoExtension) throws TransformException {

        ReferencedEnvelope envelope = coverage.getEnvelope2D();

        double minLon = 0.0;
        double minLat = 0.0;
        double maxLon = 0.0;
        double maxLat = 0.0;

        if (targetToWgs.isIdentity()) {

            minLon = envelope.getMinimum(0);
            minLat = envelope.getMinimum(1);
            maxLon = envelope.getMaximum(0);
            maxLat = envelope.getMaximum(1);
        } else {
            GridGeometry originalGridGeometry = coverage.getGridGeometry();

            int gridSpanX = originalGridGeometry.getGridRange().getSpan(0);
            int gridSpanY = originalGridGeometry.getGridRange().getSpan(1);

            Vector2d lonLatLeftUp = GaiaGeoTiffUtils.getLongitudeLatitudeDegree(coverage, 0, gridSpanY - 1, gf, targetToWgs);

            Vector2d lonLatRightDown = GaiaGeoTiffUtils.getLongitudeLatitudeDegree(coverage, gridSpanX - 1, 0, gf, targetToWgs);

            Vector2d lonLatLeftDown = GaiaGeoTiffUtils.getLongitudeLatitudeDegree(coverage, 0, 0, gf, targetToWgs);

            Vector2d lonLatRightUp = GaiaGeoTiffUtils.getLongitudeLatitudeDegree(coverage, gridSpanX - 1, gridSpanY - 1, gf, targetToWgs);

            minLon = Math.min(lonLatLeftUp.x, lonLatRightDown.x);
            minLon = Math.min(minLon, lonLatLeftDown.x);
            minLon = Math.min(minLon, lonLatRightUp.x);

            maxLon = Math.max(lonLatLeftUp.x, lonLatRightDown.x);
            maxLon = Math.max(maxLon, lonLatLeftDown.x);
            maxLon = Math.max(maxLon, lonLatRightUp.x);

            minLat = Math.min(lonLatLeftUp.y, lonLatRightDown.y);
            minLat = Math.min(minLat, lonLatLeftDown.y);
            minLat = Math.min(minLat, lonLatRightUp.y);

            maxLat = Math.max(lonLatLeftUp.y, lonLatRightDown.y);
            maxLat = Math.max(maxLat, lonLatLeftDown.y);
            maxLat = Math.max(maxLat, lonLatRightUp.y);
        }

        if (resultGeoExtension == null) {
            resultGeoExtension = new GeographicExtension();
        }

        resultGeoExtension.setDegrees(minLon, minLat, 0.0, maxLon, maxLat, 0.0);
        return resultGeoExtension;
    }

}
