package com.cowork.roadmap.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import team.themoment.sdk.autoconfigure.SdkAutoConfiguration;
import team.themoment.sdk.exception.GlobalExceptionHandler;
import team.themoment.sdk.logging.LoggingFilter;
import team.themoment.sdk.response.ApiResponseWrapper;
import team.themoment.sdk.swagger.SwaggerConfig;

class SdkAutoConfigurationTest {

    // application.yml의 sdk 설정을 그대로 읽어, the-sdk가 기본으로 켜는 기능 중 요청 로깅만 등록되는지 확인한다.
    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withSystemProperties("APP_CONFIG_PROFILE=prod")
            .withConfiguration(AutoConfigurations.of(SdkAutoConfiguration.class));

    @Test
    void registersOnlyLoggingFilter() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(LoggingFilter.class);
            assertThat(context).doesNotHaveBean(ApiResponseWrapper.class);
            assertThat(context).doesNotHaveBean(GlobalExceptionHandler.class);
            assertThat(context).doesNotHaveBean(SwaggerConfig.class);
        });
    }
}
