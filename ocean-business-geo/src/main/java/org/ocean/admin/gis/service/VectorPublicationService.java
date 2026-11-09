package org.ocean.admin.gis.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.RequiredArgsConstructor;
import org.ocean.admin.gis.config.VectorTileProperties;
import org.ocean.admin.gis.dto.GisVectorRequests;
import org.ocean.admin.gis.entity.GisPublication;
import org.ocean.admin.gis.entity.GisVectorResource;
import org.ocean.admin.gis.mapper.GisPublicationMapper;
import org.ocean.admin.gis.mapper.GisVectorMapper;
import org.ocean.admin.gis.processing.GisProcessingType;
import org.ocean.admin.gis.vo.GisPublicationVO;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Semaphore;

/** 发布固定数据版本与独立样式快照；动态瓦片按需生成，不依赖工作区当前版本。 */
@Service
@RequiredArgsConstructor
public class VectorPublicationService {
    private final GisPublicationMapper publications;
    private final GisVectorMapper mapper;
    private final VectorResourceService resources;
    private final VectorDataSetStyleService dataSetStyles;
    private final VectorStyleService styles;
    private final VectorRenderPlanner planner;
    private final VectorTileProperties limits;
    private final ObjectMapper json;
    private final org.ocean.admin.gis.mapper.GisDataSetMapper dataSets;
    private final PublicationRecordService publicationRecords;
    private final Semaphore tileSlots=new Semaphore(4,true);
    private final java.util.LinkedHashMap<String, CachedTile> tileCache = new java.util.LinkedHashMap<>(64, .75f, true);
    private long cacheBytes;

    private record CachedTile(byte[] body, long expiresAt) { }

    @Transactional(rollbackFor=Exception.class)
    public GisPublicationVO publish(GisVectorRequests.Publish request) throws IOException {
        return publish(request, "MVT");
    }

    /** 两种动态服务共享数据快照与样式发布，协议必须在创建时明确指定。 */
    @Transactional(rollbackFor=Exception.class)
    public GisPublicationVO publish(GisVectorRequests.Publish request, String protocol) throws IOException {
        if (!java.util.Set.of("MVT", "WMS").contains(protocol)) throw new IllegalArgumentException("动态发布协议无效");
        var state=dataSetStyles.require(request.dataSetId(),true);
        ObjectNode binding=bind(request);
        String tileVersion=planner.definition(state).tileVersion();
        GisVectorResource source=mapper.resources(request.dataSetId()).stream()
                .filter(resource->"PUBLICATION".equals(resource.getResourceKind()))
                .filter(resource->java.util.Objects.equals(resource.getDataVersion(),state.getDataRevision()))
                .filter(resource->tileVersion.equals(json.readTree(resource.getDefinitionJson()).path("map").path("tileVersion").asText()))
                .findFirst().orElse(null);
        if(source==null) source=resources.freeze(request.dataSetId(),"PUBLICATION");
        validateBinding(source,binding);
        GisPublication publication=new GisPublication(); publication.setId(IdWorker.getId());
        publication.setServiceProtocol(protocol);
        publication.setServiceCode("VECTOR-"+UUID.randomUUID().toString().replace("-","").toUpperCase(java.util.Locale.ROOT));
        publication.setServiceName(request.serviceName().trim()); publication.setDataSetId(request.dataSetId());
        publication.setVectorResourceId(source.getId()); publication.setVectorDefinition(json.writeValueAsString(binding));
        publication.setStatus("PUBLISHED"); publication.setPublishTime(LocalDateTime.now()); publication.setUpdateTime(publication.getPublishTime());
        publication.setDeleted(0); publications.insert(publication);
        publications.insertVectorRevision(publication.getId(),binding.path("releaseVersion").asText(),publication.getVectorDefinition());
        return toVO(publication);
    }

