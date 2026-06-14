package com.dating.user.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dating.user.model.SmsCodeEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SmsCodeRepository extends BaseMapper<SmsCodeEntity> {
}
