package com.cowork.user.service

import com.cowork.global.error.BusinessException
import com.cowork.global.storage.ImageUploadPolicy
import com.cowork.global.storage.PresignedUploadRequest
import com.cowork.global.storage.PresignedUploadResponse
import com.cowork.global.storage.StorageService
import com.cowork.user.domain.User
import com.cowork.user.dto.StudentProfile
import com.cowork.user.dto.UpdateProfileRequest
import com.cowork.user.dto.UserResponse
import com.cowork.user.dto.UserSummaryResponse
import com.cowork.user.repository.UserRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class UserService(
    private val userRepository: UserRepository,
    private val storageService: StorageService,
) {

    @Transactional
    fun upsertStudent(profile: StudentProfile): User {
        val user = userRepository.findByDataGsmId(profile.dataGsmId)
            ?: return userRepository.save(
                User(
                    dataGsmId = profile.dataGsmId,
                    name = profile.name,
                    email = profile.email,
                    sex = profile.sex,
                    grade = profile.grade,
                    classNum = profile.classNum,
                    studentNumber = profile.studentNumber,
                    major = profile.major,
                    studentRole = profile.studentRole,
                    githubId = profile.githubId,
                ),
            )
        user.syncStudentInfo(
            name = profile.name,
            email = profile.email,
            sex = profile.sex,
            grade = profile.grade,
            classNum = profile.classNum,
            studentNumber = profile.studentNumber,
            major = profile.major,
            studentRole = profile.studentRole,
            githubId = profile.githubId,
        )
        return user
    }

    @Transactional(readOnly = true)
    fun getUser(userId: Long): UserResponse = toResponse(findUser(userId))

    @Transactional(readOnly = true)
    fun search(keyword: String?, major: String?, grade: Int?, pageable: Pageable): Page<UserSummaryResponse> =
        userRepository.search(keyword?.trim()?.ifBlank { null }, major, grade, pageable)
            .map { UserSummaryResponse.of(it, profileImageUrl(it)) }

    @Transactional(readOnly = true)
    fun getSummaries(userIds: Collection<Long>): Map<Long, UserSummaryResponse> =
        userRepository.findAllById(userIds.toSet())
            .associate { it.id to UserSummaryResponse.of(it, profileImageUrl(it)) }

    @Transactional(readOnly = true)
    fun exists(userId: Long): Boolean = userRepository.existsById(userId)

    @Transactional
    fun updateProfile(userId: Long, request: UpdateProfileRequest): UserResponse {
        val user = findUser(userId)
        user.updateProfile(request.nickname, request.description, request.specialty)
        return toResponse(user)
    }

    fun createProfileImageUploadUrl(userId: Long, request: PresignedUploadRequest): PresignedUploadResponse {
        ImageUploadPolicy.validateSize(request.fileSize)
        val extension = ImageUploadPolicy.extensionOf(request.contentType)
        val objectKey = "profiles/$userId/${UUID.randomUUID()}.$extension"
        return PresignedUploadResponse(storageService.createUploadUrl(objectKey, request.contentType), objectKey)
    }

    @Transactional
    fun confirmProfileImage(userId: Long, objectKey: String): UserResponse {
        if (!objectKey.startsWith("profiles/$userId/")) {
            throw BusinessException(HttpStatus.BAD_REQUEST, "유효하지 않은 이미지 키입니다.")
        }
        val size = storageService.findObjectSize(objectKey)
            ?: throw BusinessException(HttpStatus.CONFLICT, "이미지가 아직 업로드되지 않았습니다.")
        ImageUploadPolicy.validateSize(size)

        val user = findUser(userId)
        val previousKey = user.profileImageKey
        user.changeProfileImage(objectKey)
        previousKey?.let(storageService::delete)
        return toResponse(user)
    }

    @Transactional
    fun deleteProfileImage(userId: Long) {
        val user = findUser(userId)
        val key = user.profileImageKey
            ?: throw BusinessException(HttpStatus.NOT_FOUND, "프로필 이미지가 없습니다.")
        user.changeProfileImage(null)
        storageService.delete(key)
    }

    private fun findUser(userId: Long): User =
        userRepository.findByIdOrNull(userId)
            ?: throw BusinessException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다.")

    private fun toResponse(user: User) = UserResponse.of(user, profileImageUrl(user))

    private fun profileImageUrl(user: User): String? =
        user.profileImageKey?.let(storageService::createDownloadUrl)
}
