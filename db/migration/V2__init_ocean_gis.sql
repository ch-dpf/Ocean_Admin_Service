CREATE SCHEMA IF NOT EXISTS ocean_gis;
CREATE EXTENSION IF NOT EXISTS postgis WITH SCHEMA public;

-- =========================================================
-- GIS 数据集
-- =========================================================

CREATE TABLE ocean_gis.gis_data_set (
    id              BIGINT       PRIMARY KEY,
    data_set_name   VARCHAR(100) NOT NULL,
    category_id     BIGINT       NOT NULL,
    data_set_code   VARCHAR(64)  NOT NULL,
    description     VARCHAR(500),
    file_count      INTEGER      NOT NULL DEFAULT 0,
    total_size_bytes BIGINT      NOT NULL DEFAULT 0,
    create_time     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted         INTEGER      NOT NULL DEFAULT 0,
    CONSTRAINT ck_gis_data_set_category
        CHECK (category_id BETWEEN 0 AND 2),
    CONSTRAINT ck_gis_data_set_file_count
        CHECK (file_count >= 0),
    CONSTRAINT ck_gis_data_set_total_size
        CHECK (total_size_bytes >= 0),
    CONSTRAINT ck_gis_data_set_deleted
        CHECK (deleted IN (0, 1)),
    CONSTRAINT uk_gis_data_set_code
        UNIQUE (data_set_code)
);

COMMENT ON TABLE ocean_gis.gis_data_set IS 'GIS 数据集';
COMMENT ON COLUMN ocean_gis.gis_data_set.total_size_bytes IS '未删除且上传状态为 READY 的管理文件总大小（字节），包含配套文件，与元数据提取状态无关';
COMMENT ON COLUMN ocean_gis.gis_data_set.category_id IS '数据类别：0-影像数据，1-地形数据，2-矢量数据';
COMMENT ON COLUMN ocean_gis.gis_data_set.deleted IS '逻辑删除：0-未删除，1-已删除';

CREATE UNIQUE INDEX uk_gis_data_set_active_name
    ON ocean_gis.gis_data_set (data_set_name)
    WHERE deleted = 0;

CREATE INDEX idx_gis_data_set_category
    ON ocean_gis.gis_data_set (category_id)
    WHERE deleted = 0;

CREATE INDEX idx_gis_data_set_create_time
    ON ocean_gis.gis_data_set (create_time DESC)
    WHERE deleted = 0;


-- =========================================================
-- 数据集默认矢量样式快照及分类树
-- =========================================================

CREATE TABLE ocean_gis.gis_vector_dataset_style (
    id BIGINT PRIMARY KEY,
    data_set_id BIGINT NOT NULL UNIQUE REFERENCES ocean_gis.gis_data_set(id),
    template_id VARCHAR(100) NOT NULL,
    template_version INTEGER NOT NULL CHECK (template_version > 0),
    schema_version INTEGER NOT NULL CHECK (schema_version = 2),
    definition JSONB NOT NULL CHECK (definition @> '{"schemaVersion":2,"kind":"VECTOR_DATASET_STYLE_TEMPLATE"}'::jsonb),
    template_checksum VARCHAR(64) NOT NULL,
    style_version BIGINT NOT NULL DEFAULT 1 CHECK (style_version > 0),
    data_revision BIGINT NOT NULL DEFAULT 0 CHECK (data_revision >= 0),
    display_revision BIGINT NOT NULL DEFAULT 0 CHECK (display_revision >= 0),
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (data_set_id,id)
);
COMMENT ON TABLE ocean_gis.gis_vector_dataset_style IS '创建数据集时复制的不可变模板；数据和显示修订号独立更新，不随后台模板文件变化';

CREATE TABLE ocean_gis.gis_vector_group (
    id BIGINT PRIMARY KEY,
    data_set_id BIGINT NOT NULL,
    dataset_style_id BIGINT NOT NULL,
    parent_id BIGINT,
    group_key VARCHAR(100) NOT NULL,
    group_name VARCHAR(100) NOT NULL,
    node_type VARCHAR(32) NOT NULL CHECK (node_type IN ('GEOMETRY_FOLDER','BUSINESS_FOLDER','CLASSIFICATION','UNKNOWN_FOLDER','UNKNOWN_CLASSIFICATION')),
    geometry_type VARCHAR(10) CHECK (geometry_type IN ('POINT','LINE','POLYGON')),
    assignable BOOLEAN NOT NULL,
    default_style_key VARCHAR(100),
    enabled BOOLEAN NOT NULL DEFAULT true,
    sort_order INTEGER NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 1 CHECK (version > 0),
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (data_set_id,id),
    UNIQUE (data_set_id,group_key),
    FOREIGN KEY (data_set_id,dataset_style_id) REFERENCES ocean_gis.gis_vector_dataset_style(data_set_id,id),
    FOREIGN KEY (data_set_id,parent_id) REFERENCES ocean_gis.gis_vector_group(data_set_id,id) DEFERRABLE INITIALLY DEFERRED,
    CHECK (assignable = (node_type IN ('CLASSIFICATION','UNKNOWN_CLASSIFICATION'))),
    CHECK (NOT assignable OR (geometry_type IS NOT NULL AND default_style_key IS NOT NULL))
);
CREATE INDEX idx_gis_vector_group_parent ON ocean_gis.gis_vector_group(data_set_id,parent_id,sort_order);
COMMENT ON COLUMN ocean_gis.gis_vector_group.default_style_key IS '数据集不可变快照中styles的业务键；初始化时校验引用完整性与几何类别';

-- =========================================================
-- GIS 原始文件上传、下载传输记录
-- =========================================================

