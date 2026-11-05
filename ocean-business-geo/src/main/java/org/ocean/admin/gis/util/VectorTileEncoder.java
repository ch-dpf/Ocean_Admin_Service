package org.ocean.admin.gis.util;

import lombok.RequiredArgsConstructor;
import org.locationtech.jts.algorithm.Orientation;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.precision.GeometryPrecisionReducer;
import org.locationtech.jts.simplify.DouglasPeuckerSimplifier;
import org.locationtech.jts.simplify.TopologyPreservingSimplifier;
import org.ocean.admin.gis.entity.GisVectorFeature;
import org.ocean.admin.gis.entity.GisVectorLayer;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 只编码几何与属性，完全不读取样式；文件数据与发布快照共用 MVT 2.1 编码。 */
@Component
@RequiredArgsConstructor
public class VectorTileEncoder {
    private final ObjectMapper json;
    private final VectorTemplateRules rules;
    private static final GeometryFactory FACTORY = new GeometryFactory();

    public Tile begin(int z, int x, int y, JsonNode policy, int maxBytes) {
        if (z < 0 || z > policy.path("nativeMaxZoom").asInt(18) || x < 0 || y < 0 || x >= (1L << z) || y >= (1L << z))
            throw new IllegalArgumentException("瓦片坐标超出范围");
        return new Tile(z, x, y, policy, maxBytes);
    }

    public final class Tile {
        private final int z, x, y, extent, buffer, maxBytes;
        private final JsonNode policy;
        private final List<byte[]> features = new ArrayList<>();
        private final Map<String, Integer> keys = new LinkedHashMap<>();
        private final Map<String, Integer> values = new LinkedHashMap<>();
        private final List<byte[]> encodedValues = new ArrayList<>();
        private int bytes;

        private Tile(int z, int x, int y, JsonNode policy, int maxBytes) {
            this.z=z; this.x=x; this.y=y; this.policy=policy; this.maxBytes=maxBytes;
            this.extent=policy.path("extent").asInt(8192); this.buffer=policy.path("buffer").asInt(256);
        }

        public double[] bbox() {
            double margin=(double)buffer/extent, n=Math.scalb(1, z);
            return new double[]{Math.max(-180,(x-margin)/n*360-180), latitude((y+1+margin)/n),
                    Math.min(180,(x+1+margin)/n*360-180), latitude((y-margin)/n)};
        }

        public void add(GisVectorFeature feature, GisVectorLayer layer, String groupKey) {
            JsonNode group = null;
            for (JsonNode candidate : policy.path("groups"))
                if (groupKey.equals(candidate.path("groupKey").asText())) { group=candidate; break; }
            double businessZoom = VectorTemplateRules.tileBusinessZoom(z, policy);
            if (group == null || businessZoom < group.path("minZoom").asDouble()) return;
            JsonNode properties = json.readTree(feature.getPropertiesJson() == null ? "null" : feature.getPropertiesJson());
            if ("AUTO".equals(layer.getAssignmentMode())) {
                for (JsonNode override : group.path("overrides")) {
                    if (rules.matches(override.path("filter"), properties)) {
                        if (businessZoom < override.path("minZoom").asDouble()) return;
                        break;
                    }
                }
            }
            Geometry shape = geometry(json.readTree(feature.getGeometryJson()));
            shape.apply((CoordinateFilter)c -> {
                double latitude=Math.max(-85.0511287798066,Math.min(85.0511287798066,c.y));
                double sin=Math.sin(Math.toRadians(latitude));
                c.x=((c.x+180)/360*Math.scalb(1,z)-x)*extent;
                c.y=((.5-Math.log((1+sin)/(1-sin))/(4*Math.PI))*Math.scalb(1,z)-y)*extent;
            });
            shape.geometryChanged();
            Geometry anchor = shape.getDimension() == 2 ? shape.getInteriorPoint() : null;
            double pixel=(double)extent/policy.path("tileSize").asInt(512);
            boolean preserve=group.path("preserveBoundary").asBoolean() || z==policy.path("nativeMaxZoom").asInt(18);
            if (!preserve) {
                if (shape.getDimension()==2 && shape.getArea()/pixel/pixel < policy.path("polygonMinAreaPixels").asDouble()) return;
                if (shape.getDimension()==1 && shape.getLength()/pixel < policy.path("lineMinLengthPixels").asDouble()) return;
                double tolerance=pixel*policy.path("simplifyPixels").asDouble();
                shape=shape.getDimension()==2 ? TopologyPreservingSimplifier.simplify(shape,tolerance)
                        : DouglasPeuckerSimplifier.simplify(shape,tolerance);
            }
            Map<String, JsonNode> attributes=new LinkedHashMap<>();
            attributes.put("_vector_feature_id",json.valueToTree(feature.getId().toString()));
            attributes.put("_vector_layer_id",json.valueToTree(layer.getId().toString()));
            attributes.put("_vector_group_id",json.valueToTree(layer.getGroupId().toString()));
            attributes.put("_vector_group_key",json.valueToTree(groupKey));
            var nulls=json.createArrayNode();
            // 与PostGIS MVT契约一致，只投影固定的标量配图字段。
            for (JsonNode field : policy.path("propertyFields")) {
                String name = field.asText();
                JsonNode value = properties.path(name);
                if (value.isNull()) nulls.add(name);
                else if (value.isString() || value.isNumber() || value.isBoolean()) attributes.put(name, value);
            }
            attributes.put("_vector_null_fields",json.valueToTree(json.writeValueAsString(nulls)));
            emit(feature.getId(),shape,shape.getDimension()==2 ? "FILL" : "GEOMETRY",attributes);
            if (shape.getDimension()==2) {
                emit(feature.getId(),shape.getBoundary(),"OUTLINE",attributes);
                Coordinate coordinate=anchor.getCoordinate();
                if (coordinate != null && coordinate.x>=0 && coordinate.x<extent && coordinate.y>=0 && coordinate.y<extent)
                    emit(feature.getId(),anchor,"LABEL",attributes);
            }
        }

