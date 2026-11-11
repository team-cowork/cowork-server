package com.cowork.roadmap.domain.node.service.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;

import team.themoment.sdk.exception.ExpectedException;

@DisplayName("NodeContentLinkPolicy 클래스의")
class NodeContentLinkPolicyTest {

    @Nested
    @DisplayName("validate 메서드는")
    class Describe_validate {

        @Nested
        @DisplayName("본문에 위험한 스킴의 링크가 있으면")
        class Context_with_blocked_link {

            static Stream<String> blockedContents() {
                return Stream.of("[x](javascript:alert(1))",
                        "[x](data:text/html,<script>alert(1)</script>)",
                        "[x](vbscript:msgbox(1))",
                        "![x](data:image/svg+xml;base64,AAAA)",
                        "앞 문장\n\n[x](JavaScript:alert(1)) 뒤 문장",
                        "[x](  javascript:alert(1))",
                        "[x](\njavascript:alert(1))",
                        "[x](java\tscript:alert(1))",
                        "[x](java\nscript:alert(1))",
                        "[x](\u0001javascript:alert(1))",
                        "[x](javascript :alert(1))",
                        "[x](<javascript:alert(1)>)",
                        "[x]( <java script:alert(1)>)",
                        "[x](javascript&#58;alert(1))",
                        "[x](javascript&#x3A;alert(1))",
                        "[x](javascript&colon;alert(1))",
                        "[x](&#106;avascript:alert(1))",
                        "[x](&#X6A;avascript:alert(1))",
                        "[x](&#0000106;avascript:alert(1))",
                        "[x](&#106avascript:alert(1))",
                        "[x](java&Tab;script:alert(1))",
                        "[x](java&NewLine;script:alert(1))",
                        "[x](java&#9;script:alert(1))",
                        "[x](javascript\\:alert(1))",
                        "[a [b] c](javascript:alert(1))",
                        "[x][ref]\n\n[ref]: javascript:alert(1)",
                        "[x][ref]\n\n[ref]:\n  <vbscript:msgbox(1)>",
                        "<javascript:alert(1)>",
                        "<DATA:text/html,x>");
            }

            @ParameterizedTest
            @MethodSource("blockedContents")
            @DisplayName("BAD_REQUEST 예외를 던진다")
            void it_fails_with_bad_request(String content) {
                // when
                ExpectedException error = assertThrows(ExpectedException.class,
                        () -> NodeContentLinkPolicy.validate(content));

                // then
                assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            }
        }

        @Nested
        @DisplayName("본문에 위험한 스킴의 링크가 없으면")
        class Context_without_blocked_link {

            static Stream<String> allowedContents() {
                return Stream.of("",
                        "일반 문장입니다.",
                        "[문서](https://example.com/javascript:void)",
                        "[문서](/docs/data:1)",
                        "[메일](mailto:someone@example.com)",
                        "[x](javascripts:1)",
                        "[x](java-script:alert(1))",
                        "<https://example.com>",
                        "javascript: 스킴은 링크로 쓰면 안 됩니다.",
                        "설정 예시는 data: 필드를 참고합니다 (javascript: 아님).",
                        "`const data: string = 'x'`",
                        "[x]\\(javascript:alert(1))",
                        "[x](javascript");
            }

            @ParameterizedTest
            @MethodSource("allowedContents")
            @DisplayName("예외를 던지지 않는다")
            void it_passes(String content) {
                assertThatCode(() -> NodeContentLinkPolicy.validate(content)).doesNotThrowAnyException();
            }
        }

        @Nested
        @DisplayName("본문이 null이면")
        class Context_with_null_content {

            @Test
            @DisplayName("예외를 던지지 않는다")
            void it_passes() {
                assertThatCode(() -> NodeContentLinkPolicy.validate(null)).doesNotThrowAnyException();
            }
        }
    }
}
