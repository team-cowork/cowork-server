package com.cowork.user.messaging.event

import com.cowork.user.domain.Sex
import com.fasterxml.jackson.annotation.JsonAlias

/**
 * authorization 서비스가 회원가입 완료 후 `user.data.sync` 토픽으로 발행하는 이벤트.
 * Account 생성에 필요한 최소 정보를 담는다.
 */
data class UserCreatedEvent(
    val userId: Long,
    val name: String,
    val email: String,
    val sex: Sex,
    val github: String?,
    val description: String?,
    @JsonAlias("stRole", "st_role")
    val studentRole: String?,
    @JsonAlias("stNum", "st_num")
    val studentNumber: String?,
    val major: String?,
    @JsonAlias("spe")
    val specialty: String?,
    val status: String?,
)
