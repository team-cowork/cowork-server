package com.cowork.user.dto

import com.cowork.user.domain.UserProfile

data class MyProfileResponse(
    val id: Long,
    val name: String,
    val email: String,
    val sex: String,
    val grade: Byte?,
    val `class`: Byte?,
    val classNum: Byte?,
    val major: String,
    val specialty: String?,
    val githubId: String?,
    val profileImageUrl: String?,
    val role: String,
) {
    companion object {
        fun of(profile: UserProfile, profileImageUrl: String?): MyProfileResponse =
            MyProfileResponse(
                id = profile.id,
                name = profile.name,
                email = profile.email,
                sex = profile.sex,
                grade = profile.grade,
                `class` = profile.`class`,
                classNum = profile.classNum,
                major = profile.major,
                specialty = profile.specialty,
                githubId = profile.githubId,
                profileImageUrl = profileImageUrl,
                role = profile.role,
            )
    }
}
