package com.cowork.project.global.consumer

import com.cowork.project.domain.user.entity.UserProfileProjection
import com.cowork.project.domain.user.repository.UserProfileProjectionRepository
import com.cowork.project.global.projection.toProjectionPrecision
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Component
class UserProfileProjectionHandler(private val repository: UserProfileProjectionRepository) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun apply(userId: Long, githubId: String?, deleted: Boolean, occurredAt: Instant) {
        val version = occurredAt.toProjectionPrecision()
        val projection = repository.findByIdForUpdate(userId)
        if (projection == null) {
            repository.save(
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
            repository.save(projection)
        }
    }
}
