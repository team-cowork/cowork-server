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
// JDK 26의 공백/Cf 범위를 유지한다. 보충 평면 문자는 JavaScript의 u 플래그 없이도 같은 뜻이 되도록 나열한다.
@Pattern(
    regexp = "[\\s\\S]*(?<![\\uD800-\\uDBFF])(?!(?:[" +
        "\\u0009-\\u000D\\u0020\\u0085\\u00A0\\u00AD" +
        "\\u0600-\\u0605\\u061C\\u06DD\\u070F\\u0890-\\u0891" +
        "\\u08E2\\u115F-\\u1160\\u1680\\u180E\\u2000-\\u200F" +
        "\\u2028-\\u202F\\u205F-\\u2064\\u2066-\\u206F\\u2800\\u3000" +
        "\\u3164\\uFEFF\\uFFA0\\uFFF9-\\uFFFB" +
        "]" +
        "|\ud804\udcbd|\ud804\udccd|\ud80d\udc30|\ud80d\udc31|\ud80d\udc32|\ud80d\udc33|\ud80d\udc34" +
        "|\ud80d\udc35|\ud80d\udc36|\ud80d\udc37|\ud80d\udc38|\ud80d\udc39|\ud80d\udc3a|\ud80d\udc3b" +
        "|\ud80d\udc3c|\ud80d\udc3d|\ud80d\udc3e|\ud80d\udc3f|\ud82f\udca0|\ud82f\udca1|\ud82f\udca2" +
        "|\ud82f\udca3|\ud834\udd73|\ud834\udd74|\ud834\udd75|\ud834\udd76|\ud834\udd77|\ud834\udd78" +
        "|\ud834\udd79|\ud834\udd7a|\udb40\udc01|\udb40\udc20|\udb40\udc21|\udb40\udc22|\udb40\udc23" +
        "|\udb40\udc24|\udb40\udc25|\udb40\udc26|\udb40\udc27|\udb40\udc28|\udb40\udc29|\udb40\udc2a" +
        "|\udb40\udc2b|\udb40\udc2c|\udb40\udc2d|\udb40\udc2e|\udb40\udc2f|\udb40\udc30|\udb40\udc31" +
        "|\udb40\udc32|\udb40\udc33|\udb40\udc34|\udb40\udc35|\udb40\udc36|\udb40\udc37|\udb40\udc38" +
        "|\udb40\udc39|\udb40\udc3a|\udb40\udc3b|\udb40\udc3c|\udb40\udc3d|\udb40\udc3e|\udb40\udc3f" +
        "|\udb40\udc40|\udb40\udc41|\udb40\udc42|\udb40\udc43|\udb40\udc44|\udb40\udc45|\udb40\udc46" +
        "|\udb40\udc47|\udb40\udc48|\udb40\udc49|\udb40\udc4a|\udb40\udc4b|\udb40\udc4c|\udb40\udc4d" +
        "|\udb40\udc4e|\udb40\udc4f|\udb40\udc50|\udb40\udc51|\udb40\udc52|\udb40\udc53|\udb40\udc54" +
        "|\udb40\udc55|\udb40\udc56|\udb40\udc57|\udb40\udc58|\udb40\udc59|\udb40\udc5a|\udb40\udc5b" +
        "|\udb40\udc5c|\udb40\udc5d|\udb40\udc5e|\udb40\udc5f|\udb40\udc60|\udb40\udc61|\udb40\udc62" +
        "|\udb40\udc63|\udb40\udc64|\udb40\udc65|\udb40\udc66|\udb40\udc67|\udb40\udc68|\udb40\udc69" +
        "|\udb40\udc6a|\udb40\udc6b|\udb40\udc6c|\udb40\udc6d|\udb40\udc6e|\udb40\udc6f|\udb40\udc70" +
        "|\udb40\udc71|\udb40\udc72|\udb40\udc73|\udb40\udc74|\udb40\udc75|\udb40\udc76|\udb40\udc77" +
        "|\udb40\udc78|\udb40\udc79|\udb40\udc7a|\udb40\udc7b|\udb40\udc7c|\udb40\udc7d|\udb40\udc7e" +
        "|\udb40\udc7f" +
        "))[\\s\\S]+",
    message = "팀 이름은 공백일 수 없습니다.",
)
@CodePointLength(max = 100, message = "팀 이름은 100자를 초과할 수 없습니다.")
annotation class ValidTeamName(
    val message: String = "팀 이름이 올바르지 않습니다.",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = [],
)