    private ObjectNode bind(GisVectorRequests.Publish request) {
        if (request.serviceName() == null || request.serviceName().isBlank() || request.serviceName().strip().length() > 200)
            throw new IllegalArgumentException("服务名称无效");
        if(request.styleSchemeIds()==null || request.styleSchemeIds().isEmpty() || request.styleSchemeIds().size()>100
                || new HashSet<>(request.styleSchemeIds()).size()!=request.styleSchemeIds().size()
                || !request.styleSchemeIds().contains(request.defaultStyleSchemeId()))
            throw new IllegalArgumentException("须选择1至100套不同样式，默认样式必须属于所选方案");
        ObjectNode binding=json.createObjectNode().put("schemaVersion",1).put("releaseVersion",UUID.randomUUID().toString())
                .put("defaultStyleId",request.defaultStyleSchemeId().toString());
        var schemes=binding.putArray("styles");
        for(Long id:request.styleSchemeIds()) {
            var scheme=styles.scheme(request.dataSetId(),id);
            styles.validateScheme(dataSetStyles.require(request.dataSetId(),false),scheme.definition());
            schemes.add(json.valueToTree(scheme));
        }
        return binding;
    }

    /** 样式只能引用该数据版本中存在的分类与图层，不能借样式扩大瓦片数据集合。 */
    private void validateBinding(GisVectorResource source,JsonNode binding) {
        JsonNode contract=json.readTree(source.getDefinitionJson());
        var layerIds = new java.util.HashMap<String, JsonNode>();
        for(JsonNode layer:contract.path("layers")) layerIds.put(layer.path("id").asText(), layer);
        var groups = new java.util.HashMap<String, String>();
        for (JsonNode group : contract.path("groups")) groups.put(group.path("groupKey").asText(), group.path("id").asText());
        for(JsonNode scheme:binding.path("styles")) {
            JsonNode layers=scheme.path("definition").path("cartography").path("layers");
            for (String id : layers.propertyNames()) {
                JsonNode layer = layerIds.get(id);
                if (layer == null) throw new IllegalArgumentException("样式引用了瓦片版本之外的图层: " + id);
                if (layers.path(id).path("automatic").asBoolean() != "AUTO".equals(layer.path("assignmentMode").asText()))
                    throw new IllegalArgumentException("样式与固定瓦片版本的分类方式不一致: " + id);
                for (JsonNode rule : layers.path(id).path("definition").path("rules"))
                    if (!layer.path("geometryType").asText().equals(rule.path("geometryType").asText()))
                        throw new IllegalArgumentException("样式几何类别与瓦片图层不一致");
            }
            JsonNode configuredGroups = scheme.path("definition").path("cartography").path("groups");
            for (String key : configuredGroups.propertyNames())
                if (!configuredGroups.path(key).path("id").asText().equals(groups.get(key)))
                    throw new IllegalArgumentException("样式分类不属于固定瓦片版本: " + key);
        }
    }

    @Transactional(rollbackFor=Exception.class)
    public GisPublicationVO updateStyles(String code,GisVectorRequests.Publish request,String ifMatch) {
        dataSetStyles.require(request.dataSetId(),true);
        GisPublication publication=get(code,false);
        if(!request.dataSetId().equals(publication.getDataSetId())) throw new IllegalArgumentException("不能改变发布数据集");
        JsonNode current=json.readTree(publication.getVectorDefinition());
        VectorDataSetStyleService.requireMatch(ifMatch,"\"release-"+current.path("releaseVersion").asText()+"\"");
        ObjectNode binding=bind(request); validateBinding(resources.require(publication.getVectorResourceId()),binding);
        // 名称由独立的重命名接口维护，更新样式不能覆盖其他用户刚修改的服务名称。
        publication.setVectorDefinition(json.writeValueAsString(binding));
        publication.setUpdateTime(LocalDateTime.now());
        publications.update(null, new LambdaUpdateWrapper<GisPublication>().eq(GisPublication::getId, publication.getId())
                .set(GisPublication::getVectorDefinition, publication.getVectorDefinition())
                .set(GisPublication::getUpdateTime, publication.getUpdateTime()));
        publications.insertVectorRevision(publication.getId(),binding.path("releaseVersion").asText(),publication.getVectorDefinition());
        return toVO(publication);
    }

