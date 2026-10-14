package com.cowork.team.domain.team.service.impl

import com.cowork.team.domain.team.presentation.data.response.IconPresignedUrlResponse
import com.cowork.team.domain.team.service.GenerateIconPresignedUrlService
import com.cowork.team.domain.team.service.TeamIconOwnershipService
import org.springframework.stereotype.Service

@Service
class GenerateIconPresignedUrlServiceImpl(private val ownership: TeamIconOwnershipService) :
    GenerateIconPresignedUrlService {
    override fun execute(userId: Long, contentType: String): IconPresignedUrlResponse =
        ownership.issue(userId, contentType)
}
