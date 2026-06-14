package com.dating.user.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@MapperScan("com.dating.user.repository")
public class MybatisPlusConfig {
}
