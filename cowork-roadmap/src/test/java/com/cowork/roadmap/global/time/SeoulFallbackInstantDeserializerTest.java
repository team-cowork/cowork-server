package com.cowork.roadmap.global.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.cowork.roadmap.domain.assignment.presentation.data.request.CreateAssignmentReqDto;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("SeoulFallbackInstantDeserializer 클래스의")
class SeoulFallbackInstantDeserializerTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    private Instant readDueDate(String dueDateJson) {
        String body = """
                {"roadmapId":1,"scope":"TEAM","teamId":2,"assigneeUserId":3,"dueDate":%s}
                """.formatted(dueDateJson);
        return mapper.readValue(body, CreateAssignmentReqDto.class).dueDate();
    }

    @Nested
    @DisplayName("deserialize 메서드는")
    class Describe_deserialize {

        @ParameterizedTest
        @CsvSource({"2025-03-02T00:00:00Z, 2025-03-02T00:00:00Z", "2025-03-02T09:00:00+09:00, 2025-03-02T00:00:00Z",
                "2025-03-02T00:00:00.123-05:00, 2025-03-02T05:00:00.123Z"})
        @DisplayName("오프셋이 있으면 그 오프셋 기준 시각으로 해석한다")
        void it_uses_given_offset(String input, String expected) {
            assertThat(readDueDate("\"" + input + "\"")).isEqualTo(Instant.parse(expected));
        }

        @Test
        @DisplayName("오프셋이 없으면 Asia/Seoul 기준 시각으로 해석한다")
        void it_falls_back_to_seoul() {
            assertThat(readDueDate("\"2025-03-02T09:00:00\"")).isEqualTo(Instant.parse("2025-03-02T00:00:00Z"));
        }

        @Test
        @DisplayName("값이 null이면 null을 유지한다")
        void it_keeps_null() {
            assertThat(readDueDate("null")).isNull();
        }

        @Test
        @DisplayName("일시 형식이 아니면 역직렬화 예외를 던진다")
        void it_rejects_invalid_text() {
            assertThatThrownBy(() -> readDueDate("\"2025-03-02\"")).isInstanceOf(JacksonException.class);
        }
    }
}
