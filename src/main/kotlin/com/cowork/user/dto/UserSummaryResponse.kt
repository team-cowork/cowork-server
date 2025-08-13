package com.cowork.user.dto

import com.cowork.user.domain.User

data class UserSummaryResponse(
    val id: Long,
    val name: String,
    val nickname: String?,
    val grade: Int?,
    val classNum: Int?,
    val major: String?,
    val profileImageUrl: String?,
) {
    companion object {
        fun of(user: User, profileImageUrl: String?) = UserSummaryResponse(
            id = user.id,
            name = user.name,
            nickname = user.nickname,
            grade = user.grade,
            classNum = user.classNum,
            major = user.major,
            profileImageUrl = profileImageUrl,
        )
    }
}
