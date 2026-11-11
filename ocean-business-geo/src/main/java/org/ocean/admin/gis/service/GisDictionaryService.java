package org.ocean.admin.gis.service;

import org.ocean.admin.gis.dto.ImageryProcessingParameters;
import org.ocean.admin.gis.dto.TaskStatus;
import org.ocean.admin.gis.dto.TerrainProcessingParameters;
import org.ocean.admin.gis.entity.GisDataSetCategory;
import org.ocean.admin.gis.imagery.ImageryTileOptions;
import org.ocean.admin.gis.processing.GisInputSourceType;
import org.ocean.admin.gis.processing.GisProcessingType;
import org.ocean.admin.gis.terrain.engine.TerrainOptions;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 启动时加载 GIS 选择项；接口展示和候选值校验共享同一份不可变字典。 */
@Service
public class GisDictionaryService {
    private final Map<String, List<Item>> dictionaries;

    public GisDictionaryService(ObjectMapper json) {
        try (var input = new ClassPathResource("dictionaries/data-management.json").getInputStream()) {
            // 单独启用重复字段检查，不修改应用全局的序列化配置。
            var root = json.reader().with(StreamReadFeature.STRICT_DUPLICATE_DETECTION).readTree(input);
            dictionaries = parse(root);
            validateContracts(json);
        } catch (IOException | RuntimeException ex) {
            throw new IllegalStateException("GIS 数据管理字典加载失败，请检查 dictionaries/data-management.json", ex);
        }
    }

    public Map<String, List<Item>> all() {
        return dictionaries;
    }

    public List<Item> items(String key) {
        List<Item> items = dictionaries.get(key);
        if (items == null) throw new IllegalArgumentException("未知的 GIS 字典：" + key);
        return items;
    }

    public String label(String key, String code) {
        return requireItem(key, code).label();
    }

    public Item requireItem(String key, String code) {
        for (Item item : items(key)) {
            if (item.code().equals(code)) return item;
        }
        throw new IllegalArgumentException("无效的 " + key + " 编码：" + code);
    }

    /** 空筛选条件表示全部，实际提交的编码必须来自字典。 */
    public void validateOptional(String key, String code) {
        if (code != null && !code.isBlank()) requireItem(key, code);
    }

    public String defaultCode(String key) {
        List<Item> defaults = items(key).stream()
                .filter(item -> "true".equals(item.attributes().get("default"))).toList();
        if (defaults.size() != 1) throw new IllegalStateException(key + " 必须且只能配置一个默认项");
        return defaults.getFirst().code();
    }

    private Map<String, List<Item>> parse(JsonNode root) {
        if (root == null || !root.isObject() || root.isEmpty())
            throw new IllegalStateException("字典根节点必须是非空对象");
        var result = new LinkedHashMap<String, List<Item>>();
        for (var group : root.properties()) {
            if (group.getKey().isBlank() || !group.getValue().isArray() || group.getValue().isEmpty())
                throw new IllegalStateException("字典分组必须是非空数组：" + group.getKey());
            var items = new ArrayList<Item>();
            var codes = new java.util.HashSet<String>();
            for (var node : group.getValue()) {
                if (!node.isObject() || !node.path("code").isString() || node.path("code").asText().isBlank()
                        || !node.path("label").isString() || node.path("label").asText().isBlank()
                        || !node.path("sort").isIntegralNumber() || !node.path("sort").canConvertToInt())
                    throw new IllegalStateException("字典项须包含有效的 code、label、sort：" + group.getKey());
                String code = node.path("code").asText();
                if (!code.equals(code.trim()) || !codes.add(code))
                    throw new IllegalStateException("字典编码重复或带有首尾空白：" + group.getKey() + "/" + code);
                for (var property : node.properties()) {
                    if (!Set.of("code", "label", "sort", "attributes").contains(property.getKey()))
                        throw new IllegalStateException("未知的字典字段：" + property.getKey());
                }
                var attributes = new LinkedHashMap<String, String>();
                if (node.has("attributes")) {
                    if (!node.path("attributes").isObject()) throw new IllegalStateException("attributes 必须为对象");
                    for (var property : node.path("attributes").properties()) {
                        if (!property.getValue().isString()) throw new IllegalStateException("字典扩展属性必须是字符串");
                        attributes.put(property.getKey(), property.getValue().asText());
                    }
                }
                items.add(new Item(code, node.path("label").asText(), node.path("sort").asInt(), Map.copyOf(attributes)));
            }
            items.sort(Comparator.comparingInt(Item::sort).thenComparing(Item::code));
            result.put(group.getKey(), List.copyOf(items));
        }
        return Collections.unmodifiableMap(result);
    }

    /** 枚举保留可执行编码，JSON 不能声明程序尚未支持的能力。 */
    private void validateContracts(ObjectMapper json) {
        requireCodes("datasetCategory", Arrays.stream(GisDataSetCategory.values())
                .map(category -> category.categoryId().toString()).collect(Collectors.toSet()));
        requireCodes("taskStatus", Arrays.stream(TaskStatus.values()).map(Enum::name).collect(Collectors.toSet()));
        requireCodes("processingType", Arrays.stream(GisProcessingType.values()).map(Enum::name).collect(Collectors.toSet()));
        requireCodes("inputSourceType", Arrays.stream(GisInputSourceType.values()).map(Enum::name).collect(Collectors.toSet()));
        for (GisProcessingType type : GisProcessingType.values()) {
            Item item = requireItem("processingType", type.name());
            if (!type.categoryId().toString().equals(item.attributes().get("categoryId"))
                    || item.attributes().getOrDefault("formLabel", "").isBlank()
                    || item.attributes().getOrDefault("dataLabel", "").isBlank())
                throw new IllegalStateException("处理类型的分类和表单信息无效：" + type);
        }
        for (String key : List.of("metadataStatus", "groupAssemblyStatus", "groupParseStatus", "processingInputKind",
                "processingInputStatus", "tileSetStatus", "processingStage", "importStage", "vectorPlacement",
                "datasetPublicationStatus", "fileStatus", "rawRetention")) items(key);
        // 每一个选项都交由现有引擎参数规则验证，防止 JSON 放开不支持的值。
        for (String kind : List.of("imagery", "terrain")) {
            for (String field : List.of("targetCrs", "tileProfile", "outputFormat")) {
                String key = kind + Character.toUpperCase(field.charAt(0)) + field.substring(1);
                defaultCode(key);
                for (Item item : items(key)) {
                    var parameters = json.createObjectNode().put(field, item.code());
                    if (kind.equals("imagery")) ImageryTileOptions.from(json.treeToValue(parameters, ImageryProcessingParameters.class));
                    else TerrainOptions.from(json.treeToValue(parameters, TerrainProcessingParameters.class));
                }
            }
        }
    }

    private void requireCodes(String key, Set<String> expected) {
        Set<String> actual = items(key).stream().map(Item::code).collect(Collectors.toSet());
        if (!actual.equals(expected)) throw new IllegalStateException(key + " 与当前业务编码不一致");
    }

    /** 扩展属性仅承载具体选项所需的关联信息，运行能力由业务服务判断。 */
    public record Item(String code, String label, int sort, Map<String, String> attributes) { }
}
