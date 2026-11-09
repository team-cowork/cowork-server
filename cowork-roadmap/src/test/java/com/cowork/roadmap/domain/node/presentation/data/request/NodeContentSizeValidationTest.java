package com.cowork.roadmap.domain.node.presentation.data.request;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

@DisplayName("노드 본문 크기 검증은")
class NodeContentSizeValidationTest {

    private static final int MAX_CONTENT_LENGTH = 100_000;

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        validatorFactory.close();
    }

    @Nested
    @DisplayName("CreateNodeReqDto에서")
    class Describe_create {

        @Test
        @DisplayName("상한 길이의 본문을 허용한다")
        void it_accepts_content_at_limit() {
            CreateNodeReqDto request = createRequest("a".repeat(MAX_CONTENT_LENGTH));

            assertThat(validator.validate(request)).isEmpty();
        }

        @Test
        @DisplayName("본문이 없어도 허용한다")
        void it_accepts_null_content() {
            assertThat(validator.validate(createRequest(null))).isEmpty();
        }

        @Test
        @DisplayName("상한을 넘는 본문을 거부한다")
        void it_rejects_content_over_limit() {
            CreateNodeReqDto request = createRequest("a".repeat(MAX_CONTENT_LENGTH + 1));

            assertOnlyContentViolation(validator.validate(request));
        }

        private CreateNodeReqDto createRequest(String content) {
            return new CreateNodeReqDto(null, "제목", content, null, null);
        }
    }

    @Nested
    @DisplayName("UpdateNodeReqDto에서")
    class Describe_update {

        @Test
        @DisplayName("상한 길이의 본문을 허용한다")
        void it_accepts_content_at_limit() {
            UpdateNodeReqDto request = updateRequest("a".repeat(MAX_CONTENT_LENGTH));

            assertThat(validator.validate(request)).isEmpty();
        }

        @Test
        @DisplayName("본문을 변경하지 않는 요청을 허용한다")
        void it_accepts_null_content() {
            assertThat(validator.validate(updateRequest(null))).isEmpty();
        }

        @Test
        @DisplayName("상한을 넘는 본문을 거부한다")
        void it_rejects_content_over_limit() {
            UpdateNodeReqDto request = updateRequest("a".repeat(MAX_CONTENT_LENGTH + 1));

            assertOnlyContentViolation(validator.validate(request));
        }

        private UpdateNodeReqDto updateRequest(String content) {
            return new UpdateNodeReqDto(null, content, null, null);
        }
    }

    private static <T> void assertOnlyContentViolation(Set<ConstraintViolation<T>> violations) {
        assertThat(violations).singleElement()
                .satisfies(violation -> assertThat(violation.getPropertyPath().toString()).isEqualTo("content"));
    }
}
