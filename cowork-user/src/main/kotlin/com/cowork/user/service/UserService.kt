package com.cowork.user.service

import com.cowork.user.dto.*
import com.cowork.user.repository.UserProfileRepository
import com.cowork.user.repository.UserProfileSpecification
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException

@Service
@Transactional(readOnly = true)
class UserService(
    private val userProfileRepository: UserProfileRepository,
    private val s3Service: S3Service,
) {

    private fun findProfileOrThrow(userId: Long) =
        userProfileRepository.findById(userId).orElseThrow {
            ExpectedException("사용자를 찾을 수 없습니다. id=$userId", HttpStatus.NOT_FOUND)
        }

    fun getMyProfile(userId: Long): MyProfileResponse {
        val profile = findProfileOrThrow(userId)
        val imageUrl = profile.profileImageKey?.let { s3Service.generateGetPresignedUrl(it) }
        return MyProfileResponse.of(profile, imageUrl)
    }

    @Transactional
    fun updateMyProfile(userId: Long, request: UpdateMyProfileRequest): MyProfileResponse {
        val profile = findProfileOrThrow(userId)
        profile.updateSpecialty(request.specialty)
        val imageUrl = profile.profileImageKey?.let { s3Service.generateGetPresignedUrl(it) }
        return MyProfileResponse.of(profile, imageUrl)
    }

    fun generatePresignedUrl(userId: Long, contentType: String): PresignedUrlResponse {
        s3Service.validateContentType(contentType)
        findProfileOrThrow(userId)
        val objectKey = s3Service.buildObjectKey(userId, contentType)
        val uploadUrl = s3Service.generatePutPresignedUrl(objectKey, contentType)
        return PresignedUrlResponse(uploadUrl = uploadUrl, objectKey = objectKey)
    }

    @Transactional
    fun confirmUpload(userId: Long, objectKey: String) {
        if (!objectKey.startsWith("profiles/$userId/")) {
            throw ExpectedException("유효하지 않은 objectKey입니다.", HttpStatus.BAD_REQUEST)
        }
        val profile = findProfileOrThrow(userId)
        val oldKey = profile.profileImageKey
        profile.updateProfileImageKey(objectKey)   // DB 먼저
        oldKey?.let { s3Service.deleteObject(it) } // S3 나중
    }

    @Transactional
    fun deleteProfileImage(userId: Long) {
        val profile = findProfileOrThrow(userId)
        val oldKey = profile.profileImageKey
        profile.updateProfileImageKey(null)        // DB 먼저
        oldKey?.let { s3Service.deleteObject(it) } // S3 나중
    }

    fun getUserProfile(userId: Long): UserProfileResponse {
        val profile = findProfileOrThrow(userId)
        val imageUrl = profile.profileImageKey?.let { s3Service.generateGetPresignedUrl(it) }
        return UserProfileResponse.of(profile, imageUrl)
    }

    fun searchUsers(
        name: String?,
        major: String?,
        grade: Byte?,
        cls: Byte?,
        role: String?,
        pageable: Pageable,
    ): Page<UserProfileResponse> {
        val spec = UserProfileSpecification.build(name, major, grade, cls, role)
        return userProfileRepository.findAll(spec, pageable).map { profile ->
            val imageUrl = profile.profileImageKey?.let { s3Service.generateGetPresignedUrl(it) }
            UserProfileResponse.of(profile, imageUrl)
        }
    }
}
