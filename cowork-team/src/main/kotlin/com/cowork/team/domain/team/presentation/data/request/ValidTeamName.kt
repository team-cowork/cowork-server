package com.cowork.team.domain.team.presentation.data.request

import jakarta.validation.Constraint
import jakarta.validation.Payload
import jakarta.validation.constraints.Pattern
import org.hibernate.validator.constraints.CodePointLength
import kotlin.reflect.KClass

/** `null`은 통과시키므로 생성과 부분 수정에 함께 사용한다. 길이는 DB `VARCHAR`와 같은 문자 단위로 센다. */
@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
@Constraint(validatedBy = [])
@Pattern(regexp = "(?sU).*\\S.*", message = "팀 이름은 공백일 수 없습니다.")
@CodePointLength(max = 100, message = "팀 이름은 100자를 초과할 수 없습니다.")
annotation class ValidTeamName(
    val message: String = "팀 이름이 올바르지 않습니다.",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = [],
)
