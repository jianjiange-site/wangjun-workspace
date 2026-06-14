package com.dating.user.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dating.user.model.UserTagEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserTagRepository extends BaseMapper<UserTagEntity> {
}
