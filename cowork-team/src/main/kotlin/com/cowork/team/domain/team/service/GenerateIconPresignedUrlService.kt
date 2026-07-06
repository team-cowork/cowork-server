package com.cowork.team.domain.team.service

import com.cowork.team.domain.team.presentation.data.response.IconPresignedUrlResponse

interface GenerateIconPresignedUrlService {
    fun execute(contentType: String): IconPresignedUrlResponse
}
