package com.cowork.channel.dto

data class CreateThreadRequest(
    val name: String,
    val parentMessageId: String,
)
