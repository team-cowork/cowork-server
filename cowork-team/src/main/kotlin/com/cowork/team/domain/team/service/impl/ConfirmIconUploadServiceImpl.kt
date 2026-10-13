package com.cowork.team.domain.team.service.impl

import com.cowork.team.domain.team.presentation.data.response.IconConfirmResponse
import com.cowork.team.domain.team.service.ConfirmIconUploadService
import com.cowork.team.domain.team.service.TeamIconOwnershipService
import org.springframework.stereotype.Service

@Service
class ConfirmIconUploadServiceImpl(private val ownership: TeamIconOwnershipService) : ConfirmIconUploadService {
    override fun execute(userId: Long, objectKey: String): IconConfirmResponse =
        IconConfirmResponse(iconUrl = ownership.confirm(userId, objectKey))
}
