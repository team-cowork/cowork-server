package com.cowork.project.domain.project.service

import org.springframework.data.domain.Sort
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException

/** 엔티티 속성 이름이 외부 계약으로 굳지 않도록 프로젝트 목록 조회의 정렬 필드를 허용 목록으로 제한한다. */
object ProjectListSortPolicy {

    private val ALLOWED_PROPERTIES = setOf("position", "id", "name", "createdAt", "updatedAt")

    fun requireAllowed(sort: Sort) {
        if (sort.any { it.property !in ALLOWED_PROPERTIES }) {
            throw ExpectedException("지원하지 않는 정렬 기준입니다.", HttpStatus.BAD_REQUEST)
        }
    }
}
