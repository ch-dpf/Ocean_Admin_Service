-- Ocean Admin 核心数据模型：IAM、审计与工作台指标。
CREATE SCHEMA IF NOT EXISTS ocean_platform;

-- 统一维护带 updated_at 字段表的最后更新时间。
CREATE OR REPLACE FUNCTION ocean_platform.set_updated_at()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$;

-- 平台注册表：记录可接入统一管理后台的业务平台及其入口信息。
CREATE TABLE ocean_platform.iam_platform (
    id              UUID PRIMARY KEY,
    platform_code   VARCHAR(64) NOT NULL,
    platform_name   VARCHAR(128) NOT NULL,
    entry_url       VARCHAR(500),
    icon_url        VARCHAR(500),
    description     VARCHAR(500),
    status          VARCHAR(20) NOT NULL DEFAULT 'ENABLED',
    sort_order      INTEGER NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMPTZ,
    CONSTRAINT ck_iam_platform_status CHECK (status IN ('ENABLED', 'DISABLED'))
);

CREATE UNIQUE INDEX uk_iam_platform_code
    ON ocean_platform.iam_platform(platform_code)
    WHERE deleted_at IS NULL;

-- 用户主表：保存认证凭据、有效期、锁定状态及登录安全信息。
-- username_normalized 用于大小写无关查询，username 保留原始展示形式。
CREATE TABLE ocean_platform.iam_user (
    id                    UUID PRIMARY KEY,
    username              VARCHAR(64) NOT NULL,
    username_normalized   VARCHAR(64) NOT NULL,
    password_hash         VARCHAR(255) NOT NULL,
    real_name             VARCHAR(100),
    email                 VARCHAR(255),
    phone                 VARCHAR(32),
    avatar_url            VARCHAR(500),
    status                VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    valid_from            TIMESTAMPTZ,
    valid_until           TIMESTAMPTZ,
    permanent_valid       BOOLEAN NOT NULL DEFAULT TRUE,
    max_login_devices     INTEGER NOT NULL DEFAULT 1,
    failed_attempts       INTEGER NOT NULL DEFAULT 0,
    lock_level            INTEGER NOT NULL DEFAULT 0,
    locked_until          TIMESTAMPTZ,
    password_changed_at   TIMESTAMPTZ,
    last_login_at         TIMESTAMPTZ,
    last_login_ip         VARCHAR(64),
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at            TIMESTAMPTZ,
    version               BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_iam_user_status CHECK (status IN ('ACTIVE', 'DISABLED', 'LOCKED')),
    CONSTRAINT ck_iam_user_devices CHECK (max_login_devices BETWEEN 1 AND 20),
    CONSTRAINT ck_iam_user_validity CHECK (valid_until IS NULL OR valid_from IS NULL OR valid_until > valid_from)
);

CREATE UNIQUE INDEX uk_iam_user_username
    ON ocean_platform.iam_user(username_normalized)
    WHERE deleted_at IS NULL;
CREATE INDEX idx_iam_user_status ON ocean_platform.iam_user(status) WHERE deleted_at IS NULL;

-- 平台侧 OAuth 客户端元数据；协议运行数据由后续迁移中的官方 OAuth2 表承载。
CREATE TABLE ocean_platform.iam_oauth_client (
    id                     UUID PRIMARY KEY,
    platform_id            UUID NOT NULL REFERENCES ocean_platform.iam_platform(id),
    client_id              VARCHAR(128) NOT NULL UNIQUE,
    client_secret_hash     VARCHAR(255),
    client_type            VARCHAR(20) NOT NULL,
    grant_types            VARCHAR(500) NOT NULL,
    scopes                 VARCHAR(1000) NOT NULL,
    access_token_seconds   INTEGER NOT NULL DEFAULT 900,
    refresh_token_seconds  INTEGER NOT NULL DEFAULT 604800,
    pkce_required          BOOLEAN NOT NULL DEFAULT TRUE,
    status                 VARCHAR(20) NOT NULL DEFAULT 'ENABLED',
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_oauth_client_type CHECK (client_type IN ('PUBLIC', 'CONFIDENTIAL')),
    CONSTRAINT ck_oauth_client_status CHECK (status IN ('ENABLED', 'DISABLED')),
    CONSTRAINT ck_oauth_client_secret CHECK (client_type = 'PUBLIC' OR client_secret_hash IS NOT NULL)
);

