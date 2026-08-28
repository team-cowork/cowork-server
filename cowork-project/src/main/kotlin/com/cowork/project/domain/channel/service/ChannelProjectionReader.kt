package com.cowork.project.domain.channel.service

import com.cowork.project.domain.channel.repository.ChannelProjectionRepository
import com.cowork.project.global.projection.ProjectionReadinessGate
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException

@Component
class ChannelProjectionReader(
    private val repository: ChannelProjectionRepository,
    private val readinessGate: ProjectionReadinessGate,
) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun requireProjectChannel(channelId: Long, projectId: Long) {
        readinessGate.requireReady()
        var channel = repository.findByIdForUpdate(channelId)
        if (channel == null) {
            readinessGate.requireCurrent()
            channel = repository.findByIdForUpdate(channelId)
                ?: throw ExpectedException("존재하지 않는 채널입니다.", HttpStatus.NOT_FOUND)
        }
        if (channel.deleted) {
            throw ExpectedException("존재하지 않는 채널입니다.", HttpStatus.NOT_FOUND)
        }
        if (channel.projectId != projectId) {
            throw ExpectedException("이 프로젝트 소속 채널이 아닙니다.", HttpStatus.BAD_REQUEST)
        }
    }
}
