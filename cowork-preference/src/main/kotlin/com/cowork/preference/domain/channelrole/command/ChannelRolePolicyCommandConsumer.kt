package com.cowork.preference.domain.channelrole.command

import com.cowork.preference.domain.channelrole.command.model.ChannelRolePolicyCommand
import com.cowork.preference.domain.channelrole.command.model.ChannelRolePolicyCommandEnvelope
import com.cowork.preference.domain.channelrole.command.repository.ChannelRolePolicyCommandInboxRepository
import com.cowork.preference.global.messaging.command.CommandConsumer
import com.cowork.preference.global.messaging.command.model.CommandQuarantineRecord
import com.cowork.preference.global.messaging.event.PreferenceTopics
import io.vertx.core.Vertx
import kotlinx.coroutines.CoroutineScope

class ChannelRolePolicyCommandConsumer(
    vertx: Vertx,
    bootstrapServers: String,
    groupId: String,
    private val processor: ChannelRolePolicyCommandProcessor,
    private val inboxRepository: ChannelRolePolicyCommandInboxRepository,
    scope: CoroutineScope,
) : CommandConsumer<ChannelRolePolicyCommand, ChannelRolePolicyCommandEnvelope>(
    vertx,
    bootstrapServers,
    groupId,
    PreferenceTopics.CHANNEL_ROLE_POLICY_COMMAND_TOPIC,
    "channel role policy command",
    "channel role policy command",
    scope,
) {
    override fun parse(key: String?, value: String?) = ChannelRolePolicyCommandParser.parse(key, value)

    override suspend fun applyCommand(command: ChannelRolePolicyCommand) = processor.process(command)

    override suspend fun rejectInvalid(
        envelope: ChannelRolePolicyCommandEnvelope,
        record: CommandQuarantineRecord,
        reason: String,
    ) = processor.rejectInvalid(envelope, record, reason)

    override suspend fun quarantine(record: CommandQuarantineRecord) {
        inboxRepository.quarantine(record)
    }
}
