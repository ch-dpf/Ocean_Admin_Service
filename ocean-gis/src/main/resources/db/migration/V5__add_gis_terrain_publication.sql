CREATE TABLE ocean_gis.gis_terrain_publication (
    id                  BIGINT       PRIMARY KEY,
    service_code        VARCHAR(64)  NOT NULL,
    source_task_id      BIGINT       NOT NULL,
    publish_task_id     BIGINT       NOT NULL,
    data_set_id         BIGINT,
    output_key          VARCHAR(1000) NOT NULL,
    status              VARCHAR(20)  NOT NULL DEFAULT 'PUBLISHED',
    publish_time        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted             INTEGER      NOT NULL DEFAULT 0,
    CONSTRAINT uk_gis_terrain_publication_code UNIQUE (service_code),
    CONSTRAINT uk_gis_terrain_publication_source UNIQUE (source_task_id),
    CONSTRAINT ck_gis_terrain_publication_status
        CHECK (status IN ('PUBLISHED', 'DISABLED')),
    CONSTRAINT ck_gis_terrain_publication_deleted
        CHECK (deleted IN (0, 1))
);

COMMENT ON TABLE ocean_gis.gis_terrain_publication IS 'Cesium quantized-mesh 地形发布记录';
COMMENT ON COLUMN ocean_gis.gis_terrain_publication.service_code IS '公开服务 URL 中使用的稳定编码';
COMMENT ON COLUMN ocean_gis.gis_terrain_publication.source_task_id IS '已完成的地形切片任务 ID';
COMMENT ON COLUMN ocean_gis.gis_terrain_publication.publish_task_id IS '对应的发布任务 ID';
COMMENT ON COLUMN ocean_gis.gis_terrain_publication.output_key IS '处理存储根目录下的切片产物 Key';

CREATE INDEX idx_gis_terrain_publication_status
    ON ocean_gis.gis_terrain_publication (status, publish_time DESC)
    WHERE deleted = 0;
