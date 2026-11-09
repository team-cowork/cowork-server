package com.cowork.roadmap.global.time;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAccessor;

import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/**
 * 오프셋이 있는 ISO-8601 일시는 그 오프셋대로, 오프셋이 없는 일시는 기존 클라이언트 호환을 위해 Asia/Seoul로 해석한다.
 */
public class SeoulFallbackInstantDeserializer extends ValueDeserializer<Instant> {

    private static final ZoneId LEGACY_CLIENT_ZONE = ZoneId.of("Asia/Seoul");

    @Override
    public Instant deserialize(JsonParser parser, DeserializationContext context) {
        String text = parser.getValueAsString();
        if (text == null) {
            return (Instant) context.handleUnexpectedToken(Instant.class, parser);
        }
        try {
            return parse(text.trim());
        } catch (DateTimeParseException e) {
            throw context.weirdStringException(text, Instant.class, "Expected an ISO-8601 date-time");
        }
    }

    static Instant parse(String text) {
        TemporalAccessor parsed = DateTimeFormatter.ISO_DATE_TIME
                .parseBest(text, OffsetDateTime::from, LocalDateTime::from);
        if (parsed instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime.toInstant();
        }
        return ((LocalDateTime) parsed).atZone(LEGACY_CLIENT_ZONE).toInstant();
    }
}
