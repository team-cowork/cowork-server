package com.cowork.preference.messaging

import com.cowork.preference.repository.ProjectionCheckpoint
import com.cowork.preference.repository.ProjectionCheckpointRepository
import com.cowork.preference.service.ChannelLifecycleService

class ChannelLifecycleProjectionHandler(
    private val channelLifecycleService: ChannelLifecycleService,
    private val checkpointRepository: ProjectionCheckpointRepository,
) : ProjectionRecordHandler {
    override suspend fun apply(checkpoint: ProjectionCheckpoint, key: String?, value: String?): String? =
        when (val decision = ChannelLifecycleEventParser.parse(key, value)) {
            is ChannelLifecycleRecordDecision.Apply -> {
                checkpointRepository.inTransaction(checkpoint) { connection ->
                    channelLifecycleService.apply(connection, decision.event)
                }
                null
            }
            is ChannelLifecycleRecordDecision.Quarantine -> decision.reason
        }
}
