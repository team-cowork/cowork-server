package com.cowork.preference.global.config

data class DbConfig(
    val host: String,
    val port: Int,
    val database: String,
    val schema: String,
    val username: String,
    val password: String,
    val poolSize: Int,
)