-- 一个 OAuth 客户端可配置多个合法回调地址。
CREATE TABLE ocean_platform.iam_oauth_redirect_uri (
    id            UUID PRIMARY KEY,
    oauth_client_id UUID NOT NULL REFERENCES ocean_platform.iam_oauth_client(id) ON DELETE CASCADE,
    redirect_uri  VARCHAR(1000) NOT NULL,
    UNIQUE (oauth_client_id, redirect_uri)
);

-- 角色定义：GLOBAL 角色跨平台生效，PLATFORM 角色必须归属于具体平台。
CREATE TABLE ocean_platform.iam_role (
    id           UUID PRIMARY KEY,
    platform_id  UUID REFERENCES ocean_platform.iam_platform(id),
    role_code    VARCHAR(100) NOT NULL,
    role_name    VARCHAR(100) NOT NULL,
    scope_type   VARCHAR(20) NOT NULL,
    status       VARCHAR(20) NOT NULL DEFAULT 'ENABLED',
    description  VARCHAR(500),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_iam_role_scope CHECK (scope_type IN ('GLOBAL', 'PLATFORM')),
    CONSTRAINT ck_iam_role_status CHECK (status IN ('ENABLED', 'DISABLED')),
    CONSTRAINT ck_iam_role_platform CHECK (
        (scope_type = 'GLOBAL' AND platform_id IS NULL)
        OR (scope_type = 'PLATFORM' AND platform_id IS NOT NULL)
    )
);

CREATE UNIQUE INDEX uk_iam_role_scope_code
    ON ocean_platform.iam_role(COALESCE(platform_id, '00000000-0000-0000-0000-000000000000'::uuid), role_code);

-- 细粒度权限定义，permission_code 是业务鉴权使用的稳定编码。
CREATE TABLE ocean_platform.iam_permission (
    id               UUID PRIMARY KEY,
    platform_id      UUID REFERENCES ocean_platform.iam_platform(id),
    permission_code  VARCHAR(150) NOT NULL UNIQUE,
    permission_name  VARCHAR(100) NOT NULL,
    resource         VARCHAR(100) NOT NULL,
    action           VARCHAR(50) NOT NULL,
    status           VARCHAR(20) NOT NULL DEFAULT 'ENABLED',
    description      VARCHAR(500),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_iam_permission_status CHECK (status IN ('ENABLED', 'DISABLED'))
);

-- 用户角色分配，可限定平台并设置生效、失效时间。
CREATE TABLE ocean_platform.iam_user_role (
    user_id      UUID NOT NULL REFERENCES ocean_platform.iam_user(id) ON DELETE CASCADE,
    role_id      UUID NOT NULL REFERENCES ocean_platform.iam_role(id) ON DELETE CASCADE,
    platform_id  UUID REFERENCES ocean_platform.iam_platform(id),
    valid_from   TIMESTAMPTZ,
    valid_until  TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, role_id),
    CONSTRAINT ck_iam_user_role_validity CHECK (valid_until IS NULL OR valid_from IS NULL OR valid_until > valid_from)
);

CREATE INDEX idx_iam_user_role_platform ON ocean_platform.iam_user_role(platform_id, user_id);

-- 角色与权限的多对多关联。
CREATE TABLE ocean_platform.iam_role_permission (
    role_id        UUID NOT NULL REFERENCES ocean_platform.iam_role(id) ON DELETE CASCADE,
    permission_id  UUID NOT NULL REFERENCES ocean_platform.iam_permission(id) ON DELETE CASCADE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (role_id, permission_id)
);

-- 在数据库层校验角色分配范围，避免 GLOBAL/PLATFORM 语义与 platform_id 不一致。
CREATE OR REPLACE FUNCTION ocean_platform.validate_user_role_scope()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    expected_scope VARCHAR(20);
    expected_platform UUID;
