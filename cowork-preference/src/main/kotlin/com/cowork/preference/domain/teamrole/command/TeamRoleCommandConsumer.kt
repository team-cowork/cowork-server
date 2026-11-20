package com.cowork.preference.domain.teamrole.command

import com.cowork.preference.domain.teamrole.command.model.TeamRoleCommand
import com.cowork.preference.domain.teamrole.command.model.TeamRoleCommandEnvelope
import com.cowork.preference.domain.teamrole.command.repository.TeamRoleCommandInboxRepository
import com.cowork.preference.global.messaging.command.CommandConsumer
import com.cowork.preference.global.messaging.command.model.CommandQuarantineRecord
import com.cowork.preference.global.messaging.event.PreferenceTopics
import io.vertx.core.Vertx
import kotlinx.coroutines.CoroutineScope

class TeamRoleCommandConsumer(
    vertx: Vertx,
    bootstrapServers: String,
    groupId: String,
    private val processor: TeamRoleCommandProcessor,
    private val inboxRepository: TeamRoleCommandInboxRepository,
    scope: CoroutineScope,
) : CommandConsumer<TeamRoleCommand, TeamRoleCommandEnvelope>(
    vertx,
    bootstrapServers,
    groupId,
    PreferenceTopics.TEAM_ROLE_COMMAND_TOPIC,
    "preference team-role command",
    "team-role command",
    scope,
) {
    override fun parse(key: String?, value: String?) = TeamRoleCommandParser.parse(key, value)

    override suspend fun applyCommand(command: TeamRoleCommand) = processor.process(command)

    override suspend fun rejectInvalid(
        envelope: TeamRoleCommandEnvelope,
        record: CommandQuarantineRecord,
        reason: String,
    ) = processor.rejectInvalid(envelope, record, reason)

    override suspend fun quarantine(record: CommandQuarantineRecord) {
        inboxRepository.quarantine(record)
    }
}
