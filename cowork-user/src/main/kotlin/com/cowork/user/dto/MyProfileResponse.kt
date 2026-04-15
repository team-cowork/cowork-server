package com.cowork.user.dto

import com.cowork.user.domain.Profile

data class MyProfileResponse(
    val id: Long,
    val name: String,
    val email: String,
    val sex: String,
    val github: String?,
    val accountDescription: String?,
    val stRole: String?,
    val stNum: String?,
    val major: String?,
    val spe: String?,
    val status: String,
    val img: String?,
    val nickname: String?,
    val roles: List<String>,
    val description: String?,
) {
    companion object {
        fun of(profile: Profile, imageUrl: String?): MyProfileResponse =
            MyProfileResponse(
                id = profile.account.id,
                name = profile.account.name,
                email = profile.account.email,
                sex = profile.account.sex.name,
                github = profile.account.github,
                accountDescription = profile.account.description,
                stRole = profile.account.stRole,
                stNum = profile.account.stNum,
                major = profile.account.major,
                spe = profile.account.spe,
                status = profile.account.status,
                img = imageUrl,
                nickname = profile.nickname,
                roles = profile.roles.sorted(),
                description = profile.description ?: profile.account.description,
            )
    }
}
