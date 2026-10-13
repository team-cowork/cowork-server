package com.cowork.team.domain.team.service

import com.cowork.team.domain.team.entity.Team
import com.cowork.team.domain.team.entity.TeamIconUpload
import com.cowork.team.domain.team.presentation.data.response.IconPresignedUrlResponse
import com.cowork.team.domain.team.repository.TeamIconUploadRepository
import com.cowork.team.global.support.afterCommit
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException

@Service
class TeamIconOwnershipService(private val uploads: TeamIconUploadRepository, private val storage: S3Service) {
    @Transactional
    fun issue(userId: Long, contentType: String): IconPresignedUrlResponse {
        storage.validateContentType(contentType)
        val objectKey = storage.buildObjectKey(contentType)
        val uploadKey = "team-icon-uploads/$userId/${objectKey.substringAfterLast('/')}"
        val uploadUrl = storage.generatePutPresignedUrl(uploadKey, contentType)
        uploads.save(TeamIconUpload(objectKey, uploadKey, userId, contentType))
        return IconPresignedUrlResponse(uploadUrl = uploadUrl, objectKey = uploadKey)
    }

    @Transactional
    fun confirm(userId: Long, uploadKey: String): String {
        val upload = uploads.findByUploadKeyForUpdate(uploadKey)
            ?: throw ExpectedException("발급된 아이콘 업로드를 찾을 수 없습니다.", HttpStatus.NOT_FOUND)
        if (upload.uploadedBy != userId || upload.retired) {
            throw ExpectedException("아이콘 업로드를 확인할 권한이 없습니다.", HttpStatus.FORBIDDEN)
        }
        if (!upload.confirmed) {
            storage.finalizeUpload(upload.uploadKey, upload.objectKey, upload.contentType)
            upload.confirmed = true
        }
        return storage.buildPublicUrl(upload.objectKey)
    }

    /** 호출 서비스가 팀 변경 권한을 검사하고 팀 행을 잠근 뒤 호출한다. */
    @Transactional
    fun replace(userId: Long, team: Team, iconUrl: String) {
        if (team.iconUrl == iconUrl) return
        val key = storage.extractObjectKey(iconUrl)
        val upload = uploads.findByObjectKeyForUpdate(key)
            ?: throw ExpectedException("확인된 아이콘 업로드가 필요합니다.", HttpStatus.BAD_REQUEST)
        if (!upload.confirmed || upload.retired) {
            throw ExpectedException("사용할 수 없는 아이콘입니다.", HttpStatus.BAD_REQUEST)
        }
        if (upload.teamId != null && upload.teamId != team.id) {
            throw ExpectedException("다른 팀의 아이콘을 사용할 수 없습니다.", HttpStatus.FORBIDDEN)
        }
        if (upload.teamId == null && upload.uploadedBy != userId) {
            throw ExpectedException("직접 업로드한 아이콘만 연결할 수 있습니다.", HttpStatus.FORBIDDEN)
        }
        upload.teamId = team.id
        val previous = team.iconUrl
        team.iconUrl = iconUrl
        previous?.let { retireOwnedObject(team.id, it) }
    }

    @Transactional
    fun clear(team: Team) {
        val previous = team.iconUrl ?: return
        team.iconUrl = null
        retireOwnedObject(team.id, previous)
    }

    private fun retireOwnedObject(teamId: Long, iconUrl: String) {
        // 기존 URL은 소유권 기록을 만들지 않는다. 소유권이 증명된 객체만 삭제한다.
        val key = storage.managedObjectKeyOrNull(iconUrl) ?: return
        val upload = uploads.findByObjectKeyForUpdate(key) ?: return
        if (upload.teamId != teamId || !upload.confirmed || upload.retired) return
        upload.retired = true
        afterCommit { storage.deleteObject(key) }
    }
}
