package com.cowork.preference.domain

import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

object SettingSchema {

    private val HH_MM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val NOTIFICATION_LEVEL_VALUES = NotificationLevel.entries.map { it.name }.toTypedArray()

    private val schemas: Map<ResourceType, Map<String, SettingDefinition<*>>> = mapOf(
        ResourceType.ACCOUNT to mapOf(
            "status" to choice(
                "사용자 상태",
                "사용자 상태를 ONLINE 또는 DO_NOT_DISTURB로 설정합니다. null이면 수동 상태를 해제합니다.",
                "ONLINE",
                "DO_NOT_DISTURB",
                nullable = true,
            ),
            "status_expires_at" to SettingDefinition(
                String::class,
                "상태 만료 시각",
                "수동 상태를 해제할 ISO-8601 시각입니다. UTC 연도 1~9999를 허용하며 null이면 자동 해제하지 않습니다.",
                nullable = true,
            ) { value, _ -> validateExpiresAt(value) },
            "marketing_email" to SettingDefinition(Boolean::class, "마케팅 이메일 수신", "마케팅 이메일 수신 여부를 설정합니다."),
            "theme" to choice("화면 테마", "화면 테마를 WHITE 또는 BLACK으로 설정합니다.", "WHITE", "BLACK", nullable = true),
            "language" to choice("표시 언어", "표시 언어를 한국어(KO) 또는 영어(EN)로 설정합니다.", "KO", "EN", nullable = true),
            "time_format" to choice("시간 표시 형식", "시간을 12시간제(12H) 또는 24시간제(24H)로 표시합니다.", "12H", "24H", nullable = true),
            "date_format" to choice(
                "날짜 표시 형식", "연도(YYYY), 월(MM), 일(DD)의 표시 순서를 설정합니다.",
                "YYYY_MM_DD", "MM_DD_YYYY", "DD_MM_YYYY", "YYYY_DD_MM", "DD_YYYY_MM", "MM_YYYY_DD",
                nullable = true,
            ),
            "timezone" to
                SettingDefinition(String::class, "시간대", "시간 표시와 방해 금지 예약에 사용할 IANA 시간대입니다. 예: Asia/Seoul.") {
                        value,
                        _,
                    ->
                    if (isZoneId(value)) null else "must be a valid IANA time zone (e.g. Asia/Seoul)"
                },
            "notification_level" to choice(
                "기본 알림 수준",
                "계정의 기본 알림 수준을 ALL, MENTIONS, NONE 중에서 설정합니다. 미설정이면 팀의 기본 알림 수준을 따릅니다.",
                *NOTIFICATION_LEVEL_VALUES,
            ),
            "dnd_schedule" to SettingDefinition(
                JsonObject::class,
                "방해 금지 예약",
                "enabled(boolean), start와 end(HH:mm)가 모두 필요하며 객체 전체를 교체합니다. " +
                    "시각은 timezone 기준이며 end가 start보다 이르면 자정을 넘깁니다.",
                objectKeys = setOf("enabled", "start", "end"),
            ) { value, _ -> validateDndSchedule(value) },
            "message_display" to choice("메시지 표시 방식", "메시지를 여유 있게(COZY) 또는 조밀하게(COMPACT) 표시합니다.", "COZY", "COMPACT"),
            "enter_to_send" to SettingDefinition(Boolean::class, "Enter 키로 전송", "Enter 키를 눌렀을 때 메시지를 전송할지 설정합니다."),
        ),
        ResourceType.TEAM to mapOf(
            "tag_spam_block" to SettingDefinition(
                Boolean::class,
                "태그 스팸 차단",
                "켜면 OWNER와 ADMIN을 제외한 멤버가 메시지 하나에 10명을 넘게 멘션하거나 60초 동안 20명을 넘게 멘션한 메시지를 거부합니다.",
            ),
            "nickname_format_enforced" to SettingDefinition(
                Boolean::class,
                "닉네임 형식 안내",
                "가입할 때 닉네임 형식을 안내하고 팀 닉네임 설정을 유도합니다. 서버는 형식을 판정하지 않으며 켜려면 nickname_format_example이 필요합니다.",
            ) { value, settings ->
                if (value && (settings.getValue("nickname_format_example") as? String).isNullOrBlank()) {
                    "requires nickname_format_example when true"
                } else {
                    null
                }
            },
            "nickname_format_example" to SettingDefinition(
                String::class,
                "닉네임 형식 예시",
                "닉네임 형식 안내에 보여 줄 예시입니다. 공백만 있는 값은 허용하지 않습니다.",
            ) { value, _ -> if (value.isBlank()) "cannot be blank" else null },
            "default_notification_level" to choice(
                "팀 기본 알림 수준",
                "팀 채널의 기본 알림 수준을 ALL, MENTIONS, NONE 중에서 설정합니다.",
                *NOTIFICATION_LEVEL_VALUES,
            ),
        ),
        ResourceType.VOICE_CHANNEL to mapOf(
            "bitrate" to integer(
                "음성 비트레이트",
                "클라이언트가 오디오 송신에 사용할 bps 값입니다. 8000~128000을 허용하며 미설정이면 클라이언트 기본값을 씁니다.",
                8_000..128_000,
            ),
            "max_participants" to integer("최대 참여 인원", "동시 입장 최대 인원입니다. 0~99를 허용하며 0이거나 미설정이면 제한하지 않습니다.", 0..99),
        ),
        ResourceType.TEXT_CHANNEL to mapOf(
            "slowmode_seconds" to integer("슬로우 모드", "사용자별 메시지 전송 간격(초)입니다. 0~21600을 허용하며 0은 해제입니다.", 0..21_600),
        ),
        ResourceType.GITHUB_REPO to mapOf(
            "label_auto_apply" to SettingDefinition(
                Boolean::class,
                "GitHub 라벨 자동 적용",
                "GitHub 저장소의 라벨 자동 적용 여부를 설정합니다.",
                defaultValue = true,
            ),
        ),
        ResourceType.PROJECT to emptyMap(),
    )

