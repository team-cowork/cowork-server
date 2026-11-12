package com.cowork.preference.global.config

data class KafkaConfig(
    val bootstrapServers: String,
    val teamMemberConsumerGroupId: String,
    val teamMemberTopic: String,
    val channelLifecycleConsumerGroupId: String,
    val channelTopic: String,
    val projectLifecycleConsumerGroupId: String,
    val projectTopic: String,
    val dmParticipantConsumerGroupId: String,
    val dmParticipantTopic: String,
    val teamRoleCommandConsumerGroupId: String,
    val githubRepoSettingCommandConsumerGroupId: String,
    val channelRolePolicyCommandConsumerGroupId: String,
)
