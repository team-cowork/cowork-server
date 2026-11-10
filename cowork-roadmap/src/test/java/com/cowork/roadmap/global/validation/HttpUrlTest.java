package com.cowork.roadmap.global.validation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.cowork.roadmap.domain.node.presentation.data.request.CreateNodeReqDto;
import com.cowork.roadmap.domain.node.presentation.data.request.NodeReferenceReqDto;
import com.cowork.roadmap.domain.node.presentation.data.request.UpdateNodeReqDto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

class HttpUrlTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://example.com", "http://example.com/docs?q=1#a", "HTTPS://Example.com"})
    void allowsHttpAndHttps(String url) {
        assertThat(validator.validate(new NodeReferenceReqDto("자료", url))).isEmpty();
        assertThat(validator.validate(new CreateNodeReqDto(null, "제목", null, url, null))).isEmpty();
        assertThat(validator.validate(new UpdateNodeReqDto(null, null, url, null))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"javascript:alert(1)", "JavaScript://example.com/%0aalert(1)", "data:text/html,x",
            "ftp://example.com", "//example.com", "https://", "example.com", " https://example.com"})
    void rejectsOtherSchemes(String url) {
        assertThat(validator.validate(new NodeReferenceReqDto("자료", url))).hasSize(1);
        assertThat(validator.validate(new CreateNodeReqDto(null, "제목", null, url, null))).hasSize(1);
        assertThat(validator.validate(new UpdateNodeReqDto(null, null, url, null))).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " "})
    void allowsBlankSourceUrlToClearIt(String url) {
        assertThat(validator.validate(new CreateNodeReqDto(null, "제목", null, url, null))).isEmpty();
        assertThat(validator.validate(new UpdateNodeReqDto(null, null, url, null))).isEmpty();
    }
}
