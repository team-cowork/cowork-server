package com.cowork.user.dto

import com.cowork.user.domain.User

data class UserResponse(
    val id: Long,
    val name: String,
    val nickname: String?,
    val email: String,
    val sex: String,
    val grade: Int?,
    val classNum: Int?,
    val studentNumber: Int?,
    val major: String?,
    val studentRole: String?,
    val githubId: String?,
    val description: String?,
    val specialty: String?,
    val profileImageUrl: String?,
) {
    companion object {
        fun of(user: User, profileImageUrl: String?) = UserResponse(
            id = user.id,
            name = user.name,
            nickname = user.nickname,
            email = user.email,
            sex = user.sex,
            grade = user.grade,
            classNum = user.classNum,
            studentNumber = user.studentNumber,
            major = user.major,
            studentRole = user.studentRole,
            githubId = user.githubId,
            description = user.description,
            specialty = user.specialty,
            profileImageUrl = profileImageUrl,
        )
    }
}