BEGIN
    SELECT scope_type, platform_id
      INTO expected_scope, expected_platform
      FROM ocean_platform.iam_role
     WHERE id = NEW.role_id;

    IF expected_scope = 'GLOBAL' AND NEW.platform_id IS NOT NULL THEN
        RAISE EXCEPTION 'GLOBAL role must not have platform_id';
    END IF;
    IF expected_scope = 'PLATFORM' AND NEW.platform_id IS DISTINCT FROM expected_platform THEN
        RAISE EXCEPTION 'PLATFORM role must be assigned in its owning platform';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_validate_user_role_scope
BEFORE INSERT OR UPDATE ON ocean_platform.iam_user_role
FOR EACH ROW EXECUTE FUNCTION ocean_platform.validate_user_role_scope();

-- 登录会话与刷新令牌族，用于设备管理、会话撤销和令牌轮换追踪。
CREATE TABLE ocean_platform.iam_auth_session (
    session_id          UUID PRIMARY KEY,
    user_id             UUID NOT NULL REFERENCES ocean_platform.iam_user(id),
    platform_id         UUID NOT NULL REFERENCES ocean_platform.iam_platform(id),
    oauth_client_id     UUID REFERENCES ocean_platform.iam_oauth_client(id),
    device_id           VARCHAR(200),
    device_name         VARCHAR(200),
    client_ip           VARCHAR(64),
    user_agent          VARCHAR(1000),
    refresh_token_hash  VARCHAR(255),
    token_family_id     UUID,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_active_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at          TIMESTAMPTZ NOT NULL,
    refresh_expires_at  TIMESTAMPTZ,
    revoked_at          TIMESTAMPTZ,
    revoke_reason       VARCHAR(200)
);

CREATE INDEX idx_iam_session_user_active
    ON ocean_platform.iam_auth_session(user_id, expires_at)
    WHERE revoked_at IS NULL;
CREATE INDEX idx_iam_session_family
    ON ocean_platform.iam_auth_session(token_family_id)
    WHERE token_family_id IS NOT NULL;

-- 全局唯一的账号安全策略；固定主键约束保证系统中最多只有一行。
CREATE TABLE ocean_platform.iam_security_policy (
    id                      SMALLINT PRIMARY KEY DEFAULT 1,
    min_password_length     INTEGER NOT NULL DEFAULT 8,
    require_uppercase       BOOLEAN NOT NULL DEFAULT TRUE,
    require_lowercase       BOOLEAN NOT NULL DEFAULT TRUE,
    require_digit           BOOLEAN NOT NULL DEFAULT TRUE,
    require_special         BOOLEAN NOT NULL DEFAULT FALSE,
    max_failed_attempts     INTEGER NOT NULL DEFAULT 5,
    lock_base_minutes       INTEGER NOT NULL DEFAULT 5,
    lock_max_minutes        INTEGER NOT NULL DEFAULT 720,
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_single_security_policy CHECK (id = 1)
);

-- 只追加的审计事件事实表；快照字段确保关联实体变化后仍可还原事件语境。
CREATE TABLE ocean_platform.audit_event (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id            UUID NOT NULL UNIQUE,
    event_category      VARCHAR(30) NOT NULL,
    event_type          VARCHAR(100) NOT NULL,
    platform_id         UUID,
    platform_code       VARCHAR(64),
    user_id             UUID,
    username_snapshot   VARCHAR(64),
    session_id          UUID,
    resource_type       VARCHAR(100),
    resource_id         VARCHAR(200),
    operation_code      VARCHAR(100),
    outcome             VARCHAR(20) NOT NULL,
    failure_reason      VARCHAR(500),
    request_method      VARCHAR(10),
    request_path        VARCHAR(500),
    request_summary     JSONB,
    client_ip           VARCHAR(64),
    user_agent          VARCHAR(1000),
    device_id           VARCHAR(200),
    duration_ms         BIGINT,
    request_id          VARCHAR(64),
    trace_id            VARCHAR(64),
    occurred_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_audit_event_category CHECK (event_category IN ('LOGIN', 'OPERATION', 'SECURITY', 'SYSTEM')),
    CONSTRAINT ck_audit_event_outcome CHECK (outcome IN ('SUCCESS', 'FAILURE', 'UNKNOWN'))
);

