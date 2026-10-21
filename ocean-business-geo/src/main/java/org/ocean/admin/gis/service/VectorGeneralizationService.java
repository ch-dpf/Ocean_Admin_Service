package org.ocean.admin.gis.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.extern.slf4j.Slf4j;
import org.ocean.admin.gis.entity.GisVectorGeneralizationBuild;
import org.ocean.admin.gis.entity.GisVectorLayer;
import org.ocean.admin.gis.mapper.GisVectorGeneralizationMapper;
import org.ocean.admin.gis.mapper.GisVectorMapper;
import org.ocean.admin.gis.util.VectorGeneralizationPolicy;
import org.ocean.admin.gis.vo.GisVectorVO;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.util.List;

/** 原始要素与显示概化分离；构建在一致性快照内完成，控制事务负责租约和原子发布。 */
@Service
@Slf4j
public class VectorGeneralizationService {
    private static final double WORLD = 20037508.342789244;
    private static final int MAX_VALIDATION_TILES = 50000;
    private final GisVectorGeneralizationMapper mapper;
    private final GisVectorMapper vectors;
    private final ObjectMapper json;
    private final TransactionTemplate control;
    private final TransactionTemplate builds;
    private final org.ocean.admin.gis.config.VectorTileProperties properties;

    public VectorGeneralizationService(GisVectorGeneralizationMapper mapper, GisVectorMapper vectors,
            ObjectMapper json, PlatformTransactionManager transactions,
            org.ocean.admin.gis.config.VectorTileProperties properties) {
        this.mapper=mapper; this.vectors=vectors; this.json=json; this.properties=properties;
        control=new TransactionTemplate(transactions);
        control.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        builds=new TransactionTemplate(transactions);
        builds.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public static String etag(Long layerId, long version) { return "\"generalization-"+layerId+"-v"+version+"\""; }

    @Transactional(readOnly=true)
    public GisVectorVO.Generalization detail(Long layerId) {
        var layer=ready(layerId,false);
        var c=mapper.config(layerId);
        var policy=c==null ? json.valueToTree(VectorGeneralizationPolicy.defaults(false)) : json.readTree(c.policyJson());
        return new GisVectorVO.Generalization(layerId.toString(),policy,Long.toString(c==null?0:c.policyVersion()),
                layer.getDataVersion().toString(),toVO(mapper.latest(layerId)),
                c==null || c.publishedBuildId()==null ? null : toVO(mapper.build(c.publishedBuildId())));
    }

    @Transactional(rollbackFor=Exception.class)
    public GisVectorVO.Generalization save(Long layerId, String ifMatch, JsonNode definition) {
        ready(layerId,true);
        var p=VectorGeneralizationPolicy.parse(definition,json);
        var c=mapper.config(layerId);
        match(ifMatch,etag(layerId,c==null?0:c.policyVersion()));
        if (c==null) mapper.insertConfig(layerId,json.writeValueAsString(p));
        else if (mapper.updateConfig(layerId,json.writeValueAsString(p),c.policyVersion())!=1) throw conflict("概化策略已变化，请重新加载");
        if (p.enabled()) mapper.enqueue(IdWorker.getId(),layerId);
        return detail(layerId);
    }

    @Transactional(rollbackFor=Exception.class)
    public GisVectorVO.Generalization rebuild(Long layerId, String ifMatch) {
        ready(layerId,true);
        var c=mapper.config(layerId);
        match(ifMatch,etag(layerId,c==null?0:c.policyVersion()));
        if (c==null || !VectorGeneralizationPolicy.parse(json.readTree(c.policyJson()),json).enabled()) throw conflict("请先启用并保存概化策略");
        mapper.enqueue(IdWorker.getId(),layerId);
        return detail(layerId);
    }

    /** 调用者的导入/编辑事务内登记，事务回滚时待办同时回滚。 */
    @Transactional(rollbackFor=Exception.class)
    public void markDirty(Long layerId) {
        var layer=ready(layerId,true);
        mapper.insertConfig(layerId,json.writeValueAsString(VectorGeneralizationPolicy.defaults(layer.getFeatureCount()>20000)));
        mapper.enqueue(IdWorker.getId(),layerId);
    }

    public record Plan(String buildId, String policyVersion, int maxZoom, boolean available, String status, String message) { }

    public Plan plan(Long layerId, long dataVersion, List<String> fields, int buffer) {
        var c=mapper.config(layerId);
        if (c==null) return new Plan("0","0",-1,false,"DISABLED",null);
        var p=VectorGeneralizationPolicy.parse(json.readTree(c.policyJson()),json);
        if (!p.enabled()) return new Plan("0",Long.toString(c.policyVersion()),-1,false,"DISABLED",null);
        var published=c.publishedBuildId()==null?null:mapper.build(c.publishedBuildId());
        String id=published==null?"0":published.getId().toString();
        var missing=p.unsupported(fields);
        if (!missing.isEmpty() || buffer>p.maxBufferPixels()) return new Plan(id,Long.toString(c.policyVersion()),p.detailMinZoom()-1,
                false,"UNSUPPORTED",!missing.isEmpty()?"概览未准备样式字段："+String.join("、",missing)+"；请加入概化分组后重建，或放大到Z"+p.detailMinZoom():
                "当前符号超出概览缓冲预算；请调整符号或放大到Z"+p.detailMinZoom());
        if (published!=null && "READY".equals(published.getBuildStatus()) && published.getDataVersion()==dataVersion
                && published.getPolicyVersion()==c.policyVersion()) return new Plan(id,Long.toString(c.policyVersion()),p.detailMinZoom()-1,true,"READY",null);
        var latest=mapper.latest(layerId);
        String state=latest==null?"QUEUED":latest.getBuildStatus();
        if ("READY".equals(state) || "SUPERSEDED".equals(state)) state="QUEUED";
        return new Plan("0",Long.toString(c.policyVersion()),p.detailMinZoom()-1,false,state,
                "FAILED".equals(state)?"概览构建失败："+latest.getErrorMessage():"概览数据正在更新；可放大到Z"+p.detailMinZoom()+"查看明细");
    }

    public void process(Long id) {
        var task=control.execute(s -> mapper.claim(id));
        if (task==null) return;
        try {
            Long maximum=builds.execute(s -> build(task));
            control.executeWithoutResult(s -> publish(task,maximum==null?0:maximum));
        } catch (Exception ex) {
            String message=ex.getMessage()==null?ex.getClass().getSimpleName():ex.getMessage();
            if (message.length()>1000) message=message.substring(0,1000);
            String reason=message;
            control.executeWithoutResult(s -> {
                mapper.finish(id,task.getAttempt(),ex instanceof Superseded?"SUPERSEDED":"FAILED",0,reason);
                mapper.removeUnpublished(id);
            });
            log.warn("概化构建未发布: build={}, reason={}",id,reason);
        }
    }

    private long build(GisVectorGeneralizationBuild task) {
        if (!mapper.advisoryLock(task.getId())) throw new Superseded();
        var layer=ready(task.getLayerId(),false);
        var c=mapper.config(task.getLayerId());
        if (!task.getDataVersion().equals(layer.getDataVersion()) || c==null || task.getPolicyVersion()!=c.policyVersion()) throw new Superseded();
        var p=VectorGeneralizationPolicy.parse(json.readTree(task.getPolicyJson()),json);
        vectors.setTileQueryTimeout(properties.getGeneralizationQueryTimeoutMs());
        if (!mapper.boundedProperties(task.getLayerId(),json.writeValueAsString(p.groupFields()))) throw conflict("概览分组属性单条超过4096字节，请减少分组字段");
        if ("COVERAGE".equals(p.polygonMode()) && !mapper.validCoverage(task.getLayerId())) throw conflict("面数据不是有效覆盖：存在重叠或共享边不匹配，请修复数据或选择独立面概化");
        mapper.removeUnpublished(task.getId());
        var extent=mapper.extent(task.getLayerId());
        long maximum=0;
        long checked=0;
        for (int z=0;z<p.detailMinZoom();z++) {
            long size=Long.MAX_VALUE;
            for (int factor=1;factor<=8;factor*=2) {
                mapper.deleteZoom(task.getId(),z);
                mapper.buildZoom(task.getId(),task.getLayerId(),z,task.getPolicyJson(),factor,p.polygonMode());
                heartbeat(task,z-1);
                int[] range=range(extent,z,p.maxBufferPixels());
                long count=(long)(range[2]-range[0]+1)*(range[3]-range[1]+1);
                if (checked+count>MAX_VALIDATION_TILES) throw conflict("概览校验超过50000张瓦片，请降低detailMinZoom或划分数据范围");
                size=validate(task,p,z,range);
                if (size<=properties.getMaxTileBytes()) { checked+=count; break; }
            }
            if (size>properties.getMaxTileBytes()) throw conflict("Z"+z+"概化后瓦片仍超过预算，请调整道路等级、分组字段或建筑聚合策略");
            maximum=Math.max(maximum,size);
            heartbeat(task,z);
        }
        return maximum;
    }

    private long validate(GisVectorGeneralizationBuild task, VectorGeneralizationPolicy p, int z, int[] range) {
        long largest=0;
        int count=0;
        for (int x=range[0];x<=range[2];x++) for (int y=range[1];y<=range[3];y++) {
            var tile=mapper.tile(task.getId(),task.getLayerId(),z,x,y,json.writeValueAsString(p.groupFields()),
                    properties.getGridPixels(),p.maxBufferPixels(),true,properties.getMaxTileBytes());
            largest=Math.max(largest,tile.byteSize());
            if (largest>properties.getMaxTileBytes()) return largest;
            if (++count%32==0) heartbeat(task,z-1);
        }
        return largest;
    }

    private void heartbeat(GisVectorGeneralizationBuild task,int zoom) {
        Boolean renewed=control.execute(s -> mapper.progress(task.getId(),task.getAttempt(),zoom)==1);
        if (!Boolean.TRUE.equals(renewed)) throw new Superseded();
    }

    private void publish(GisVectorGeneralizationBuild task,long maximum) {
        var layer=vectors.lockLayer(task.getLayerId());
        var c=mapper.config(task.getLayerId());
        boolean current=layer!=null && "READY".equals(layer.getLayerStatus()) && c!=null
                && task.getDataVersion().equals(layer.getDataVersion()) && task.getPolicyVersion()==c.policyVersion();
        if (mapper.finish(task.getId(),task.getAttempt(),current?"READY":"SUPERSEDED",maximum,null)!=1) throw new Superseded();
        if (current && mapper.publish(task.getId(),task.getLayerId())!=1) throw new Superseded();
        mapper.removeOldGeometry(task.getLayerId());
    }

    private int[] range(GisVectorVO.GeneralizationExtent e,int z,int buffer) {
        if (e==null) return new int[]{0,0,-1,-1};
        int n=1<<z;
        double width=2*WORLD/n,margin=buffer*width/256;
        return new int[]{clamp((int)Math.floor((e.west()-margin+WORLD)/width),n),
                clamp((int)Math.floor((WORLD-e.north()-margin)/width),n),
                clamp((int)Math.floor((e.east()+margin+WORLD)/width),n),
                clamp((int)Math.floor((WORLD-e.south()+margin)/width),n)};
    }
    private int clamp(int value,int n) { return Math.max(0,Math.min(n-1,value)); }

    private GisVectorLayer ready(Long id,boolean lock) {
        if (id==null || id<1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"图层ID无效");
        var layer=lock?vectors.lockLayer(id):vectors.layer(id);
        if (layer==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"图层不存在或已删除");
        if (!"READY".equals(layer.getLayerStatus())) throw conflict("图层尚未完成导入");
        return layer;
    }
    private GisVectorVO.GeneralizationBuild toVO(GisVectorGeneralizationBuild b) {
        return b==null?null:new GisVectorVO.GeneralizationBuild(b.getId().toString(),b.getDataVersion().toString(),
                b.getPolicyVersion().toString(),b.getBuildStatus(),b.getCompletedZoom(),b.getMaxTileBytes(),b.getErrorMessage(),b.getCreateTime(),b.getFinishTime());
    }
    private void match(String supplied,String expected) {
        if (supplied==null || supplied.isBlank()) throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,"须携带概化策略ETag作为If-Match");
        if (!expected.equals(supplied.trim())) throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,"概化策略已变化，请重新加载后保存");
    }
    private ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT,message); }
    private static final class Superseded extends RuntimeException { Superseded() { super("源数据、策略或执行租约已变化，构建已失效"); } }
}
