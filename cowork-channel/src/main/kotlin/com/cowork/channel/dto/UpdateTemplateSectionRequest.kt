package com.cowork.channel.dto

data class UpdateTemplateSectionRequest(
    val title: String? = null,
    val type: String? = null,
    val placeholder: String? = null,
    val isRequired: Boolean? = null,
)
