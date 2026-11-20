package com.cowork.team.domain.team.service.impl

import com.cowork.team.domain.team.presentation.data.response.IconPresignedUrlResponse
import com.cowork.team.domain.team.service.GenerateIconPresignedUrlService
import com.cowork.team.domain.team.service.TeamIconOwnershipManager
import org.springframework.stereotype.Service

@Service
class GenerateIconPresignedUrlServiceImpl(private val teamIconOwnershipManager: TeamIconOwnershipManager) :
    GenerateIconPresignedUrlService {
    override fun execute(userId: Long, contentType: String): IconPresignedUrlResponse =
        teamIconOwnershipManager.issue(userId, contentType)
}