    fun definitions(): JsonArray = JsonArray(
        ResourceType.entries.map { type ->
            JsonObject()
                .put("resourceType", type.name)
                .put(
                    "settings",
                    JsonArray(
                        schemas.getValue(type).map { (key, definition) ->
                            definition.metadata(key)
                        },
                    ),
                )
        },
    )

    fun validKeys(type: ResourceType): Set<String> = schemas.getValue(type).keys

    fun resolve(type: ResourceType, settings: JsonObject?): JsonObject =
        applyDefaults(type, filter(type, settings ?: JsonObject()))

    fun filter(type: ResourceType, raw: JsonObject): JsonObject = JsonObject().apply {
        schemas.getValue(type).forEach { (key, definition) ->
            if (raw.containsKey(key)) put(key, definition.filter(raw.getValue(key)))
        }
    }

    fun validate(type: ResourceType, settings: JsonObject): String? {
        schemas.getValue(type).forEach { (key, definition) ->
            if (settings.containsKey(key)) {
                definition.validate(key, settings.getValue(key), settings)?.let { return it }
            }
        }
        return null
    }

    /** 기본값은 조회에만 보충한다. 부분 수정에 넣으면 요청하지 않은 기존 값을 덮어쓸 수 있다. */
    fun applyDefaults(type: ResourceType, settings: JsonObject?): JsonObject =
        (settings?.copy() ?: JsonObject()).apply {
            schemas.getValue(type).forEach { (key, definition) ->
                if (getValue(key) == null && definition.defaultValue != null) put(key, definition.resolve(null))
            }
        }

    private fun choice(title: String, description: String, vararg values: String, nullable: Boolean = false) =
        SettingDefinition(String::class, title, description, nullable = nullable) { value, _ ->
            if (value in values) null else "must be one of ${values.toList()}"
        }

    private fun integer(title: String, description: String, range: IntRange) =
        SettingDefinition(Int::class, title, description) {
                value,
                _,
            ->
            if (value in range) null else "must be an integer between ${range.first} and ${range.last}"
        }

    private fun validateExpiresAt(value: String): String? {
        val parsed = try {
            Instant.parse(value)
        } catch (_: DateTimeParseException) {
            return "must be a valid ISO-8601 datetime (e.g. 2026-04-19T12:00:00Z)"
        }
        // PostgreSQL timestamptz 변환 범위를 벗어나면 만료 쿼리 전체가 실패하므로 UTC 연도 1~9999만 허용한다.
        return if (parsed.atOffset(ZoneOffset.UTC).year !in 1..9999) "year must be between 1 and 9999" else null
    }

    private fun validateDndSchedule(schedule: JsonObject): String? {
        if (schedule.getValue("enabled") !is Boolean) return "enabled must be a boolean"
        for (key in listOf("start", "end")) {
            val time = schedule.getValue(key)
            if (time !is String || !isHourMinute(time)) return "$key must be HH:mm"
        }
        return null
    }

    private fun isZoneId(value: String): Boolean = try {
        ZoneId.of(value)
        true
    } catch (_: DateTimeException) {
        false
    }

    private fun isHourMinute(value: String): Boolean = try {
        LocalTime.parse(value, HH_MM)
        true
    } catch (_: DateTimeParseException) {
        false
    }
}