CREATE TABLE ocean_gis.gis_file_transfer_record (
    id                  BIGINT        PRIMARY KEY,
    record_no           VARCHAR(64)   NOT NULL,
    request_id          VARCHAR(64),
    operation_type      VARCHAR(16)   NOT NULL,
    data_set_id         BIGINT        NOT NULL,
    vector_placement    VARCHAR(10) CHECK (vector_placement IN ('AUTO','UNKNOWN')),
    vector_dataset_style_id BIGINT,
    total_count         INTEGER       NOT NULL DEFAULT 0,
    completed_count     INTEGER       NOT NULL DEFAULT 0,
    failed_count        INTEGER       NOT NULL DEFAULT 0,
    total_bytes         BIGINT        NOT NULL DEFAULT 0,
    uploaded_bytes      BIGINT        NOT NULL DEFAULT 0,
    record_status       VARCHAR(20)   NOT NULL DEFAULT 'QUEUED',
    current_stage       VARCHAR(32),
    result_storage_key  VARCHAR(1000),
    error_message       VARCHAR(1000),
    operator_id         BIGINT,
    version             BIGINT        NOT NULL DEFAULT 0,
    start_time          TIMESTAMP,
    finish_time         TIMESTAMP,
    create_time         TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time         TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted             INTEGER       NOT NULL DEFAULT 0,
    CONSTRAINT uk_gis_file_transfer_record_no
        UNIQUE (record_no),
    CONSTRAINT fk_gis_file_transfer_record_data_set
        FOREIGN KEY (data_set_id) REFERENCES ocean_gis.gis_data_set (id),
    FOREIGN KEY (data_set_id,vector_dataset_style_id) REFERENCES ocean_gis.gis_vector_dataset_style(data_set_id,id),
    CONSTRAINT ck_gis_file_transfer_record_operation
        CHECK (operation_type IN ('IMPORT', 'EXPORT')),
    CONSTRAINT ck_gis_file_transfer_record_status
        CHECK (record_status IN ('QUEUED', 'RUNNING', 'COMPLETED', 'PARTIAL_FAILED', 'FAILED')),
    CONSTRAINT ck_gis_file_transfer_record_counts
        CHECK (
            total_count >= 0
            AND completed_count >= 0
            AND failed_count >= 0
            AND completed_count + failed_count <= total_count
        ),
    CONSTRAINT ck_gis_file_transfer_record_bytes
        CHECK (
            total_bytes >= 0
            AND uploaded_bytes >= 0
            AND uploaded_bytes <= total_bytes
        ),
    CONSTRAINT ck_gis_file_transfer_record_version
        CHECK (version >= 0),
    CONSTRAINT ck_gis_file_transfer_record_timing
        CHECK (
            (record_status = 'QUEUED' AND finish_time IS NULL)
            OR (record_status = 'RUNNING' AND start_time IS NOT NULL AND finish_time IS NULL)
            OR (
                record_status IN ('COMPLETED', 'PARTIAL_FAILED', 'FAILED')
                AND start_time IS NOT NULL
                AND finish_time IS NOT NULL
                AND completed_count + failed_count = total_count
            )
        ),
    CONSTRAINT ck_gis_file_transfer_record_deleted
        CHECK (deleted IN (0, 1))
);

COMMENT ON TABLE ocean_gis.gis_file_transfer_record IS 'GIS 原始文件上传、下载传输记录';
COMMENT ON COLUMN ocean_gis.gis_file_transfer_record.record_no IS '对外暴露的稳定文件传输记录编号';
COMMENT ON COLUMN ocean_gis.gis_file_transfer_record.request_id IS '客户端上传批次幂等标识，仅导入任务使用';
COMMENT ON COLUMN ocean_gis.gis_file_transfer_record.operation_type IS '操作类型：IMPORT-导入，EXPORT-导出';
COMMENT ON COLUMN ocean_gis.gis_file_transfer_record.record_status IS '记录状态：QUEUED、RUNNING、COMPLETED、PARTIAL_FAILED、FAILED';
COMMENT ON COLUMN ocean_gis.gis_file_transfer_record.total_bytes IS '批次声明的文件总字节数';
COMMENT ON COLUMN ocean_gis.gis_file_transfer_record.uploaded_bytes IS '已确认落盘的文件字节数';
COMMENT ON COLUMN ocean_gis.gis_file_transfer_record.result_storage_key IS '导出结果的相对存储路径或对象存储 Key';
COMMENT ON COLUMN ocean_gis.gis_file_transfer_record.version IS '状态版本号，用于进度快照与实时事件合并';

CREATE INDEX idx_gis_file_transfer_record_data_set
    ON ocean_gis.gis_file_transfer_record (data_set_id, operation_type, create_time DESC)
    WHERE deleted = 0;

CREATE INDEX idx_gis_file_transfer_record_status
    ON ocean_gis.gis_file_transfer_record (record_status, create_time)
    WHERE deleted = 0;

CREATE UNIQUE INDEX uk_gis_file_transfer_record_request
    ON ocean_gis.gis_file_transfer_record (request_id)
    WHERE deleted = 0 AND operation_type = 'IMPORT' AND request_id IS NOT NULL;


-- =========================================================
-- GIS 文件元数据
-- =========================================================

