package com.cowork.team.domain.team.service.impl

import com.cowork.team.domain.team.presentation.data.response.IconConfirmResponse
import com.cowork.team.domain.team.service.ConfirmIconUploadService
import com.cowork.team.domain.team.service.S3Service
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class ConfirmIconUploadServiceImpl(private val s3Service: S3Service) : ConfirmIconUploadService {

    override fun execute(objectKey: String): IconConfirmResponse {
        val iconUrl = s3Service.confirmObject(objectKey)
        return IconConfirmResponse(iconUrl = iconUrl)
    }
}
