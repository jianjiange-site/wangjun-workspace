package com.dating.user.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dating.user.model.UserSessionEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserSessionRepository extends BaseMapper<UserSessionEntity> {
}