CREATE INDEX idx_audit_event_platform_time ON ocean_platform.audit_event(platform_code, occurred_at DESC);
CREATE INDEX idx_audit_event_user_time ON ocean_platform.audit_event(user_id, occurred_at DESC);
CREATE INDEX idx_audit_event_category_time ON ocean_platform.audit_event(event_category, occurred_at DESC);
CREATE INDEX idx_audit_event_trace ON ocean_platform.audit_event(trace_id) WHERE trace_id IS NOT NULL;

COMMENT ON COLUMN ocean_platform.audit_event.request_summary IS
    '仅允许写入已脱敏的业务摘要，禁止包含密码、令牌、Cookie 或其他密钥信息。';

-- 相同指纹的未关闭异常只保留一个处置记录，并累计发生次数。
CREATE TABLE ocean_platform.audit_exception (
    id                   UUID PRIMARY KEY,
    fingerprint          VARCHAR(128) NOT NULL,
    platform_code        VARCHAR(64),
    exception_type       VARCHAR(300) NOT NULL,
    message_summary      VARCHAR(1000),
    stack_trace          TEXT,
    first_occurred_at    TIMESTAMPTZ NOT NULL,
    last_occurred_at     TIMESTAMPTZ NOT NULL,
    occurrence_count     BIGINT NOT NULL DEFAULT 1,
    status               VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    handler_user_id      UUID,
    handle_remark        VARCHAR(1000),
    handled_at           TIMESTAMPTZ,
    last_trace_id        VARCHAR(64),
    CONSTRAINT ck_audit_exception_status CHECK (status IN ('OPEN', 'PROCESSING', 'RESOLVED', 'IGNORED'))
);

CREATE UNIQUE INDEX uk_audit_exception_open_fingerprint
    ON ocean_platform.audit_exception(fingerprint)
    WHERE status IN ('OPEN', 'PROCESSING');

-- 工作台每日指标快照，按日期和平台唯一，用于低成本趋势查询。
CREATE TABLE ocean_platform.wb_metric_daily (
    metric_date             DATE NOT NULL,
    platform_id             UUID NOT NULL REFERENCES ocean_platform.iam_platform(id),
    login_count             BIGINT NOT NULL DEFAULT 0,
    login_success_count     BIGINT NOT NULL DEFAULT 0,
    login_failure_count     BIGINT NOT NULL DEFAULT 0,
    active_user_count       BIGINT NOT NULL DEFAULT 0,
    operation_count         BIGINT NOT NULL DEFAULT 0,
    failed_operation_count  BIGINT NOT NULL DEFAULT 0,
    security_event_count    BIGINT NOT NULL DEFAULT 0,
    exception_count         BIGINT NOT NULL DEFAULT 0,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (metric_date, platform_id)
);

-- 为所有包含 updated_at 的核心表安装统一更新时间触发器。
CREATE TRIGGER trg_iam_platform_updated_at BEFORE UPDATE ON ocean_platform.iam_platform
FOR EACH ROW EXECUTE FUNCTION ocean_platform.set_updated_at();
CREATE TRIGGER trg_iam_user_updated_at BEFORE UPDATE ON ocean_platform.iam_user
FOR EACH ROW EXECUTE FUNCTION ocean_platform.set_updated_at();
CREATE TRIGGER trg_iam_oauth_client_updated_at BEFORE UPDATE ON ocean_platform.iam_oauth_client
FOR EACH ROW EXECUTE FUNCTION ocean_platform.set_updated_at();
CREATE TRIGGER trg_iam_role_updated_at BEFORE UPDATE ON ocean_platform.iam_role
FOR EACH ROW EXECUTE FUNCTION ocean_platform.set_updated_at();
CREATE TRIGGER trg_iam_permission_updated_at BEFORE UPDATE ON ocean_platform.iam_permission
FOR EACH ROW EXECUTE FUNCTION ocean_platform.set_updated_at();
CREATE TRIGGER trg_wb_metric_daily_updated_at BEFORE UPDATE ON ocean_platform.wb_metric_daily
FOR EACH ROW EXECUTE FUNCTION ocean_platform.set_updated_at();
