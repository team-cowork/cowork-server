package com.cowork.team.domain.team.service

import com.cowork.team.domain.team.presentation.data.response.IconConfirmResponse

interface ConfirmIconUploadService {
    fun execute(objectKey: String): IconConfirmResponse
}
