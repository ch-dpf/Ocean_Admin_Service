package org.ocean.admin.platform.identity.dao.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;

/** 用户角色分配关系。 */
@TableName(value = "ocean_platform.iam_user_role", autoResultMap = true)
public class IamUserRoleEntity {
    @TableField("user_id")
    private UUID userId;
    @TableField("role_id")
    private UUID roleId;
    @TableField("platform_id")
    private UUID platformId;
    private OffsetDateTime validFrom;
    private OffsetDateTime validUntil;

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public UUID getRoleId() { return roleId; }
    public void setRoleId(UUID roleId) { this.roleId = roleId; }
    public UUID getPlatformId() { return platformId; }
    public void setPlatformId(UUID platformId) { this.platformId = platformId; }
    public OffsetDateTime getValidFrom() { return validFrom; }
    public void setValidFrom(OffsetDateTime validFrom) { this.validFrom = validFrom; }
    public OffsetDateTime getValidUntil() { return validUntil; }
    public void setValidUntil(OffsetDateTime validUntil) { this.validUntil = validUntil; }
}
