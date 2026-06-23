package com.cowork.channel.domain.meetingNote.presentation.data.request

data class CreateTemplateSectionRequest(
    val title: String,
    val type: String,
    val placeholder: String? = null,
    val isRequired: Boolean = false,
)
