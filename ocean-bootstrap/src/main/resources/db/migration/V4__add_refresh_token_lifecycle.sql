-- 将会话升级为刷新令牌族的权威状态，并迁移可能存在的旧单令牌摘要。
ALTER TABLE ocean_platform.iam_auth_session
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

UPDATE ocean_platform.iam_auth_session
   SET token_family_id = session_id
 WHERE token_family_id IS NULL;

ALTER TABLE ocean_platform.iam_auth_session
    ALTER COLUMN token_family_id SET NOT NULL,
    ADD CONSTRAINT uk_iam_session_family UNIQUE (session_id, token_family_id),
    ADD CONSTRAINT ck_iam_session_version CHECK (version >= 0),
    ADD CONSTRAINT ck_iam_session_expiry CHECK (
        refresh_expires_at IS NULL OR refresh_expires_at <= expires_at
    );

CREATE TABLE ocean_platform.iam_refresh_token (
    token_id               UUID PRIMARY KEY,
    session_id             UUID NOT NULL,
    token_family_id        UUID NOT NULL,
    token_hash             VARCHAR(255) NOT NULL,
    sequence_number        BIGINT NOT NULL,
    status                 VARCHAR(20) NOT NULL DEFAULT 'ISSUED',
    issued_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at             TIMESTAMPTZ NOT NULL,
    used_at                TIMESTAMPTZ,
    revoked_at             TIMESTAMPTZ,
    revoke_reason          VARCHAR(200),
    replaced_by_token_id   UUID,
    version                BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_iam_refresh_token_session_family
        FOREIGN KEY (session_id, token_family_id)
        REFERENCES ocean_platform.iam_auth_session(session_id, token_family_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_iam_refresh_token_replacement
        FOREIGN KEY (replaced_by_token_id)
        REFERENCES ocean_platform.iam_refresh_token(token_id),
    CONSTRAINT uk_iam_refresh_token_hash UNIQUE (token_hash),
    CONSTRAINT uk_iam_refresh_token_sequence UNIQUE (token_family_id, sequence_number),
    CONSTRAINT ck_iam_refresh_token_status CHECK (status IN ('ISSUED', 'USED', 'REVOKED')),
    CONSTRAINT ck_iam_refresh_token_sequence CHECK (sequence_number >= 0),
    CONSTRAINT ck_iam_refresh_token_version CHECK (version >= 0),
    CONSTRAINT ck_iam_refresh_token_expiry CHECK (expires_at > issued_at),
    CONSTRAINT ck_iam_refresh_token_state CHECK (
        (status = 'ISSUED' AND used_at IS NULL AND revoked_at IS NULL)
        OR (status = 'USED' AND used_at IS NOT NULL AND revoked_at IS NULL)
        OR (status = 'REVOKED' AND used_at IS NULL AND revoked_at IS NOT NULL)
    )
);

-- 兼容迁移：若旧版本已经写入摘要，将其作为令牌族的第 0 枚活跃令牌保留。
INSERT INTO ocean_platform.iam_refresh_token (
    token_id, session_id, token_family_id, token_hash, sequence_number,
    status, issued_at, expires_at, revoked_at, revoke_reason
)
SELECT session_id, session_id, token_family_id, refresh_token_hash, 0,
       CASE WHEN revoked_at IS NULL THEN 'ISSUED' ELSE 'REVOKED' END,
       created_at,
       GREATEST(COALESCE(refresh_expires_at, expires_at), created_at + INTERVAL '1 second'),
       revoked_at, revoke_reason
  FROM ocean_platform.iam_auth_session
 WHERE refresh_token_hash IS NOT NULL;

ALTER TABLE ocean_platform.iam_auth_session
    DROP COLUMN refresh_token_hash;

CREATE UNIQUE INDEX uk_iam_refresh_token_active_session
    ON ocean_platform.iam_refresh_token(session_id)
    WHERE status = 'ISSUED';

CREATE INDEX idx_iam_refresh_token_family_status
    ON ocean_platform.iam_refresh_token(token_family_id, status);

CREATE INDEX idx_iam_refresh_token_session_issued
    ON ocean_platform.iam_refresh_token(session_id, issued_at DESC);

-- 数据库层禁止状态倒退或在两个终态之间横跳；应用层事务负责合法转换的附带字段。
CREATE OR REPLACE FUNCTION ocean_platform.validate_refresh_token_transition()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.status IS DISTINCT FROM OLD.status
       AND NOT (OLD.status = 'ISSUED' AND NEW.status IN ('USED', 'REVOKED')) THEN
        RAISE EXCEPTION 'illegal refresh token transition: % -> %', OLD.status, NEW.status;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_validate_refresh_token_transition
BEFORE UPDATE OF status ON ocean_platform.iam_refresh_token
FOR EACH ROW EXECUTE FUNCTION ocean_platform.validate_refresh_token_transition();

COMMENT ON TABLE ocean_platform.iam_refresh_token IS
    '刷新令牌轮换状态；只保存不可逆摘要，不保存令牌原文。';
