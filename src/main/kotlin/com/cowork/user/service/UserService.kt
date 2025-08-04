package com.cowork.user.service

import com.cowork.user.domain.User
import com.cowork.user.dto.StudentProfile
import com.cowork.user.repository.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class UserService(
    private val userRepository: UserRepository,
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
}
