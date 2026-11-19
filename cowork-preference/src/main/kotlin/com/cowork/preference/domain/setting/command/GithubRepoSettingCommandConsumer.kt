package com.cowork.preference.domain.setting.command

import com.cowork.preference.domain.setting.command.model.GithubRepoSettingCommand
import com.cowork.preference.domain.setting.command.model.GithubRepoSettingCommandEnvelope
import com.cowork.preference.domain.setting.command.repository.GithubRepoSettingCommandInboxRepository
import com.cowork.preference.global.messaging.command.CommandConsumer
import com.cowork.preference.global.messaging.command.model.CommandQuarantineRecord
import com.cowork.preference.global.messaging.event.PreferenceTopics
import io.vertx.core.Vertx
import kotlinx.coroutines.CoroutineScope

class GithubRepoSettingCommandConsumer(
    vertx: Vertx,
    bootstrapServers: String,
    groupId: String,
    private val processor: GithubRepoSettingCommandProcessor,
    private val inboxRepository: GithubRepoSettingCommandInboxRepository,
    scope: CoroutineScope,
) : CommandConsumer<GithubRepoSettingCommand, GithubRepoSettingCommandEnvelope>(
    vertx,
    bootstrapServers,
    groupId,
    PreferenceTopics.GITHUB_REPO_SETTING_COMMAND_TOPIC,
    "GitHub repo setting command",
    "GitHub repo setting command",
    scope,
) {
    override fun parse(key: String?, value: String?) = GithubRepoSettingCommandParser.parse(key, value)

    override suspend fun applyCommand(command: GithubRepoSettingCommand) = processor.process(command)

    override suspend fun rejectInvalid(
        envelope: GithubRepoSettingCommandEnvelope,
        record: CommandQuarantineRecord,
        reason: String,
    ) = processor.rejectInvalid(envelope, record, reason)

    override suspend fun quarantine(record: CommandQuarantineRecord) {
        inboxRepository.quarantine(record)
    }
}
