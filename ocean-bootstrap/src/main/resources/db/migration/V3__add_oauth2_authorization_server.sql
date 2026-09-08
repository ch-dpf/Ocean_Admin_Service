-- Spring Security Authorization Server 7.1.1 JDBC 表结构的 PostgreSQL 适配版。
-- 按官方建议，时间字段使用 TIMESTAMPTZ，二进制大字段使用 TEXT。
-- 官方 JDBC 实现查询时不限定 schema，应用配置会把 ocean_platform 设为连接默认 schema。

-- OAuth2/OIDC 协议客户端注册信息，包括认证方式、授权模式和令牌策略。
CREATE TABLE ocean_platform.oauth2_registered_client (
    id                              VARCHAR(100) NOT NULL,
    client_id                       VARCHAR(100) NOT NULL,
    client_id_issued_at             TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    client_secret                   VARCHAR(200),
    client_secret_expires_at        TIMESTAMPTZ,
    client_name                     VARCHAR(200) NOT NULL,
    client_authentication_methods   VARCHAR(1000) NOT NULL,
    authorization_grant_types       VARCHAR(1000) NOT NULL,
    redirect_uris                   VARCHAR(1000),
    post_logout_redirect_uris       VARCHAR(1000),
    scopes                          VARCHAR(1000) NOT NULL,
    client_settings                 VARCHAR(2000) NOT NULL,
    token_settings                  VARCHAR(2000) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_oauth2_registered_client_client_id UNIQUE (client_id)
);

-- 授权事务及其产生的各类令牌；字段需与当前 Spring Security 官方 JDBC 契约保持一致。
CREATE TABLE ocean_platform.oauth2_authorization (
    id                                  VARCHAR(100) NOT NULL,
    registered_client_id                VARCHAR(100) NOT NULL,
    principal_name                      VARCHAR(200) NOT NULL,
    authorization_grant_type            VARCHAR(100) NOT NULL,
    authorized_scopes                   VARCHAR(1000),
    attributes                          TEXT,
    state                               VARCHAR(500),
    authorization_code_value            TEXT,
    authorization_code_issued_at        TIMESTAMPTZ,
    authorization_code_expires_at       TIMESTAMPTZ,
    authorization_code_metadata         TEXT,
    access_token_value                  TEXT,
    access_token_issued_at              TIMESTAMPTZ,
    access_token_expires_at             TIMESTAMPTZ,
    access_token_metadata               TEXT,
    access_token_type                   VARCHAR(100),
    access_token_scopes                 VARCHAR(1000),
    oidc_id_token_value                 TEXT,
    oidc_id_token_issued_at             TIMESTAMPTZ,
    oidc_id_token_expires_at            TIMESTAMPTZ,
    oidc_id_token_metadata              TEXT,
    refresh_token_value                 TEXT,
    refresh_token_issued_at             TIMESTAMPTZ,
    refresh_token_expires_at            TIMESTAMPTZ,
    refresh_token_metadata              TEXT,
    user_code_value                     TEXT,
    user_code_issued_at                 TIMESTAMPTZ,
    user_code_expires_at                TIMESTAMPTZ,
    user_code_metadata                  TEXT,
    device_code_value                   TEXT,
    device_code_issued_at               TIMESTAMPTZ,
    device_code_expires_at              TIMESTAMPTZ,
    device_code_metadata                TEXT,
    PRIMARY KEY (id),
    CONSTRAINT fk_oauth2_authorization_registered_client
        FOREIGN KEY (registered_client_id)
        REFERENCES ocean_platform.oauth2_registered_client(id)
        ON DELETE CASCADE
);

-- 常规检索字段使用 B-tree；长令牌值只做等值查询，因此使用 HASH 部分索引。
CREATE INDEX idx_oauth2_authorization_registered_client
    ON ocean_platform.oauth2_authorization(registered_client_id);
CREATE INDEX idx_oauth2_authorization_principal
    ON ocean_platform.oauth2_authorization(principal_name);
CREATE INDEX idx_oauth2_authorization_state
    ON ocean_platform.oauth2_authorization(state)
    WHERE state IS NOT NULL;
CREATE INDEX idx_oauth2_authorization_code
    ON ocean_platform.oauth2_authorization USING HASH (authorization_code_value)
    WHERE authorization_code_value IS NOT NULL;
CREATE INDEX idx_oauth2_authorization_access_token
    ON ocean_platform.oauth2_authorization USING HASH (access_token_value)
    WHERE access_token_value IS NOT NULL;
CREATE INDEX idx_oauth2_authorization_oidc_id_token
    ON ocean_platform.oauth2_authorization USING HASH (oidc_id_token_value)
    WHERE oidc_id_token_value IS NOT NULL;
CREATE INDEX idx_oauth2_authorization_refresh_token
    ON ocean_platform.oauth2_authorization USING HASH (refresh_token_value)
    WHERE refresh_token_value IS NOT NULL;
CREATE INDEX idx_oauth2_authorization_user_code
    ON ocean_platform.oauth2_authorization USING HASH (user_code_value)
    WHERE user_code_value IS NOT NULL;
CREATE INDEX idx_oauth2_authorization_device_code
    ON ocean_platform.oauth2_authorization USING HASH (device_code_value)
    WHERE device_code_value IS NOT NULL;

-- 记录用户针对某个客户端明确同意的 scope/authority 集合。
CREATE TABLE ocean_platform.oauth2_authorization_consent (
    registered_client_id    VARCHAR(100) NOT NULL,
    principal_name          VARCHAR(200) NOT NULL,
    authorities             VARCHAR(1000) NOT NULL,
    PRIMARY KEY (registered_client_id, principal_name),
    CONSTRAINT fk_oauth2_authorization_consent_registered_client
        FOREIGN KEY (registered_client_id)
        REFERENCES ocean_platform.oauth2_registered_client(id)
        ON DELETE CASCADE
);

-- 关联平台侧客户端元数据与授权服务器的协议客户端记录。
-- 协议记录删除时保留平台元数据，仅解除关联，便于重新注册或审计。
ALTER TABLE ocean_platform.iam_oauth_client
    ADD COLUMN registered_client_id VARCHAR(100),
    ADD CONSTRAINT uk_iam_oauth_client_registered_client UNIQUE (registered_client_id),
    ADD CONSTRAINT fk_iam_oauth_client_registered_client
        FOREIGN KEY (registered_client_id)
        REFERENCES ocean_platform.oauth2_registered_client(id)
        ON DELETE SET NULL;

COMMENT ON COLUMN ocean_platform.iam_oauth_client.registered_client_id IS
    '关联平台侧客户端元数据与 Spring Authorization Server 协议数据。';
