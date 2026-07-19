package com.cowork.roadmap.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.r2dbc.config.EnableR2dbcAuditing;

/**
 * created_at/updated_at 컬럼은 NOT NULL이며, R2DBC INSERT가 null을 보내면 DB 기본값을 덮어쓴다.
 * 감사(auditing)를 켜서 @CreatedDate/@LastModifiedDate 필드를 저장 직전에 채운다.
 */
@Configuration
@EnableR2dbcAuditing
public class R2dbcConfig {
}