CREATE TABLE ocean_gis.gis_file_meta (
    id                       BIGINT        PRIMARY KEY,
    data_set_id              BIGINT        NOT NULL,
    vector_placement         VARCHAR(10) CHECK (vector_placement IN ('AUTO','UNKNOWN')),
    vector_dataset_style_id  BIGINT,
    original_name            VARCHAR(255)  NOT NULL,
    storage_name             VARCHAR(255),
    storage_key              VARCHAR(1000),
    storage_type             VARCHAR(20)   NOT NULL DEFAULT 'LOCAL',
    extension                VARCHAR(32),
    size_bytes               BIGINT        NOT NULL DEFAULT 0,
    sha256                   VARCHAR(64),
    uploaded_by              BIGINT,
    upload_status            VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    cleanup_status           VARCHAR(20)   NOT NULL DEFAULT 'NOT_REQUIRED',
    error_message            VARCHAR(1000),
    metadata_status          VARCHAR(24)   NOT NULL DEFAULT 'QUEUED',
    metadata_version         BIGINT        NOT NULL DEFAULT 0,
    metadata_lease_expires_at TIMESTAMP,
    metadata_error_code      VARCHAR(64),
    metadata_error_message   VARCHAR(1000),
    metadata_extracted_at    TIMESTAMP,
    representative_longitude DOUBLE PRECISION,
    representative_latitude  DOUBLE PRECISION,
    representative_layer_key VARCHAR(255),
    representative_extent_kind VARCHAR(32),
    create_time              TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time              TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted                  INTEGER       NOT NULL DEFAULT 0,
    CONSTRAINT ck_gis_file_meta_storage_type
        CHECK (storage_type IN ('LOCAL', 'MINIO', 'S3')),
    FOREIGN KEY (data_set_id,vector_dataset_style_id) REFERENCES ocean_gis.gis_vector_dataset_style(data_set_id,id),
    CONSTRAINT ck_gis_file_meta_metadata_status
        CHECK (metadata_status IN ('QUEUED', 'RUNNING', 'COMPLETED', 'FAILED', 'NOT_APPLICABLE')),
    CONSTRAINT ck_gis_file_meta_metadata_version CHECK (metadata_version >= 0),
    CONSTRAINT ck_gis_file_meta_representative_point
        CHECK (
            (representative_longitude IS NULL AND representative_latitude IS NULL
             AND representative_layer_key IS NULL AND representative_extent_kind IS NULL)
            OR (representative_longitude IS NOT NULL AND representative_latitude IS NOT NULL
                AND representative_layer_key IS NOT NULL AND representative_extent_kind IS NOT NULL
                AND representative_longitude BETWEEN -180 AND 180
                AND representative_latitude BETWEEN -90 AND 90
                AND representative_layer_key <> ''
                AND representative_extent_kind IN ('RASTER_FOOTPRINT', 'VECTOR_ENVELOPE'))
        ),
    CONSTRAINT ck_gis_file_meta_completed_point
        CHECK ((metadata_status = 'COMPLETED') = (representative_longitude IS NOT NULL)),
    CONSTRAINT ck_gis_file_meta_metadata_lease
        CHECK ((metadata_status = 'RUNNING') = (metadata_lease_expires_at IS NOT NULL)),
    CONSTRAINT ck_gis_file_meta_size
        CHECK (size_bytes >= 0),
    CONSTRAINT ck_gis_file_meta_deleted
        CHECK (deleted IN (0, 1)),
    CONSTRAINT ck_gis_file_meta_upload_status
        CHECK (upload_status IN ('PENDING', 'READY', 'FAILED')),
    CONSTRAINT ck_gis_file_meta_cleanup_status
        CHECK (cleanup_status IN ('NOT_REQUIRED', 'PENDING', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_gis_file_meta_storage_required
        CHECK (upload_status = 'FAILED' OR (storage_name IS NOT NULL AND storage_key IS NOT NULL)),
    CONSTRAINT ck_gis_file_meta_cleanup_upload_status
        CHECK (upload_status = 'FAILED' OR cleanup_status = 'NOT_REQUIRED'),
    CONSTRAINT ck_gis_file_meta_cleanup_pending_key
        CHECK (
            cleanup_status NOT IN ('PENDING', 'FAILED')
            OR (upload_status = 'FAILED' AND storage_key IS NOT NULL)
        ),
    CONSTRAINT ck_gis_file_meta_cleanup_completed
        CHECK (
            cleanup_status <> 'COMPLETED'
            OR (upload_status = 'FAILED' AND storage_key IS NULL)
        )
);

COMMENT ON TABLE ocean_gis.gis_file_meta IS 'GIS 文件元数据';
COMMENT ON COLUMN ocean_gis.gis_file_meta.storage_key IS '相对存储路径或对象存储 Key';
COMMENT ON COLUMN ocean_gis.gis_file_meta.storage_type IS '存储类型：LOCAL、MINIO 或 S3';
COMMENT ON COLUMN ocean_gis.gis_file_meta.cleanup_status IS '失败文件资源清理状态：NOT_REQUIRED、PENDING、COMPLETED、FAILED';
COMMENT ON COLUMN ocean_gis.gis_file_meta.representative_longitude IS '元数据完成时保存的文件代表点经度，EPSG:4326；分页直接读取';
COMMENT ON COLUMN ocean_gis.gis_file_meta.representative_latitude IS '元数据完成时保存的文件代表点纬度，EPSG:4326';
COMMENT ON COLUMN ocean_gis.gis_file_meta.representative_layer_key IS '代表点来源图层键；面按实际面积、线按实际长度选择主要分块';
COMMENT ON COLUMN ocean_gis.gis_file_meta.representative_extent_kind IS '代表点来源范围类型；VECTOR_ENVELOPE 不保证点位于真实矢量要素内';

CREATE UNIQUE INDEX uk_gis_file_meta_active_storage
    ON ocean_gis.gis_file_meta (storage_type, storage_key)
    WHERE deleted = 0;

CREATE INDEX idx_gis_file_meta_data_set
    ON ocean_gis.gis_file_meta (data_set_id, create_time DESC)
    WHERE deleted = 0;

CREATE INDEX idx_gis_file_meta_sha256
    ON ocean_gis.gis_file_meta (sha256)
    WHERE deleted = 0 AND sha256 IS NOT NULL;

CREATE INDEX idx_gis_file_meta_metadata_queue
    ON ocean_gis.gis_file_meta (metadata_status, metadata_lease_expires_at, create_time)
    WHERE deleted = 0 AND upload_status = 'READY';

COMMENT ON COLUMN ocean_gis.gis_file_meta.metadata_status IS '元数据提取状态；COMPLETED 后空间信息可检索';
COMMENT ON COLUMN ocean_gis.gis_file_meta.metadata_version IS '领取或重新提取时递增，阻止过期任务提交';
COMMENT ON COLUMN ocean_gis.gis_file_meta.metadata_lease_expires_at IS '后台执行租约到期时间';

CREATE TABLE ocean_gis.gis_file_spatial_metadata (
    id                      BIGINT PRIMARY KEY,
    file_meta_id            BIGINT NOT NULL REFERENCES ocean_gis.gis_file_meta(id) ON DELETE CASCADE,
    layer_key               VARCHAR(255) NOT NULL,
    layer_name              VARCHAR(255),
    data_kind               VARCHAR(16) NOT NULL CHECK (data_kind IN ('RASTER', 'VECTOR')),
    source_crs_code         VARCHAR(100),
    source_crs_wkt          TEXT NOT NULL,
    source_unit             VARCHAR(100),
    source_extent           JSONB NOT NULL,
    geographic_extent       public.geometry(Geometry,4326) NOT NULL,
    extent_kind             VARCHAR(32) NOT NULL CHECK (extent_kind IN ('RASTER_FOOTPRINT', 'VECTOR_ENVELOPE')),
    raster_width            INTEGER CHECK (raster_width > 0),
    raster_height           INTEGER CHECK (raster_height > 0),
    band_count              INTEGER CHECK (band_count > 0),
    resolution_x            DOUBLE PRECISION CHECK (resolution_x > 0 AND resolution_x < 'Infinity'::float8),
    resolution_y            DOUBLE PRECISION CHECK (resolution_y > 0 AND resolution_y < 'Infinity'::float8),
    resolution_unit         VARCHAR(100),
    tile_matrix_set_id      VARCHAR(100),
    tile_width              INTEGER CHECK (tile_width > 0),
    tile_height             INTEGER CHECK (tile_height > 0),
    recommended_max_zoom    INTEGER CHECK (recommended_max_zoom BETWEEN 0 AND 22),
    calculation_details     JSONB NOT NULL DEFAULT '{}'::jsonb,
    extra_metadata          JSONB NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT uk_gis_file_spatial_metadata_layer UNIQUE (file_meta_id, layer_key),
    CONSTRAINT ck_gis_file_spatial_metadata_geometry
        CHECK (NOT public.ST_IsEmpty(geographic_extent) AND public.ST_IsValid(geographic_extent)),
    CONSTRAINT ck_gis_file_spatial_metadata_zoom
        CHECK (recommended_max_zoom IS NULL OR (tile_matrix_set_id IS NOT NULL AND tile_width IS NOT NULL AND tile_height IS NOT NULL))
);

CREATE INDEX idx_gis_file_spatial_metadata_extent ON ocean_gis.gis_file_spatial_metadata USING GIST (geographic_extent);
CREATE INDEX idx_gis_file_spatial_metadata_zoom ON ocean_gis.gis_file_spatial_metadata (tile_matrix_set_id, recommended_max_zoom);
COMMENT ON TABLE ocean_gis.gis_file_spatial_metadata IS '文件各图层的源空间参考、统一检索范围及推荐层级';
COMMENT ON COLUMN ocean_gis.gis_file_spatial_metadata.geographic_extent IS 'EPSG:4326 经度/纬度范围；矢量包围范围不代表精确要素覆盖区';
COMMENT ON COLUMN ocean_gis.gis_file_spatial_metadata.recommended_max_zoom IS '按源分辨率估算的推荐层级，不代表成果实际层级';

-- =========================================================
-- GIS 文件操作逐文件明细
-- =========================================================

CREATE TABLE ocean_gis.gis_vector_layer (
    id BIGINT PRIMARY KEY,
    data_set_id BIGINT NOT NULL REFERENCES ocean_gis.gis_data_set(id),
    source_file_id BIGINT REFERENCES ocean_gis.gis_file_meta(id),
    group_id BIGINT NOT NULL,
    original_group_id BIGINT NOT NULL,
    geometry_type VARCHAR(10) NOT NULL CHECK (geometry_type IN ('POINT','LINE','POLYGON')),
    assignment_mode VARCHAR(10) NOT NULL DEFAULT 'AUTO' CHECK (assignment_mode IN ('AUTO','MANUAL')),
    enabled BOOLEAN NOT NULL DEFAULT false,
    style_mode VARCHAR(32) NOT NULL CHECK (style_mode IN ('UNCONFIGURED','INHERIT_TARGET_GROUP','CUSTOM')),
    presentation_version BIGINT NOT NULL DEFAULT 1 CHECK (presentation_version > 0),
    layer_key VARCHAR(255) NOT NULL,
    layer_name VARCHAR(100) NOT NULL,
    layer_status VARCHAR(20) NOT NULL DEFAULT 'IMPORTING'
        CHECK (layer_status IN ('IMPORTING','READY','FAILED')),
    feature_count BIGINT NOT NULL DEFAULT 0 CHECK (feature_count >= 0),
    geographic_extent public.geometry(Geometry,4326),
    geometry_types JSONB NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(geometry_types) = 'array'),
    data_version BIGINT NOT NULL DEFAULT 0 CHECK (data_version >= 0),
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted INTEGER NOT NULL DEFAULT 0 CHECK (deleted IN (0,1)),
    UNIQUE (source_file_id, layer_key),
    FOREIGN KEY (data_set_id,group_id) REFERENCES ocean_gis.gis_vector_group(data_set_id,id),
    FOREIGN KEY (data_set_id,original_group_id) REFERENCES ocean_gis.gis_vector_group(data_set_id,id),
    CHECK (style_mode != 'UNCONFIGURED' OR NOT enabled),
    CHECK (deleted = 1 OR source_file_id IS NOT NULL),
    CHECK (geographic_extent IS NULL OR
        (NOT public.ST_IsEmpty(geographic_extent) AND public.ST_IsValid(geographic_extent)))
);
CREATE INDEX idx_gis_vector_layer_dataset ON ocean_gis.gis_vector_layer(data_set_id, id) WHERE deleted = 0;
CREATE INDEX idx_gis_vector_layer_source ON ocean_gis.gis_vector_layer(source_file_id) WHERE deleted = 0;
CREATE INDEX idx_gis_vector_layer_group ON ocean_gis.gis_vector_layer(data_set_id,group_id,id) WHERE deleted=0;

CREATE TABLE ocean_gis.gis_vector_style (
    id BIGINT PRIMARY KEY,
    layer_id BIGINT NOT NULL REFERENCES ocean_gis.gis_vector_layer(id) ON DELETE CASCADE,
    name VARCHAR(100) NOT NULL CHECK (btrim(name) <> ''),
    is_default BOOLEAN NOT NULL DEFAULT false,
    definition JSONB NOT NULL,
    version BIGINT NOT NULL DEFAULT 1 CHECK (version >= 1),
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (layer_id, name),
    CHECK (jsonb_typeof(definition) = 'object'
        AND definition @> '{"schemaVersion":1}'::jsonb
        AND definition ? 'rules' AND jsonb_typeof(definition->'rules') = 'array')
);
CREATE UNIQUE INDEX uk_gis_vector_style_default ON ocean_gis.gis_vector_style(layer_id) WHERE is_default;
COMMENT ON TABLE ocean_gis.gis_vector_style IS '图层样式方案及有序渲染规则，样式版本与要素数据版本独立';
COMMENT ON COLUMN ocean_gis.gis_vector_style.definition IS 'schemaVersion=1；rules 按几何类别、属性及连续 zoom 匹配，首条命中生效';
COMMENT ON COLUMN ocean_gis.gis_vector_style.is_default IS '同一图层至多一个默认方案，业务层保证默认方案不被删除';

CREATE TABLE ocean_gis.gis_vector_feature (
    id BIGINT PRIMARY KEY,
    layer_id BIGINT NOT NULL REFERENCES ocean_gis.gis_vector_layer(id) ON DELETE CASCADE,
    geom public.geometry NOT NULL,
    properties JSONB,
    source_feature_id JSONB,
    source_feature_index BIGINT CHECK (source_feature_index >= 0),
    source_geometry_path VARCHAR(512),
    classification_rule_key VARCHAR(100),
    version BIGINT NOT NULL DEFAULT 1 CHECK (version >= 1),
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted INTEGER NOT NULL DEFAULT 0 CHECK (deleted IN (0,1)),
    UNIQUE (layer_id, source_feature_index, source_geometry_path),
    CHECK ((source_feature_index IS NULL AND source_geometry_path IS NULL)
        OR (source_feature_index IS NOT NULL AND source_geometry_path IS NOT NULL AND source_geometry_path LIKE '/geometry%')),
    CHECK (public.ST_SRID(geom) = 4326 AND public.ST_NDims(geom) IN (2,3)
        AND NOT public.ST_IsEmpty(geom) AND public.ST_IsValid(geom)),
    CHECK (properties IS NULL OR jsonb_typeof(properties) IN ('object','null')),
    CHECK (source_feature_id IS NULL OR jsonb_typeof(source_feature_id) IN ('string','number'))
);
CREATE INDEX idx_gis_vector_feature_layer ON ocean_gis.gis_vector_feature(layer_id, id) WHERE deleted = 0;
CREATE INDEX idx_gis_vector_feature_geom ON ocean_gis.gis_vector_feature USING GIST(geom) WHERE deleted = 0;
COMMENT ON TABLE ocean_gis.gis_vector_layer IS '从原始矢量文件提取的可编辑图层；同一来源文件允许生成多个图层';
COMMENT ON COLUMN ocean_gis.gis_vector_layer.layer_key IS '来源文件、初始分类及几何类别组成的稳定键；手动移组不变更';
COMMENT ON COLUMN ocean_gis.gis_vector_layer.geographic_extent IS '当前要素的二维包围范围；与来源文件范围独立维护';
COMMENT ON TABLE ocean_gis.gis_vector_feature IS '逐要素几何与属性；编辑不回写来源文件';
COMMENT ON COLUMN ocean_gis.gis_vector_feature.geom IS 'SRID 4326，保留 XY 或 XYZ；空间索引和默认空间查询为二维';
COMMENT ON COLUMN ocean_gis.gis_vector_feature.source_feature_index IS '来源文件内从零开始的要素序号；交互新增的要素为空';

-- 显示查询中的参数化条件解释器；不拼接或执行模板提供的SQL。
CREATE FUNCTION ocean_gis.vector_rule_value(v JSONB, field_name TEXT) RETURNS JSONB
LANGUAGE plpgsql IMMUTABLE PARALLEL SAFE AS $$
DECLARE t TEXT;
BEGIN
    IF v IS NULL OR v='null'::jsonb THEN RETURN NULL; END IF;
    IF jsonb_typeof(v)='string' THEN
        t:=btrim(v #>> '{}', E' \t\r\n');
        IF t='' THEN RETURN NULL; END IF;
    END IF;
    IF field_name='fclass' THEN
        IF jsonb_typeof(v)!='string' THEN RETURN NULL; END IF;
        RETURN to_jsonb(lower(t));
    ELSIF field_name='id' THEN
        IF jsonb_typeof(v)='string' THEN RETURN to_jsonb(t); END IF;
        IF jsonb_typeof(v)='number' AND (v #>> '{}')::numeric=trunc((v #>> '{}')::numeric)
            THEN RETURN to_jsonb(trunc((v #>> '{}')::numeric)::text); END IF;
        RETURN NULL;
    ELSIF field_name='code' THEN
        IF jsonb_typeof(v)='string' AND t ~ '^[+-]?[0-9]+$' THEN RETURN to_jsonb(t::numeric); END IF;
        IF jsonb_typeof(v)='number' AND (v #>> '{}')::numeric=trunc((v #>> '{}')::numeric) THEN RETURN v; END IF;
        RETURN NULL;
    END IF;
    RETURN v;
END $$;

CREATE FUNCTION ocean_gis.vector_filter_matches(p JSONB, f JSONB, normalized BOOLEAN) RETURNS BOOLEAN
LANGUAGE plpgsql IMMUTABLE PARALLEL SAFE AS $$
DECLARE op TEXT; field_name TEXT; a JSONB; b JSONB; child JSONB;
BEGIN
    IF f IS NULL OR f='null'::jsonb THEN RETURN true; END IF;
    op:=f->>'op';
    IF op IN ('and','or') THEN
        FOR child IN SELECT value FROM jsonb_array_elements(f->'conditions') LOOP
            IF op='and' AND NOT ocean_gis.vector_filter_matches(p,child,normalized) THEN RETURN false; END IF;
            IF op='or' AND ocean_gis.vector_filter_matches(p,child,normalized) THEN RETURN true; END IF;
        END LOOP;
        RETURN op='and';
    ELSIF op='not' THEN RETURN NOT ocean_gis.vector_filter_matches(p,f->'condition',normalized);
    END IF;
    field_name:=f->>'field'; a:=p->field_name; b:=f->'value';
    IF op='exists' THEN RETURN (a IS NOT NULL)=COALESCE((b #>> '{}')::boolean,true); END IF;
    IF op='missingOrEmpty' THEN RETURN a IS NULL OR a='null'::jsonb
        OR (jsonb_typeof(a)='string' AND btrim(a #>> '{}', E' \t\r\n')=''); END IF;
    IF normalized THEN
        a:=ocean_gis.vector_rule_value(a,field_name);
        IF op!='in' THEN b:=ocean_gis.vector_rule_value(b,field_name); END IF;
    END IF;
    IF a IS NULL THEN RETURN false; END IF;
    IF op='in' THEN
        FOR child IN SELECT value FROM jsonb_array_elements(b) LOOP
            IF normalized THEN child:=ocean_gis.vector_rule_value(child,field_name); END IF;
            IF a=child THEN RETURN true; END IF;
        END LOOP;
        RETURN false;
    ELSIF op='regex' THEN RETURN COALESCE((a #>> '{}') ~ ('^(?:' || (b #>> '{}') || ')$'),false);
    ELSIF op='eq' THEN RETURN COALESCE(a=b,false);
    ELSIF op='ne' THEN RETURN b IS NULL OR a!=b;
    ELSIF jsonb_typeof(a)='number' AND jsonb_typeof(b)='number' THEN
        CASE op
            WHEN 'gt' THEN RETURN (a #>> '{}')::numeric>(b #>> '{}')::numeric;
            WHEN 'gte' THEN RETURN (a #>> '{}')::numeric>=(b #>> '{}')::numeric;
            WHEN 'lt' THEN RETURN (a #>> '{}')::numeric<(b #>> '{}')::numeric;
            WHEN 'lte' THEN RETURN (a #>> '{}')::numeric<=(b #>> '{}')::numeric;
            ELSE RETURN false;
        END CASE;
    END IF;
    RETURN false;
END $$;


CREATE TABLE ocean_gis.gis_file_transfer_record_item (
    id                  BIGINT        PRIMARY KEY,
    record_id           BIGINT        NOT NULL,
    file_meta_id        BIGINT,
    sequence_no         INTEGER       NOT NULL,
    original_name       VARCHAR(255)  NOT NULL,
    operation_status    VARCHAR(20)   NOT NULL,
    error_message       VARCHAR(1000),
    size_bytes          BIGINT        NOT NULL DEFAULT 0,
    storage_name        VARCHAR(255),
    storage_key         VARCHAR(1000),
    storage_type        VARCHAR(20),
    extension           VARCHAR(32),
    sha256              VARCHAR(64),
    uploaded_by         BIGINT,
    upload_status       VARCHAR(20),
    cleanup_status      VARCHAR(20),
    create_time         TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time         TIMESTAMP,
    CONSTRAINT fk_gis_file_transfer_record_item_record
        FOREIGN KEY (record_id) REFERENCES ocean_gis.gis_file_transfer_record (id),
    CONSTRAINT fk_gis_file_transfer_record_item_file
        FOREIGN KEY (file_meta_id) REFERENCES ocean_gis.gis_file_meta (id),
    CONSTRAINT uk_gis_file_transfer_record_item_sequence
        UNIQUE (record_id, sequence_no),
    CONSTRAINT ck_gis_file_transfer_record_item_status
        CHECK (operation_status IN ('PENDING', 'RUNNING', 'SUCCESS', 'FAILED')),
    CONSTRAINT ck_gis_file_transfer_record_item_size
        CHECK (size_bytes >= 0)
);

COMMENT ON TABLE ocean_gis.gis_file_transfer_record_item IS 'GIS 文件上传下载批次的逐文件处理明细';
COMMENT ON COLUMN ocean_gis.gis_file_transfer_record_item.file_meta_id IS '对应文件元数据；文件形成前失败时允许为空';
COMMENT ON COLUMN ocean_gis.gis_file_transfer_record_item.operation_status IS '本次操作状态：PENDING、RUNNING、SUCCESS、FAILED';
COMMENT ON COLUMN ocean_gis.gis_file_transfer_record_item.storage_name IS '操作发生时的系统存储文件名快照';
COMMENT ON COLUMN ocean_gis.gis_file_transfer_record_item.storage_key IS '操作发生时的存储路径或对象存储 Key 快照';
COMMENT ON COLUMN ocean_gis.gis_file_transfer_record_item.storage_type IS '操作发生时的存储类型快照';
COMMENT ON COLUMN ocean_gis.gis_file_transfer_record_item.extension IS '操作发生时的扩展名快照';
COMMENT ON COLUMN ocean_gis.gis_file_transfer_record_item.sha256 IS '操作发生时的 SHA-256 快照';
COMMENT ON COLUMN ocean_gis.gis_file_transfer_record_item.uploaded_by IS '操作发生时的上传人快照';
COMMENT ON COLUMN ocean_gis.gis_file_transfer_record_item.upload_status IS '操作发生时的上传状态快照';
COMMENT ON COLUMN ocean_gis.gis_file_transfer_record_item.cleanup_status IS '操作发生时的资源清理状态快照';
COMMENT ON COLUMN ocean_gis.gis_file_transfer_record_item.update_time IS '操作完成时的文件更新时间快照';

CREATE INDEX idx_gis_file_transfer_record_item_record
    ON ocean_gis.gis_file_transfer_record_item (record_id, sequence_no);

CREATE INDEX idx_gis_file_transfer_record_item_file
    ON ocean_gis.gis_file_transfer_record_item (file_meta_id, create_time)
    WHERE file_meta_id IS NOT NULL;

CREATE UNIQUE INDEX uk_gis_file_transfer_record_item_file
    ON ocean_gis.gis_file_transfer_record_item (record_id, file_meta_id)
    WHERE file_meta_id IS NOT NULL;


-- =========================================================
-- GIS 处理任务
-- =========================================================

CREATE TABLE ocean_gis.gis_processing_task (
    id                        BIGINT       PRIMARY KEY,
    task_no                   VARCHAR(64)  NOT NULL,
    task_name                 VARCHAR(200) NOT NULL,
    processing_type           VARCHAR(20)  NOT NULL,
    source_type               VARCHAR(20)  NOT NULL,
    parameters                JSONB        NOT NULL DEFAULT '{}'::jsonb,
    parameter_schema_version  INTEGER      NOT NULL DEFAULT 1,
    request_fingerprint       VARCHAR(64),
    priority                  INTEGER      NOT NULL DEFAULT 0,
    total_count               BIGINT       NOT NULL DEFAULT 0,
    completed_count           BIGINT       NOT NULL DEFAULT 0,
    failed_count              BIGINT       NOT NULL DEFAULT 0,
    task_status               VARCHAR(20)  NOT NULL DEFAULT 'QUEUED',
    current_stage             VARCHAR(32),
    error_message             VARCHAR(1000),
    start_time                TIMESTAMP,
    finish_time               TIMESTAMP,
    create_time               TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time               TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted                   INTEGER      NOT NULL DEFAULT 0,
    CONSTRAINT uk_gis_processing_task_no
        UNIQUE (task_no),
    CONSTRAINT ck_gis_processing_task_type
        CHECK (processing_type IN ('TERRAIN', 'IMAGERY', 'VECTOR')),
    CONSTRAINT ck_gis_processing_task_source
        CHECK (source_type IN ('UPLOAD', 'WORKSPACE', 'MANAGED_FILE')),
    CONSTRAINT ck_gis_processing_task_schema_version
        CHECK (parameter_schema_version > 0),
    CONSTRAINT ck_gis_processing_task_counts
        CHECK (total_count >= 0 AND completed_count >= 0 AND failed_count >= 0
            AND completed_count + failed_count <= total_count),
    CONSTRAINT ck_gis_processing_task_deleted
        CHECK (deleted IN (0, 1)),
    CONSTRAINT ck_gis_processing_task_status
        CHECK (task_status IN ('QUEUED', 'RUNNING', 'COMPLETED', 'PARTIAL_FAILED', 'FAILED'))
);

COMMENT ON TABLE ocean_gis.gis_processing_task IS '静态瓦片生成任务及不可变处理参数快照';
COMMENT ON COLUMN ocean_gis.gis_processing_task.parameters IS '按处理类型保存的完整参数 JSON 快照';

CREATE INDEX idx_gis_processing_task_schedule
    ON ocean_gis.gis_processing_task (priority DESC, create_time)
    WHERE deleted = 0;

CREATE INDEX idx_gis_processing_task_status
    ON ocean_gis.gis_processing_task (task_status, priority DESC, create_time)
    WHERE deleted = 0;

CREATE INDEX idx_gis_processing_task_type
    ON ocean_gis.gis_processing_task (processing_type, source_type, create_time DESC)
    WHERE deleted = 0;

-- =========================================================
-- GIS 处理输入
-- =========================================================

CREATE TABLE ocean_gis.gis_processing_input (
    id              BIGINT       PRIMARY KEY,
    task_id         BIGINT       NOT NULL,
    sequence_no     INTEGER      NOT NULL,
    input_kind      VARCHAR(16)  NOT NULL,
    input_status    VARCHAR(20)  NOT NULL DEFAULT 'READY',
    file_meta_id    BIGINT,
    workspace_code  VARCHAR(64),
    relative_path   VARCHAR(1000),
    storage_key     VARCHAR(1000),
    original_name   VARCHAR(500) NOT NULL,
    extension       VARCHAR(32),
    size_bytes      BIGINT,
    sha256          VARCHAR(64),
    error_message   VARCHAR(1000),
    create_time     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_gis_processing_input_task
        FOREIGN KEY (task_id) REFERENCES ocean_gis.gis_processing_task (id),
    CONSTRAINT fk_gis_processing_input_file
        FOREIGN KEY (file_meta_id) REFERENCES ocean_gis.gis_file_meta (id),
    CONSTRAINT uk_gis_processing_input_sequence
        UNIQUE (task_id, sequence_no),
    CONSTRAINT ck_gis_processing_input_sequence
        CHECK (sequence_no > 0),
    CONSTRAINT ck_gis_processing_input_kind
        CHECK (input_kind IN ('FILE', 'DIRECTORY')),
    CONSTRAINT ck_gis_processing_input_status
        CHECK (input_status IN ('READY', 'RUNNING', 'CONSUMED', 'FAILED')),
    CONSTRAINT ck_gis_processing_input_size
        CHECK (size_bytes IS NULL OR size_bytes >= 0),
    CONSTRAINT ck_gis_processing_input_locator
        CHECK (
            (file_meta_id IS NOT NULL AND workspace_code IS NULL
                AND relative_path IS NULL AND storage_key IS NULL)
            OR (file_meta_id IS NULL AND workspace_code IS NOT NULL
                AND relative_path IS NOT NULL AND storage_key IS NULL)
            OR (file_meta_id IS NULL AND workspace_code IS NULL
                AND relative_path IS NULL AND storage_key IS NOT NULL)
        )
);

COMMENT ON TABLE ocean_gis.gis_processing_input IS '处理任务输入快照，统一上传、受控工作空间和已管理文件';
COMMENT ON COLUMN ocean_gis.gis_processing_input.relative_path IS '受控工作空间根目录下的相对路径，禁止保存绝对路径';

CREATE INDEX idx_gis_processing_input_task
    ON ocean_gis.gis_processing_input (task_id, sequence_no);

CREATE INDEX idx_gis_processing_input_file
    ON ocean_gis.gis_processing_input (file_meta_id)
    WHERE file_meta_id IS NOT NULL;

-- =========================================================
-- GIS 静态瓦片集
-- =========================================================

CREATE TABLE ocean_gis.gis_tile_set (
    id              BIGINT        PRIMARY KEY,
    task_id         BIGINT        NOT NULL,
    tile_type       VARCHAR(20)   NOT NULL,
    tile_set_status VARCHAR(20)   NOT NULL DEFAULT 'BUILDING',
    output_key      VARCHAR(1000) NOT NULL,
    target_crs      VARCHAR(100),
    tile_profile    VARCHAR(100),
    output_format   VARCHAR(100),
    min_zoom        INTEGER,
    max_zoom        INTEGER,
    manifest_key    VARCHAR(1000),
    error_message   VARCHAR(1000),
    create_time     TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_gis_tile_set_task
        FOREIGN KEY (task_id) REFERENCES ocean_gis.gis_processing_task (id),
    CONSTRAINT uk_gis_tile_set_task UNIQUE (task_id),
    CONSTRAINT uk_gis_tile_set_output UNIQUE (output_key),
    CONSTRAINT ck_gis_tile_set_type
        CHECK (tile_type IN ('TERRAIN', 'IMAGERY', 'VECTOR')),
    CONSTRAINT ck_gis_tile_set_status
        CHECK (tile_set_status IN ('BUILDING', 'READY', 'FAILED')),
    CONSTRAINT ck_gis_tile_set_zoom
        CHECK ((min_zoom IS NULL AND max_zoom IS NULL)
            OR (min_zoom BETWEEN 0 AND 22 AND max_zoom BETWEEN 0 AND 22
                AND min_zoom <= max_zoom))
);

COMMENT ON TABLE ocean_gis.gis_tile_set IS '一次处理任务唯一生成的静态瓦片集';
COMMENT ON COLUMN ocean_gis.gis_tile_set.output_key IS '处理存储根目录下的瓦片集 Key';

CREATE INDEX idx_gis_tile_set_type_status
    ON ocean_gis.gis_tile_set (tile_type, tile_set_status, create_time DESC);

-- =========================================================
-- GIS 静态瓦片集成果归档
-- =========================================================

CREATE TABLE ocean_gis.gis_tile_set_archive (
    id                      BIGINT        PRIMARY KEY,
    archive_no              VARCHAR(64)   NOT NULL,
    processing_task_id      BIGINT        NOT NULL,
    tile_set_id             BIGINT        NOT NULL,
    archive_name            VARCHAR(255)  NOT NULL,
    archive_format          VARCHAR(16)   NOT NULL DEFAULT 'ZIP',
    archive_status          VARCHAR(20)   NOT NULL DEFAULT 'QUEUED',
    current_stage           VARCHAR(32),
    total_file_count        BIGINT        NOT NULL DEFAULT 0,
    processed_file_count    BIGINT        NOT NULL DEFAULT 0,
    total_bytes             BIGINT        NOT NULL DEFAULT 0,
    processed_bytes         BIGINT        NOT NULL DEFAULT 0,
    archive_size_bytes      BIGINT        NOT NULL DEFAULT 0,
    storage_key             VARCHAR(1000),
    sha256                  VARCHAR(64),
    operator_id             BIGINT,
    download_count          BIGINT        NOT NULL DEFAULT 0,
    last_download_time      TIMESTAMP,
    error_message           VARCHAR(1000),
    version                 BIGINT        NOT NULL DEFAULT 0,
    start_time              TIMESTAMP,
    finish_time             TIMESTAMP,
    create_time             TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time             TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted                 INTEGER       NOT NULL DEFAULT 0,
    CONSTRAINT uk_gis_tile_set_archive_no UNIQUE (archive_no),
    CONSTRAINT fk_gis_tile_set_archive_task
        FOREIGN KEY (processing_task_id) REFERENCES ocean_gis.gis_processing_task (id),
    CONSTRAINT fk_gis_tile_set_archive_tile_set
        FOREIGN KEY (tile_set_id) REFERENCES ocean_gis.gis_tile_set (id),
    CONSTRAINT ck_gis_tile_set_archive_format
        CHECK (archive_format IN ('ZIP')),
    CONSTRAINT ck_gis_tile_set_archive_status
        CHECK (archive_status IN ('QUEUED', 'RUNNING', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_gis_tile_set_archive_counts
        CHECK (
            total_file_count >= 0
            AND processed_file_count >= 0
            AND processed_file_count <= total_file_count
        ),
    CONSTRAINT ck_gis_tile_set_archive_bytes
        CHECK (
            total_bytes >= 0
            AND processed_bytes >= 0
            AND processed_bytes <= total_bytes
            AND archive_size_bytes >= 0
        ),
    CONSTRAINT ck_gis_tile_set_archive_downloads
        CHECK (download_count >= 0),
    CONSTRAINT ck_gis_tile_set_archive_version
        CHECK (version >= 0),
    CONSTRAINT ck_gis_tile_set_archive_result
        CHECK (
            archive_status <> 'COMPLETED'
            OR (
                storage_key IS NOT NULL
                AND sha256 IS NOT NULL
                AND processed_file_count = total_file_count
                AND processed_bytes = total_bytes
            )
        ),
    CONSTRAINT ck_gis_tile_set_archive_timing
        CHECK (
            (archive_status = 'QUEUED' AND start_time IS NULL AND finish_time IS NULL)
            OR (archive_status = 'RUNNING' AND start_time IS NOT NULL AND finish_time IS NULL)
            OR (
                archive_status IN ('COMPLETED', 'FAILED')
                AND start_time IS NOT NULL
                AND finish_time IS NOT NULL
            )
        ),
    CONSTRAINT ck_gis_tile_set_archive_deleted
        CHECK (deleted IN (0, 1))
);

COMMENT ON TABLE ocean_gis.gis_tile_set_archive IS 'GIS 静态瓦片集成果归档及其生成状态';
COMMENT ON COLUMN ocean_gis.gis_tile_set_archive.archive_no IS '对外暴露的稳定归档编号';
COMMENT ON COLUMN ocean_gis.gis_tile_set_archive.processing_task_id IS '产生静态瓦片集的处理任务';
COMMENT ON COLUMN ocean_gis.gis_tile_set_archive.tile_set_id IS '被归档的静态瓦片集';
COMMENT ON COLUMN ocean_gis.gis_tile_set_archive.storage_key IS '处理存储根目录下的归档文件 Key';
COMMENT ON COLUMN ocean_gis.gis_tile_set_archive.sha256 IS '归档 ZIP 文件的 SHA-256';

CREATE UNIQUE INDEX uk_gis_tile_set_archive_task
    ON ocean_gis.gis_tile_set_archive (processing_task_id)
    WHERE deleted = 0;

CREATE UNIQUE INDEX uk_gis_tile_set_archive_tile_set
    ON ocean_gis.gis_tile_set_archive (tile_set_id)
    WHERE deleted = 0;

CREATE INDEX idx_gis_tile_set_archive_status
    ON ocean_gis.gis_tile_set_archive (archive_status, create_time DESC)
    WHERE deleted = 0;

CREATE INDEX idx_gis_tile_set_archive_create_time
    ON ocean_gis.gis_tile_set_archive (create_time DESC)
    WHERE deleted = 0;

-- =========================================================
-- GIS 瓦片服务发布记录
-- =========================================================

CREATE TABLE ocean_gis.gis_publication (
    id                  BIGINT       PRIMARY KEY,
    service_code        VARCHAR(64)  NOT NULL,
    tile_set_id         BIGINT       NOT NULL,
    data_set_id         BIGINT,
    status              VARCHAR(20)  NOT NULL DEFAULT 'PUBLISHED',
    publish_time        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted             INTEGER      NOT NULL DEFAULT 0,
    CONSTRAINT uk_gis_publication_code UNIQUE (service_code),
    CONSTRAINT uk_gis_publication_tile_set UNIQUE (tile_set_id),
    CONSTRAINT fk_gis_publication_tile_set
        FOREIGN KEY (tile_set_id) REFERENCES ocean_gis.gis_tile_set (id),
    CONSTRAINT fk_gis_publication_data_set
        FOREIGN KEY (data_set_id) REFERENCES ocean_gis.gis_data_set (id),
    CONSTRAINT ck_gis_publication_status
        CHECK (status IN ('PUBLISHED', 'DISABLED')),
    CONSTRAINT ck_gis_publication_deleted
        CHECK (deleted IN (0, 1))
);

COMMENT ON TABLE ocean_gis.gis_publication IS '静态瓦片集的公开发布记录';
COMMENT ON COLUMN ocean_gis.gis_publication.service_code IS '公开服务 URL 中使用的稳定编码';
COMMENT ON COLUMN ocean_gis.gis_publication.tile_set_id IS '被发布的静态瓦片集 ID';

CREATE INDEX idx_gis_publication_status
    ON ocean_gis.gis_publication (status, publish_time DESC)
    WHERE deleted = 0;