        private void emit(long id, Geometry shape, String role, Map<String, JsonNode> attributes) {
            int dimension=shape.getDimension();
            Geometry clip=FACTORY.toGeometry(new Envelope(-buffer,extent+buffer,-buffer,extent+buffer));
            Geometry clipped=GeometryPrecisionReducer.reduce(shape.intersection(clip),new PrecisionModel(1));
            if (clipped.isEmpty()) return;
            List<Geometry> parts=new ArrayList<>(); collect(clipped,dimension,parts);
            if (parts.isEmpty()) return;
            attributes.put("_vector_representation_id",json.valueToTree(id+":"+role));
            attributes.put("_vector_render_role",json.valueToTree(role));
            attributes.put("_vector_category",json.valueToTree(dimension==2?"POLYGON":dimension==1?"LINE":"POINT"));
            Proto tags=new Proto();
            for (var attribute : attributes.entrySet()) {
                int key=keys.computeIfAbsent(attribute.getKey(),k->keys.size());
                String valueKey=attribute.getValue().toString();
                Integer value=values.get(valueKey);
                if (value==null) {
                    value=values.size(); values.put(valueKey,value); byte[] encoded=encodeValue(attribute.getValue());
                    encodedValues.add(encoded); bytes+=encoded.length+attribute.getKey().length()+16;
                }
                tags.varint(key); tags.varint(value);
            }
            Proto commands=new Proto(); int[] cursor={0,0};
            for (Geometry part : parts) {
                if (part instanceof Point point) path(commands,new Coordinate[]{point.getCoordinate()},cursor,false,false);
                else if (part instanceof LineString line) path(commands,line.getCoordinates(),cursor,false,false);
                else if (part instanceof Polygon polygon) {
                    path(commands,polygon.getExteriorRing().getCoordinates(),cursor,true,true);
                    for(int i=0;i<polygon.getNumInteriorRing();i++) path(commands,polygon.getInteriorRingN(i).getCoordinates(),cursor,true,false);
                }
            }
            if (commands.out.size()==0) return;
            Proto result=new Proto(); result.fieldBytes(2,tags.bytes()); result.fieldInt(3,dimension+1); result.fieldBytes(4,commands.bytes());
            byte[] encoded=result.bytes(); features.add(encoded); bytes+=encoded.length+16;
            if (bytes>maxBytes) throw new IllegalArgumentException("瓦片超过解码体积上限，未截断要素");
        }

        public byte[] finish() {
            if(features.isEmpty()) return new byte[0];
            Proto layer=new Proto(); layer.fieldString(1,"features");
            for(byte[] feature:features) layer.fieldBytes(2,feature);
            for(String key:keys.keySet()) layer.fieldString(3,key);
            for(byte[] value:encodedValues) layer.fieldBytes(4,value);
            layer.fieldInt(5,extent); layer.fieldInt(15,2);
            Proto tile=new Proto(); tile.fieldBytes(3,layer.bytes());
            if(tile.out.size()>maxBytes) throw new IllegalArgumentException("瓦片超过解码体积上限");
            return tile.bytes();
        }
    }

    private static double latitude(double y) { return Math.toDegrees(Math.atan(Math.sinh(Math.PI*(1-2*y)))); }

    private static void collect(Geometry shape,int dimension,List<Geometry> parts) {
        if(shape instanceof GeometryCollection collection) {
            for(int i=0;i<collection.getNumGeometries();i++) collect(collection.getGeometryN(i),dimension,parts);
        } else if(!shape.isEmpty() && shape.getDimension()==dimension) parts.add(shape);
    }

