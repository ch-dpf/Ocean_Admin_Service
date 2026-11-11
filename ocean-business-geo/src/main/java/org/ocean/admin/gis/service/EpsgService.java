package org.ocean.admin.gis.service;

import org.geotools.api.referencing.FactoryException;
import org.geotools.api.referencing.NoSuchAuthorityCodeException;
import org.geotools.api.referencing.crs.*;
import org.geotools.referencing.CRS;
import org.geotools.referencing.ReferencingFactoryFinder;
import org.geotools.util.factory.Hints;
import org.ocean.admin.kernel.common.PageResult;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/** 通过当前 GeoTools 工厂查询 EPSG 定义，数据库位置和生命周期由工厂管理。 */
@Service
public class EpsgService {
    private List<Summary> catalog;

    public PageResult<List<Summary>> search(String keyword, long current, long size) {
        if (current < 1 || size < 1 || size > 100) {
            throw new IllegalArgumentException("分页范围无效，每页最多查询100条");
        }
        String query = keyword == null ? "" : keyword.trim().toUpperCase(Locale.ROOT);
        if (query.length() > 100) {
            throw new IllegalArgumentException("搜索关键字不能超过100个字符");
        }
        if (query.startsWith("EPSG:")) {
            query = query.substring(5).trim();
        }
        String searchText = query;
        List<Summary> matches = catalog().stream()
                .filter(item -> item.code().substring(5).contains(searchText)
                        || item.name().toUpperCase(Locale.ROOT).contains(searchText))
                .sorted(Comparator.comparing((Summary item) -> !item.code().equals("EPSG:" + searchText))
                        .thenComparing(item -> !item.code().substring(5).startsWith(searchText)))
                .toList();
        long total = matches.size();
        // 先判断页码，避免极大页码乘以每页条数时溢出。
        if (current > (total + size - 1) / size) {
            return new PageResult<>(current, size, total, List.of());
        }
        int from = (int) ((current - 1) * size);
        int to = (int) Math.min(from + size, total);
        return new PageResult<>(current, size, total, List.copyOf(matches.subList(from, to)));
    }

    public Detail detail(String code) {
        String normalized = normalizeCode(code);
        CoordinateReferenceSystem crs = resolve(normalized);
        var units = new LinkedHashSet<String>();
        var coordinateSystem = crs.getCoordinateSystem();
        for (int index = 0; index < coordinateSystem.getDimension(); index++) {
            var unit = coordinateSystem.getAxis(index).getUnit();
            if (unit != null) units.add(unit.toString());
        }
        String area = null;
        var domain = crs.getDomainOfValidity();
        if (domain != null && domain.getDescription() != null) {
            area = domain.getDescription().toString(Locale.ENGLISH);
        }
        var geographicBounds = CRS.getGeographicBoundingBox(crs);
        List<Double> bounds = null;
        if (geographicBounds != null) {
            bounds = List.of(geographicBounds.getWestBoundLongitude(), geographicBounds.getSouthBoundLatitude(),
                    geographicBounds.getEastBoundLongitude(), geographicBounds.getNorthBoundLatitude());
        }
        String wkt = null;
        try {
            wkt = crs.toWKT();
        } catch (UnsupportedOperationException ignored) {
            // 少数定义无法表示为 WKT；仍可按其 EPSG 编码使用。
        }
        return new Detail(normalized, crs.getName().getCode(), coordinateType(crs), List.copyOf(units), area, bounds, wkt);
    }

    /** 保存配置时仅解析指定编码，避免为单个编码校验加载整个搜索目录。 */
    public void validateCode(String code) {
        resolve(normalizeCode(code));
    }

    private CoordinateReferenceSystem resolve(String code) {
        try {
            return factory().createCoordinateReferenceSystem(code);
        } catch (NoSuchAuthorityCodeException exception) {
            throw new IllegalArgumentException("源坐标系编码不存在：" + code, exception);
        } catch (FactoryException exception) {
            throw new IllegalStateException("无法解析坐标系 " + code + "，请检查本地EPSG库及投影支持", exception);
        }
    }

    private String normalizeCode(String code) {
        String normalized = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        if (normalized.startsWith("EPSG:")) normalized = normalized.substring(5);
        if (!normalized.matches("[1-9][0-9]{0,8}")) {
            throw new IllegalArgumentException("请输入有效的EPSG编码");
        }
        return "EPSG:" + normalized;
    }

    private CRSAuthorityFactory factory() {
        // 与读取和制图链路使用的经度、纬度顺序保持一致，不设置数据库目录。
        return ReferencingFactoryFinder.getCRSAuthorityFactory("EPSG",
                new Hints(Hints.FORCE_LONGITUDE_FIRST_AXIS_ORDER, true));
    }

    private synchronized List<Summary> catalog() {
        if (catalog != null) return catalog;
        try {
            CRSAuthorityFactory factory = factory();
            List<Summary> entries = new ArrayList<>();
            for (String code : factory.getAuthorityCodes(CoordinateReferenceSystem.class)) {
                String normalized = normalizeCode(code);
                var description = factory.getDescriptionText(code);
                String name = normalized;
                if (description != null) name = description.toString(Locale.ENGLISH);
                entries.add(new Summary(normalized, name));
            }
            entries.sort(Comparator.comparingInt((Summary item) -> Integer.parseInt(item.code().substring(5))));
            if (entries.isEmpty()) throw new IllegalStateException("本地EPSG目录为空，请检查GeoTools数据源");
            // 完整构建成功后才发布缓存，失败时允许下一次查询重新加载。
            catalog = List.copyOf(entries);
            return catalog;
        } catch (FactoryException exception) {
            throw new IllegalStateException("无法读取本地EPSG目录，请稍后重试", exception);
        }
    }

    private String coordinateType(CoordinateReferenceSystem crs) {
        if (crs instanceof ProjectedCRS) return "投影坐标系";
        if (crs instanceof GeographicCRS) return "地理坐标系";
        if (crs instanceof GeocentricCRS) return "地心坐标系";
        if (crs instanceof VerticalCRS) return "垂直坐标系";
        if (crs instanceof CompoundCRS) return "复合坐标系";
        if (crs instanceof EngineeringCRS) return "工程坐标系";
        return "其他坐标系";
    }

    public record Summary(String code, String name) { }

    /** 范围依次为西、南、东、北边界，单位为度；缺失的元数据返回空值。 */
    public record Detail(String code, String name, String type, List<String> units,
                         String area, List<Double> bounds, String wkt) { }
}
