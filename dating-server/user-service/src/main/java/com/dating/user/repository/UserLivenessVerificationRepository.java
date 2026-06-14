package com.dating.user.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dating.user.model.UserLivenessVerificationEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserLivenessVerificationRepository extends BaseMapper<UserLivenessVerificationEntity> {
}