    private static void path(Proto output,Coordinate[] input,int[] cursor,boolean ring,boolean exterior) {
        List<Coordinate> points=new ArrayList<>();
        int size=ring?input.length-1:input.length;
        for(int i=0;i<size;i++) {
            Coordinate point=new Coordinate(Math.rint(input[i].x),Math.rint(input[i].y));
            if(points.isEmpty() || !points.getLast().equals2D(point)) points.add(point);
        }
        if(ring && points.size()>1 && points.getFirst().equals2D(points.getLast())) points.removeLast();
        if(points.size()<(ring?3:input.length==1?1:2)) return;
        if(ring) {
            var closed=new ArrayList<>(points); closed.add(points.getFirst());
            // 屏幕坐标Y向下，外环应为顺时针；数值坐标中的CCW对应屏幕顺时针。
            if(Orientation.isCCW(closed.toArray(Coordinate[]::new))!=exterior) Collections.reverse(points);
        }
        output.varint(9); delta(output,points.getFirst(),cursor);
        if(points.size()>1) {
            output.varint(((long)(points.size()-1)<<3)|2);
            for(int i=1;i<points.size();i++) delta(output,points.get(i),cursor);
        }
        if(ring) output.varint(15);
    }

    private static void delta(Proto output,Coordinate point,int[] cursor) {
        int dx=(int)point.x-cursor[0],dy=(int)point.y-cursor[1];
        output.varint(((long)dx<<1)^(dx>>31)); output.varint(((long)dy<<1)^(dy>>31));
        cursor[0]=(int)point.x;cursor[1]=(int)point.y;
    }

    private Geometry geometry(JsonNode node) {
        JsonNode coordinates=node.path("coordinates");
        return switch(node.path("type").asText()) {
            case "Point" -> FACTORY.createPoint(coordinate(coordinates));
            case "LineString" -> FACTORY.createLineString(coordinates(coordinates));
            case "Polygon" -> polygon(coordinates);
            case "MultiPoint" -> FACTORY.createMultiPointFromCoords(coordinates(coordinates));
            case "MultiLineString" -> {
                LineString[] lines=new LineString[coordinates.size()];
                for(int i=0;i<lines.length;i++) lines[i]=FACTORY.createLineString(coordinates(coordinates.get(i)));
                yield FACTORY.createMultiLineString(lines);
            }
            case "MultiPolygon" -> {
                Polygon[] polygons=new Polygon[coordinates.size()];
                for(int i=0;i<polygons.length;i++) polygons[i]=polygon(coordinates.get(i));
                yield FACTORY.createMultiPolygon(polygons);
            }
            default -> throw new IllegalArgumentException("工作要素几何类型不支持瓦片编码");
        };
    }

    private Polygon polygon(JsonNode rings) {
        LinearRing shell=FACTORY.createLinearRing(coordinates(rings.get(0)));
        LinearRing[] holes=new LinearRing[rings.size()-1];
        for(int i=0;i<holes.length;i++) holes[i]=FACTORY.createLinearRing(coordinates(rings.get(i+1)));
        return FACTORY.createPolygon(shell,holes);
    }
    private static Coordinate coordinate(JsonNode node) { return new Coordinate(node.get(0).asDouble(),node.get(1).asDouble()); }
    private static Coordinate[] coordinates(JsonNode nodes) {
        Coordinate[] values=new Coordinate[nodes.size()];
        for(int i=0;i<values.length;i++) values[i]=coordinate(nodes.get(i));
        return values;
    }

    private static byte[] encodeValue(JsonNode value) {
        Proto result=new Proto();
        if(value.isBoolean()) result.fieldInt(7,value.asBoolean()?1:0);
        else if (value.isIntegralNumber() && value.canConvertToLong()) result.fieldInt(4, value.asLong());
        else if(value.isNumber()) {
            result.varint((3<<3)|1);
            result.out.writeBytes(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putDouble(value.asDouble()).array());
        } else result.fieldString(1,value.asText());
        return result.bytes();
    }

    /** MVT使用的有限protobuf写入操作，不依赖反射或动态协议。 */
    private static final class Proto {
        private final ByteArrayOutputStream out=new ByteArrayOutputStream();
        private void varint(long value) { while((value&~127L)!=0) { out.write((int)(value&127)|128);value>>>=7; }out.write((int)value); }
        private void fieldInt(int field,long value) {varint((long)field<<3);varint(value);}
        private void fieldString(int field,String value) {fieldBytes(field,value.getBytes(StandardCharsets.UTF_8));}
        private void fieldBytes(int field,byte[] value) {varint(((long)field<<3)|2);varint(value.length);out.writeBytes(value);}
        private byte[] bytes() {return out.toByteArray();}
    }
}
