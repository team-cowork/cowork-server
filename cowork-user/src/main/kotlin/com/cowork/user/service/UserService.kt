package com.cowork.user.service

import com.cowork.user.domain.UserProfile
import com.cowork.user.dto.*
import com.cowork.user.repository.UserProfileRepository
import com.cowork.user.repository.UserProfileSpecification
import org.springframework.data.repository.findByIdOrNull
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
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
        profile.updateProfileImageKey(objectKey)
        oldKey?.let { key ->
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCommit() = s3Service.deleteObject(key)
            })
        }
    }

    @Transactional
    fun deleteProfileImage(userId: Long) {
        val profile = findProfileOrThrow(userId)
        val oldKey = profile.profileImageKey
        profile.updateProfileImageKey(null)
        oldKey?.let { key ->
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCommit() = s3Service.deleteObject(key)
            })
        }
    }

    fun getUserProfile(userId: Long): UserProfileResponse {
        val profile = findProfileOrThrow(userId)
        val imageUrl = profile.profileImageKey?.let { s3Service.generateGetPresignedUrl(it) }
        return UserProfileResponse.of(profile, imageUrl)
    }

    @Transactional
    fun upsertUser(request: UpsertUserRequest): UserProfileResponse {
        val profile = userProfileRepository.findByIdOrNull(request.userId)?.also {
            it.updateFromSync(
                name = request.name,
                email = request.email,
                sex = request.sex,
                grade = request.grade,
                `class` = request.`class`,
                classNum = request.classNum,
                major = request.major,
                role = request.role,
                githubId = request.githubId,
            )
        } ?: userProfileRepository.save(
            UserProfile(
                id = request.userId,
                name = request.name,
                email = request.email,
                sex = request.sex,
                grade = request.grade,
                `class` = request.`class`,
                classNum = request.classNum,
                major = request.major,
                role = request.role,
                githubId = request.githubId,
                specialty = null,
                profileImageKey = null,
            )
        )
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
