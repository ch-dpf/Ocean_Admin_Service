package org.ocean.admin.gis.service;

import org.ocean.admin.gis.entity.GisProcessingTask;
import org.ocean.admin.gis.entity.GisPublication;
import org.ocean.admin.gis.entity.GisTileSet;
import org.ocean.admin.gis.processing.GisProcessingType;

import java.nio.file.Path;

/** 一种 GIS 瓦片发布协议的产物校验、公开资源解析与访问地址契约。 */
public interface PublicationProtocol {

    GisProcessingType type();

    String displayName();

    PublishedArtifact validateForPublish(GisProcessingTask task, GisTileSet tileSet);

    String generateServiceCode();

    String normalizeServiceCode(String serviceCode);

    void activate(String serviceCode, PublishedArtifact artifact);

    void deactivate(String serviceCode);

    Path resolvePublishedFile(String serviceCode, String relativePath);

    PublicationAccess buildAccess(GisPublication publication, GisTileSet tileSet);

    record PublishedArtifact(
            Path root,
            String extension,
            Integer minZoom,
            Integer maxZoom,
            String targetCrs) {
    }

    record PublicationAccess(
            String serviceUrl,
            String descriptorUrl,
            String tileUrlTemplate) {
    }
}
