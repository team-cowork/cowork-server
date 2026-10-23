package com.cowork.preference.messaging

import com.cowork.preference.repository.ProjectAccessStateRepository
import com.cowork.preference.repository.ProjectLifecycleProjectionRepository
import com.cowork.preference.repository.ProjectionCheckpoint
import com.cowork.preference.repository.ProjectionCheckpointRepository

class ProjectLifecycleProjectionHandler(
    private val projectRepository: ProjectLifecycleProjectionRepository,
    private val checkpointRepository: ProjectionCheckpointRepository,
    private val projectAccess: ProjectAccessStateRepository,
) : ProjectionRecordHandler {
    override suspend fun apply(checkpoint: ProjectionCheckpoint, key: String?, value: String?): String? =
        when (val decision = ProjectLifecycleEventParser.parse(key, value)) {
            is ProjectLifecycleRecordDecision.Apply -> {
                checkpointRepository.inTransaction(checkpoint) { connection ->
                    if (projectRepository.apply(connection, decision.event)) {
                        projectAccess.refresh(connection, decision.event.projectId)
                    }
                }
                null
            }
            is ProjectLifecycleRecordDecision.Quarantine -> decision.reason
        }
}
