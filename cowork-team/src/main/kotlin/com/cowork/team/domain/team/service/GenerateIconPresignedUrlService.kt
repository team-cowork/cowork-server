package com.cowork.team.domain.team.service

import com.cowork.team.domain.team.presentation.data.response.IconPresignedUrlResponse

interface GenerateIconPresignedUrlService {
    fun generateIconPresignedUrl(contentType: String): IconPresignedUrlResponse
}
