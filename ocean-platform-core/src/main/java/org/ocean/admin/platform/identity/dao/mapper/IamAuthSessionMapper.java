package org.ocean.admin.platform.identity.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.ocean.admin.platform.identity.dao.entity.IamAuthSessionEntity;

/** IAM 会话 MyBatis-Plus Mapper。 */
@Mapper
public interface IamAuthSessionMapper extends BaseMapper<IamAuthSessionEntity> {
}