    public GisPublication get(String code,boolean requirePublished) {
        var publication=publications.selectOne(new LambdaQueryWrapper<GisPublication>()
                .eq(GisPublication::getServiceCode,code).isNotNull(GisPublication::getVectorResourceId));
        if(publication==null || requirePublished && !"PUBLISHED".equals(publication.getStatus()))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"矢量发布服务不存在或已停用");
        return publication;
    }

    public GisPublicationVO disable(String code) {
        var publication=get(code,false); publication.setStatus("DISABLED");publication.setUpdateTime(LocalDateTime.now());
        publications.update(null, new LambdaUpdateWrapper<GisPublication>().eq(GisPublication::getId, publication.getId())
                .set(GisPublication::getStatus, "DISABLED").set(GisPublication::getUpdateTime, publication.getUpdateTime()));
        return toVO(get(code, false));
    }

    public GisPublicationVO rename(String code,String name) {
        if(name==null || name.isBlank() || name.strip().length()>200) throw new IllegalArgumentException("服务名称无效");
        var publication=get(code,false);publication.setServiceName(name.strip());publication.setUpdateTime(LocalDateTime.now());
        publications.update(null, new LambdaUpdateWrapper<GisPublication>().eq(GisPublication::getId, publication.getId())
                .set(GisPublication::getServiceName, name.strip()).set(GisPublication::getUpdateTime, publication.getUpdateTime()));
        return toVO(get(code, false));
    }

    public ObjectNode descriptor(String code) {
        var publication=get(code,true);var resource=resources.require(publication.getVectorResourceId());
        requireMvt(publication);
        JsonNode contract=json.readTree(resource.getDefinitionJson());JsonNode binding=json.readTree(publication.getVectorDefinition());
        ObjectNode result=json.createObjectNode().put("tilejson","3.0.0").put("name",publication.getServiceName())
                .put("releaseVersion",binding.path("releaseVersion").asText()).put("defaultStyleId",binding.path("defaultStyleId").asText())
                .put("tileVersion",resource.getId().toString()).put("minzoom",Math.max(0,contract.path("map").path("minZoom").asInt() - 1))
                .put("maxzoom",contract.path("map").path("nativeMaxZoom").asInt());
        result.putArray("tiles").add(base(code)+"/tiles/"+resource.getId()+"/{z}/{x}/{y}.mvt");
        ObjectNode dataContract = (ObjectNode) contract.path("map").deepCopy();
        dataContract.put("tiles", base(code)+"/tiles/"+resource.getId()+"/{z}/{x}/{y}.mvt");
        dataContract.put("tileVersion", resource.getId().toString());
        result.set("dataContract", dataContract);
        var options=result.putArray("styles");
        for(JsonNode scheme:binding.path("styles")) options.addObject().put("id",scheme.path("id").asText())
                .put("name",scheme.path("name").asText()).put("version",scheme.path("version").asLong())
                .put("url",base(code)+"/styles/"+binding.path("releaseVersion").asText()+"/"+scheme.path("id").asText()+".json");
        return result;
    }

    public JsonNode style(String code,String releaseVersion,String schemeId) {
        var publication=get(code,true);
        requireMvt(publication);
        String saved=publications.vectorRevision(publication.getId(),releaseVersion);
        if(saved==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"样式发布版本不存在");
        JsonNode binding=json.readTree(saved);
        for(JsonNode scheme:binding.path("styles")) if(schemeId.equals(scheme.path("id").asText())) {
            ObjectNode result=(ObjectNode)scheme.deepCopy();
            result.put("tiles",base(code)+"/tiles/"+publication.getVectorResourceId()+"/{z}/{x}/{y}.mvt");
            return result;
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND,"当前服务未发布此样式");
    }

    public byte[] tile(String code,Long resourceId,int z,int x,int y) throws IOException {
        var publication=get(code,true);
        requireMvt(publication);
        if(!resourceId.equals(publication.getVectorResourceId())) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"瓦片版本不属于该服务");
        if (z < 0 || z > 18 || x < 0 || y < 0 || x >= (1L << z) || y >= (1L << z))
            throw new IllegalArgumentException("瓦片坐标超出范围");
        // 缓存只使用数据版本，多个服务与多个样式共用同一份瓦片；先校验服务状态再读缓存。
        String key = resourceId + ":" + z + ":" + x + ":" + y;
        synchronized (tileCache) {
            CachedTile cached = tileCache.get(key);
            if (cached != null && cached.expiresAt() > System.nanoTime()) return cached.body();
            if (cached != null) { tileCache.remove(key); cacheBytes -= cached.body().length; }
        }
        if(!tileSlots.tryAcquire()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"矢量瓦片服务繁忙，请稍后重试");
        try {
            byte[] bytes=resources.tile(publication.getDataSetId(),resources.require(resourceId),z,x,y,limits.getMaxDecodedTileBytes());
            var compressed=new java.io.ByteArrayOutputStream();
            try(var gzip=new java.util.zip.GZIPOutputStream(compressed)) {gzip.write(bytes);}
            if(compressed.size()>limits.getMaxTileBytes()) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"矢量瓦片超过传输体积上限");
            synchronized (tileCache) {
                CachedTile previous = tileCache.put(key, new CachedTile(bytes, System.nanoTime()
                        + java.util.concurrent.TimeUnit.SECONDS.toNanos(limits.getTileCacheTtlSeconds())));
                cacheBytes += bytes.length - (previous == null ? 0 : previous.body().length);
                while (cacheBytes > limits.getMaxLocalCacheBytes() || tileCache.size() > limits.getMaxCachedTiles())
                    cacheBytes -= tileCache.pollFirstEntry().getValue().body().length;
            }
            return bytes;
        } finally {tileSlots.release();}
    }

    public GisPublicationVO toVO(GisPublication publication) {
        JsonNode binding = json.readTree(publication.getVectorDefinition());
        boolean wms = "WMS".equals(publication.getServiceProtocol());
        var dataSet = publication.getDataSetId() == null ? null : dataSets.selectById(publication.getDataSetId());
        String root = wms
                ? publicationRecords.buildServiceRoot(GisProcessingType.VECTOR, publication.getServiceCode()).replaceAll("/$", "")
                : base(publication.getServiceCode());
        return GisPublicationVO.builder().id(publication.getId()).serviceCode(publication.getServiceCode())
                .serviceProtocol(publication.getServiceProtocol()).dataSetName(dataSet == null ? null : dataSet.getDataSetName())
                .serviceName(publication.getServiceName()).processingType(GisProcessingType.VECTOR).dataSetId(publication.getDataSetId())
                .status(publication.getStatus()).publishTime(publication.getPublishTime()).updateTime(publication.getUpdateTime())
                .vectorResourceId(publication.getVectorResourceId().toString()).releaseVersion(binding.path("releaseVersion").asText())
                .defaultStyleId(binding.path("defaultStyleId").asText()).styles(binding.path("styles"))
                .targetCrs(wms ? "EPSG:3857 / EPSG:4326" : "EPSG:3857").outputFormat(wms ? "PNG" : "MVT")
                .serviceUrl(wms ? root + "/wms" : root)
                .descriptorUrl(wms ? root + "/wms?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetCapabilities" : root + "/manifest.json")
                .tileUrlTemplate(wms ? null : root + "/tiles/" + publication.getVectorResourceId() + "/{z}/{x}/{y}.mvt").build();
    }

    private static void requireMvt(GisPublication publication) {
        if (!"MVT".equals(publication.getServiceProtocol()))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "当前服务不提供 MVT 资源");
    }

    private String base(String code) {return "/gis/publications/VECTOR/"+code;}
}
