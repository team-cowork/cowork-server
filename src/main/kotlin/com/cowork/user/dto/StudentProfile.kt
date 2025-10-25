package com.cowork.user.dto

data class StudentProfile(
    val dataGsmId: Long,
    val name: String,
    val email: String,
    val sex: String,
    val grade: Int?,
    val classNum: Int?,
    val studentNumber: Int?,
    val major: String?,
    val studentRole: String?,
    val githubId: String?,
)
