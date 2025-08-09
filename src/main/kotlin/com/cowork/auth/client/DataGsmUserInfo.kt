package com.cowork.auth.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class DataGsmUserInfo(
    val id: Long,
    val email: String,
    val role: String? = null,
    val isStudent: Boolean = false,
    val student: DataGsmStudent? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class DataGsmStudent(
    val id: Long,
    val name: String,
    val sex: String,
    val grade: Int? = null,
    val classNum: Int? = null,
    val number: Int? = null,
    val major: String? = null,
    val specialty: String? = null,
    val githubId: String? = null,
    val role: String? = null,
    val isLeaveSchool: Boolean = false,
)
