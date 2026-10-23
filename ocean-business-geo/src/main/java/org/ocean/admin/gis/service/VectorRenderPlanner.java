package org.ocean.admin.gis.service;

import lombok.RequiredArgsConstructor;
import org.ocean.admin.gis.entity.GisVectorDataSetStyle;
import org.ocean.admin.gis.vo.GisVectorVO;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 只规划稳定的数据瓦片协议；不读取配图、显隐、视窗或连续缩放。 */
@Service
@RequiredArgsConstructor
public class VectorRenderPlanner {
    private final ObjectMapper json;

    public JsonNode policy(GisVectorDataSetStyle snapshot) {
        if (snapshot.getSchemaVersion() != 3)
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT, "该数据集未使用新版分类协议，请保留原数据并重新导入新数据集");
        return json.readTree(snapshot.getDefinitionJson()).path("tilePolicy");
    }

    public GisVectorVO.MapDefinition definition(GisVectorDataSetStyle snapshot) {
        var root = json.readTree(snapshot.getDefinitionJson());
        var policy = policy(snapshot);
        var contract = json.createObjectNode();
        contract.set("classification", root.path("classification"));
        contract.set("tilePolicy", policy);
        contract.put("encodingImplementation", 2);
        String version = digest(snapshot.getDataSetId() + ":" + snapshot.getDataRevision() + ":" + json.writeValueAsString(contract));
        return new GisVectorVO.MapDefinition(snapshot.getDataSetId().toString(), version, snapshot.getDataRevision(),
                policy.path("minZoom").asInt(), policy.path("nativeMaxZoom").asInt(), policy.path("displayMaxZoom").asInt(),
                policy.path("tileSize").asInt(), "/api/gis/vector/datasets/" + snapshot.getDataSetId()
                    + "/tiles/" + version + "/{z}/{x}/{y}.mvt", null);
    }

    public static String digest(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    private static String digest(String value) { return digest(value.getBytes(StandardCharsets.UTF_8)); }
}
