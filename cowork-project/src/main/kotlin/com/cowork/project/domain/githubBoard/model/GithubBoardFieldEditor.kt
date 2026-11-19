package com.cowork.project.domain.githubBoard.model

import com.cowork.project.domain.githubBoard.event.GithubProjectField
import com.cowork.project.domain.githubBoard.presentation.data.request.SetGithubBoardFieldReqDto
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException
import java.time.LocalDate
import java.time.format.DateTimeParseException

object GithubBoardFieldEditor {
    private val supportedTypes = setOf("TEXT", "NUMBER", "DATE", "SINGLE_SELECT", "ITERATION")

    fun requireEditable(board: GithubBoard, fieldId: String): GithubProjectField {
        val field = board.field(fieldId)
            ?: throw ExpectedException("보드에서 필드를 찾을 수 없습니다.", HttpStatus.NOT_FOUND)
        if (field.dataType !in supportedTypes) {
            throw ExpectedException("편집할 수 없는 필드입니다.", HttpStatus.BAD_REQUEST)
        }
        return field
    }

    fun value(field: GithubProjectField, request: SetGithubBoardFieldReqDto): Map<String, Any> {
        val values = listOfNotNull(
            request.text?.let { "text" to it },
            request.number?.let { "number" to it },
            request.date?.let { "date" to it },
            request.singleSelectOptionId?.let { "singleSelectOptionId" to it },
            request.iterationId?.let { "iterationId" to it },
        )
        val valid = values.size == 1 && when (field.dataType) {
            "TEXT" -> request.text != null
            "NUMBER" -> request.number?.isFinite() == true
            "DATE" -> request.date?.let(::isDate) == true
            "SINGLE_SELECT" -> field.options.any { it.id == request.singleSelectOptionId }
            "ITERATION" -> field.iterations.any { it.id == request.iterationId }
            else -> false
        }
        if (!valid) {
            throw ExpectedException("필드 타입에 맞는 값 하나를 지정해 주십시오.", HttpStatus.BAD_REQUEST)
        }
        return values.toMap()
    }

    private fun isDate(value: String): Boolean {
        if (!Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(value)) return false
        return try {
            LocalDate.parse(value).year in 1..9999
        } catch (_: DateTimeParseException) {
            false
        }
    }
}
