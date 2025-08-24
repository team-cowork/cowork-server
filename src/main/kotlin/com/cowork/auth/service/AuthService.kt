package com.cowork.auth.service

import com.cowork.auth.client.DataGsmClient
import com.cowork.auth.client.DataGsmUserInfo
import com.cowork.auth.domain.RefreshToken
import com.cowork.auth.dto.TokenResponse
import com.cowork.auth.repository.RefreshTokenRepository
import com.cowork.global.error.BusinessException
import com.cowork.global.security.JwtProperties
import com.cowork.global.security.JwtTokenProvider
import com.cowork.user.domain.User
import com.cowork.user.dto.StudentProfile
import com.cowork.user.repository.UserRepository
import com.cowork.user.service.UserService
import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime

/**
 * DataGSM 로그인과 cowork 토큰 발급을 담당한다.
 *
 * - 액세스 토큰은 JWT로, 리프레시 토큰은 임의 문자열로 발급하고 서버에는 해시만 저장한다.
 * - 리프레시 토큰은 한 번 쓰면 폐기하고 새로 발급한다(rotation).
 * - 학생이 아닌 DataGSM 계정은 로그인할 수 없다.
 */
@Service
class AuthService(
    private val dataGsmClient: DataGsmClient,
    private val userService: UserService,
    private val userRepository: UserRepository,
    private val refreshTokenRepository: RefreshTokenRepository,
    private val jwtTokenProvider: JwtTokenProvider,
    private val jwtProperties: JwtProperties,
    private val clock: Clock,
) {

    @Transactional
    fun login(code: String, codeVerifier: String, redirectUri: String, deviceInfo: String?): TokenResponse {
        val accessToken = dataGsmClient.exchangeCode(code, codeVerifier, redirectUri)
        val userInfo = dataGsmClient.fetchUserInfo(accessToken)
        val user = userService.upsertStudent(toStudentProfile(userInfo))
        return issueTokens(user, deviceInfo)
    }

    @Transactional
    fun refresh(rawRefreshToken: String): TokenResponse {
        val refreshToken = refreshTokenRepository.findByTokenHash(TokenHasher.hash(rawRefreshToken))
            ?: throw BusinessException(HttpStatus.UNAUTHORIZED, "유효하지 않은 리프레시 토큰입니다.")
        refreshTokenRepository.delete(refreshToken)

        if (refreshToken.isExpired(LocalDateTime.now(clock))) {
            throw BusinessException(HttpStatus.UNAUTHORIZED, "만료된 리프레시 토큰입니다.")
        }
        val user = userRepository.findByIdOrNull(refreshToken.userId)
            ?: throw BusinessException(HttpStatus.UNAUTHORIZED, "사용자를 찾을 수 없습니다.")
        return issueTokens(user, refreshToken.deviceInfo)
    }

    @Transactional
    fun signOut(userId: Long, rawRefreshToken: String) {
        val refreshToken = refreshTokenRepository.findByTokenHash(TokenHasher.hash(rawRefreshToken))
            ?: throw BusinessException(HttpStatus.NOT_FOUND, "리프레시 토큰을 찾을 수 없습니다.")
        if (refreshToken.userId != userId) {
            throw BusinessException(HttpStatus.FORBIDDEN, "본인의 토큰만 만료할 수 있습니다.")
        }
        refreshTokenRepository.delete(refreshToken)
    }

    private fun issueTokens(user: User, deviceInfo: String?): TokenResponse {
        val rawRefreshToken = TokenHasher.generate()
        refreshTokenRepository.save(
            RefreshToken(
                userId = user.id,
                tokenHash = TokenHasher.hash(rawRefreshToken),
                expiresAt = LocalDateTime.now(clock).plus(jwtProperties.refreshTokenTtl),
                deviceInfo = deviceInfo?.take(255),
            ),
        )
        return TokenResponse(
            accessToken = jwtTokenProvider.createAccessToken(user.id, user.role),
            refreshToken = rawRefreshToken,
            expiresIn = jwtTokenProvider.accessTokenTtlSeconds,
        )
    }

    private fun toStudentProfile(userInfo: DataGsmUserInfo): StudentProfile {
        val student = userInfo.student
        if (!userInfo.isStudent || student == null) {
            throw BusinessException(HttpStatus.FORBIDDEN, "학생 계정만 로그인할 수 있습니다.")
        }
        return StudentProfile(
            dataGsmId = userInfo.id,
            name = student.name,
            email = userInfo.email,
            sex = student.sex,
            grade = student.grade,
            classNum = student.classNum,
            studentNumber = student.number,
            major = student.major,
            studentRole = student.role,
            githubId = student.githubId,
        )
    }
}
