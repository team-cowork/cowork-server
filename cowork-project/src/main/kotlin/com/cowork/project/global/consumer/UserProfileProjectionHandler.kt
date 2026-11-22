package com.cowork.project.global.consumer

import com.cowork.project.domain.user.entity.UserProfileProjection
import com.cowork.project.domain.user.repository.UserProfileProjectionRepository
import com.cowork.project.global.projection.toProjectionPrecision
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Component
class UserProfileProjectionHandler(private val userProfileProjectionRepository: UserProfileProjectionRepository) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun apply(userId: Long, githubId: String?, deleted: Boolean, occurredAt: Instant) {
        val version = occurredAt.toProjectionPrecision()
        val projection = userProfileProjectionRepository.findByIdForUpdate(userId)
        if (projection == null) {
            userProfileProjectionRepository.save(
                UserProfileProjection(
                    userId = userId,
                    githubId = githubId,
                    deleted = deleted,
                    sourceOccurredAt = version,
                ),
            )
            return
        }

        if (projection.apply(githubId, deleted, version)) {
            userProfileProjectionRepository.save(projection)
        }
    }
}
