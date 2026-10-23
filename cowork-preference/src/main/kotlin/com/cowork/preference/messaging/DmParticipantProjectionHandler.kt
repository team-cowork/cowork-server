package com.cowork.preference.messaging

import com.cowork.preference.repository.DmParticipantProjectionRepository
import com.cowork.preference.repository.ProjectionCheckpoint
import com.cowork.preference.repository.ProjectionCheckpointRepository

class DmParticipantProjectionHandler(
    private val memberRepository: DmParticipantProjectionRepository,
    private val checkpointRepository: ProjectionCheckpointRepository,
) : ProjectionRecordHandler {
    override suspend fun apply(checkpoint: ProjectionCheckpoint, key: String?, value: String?): String? =
        when (val decision = DmParticipantEventParser.parse(key, value)) {
            is DmParticipantRecordDecision.Apply -> {
                checkpointRepository.inTransaction(checkpoint) { connection ->
                    memberRepository.apply(connection, decision.event)
                }
                null
            }
            is DmParticipantRecordDecision.Quarantine -> decision.reason
        }
}
