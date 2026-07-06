package com.cowork.team.domain.team.service.impl

import com.cowork.team.domain.team.presentation.data.response.IconPresignedUrlResponse
import com.cowork.team.domain.team.service.GenerateIconPresignedUrlService
import com.cowork.team.domain.team.service.S3Service
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class GenerateIconPresignedUrlServiceImpl(private val s3Service: S3Service) : GenerateIconPresignedUrlService {

    override fun execute(contentType: String): IconPresignedUrlResponse {
        s3Service.validateContentType(contentType)
        val objectKey = s3Service.buildObjectKey(contentType)
        val uploadUrl = s3Service.generatePutPresignedUrl(objectKey, contentType)
        return IconPresignedUrlResponse(uploadUrl = uploadUrl, objectKey = objectKey)
    }
}
