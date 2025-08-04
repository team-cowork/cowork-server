package com.cowork.global.error

import org.springframework.http.HttpStatus

class BusinessException(
    val status: HttpStatus,
    override val message: String,
) : RuntimeException(message)
