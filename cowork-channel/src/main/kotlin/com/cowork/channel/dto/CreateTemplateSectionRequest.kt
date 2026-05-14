package com.cowork.channel.dto

data class CreateTemplateSectionRequest(
    val title: String,
    val type: String,
    val placeholder: String? = null,
    val isRequired: Boolean = false,
)
