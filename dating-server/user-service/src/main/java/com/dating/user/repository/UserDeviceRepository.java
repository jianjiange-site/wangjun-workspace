package com.dating.user.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dating.user.model.UserDeviceEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserDeviceRepository extends BaseMapper<UserDeviceEntity> {
}
