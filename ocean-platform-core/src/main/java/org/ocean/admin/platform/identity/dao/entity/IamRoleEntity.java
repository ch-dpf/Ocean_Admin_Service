package org.ocean.admin.platform.identity.dao.entity;

import java.util.UUID;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

/** IAM 角色持久化模型。 */
@TableName(value = "ocean_platform.iam_role", autoResultMap = true)
public class IamRoleEntity {
    @TableId
    private UUID id;
    @TableField("platform_id")
    private UUID platformId;
    private String roleCode;
    private String roleName;
    private String scopeType;
    private String status;
    private String description;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getPlatformId() { return platformId; }
    public void setPlatformId(UUID platformId) { this.platformId = platformId; }
    public String getRoleCode() { return roleCode; }
    public void setRoleCode(String roleCode) { this.roleCode = roleCode; }
    public String getRoleName() { return roleName; }
    public void setRoleName(String roleName) { this.roleName = roleName; }
    public String getScopeType() { return scopeType; }
    public void setScopeType(String scopeType) { this.scopeType = scopeType; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}
