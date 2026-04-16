package com.cowork.user.dto

import com.cowork.user.domain.Profile

data class UserProfileResponse(
    val id: Long,
    val name: String,
    val email: String,
    val sex: String,
    val github: String?,
    val accountDescription: String?,
    val studentRole: String?,
    val studentNumber: String?,
    val major: String?,
    val specialty: String?,
    val status: String,
    val profileImageUrl: String?,
    val nickname: String?,
    val roles: List<String>,
    val description: String?,
) {
    companion object {
        fun of(profile: Profile, imageUrl: String?): UserProfileResponse =
            UserProfileResponse(
                id = profile.account.id,
                name = profile.account.name,
                email = profile.account.email,
                sex = profile.account.sex.name,
                github = profile.account.github,
                accountDescription = profile.account.description,
                studentRole = profile.account.studentRole,
                studentNumber = profile.account.studentNumber,
                major = profile.account.major,
                specialty = profile.account.specialty,
                status = profile.account.status,
                profileImageUrl = imageUrl,
                nickname = profile.nickname,
                roles = profile.roles.sorted(),
                description = profile.description ?: profile.account.description,
            )
    }
}
