package com.cowork.user.service

import com.cowork.global.error.BusinessException
import com.cowork.global.storage.StorageService
import com.cowork.user.domain.User
import com.cowork.user.dto.StudentProfile
import com.cowork.user.dto.UpdateProfileRequest
import com.cowork.user.dto.UserResponse
import com.cowork.user.repository.UserRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

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

    private fun findUser(userId: Long): User =
        userRepository.findByIdOrNull(userId)
            ?: throw BusinessException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다.")

    private fun toResponse(user: User) = UserResponse.of(user, profileImageUrl(user))

    private fun profileImageUrl(user: User): String? =
        user.profileImageKey?.let(storageService::createDownloadUrl)
}
