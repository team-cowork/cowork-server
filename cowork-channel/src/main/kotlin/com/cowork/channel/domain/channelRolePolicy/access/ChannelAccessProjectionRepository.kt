package com.cowork.channel.domain.channelRolePolicy.access

import org.springframework.data.jpa.repository.JpaRepository

interface ChannelAccessProjectionRepository : JpaRepository<ChannelAccessProjection, String>
