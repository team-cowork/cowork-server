package com.cowork.channel.domain.meetingNote.presentation.data.request

data class UpdateTemplateSectionRequest(
    val title: String? = null,
    val type: String? = null,
    val placeholder: String? = null,
    val isRequired: Boolean? = null,
)
