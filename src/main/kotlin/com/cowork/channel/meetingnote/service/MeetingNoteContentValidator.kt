package com.cowork.channel.meetingnote.service

import com.cowork.channel.meetingnote.domain.NoteSection
import com.cowork.channel.meetingnote.domain.SectionType
import com.cowork.channel.meetingnote.domain.TemplateSection
import com.cowork.global.error.BusinessException
import org.springframework.http.HttpStatus
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeParseException

/**
 * 템플릿 섹션 정의에 맞게 회의록 입력값을 검증하고 저장할 형태로 변환한다.
 */
object MeetingNoteContentValidator {

    private const val MAX_TEXT_LENGTH = 20_000

    fun validate(sections: List<TemplateSection>, values: Map<Long, Any?>, teamMemberIds: Set<Long>): List<NoteSection> {
        val unknown = values.keys - sections.map { it.id }.toSet()
        if (unknown.isNotEmpty()) {
            throw invalid("템플릿에 없는 섹션이 포함되어 있습니다.")
        }
        return sections.map { section ->
            val value = values[section.id]?.let { normalize(section, it, teamMemberIds) }
            if (section.required && isEmpty(value)) {
                throw invalid("필수 섹션 '${section.title}'을 입력해 주세요.")
            }
            NoteSection(section.id, section.title, section.type, value)
        }
    }

    private fun normalize(section: TemplateSection, value: Any, teamMemberIds: Set<Long>): Any? = when (section.type) {
        SectionType.TEXT, SectionType.MARKDOWN -> {
            val text = value as? String ?: throw invalid("'${section.title}'은 문자열이어야 합니다.")
            if (text.length > MAX_TEXT_LENGTH) throw invalid("'${section.title}'의 길이가 너무 깁니다.")
            text
        }
        SectionType.DATE -> parse(section) { LocalDate.parse(value as String).toString() }
        SectionType.DATETIME -> parse(section) { LocalDateTime.parse(value as String).toString() }
        SectionType.USER_LIST -> {
            val ids = (value as? List<*>)?.map { (it as? Number)?.toLong() ?: throw invalid("'${section.title}'은 사용자 ID 목록이어야 합니다.") }
                ?: throw invalid("'${section.title}'은 사용자 ID 목록이어야 합니다.")
            if (!teamMemberIds.containsAll(ids)) {
                throw invalid("'${section.title}'에는 팀 멤버만 지정할 수 있습니다.")
            }
            ids.distinct()
        }
    }

    private fun parse(section: TemplateSection, block: () -> String): String = try {
        block()
    } catch (e: DateTimeParseException) {
        throw invalid("'${section.title}'의 날짜 형식이 올바르지 않습니다.")
    } catch (e: ClassCastException) {
        throw invalid("'${section.title}'의 날짜 형식이 올바르지 않습니다.")
    }

    private fun isEmpty(value: Any?): Boolean = when (value) {
        null -> true
        is String -> value.isBlank()
        is Collection<*> -> value.isEmpty()
        else -> false
    }

    private fun invalid(message: String) = BusinessException(HttpStatus.BAD_REQUEST, message)
}
