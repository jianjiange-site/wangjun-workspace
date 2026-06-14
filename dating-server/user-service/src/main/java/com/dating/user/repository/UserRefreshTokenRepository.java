package com.dating.user.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dating.user.model.UserRefreshTokenEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserRefreshTokenRepository extends BaseMapper<UserRefreshTokenEntity> {
}
