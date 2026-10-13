package com.cowork.preference.messaging

import com.cowork.preference.repository.ChannelMemberProjectionRepository
import com.cowork.preference.repository.ProjectionCheckpoint
import com.cowork.preference.repository.ProjectionCheckpointRepository

class ChannelMemberProjectionHandler(
    private val memberRepository: ChannelMemberProjectionRepository,
    private val checkpointRepository: ProjectionCheckpointRepository,
) : ProjectionRecordHandler {
    override suspend fun apply(checkpoint: ProjectionCheckpoint, key: String?, value: String?): String? =
        when (val decision = ChannelMemberEventParser.parse(key, value)) {
            is ChannelMemberRecordDecision.Apply -> {
                checkpointRepository.inTransaction(checkpoint) { connection ->
                    memberRepository.apply(connection, decision.event)
                }
                null
            }
            is ChannelMemberRecordDecision.Quarantine -> decision.reason
        }
}
