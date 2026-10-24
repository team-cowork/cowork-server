package com.cowork.preference.messaging

import com.cowork.preference.repository.ProjectAccessStateRepository
import com.cowork.preference.repository.ProjectLifecycleProjectionRepository
import com.cowork.preference.repository.ProjectionCheckpoint
import com.cowork.preference.repository.ProjectionCheckpointRepository
import com.cowork.preference.service.InitialResourceAccessService

class ProjectLifecycleProjectionHandler(
    private val projectRepository: ProjectLifecycleProjectionRepository,
    private val checkpointRepository: ProjectionCheckpointRepository,
    private val projectAccess: ProjectAccessStateRepository,
    private val initialAccess: InitialResourceAccessService,
) : ProjectionRecordHandler {
    override suspend fun apply(checkpoint: ProjectionCheckpoint, key: String?, value: String?): String? =
        when (val decision = ProjectLifecycleEventParser.parse(key, value)) {
            is ProjectLifecycleRecordDecision.Apply -> {
                checkpointRepository.inTransaction(checkpoint) { connection ->
                    val applied = projectRepository.apply(connection, decision.event)
                    val initialized = if (decision.event.deleted || decision.event.initialReadGrant != null) {
                        projectRepository.findForRead(connection, decision.event.projectId)?.let { current ->
                            initialAccess.project(connection, decision.event, current)
                        } ?: false
                    } else false
                    if (applied || initialized) {
                        projectAccess.refresh(connection, decision.event.projectId)
                    }
                }
                null
            }
            is ProjectLifecycleRecordDecision.Quarantine -> decision.reason
        }
}
