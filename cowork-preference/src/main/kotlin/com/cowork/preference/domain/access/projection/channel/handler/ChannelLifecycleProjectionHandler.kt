package com.cowork.preference.domain.access.projection.channel.handler

import com.cowork.preference.domain.access.projection.channel.model.ChannelLifecycleRecordDecision
import com.cowork.preference.domain.access.projection.channel.parser.ChannelLifecycleEventParser
import com.cowork.preference.domain.access.service.ChannelLifecycleService
import com.cowork.preference.global.projection.checkpoint.ProjectionCheckpoint
import com.cowork.preference.global.projection.checkpoint.ProjectionCheckpointRepository
import com.cowork.preference.global.projection.consumer.ProjectionRecordHandler

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
