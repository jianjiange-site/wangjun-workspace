package com.dating.user.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dating.user.model.UserAuthIdentityEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserAuthIdentityRepository extends BaseMapper<UserAuthIdentityEntity> {
}
