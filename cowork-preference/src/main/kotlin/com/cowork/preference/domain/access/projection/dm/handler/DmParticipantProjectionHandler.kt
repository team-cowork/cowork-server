package com.cowork.preference.domain.access.projection.dm.handler

import com.cowork.preference.domain.access.projection.dm.model.DmParticipantRecordDecision
import com.cowork.preference.domain.access.projection.dm.parser.DmParticipantEventParser
import com.cowork.preference.domain.access.projection.dm.repository.DmParticipantProjectionRepository
import com.cowork.preference.global.projection.checkpoint.ProjectionCheckpoint
import com.cowork.preference.global.projection.checkpoint.ProjectionCheckpointRepository
import com.cowork.preference.global.projection.consumer.ProjectionRecordHandler

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
