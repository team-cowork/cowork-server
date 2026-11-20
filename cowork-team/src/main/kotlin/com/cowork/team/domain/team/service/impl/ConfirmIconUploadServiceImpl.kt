package com.cowork.team.domain.team.service.impl

import com.cowork.team.domain.team.presentation.data.response.IconConfirmResponse
import com.cowork.team.domain.team.service.ConfirmIconUploadService
import com.cowork.team.domain.team.service.TeamIconOwnershipManager
import org.springframework.stereotype.Service

@Service
class ConfirmIconUploadServiceImpl(private val teamIconOwnershipManager: TeamIconOwnershipManager) :
    ConfirmIconUploadService {
    override fun execute(userId: Long, objectKey: String): IconConfirmResponse =
        IconConfirmResponse(iconUrl = teamIconOwnershipManager.confirm(userId, objectKey))
}
