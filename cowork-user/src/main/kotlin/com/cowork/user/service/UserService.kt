package com.cowork.user.service

import com.cowork.user.domain.Account
import com.cowork.user.domain.Profile
import com.cowork.user.dto.*
import com.cowork.user.messaging.event.UserCreatedEvent
import com.cowork.user.repository.AccountRepository
import com.cowork.user.repository.ProfileRepository
import com.cowork.user.repository.ProfileSpecification
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
    private val accountRepository: AccountRepository,
    private val profileRepository: ProfileRepository,
    private val s3Service: S3Service,
) {

    private fun findProfileOrThrow(userId: Long) =
        profileRepository.findByAccountId(userId).orElseThrow {
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
        profile.updateProfile(
            nickname = request.nickname,
            description = request.description,
            roles = request.roles,
        )
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
        s3Service.verifyUpload(userId, objectKey)
        val profile = findProfileOrThrow(userId)
        val previousProfileImageKey = profile.profileImageKey
        profile.updateProfileImageKey(objectKey)
        previousProfileImageKey?.let { key ->
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCommit() = s3Service.deleteObject(key)
            })
        }
    }

    @Transactional
    fun deleteProfileImage(userId: Long) {
        val profile = findProfileOrThrow(userId)
        val previousProfileImageKey = profile.profileImageKey
        profile.updateProfileImageKey(null)
        previousProfileImageKey?.let { key ->
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
    fun upsertUser(userId: Long, request: UpsertUserRequest): UserProfileResponse {
        val account = upsertAccount(
            userId = userId,
            name = request.name,
            email = request.email,
            sex = request.sex,
            github = request.githubId,
            description = null,
            studentRole = request.role.name,
            studentNumber = buildStudentNumber(request.grade, request.`class`, request.classNum),
            major = request.major.name,
            specialty = null,
            status = null,
        )
        val profile = getOrCreateProfile(account)
        val imageUrl = profile.profileImageKey?.let { s3Service.generateGetPresignedUrl(it) }
        return UserProfileResponse.of(profile, imageUrl)
    }

    @Transactional
    fun upsertUserFromSyncEvent(event: UserCreatedEvent): UserProfileResponse {
        val account = upsertAccount(
            userId = event.userId,
            name = event.name,
            email = event.email,
            sex = event.sex,
            github = event.github,
            description = event.description,
            studentRole = event.studentRole,
            studentNumber = event.studentNumber,
            major = event.major,
            specialty = event.specialty,
            status = event.status ?: "offline",
        )
        val profile = getOrCreateProfile(account)
        val imageUrl = profile.profileImageKey?.let { s3Service.generateGetPresignedUrl(it) }
        return UserProfileResponse.of(profile, imageUrl)
    }

    fun searchUsers(
        name: String?,
        nickname: String?,
        major: String?,
        studentRole: String?,
        status: String?,
        role: String?,
        pageable: Pageable,
    ): Page<UserProfileResponse> {
        val spec = ProfileSpecification.build(name, nickname, major, studentRole, status, role)
        return profileRepository.findAll(spec, pageable).map { profile ->
            val imageUrl = profile.profileImageKey?.let { s3Service.generateGetPresignedUrl(it) }
            UserProfileResponse.of(profile, imageUrl)
        }
    }

    private fun upsertAccount(
        userId: Long,
        name: String,
        email: String,
        sex: com.cowork.user.domain.Sex,
        github: String?,
        description: String?,
        studentRole: String?,
        studentNumber: String?,
        major: String?,
        specialty: String?,
        status: String?,
    ): Account =
        accountRepository.findById(userId)
            .map {
                it.updateFromSync(
                    name = name,
                    email = email,
                    sex = sex,
                    github = github,
                    description = description ?: it.description,
                    studentRole = studentRole ?: it.studentRole,
                    studentNumber = studentNumber ?: it.studentNumber,
                    major = major ?: it.major,
                    specialty = specialty ?: it.specialty,
                    status = status ?: it.status,
                )
                it
            }
            .orElseGet {
                accountRepository.save(
                    Account(
                        id = userId,
                        name = name,
                        email = email,
                        sex = sex,
                        github = github,
                        description = description,
                        studentRole = studentRole,
                        studentNumber = studentNumber,
                        major = major,
                        specialty = specialty,
                        status = status ?: "offline",
                    ),
                )
            }

    private fun getOrCreateProfile(account: Account): Profile =
        profileRepository.findByAccountId(account.id)
            .orElseGet {
                profileRepository.save(
                    Profile(
                        account = account,
                        profileImageKey = null,
                        nickname = null,
                        description = null,
                        roles = mutableSetOf(),
                    ),
                )
            }

    private fun buildStudentNumber(
        grade: Byte?,
        classNumber: Byte?,
        classNum: Byte?,
    ): String? {
        if (grade == null || classNumber == null || classNum == null) {
            return null
        }
        return "%d%d%02d".format(grade, classNumber, classNum)
    }
}
