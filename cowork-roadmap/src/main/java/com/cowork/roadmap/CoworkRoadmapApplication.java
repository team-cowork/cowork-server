package com.cowork.roadmap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import team.themoment.sdk.autoconfigure.SdkAutoConfiguration;

// 런타임 쿼리는 R2DBC, 마이그레이션만 Flyway(JDBC DataSource)로 처리한다.
// the-sdk는 ExpectedException만 사용하므로, globalExceptionHandler 등을 등록하는 SdkAutoConfiguration은 제외한다.
@SpringBootApplication(exclude = SdkAutoConfiguration.class)
public class CoworkRoadmapApplication {

    public static void main(String[] args) {
        SpringApplication.run(CoworkRoadmapApplication.class, args);
    }
}
