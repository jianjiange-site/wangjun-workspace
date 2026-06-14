package com.dating.user.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dating.user.model.TagEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TagRepository extends BaseMapper<TagEntity> {
}
