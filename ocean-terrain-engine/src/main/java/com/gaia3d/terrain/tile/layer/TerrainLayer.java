/*
 * SPDX-License-Identifier: MPL-2.0
 * 基于 Gaia3D/mago-3d-terrainer v1.14.2-release，已为本库同 JVM 执行改造。
 */
package com.gaia3d.terrain.tile.layer;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import com.gaia3d.terrain.tile.core.TileRange;
import com.gaia3d.terrain.tile.custom.AvailableTileSet;
import com.gaia3d.util.FileUtils;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.util.*;

@Getter
@Setter
@Slf4j
public class TerrainLayer {
    private String tilejson = null;
    private String name = null;
    private String description = null;
    private String version = null;
    private String format = null;
    private String attribution = null;
    private String template = null;
    private String legend = null;
    private String scheme = null;
    private List<String> extensions = null;
    private String[] tiles = null;
    private String projection = null;
    private double[] bounds = null;

    private final ObjectMapper objectMapper;

    public TerrainLayer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.setDefault();
    }

    public void setDefault() {
        this.tilejson = "2.1.0";
        this.name = "insert name here";
        this.description = "insert description here";
        this.version = "1.1.0";
        this.format = "quantized-mesh-1.0";
        this.attribution = "insert attribution here";
        this.template = "terrain";
        this.legend = "insert legend here";
        this.scheme = "tms";
        this.tiles = new String[1];
        this.tiles[0] = "{z}/{x}/{y}.terrain?v={version}";
        this.projection = "EPSG:4326";
        this.extensions = new ArrayList<>();
        this.bounds = new double[4];
        this.bounds[0] = 0.0;
        this.bounds[1] = 0.0;
        this.bounds[2] = 0.0;
        this.bounds[3] = 0.0;
    }

    public void addExtension(String extension) {
        if (this.extensions == null) {
            this.extensions = new ArrayList<>();
        }
        this.extensions.add(extension);
    }

    public void saveJsonFileCustom(String outputDirectory, String layerJsonName, AvailableTileSet availableTileSet) {
        String fullFileName = outputDirectory + File.separator + layerJsonName;
        FileUtils.createAllFoldersIfNoExist(outputDirectory);

        ObjectNode objectNodeRoot = objectMapper.createObjectNode();
        objectNodeRoot.put("tilejson", this.tilejson);
        objectNodeRoot.put("name", this.name);
        objectNodeRoot.put("description", this.description);
        objectNodeRoot.put("version", this.version);
        objectNodeRoot.put("format", this.format);
        objectNodeRoot.put("attribution", this.attribution);
        objectNodeRoot.put("template", this.template);
        objectNodeRoot.put("legend", this.legend);
        objectNodeRoot.put("scheme", this.scheme);
        objectNodeRoot.put("projection", this.projection);
        objectNodeRoot.putArray("tiles").add(this.tiles[0]);
        objectNodeRoot.putArray("bounds").add(this.bounds[0]).add(this.bounds[1]).add(this.bounds[2]).add(this.bounds[3]);

        if (this.extensions != null && this.extensions.size() > 0) {
            ArrayNode objectNodeExtensions = objectMapper.createArrayNode();
            for (String extension : this.extensions) {
                objectNodeExtensions.add(extension);
            }
            objectNodeRoot.set("extensions", objectNodeExtensions);
        }

        ArrayNode objectNodeAvailable = objectMapper.createArrayNode();
        Map<Integer, List<TileRange>> mapDepthAvailableTileRanges = availableTileSet.getMapDepthAvailableTileRanges();
        for (int tileDepth = 0; tileDepth <= mapDepthAvailableTileRanges.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1); tileDepth++) {
            if (!mapDepthAvailableTileRanges.containsKey(tileDepth)) { objectNodeAvailable.addArray(); continue; }
            List<TileRange> tileRanges = mapDepthAvailableTileRanges.get(tileDepth);
            ArrayNode objectNodeTileDepth_array = objectMapper.createArrayNode();
            for (TileRange tilesRange : tileRanges) {
                ObjectNode objectNodeTileDepth = objectMapper.createObjectNode();
                objectNodeTileDepth.put("startX", tilesRange.getMinTileX());
                objectNodeTileDepth.put("endX", tilesRange.getMaxTileX());
                objectNodeTileDepth.put("startY", tilesRange.getMinTileY());
                objectNodeTileDepth.put("endY", tilesRange.getMaxTileY());
                objectNodeTileDepth_array.add(objectNodeTileDepth);
            }
            objectNodeAvailable.add(objectNodeTileDepth_array);
        }

        objectNodeRoot.set("available", objectNodeAvailable);

        try {
            objectMapper.writeValue(new File(fullFileName), objectNodeRoot);
        } catch (RuntimeException e) {
            throw new IllegalStateException("地形元数据读写失败: " + e.getMessage(), e);
        }
    }

    /** 按实际文件构造可用范围，保留源数据 bounds，避免把空洞声明为存在的瓦片。 */
    public void saveFinalJson(java.nio.file.Path output, com.gaia3d.terrain.tile.generation.TerrainTilingContext context)
            throws IOException {
        context.phase("finalizing", "正在整理地形元数据");
        ObjectNode json = (ObjectNode) objectMapper.readTree(output.resolve("layer.json").toFile());
        ArrayNode levels = objectMapper.createArrayNode();
        boolean found = false;
        for (int depth = 0; depth <= 22; depth++) {
            context.checkCancelled();
            ArrayNode ranges = levels.addArray();
            java.nio.file.Path directory = output.resolve(Integer.toString(depth));
            if (!java.nio.file.Files.isDirectory(directory)) continue;
            try (var columns = java.nio.file.Files.list(directory)) {
                for (var column : columns.sorted().toList()) {
                    if (!java.nio.file.Files.isDirectory(column) || !column.getFileName().toString().matches("[0-9]+")) continue;
                    int x = Integer.parseInt(column.getFileName().toString());
                    if (x >= (1L << (depth + 1))) throw new IOException("地形瓦片列号越界: " + column);
                    List<Integer> rows = new ArrayList<>();
                    try (var tiles = java.nio.file.Files.list(column)) {
                        for (var tile : tiles.toList()) {
                            context.checkCancelled();
                            String name = tile.getFileName().toString();
                            if (!name.matches("[0-9]+[.]terrain") || !java.nio.file.Files.isRegularFile(tile)) continue;
                            if (java.nio.file.Files.size(tile) == 0) throw new IOException("地形瓦片为空: " + tile);
                            int y = Integer.parseInt(name.substring(0, name.length() - 8));
                            if (y >= (1L << depth)) throw new IOException("地形瓦片行号越界: " + tile);
                            rows.add(y);
                        }
                    }
                    Collections.sort(rows);
                    for (int index = 0; index < rows.size();) {
                        int start = rows.get(index++);
                        int end = start;
                        while (index < rows.size() && rows.get(index) == end + 1) end = rows.get(index++);
                        ranges.addObject().put("startX", x).put("endX", x).put("startY", start).put("endY", end);
                        found = true;
                    }
                }
            }
        }
        if (!found) throw new IOException("地形引擎未生成有效瓦片");
        while (levels.size() > 0 && levels.get(levels.size() - 1).isEmpty()) levels.remove(levels.size() - 1);
        json.set("available", levels);
        json.putArray("bounds").add(bounds[0]).add(bounds[1]).add(bounds[2]).add(bounds[3]);
        java.nio.file.Path temporary = output.resolve("layer.json.tmp");
        objectMapper.writeValue(temporary.toFile(), json);
        context.checkCancelled();
        java.nio.file.Files.move(temporary, output.resolve("layer.json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
}
