package com.cowork.channel.dto

data class UpdateThreadRequest(
    val name: String? = null,
    val isArchived: Boolean? = null,
)
