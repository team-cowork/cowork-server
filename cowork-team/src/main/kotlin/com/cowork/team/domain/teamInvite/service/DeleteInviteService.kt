package com.cowork.team.domain.teamInvite.service

interface DeleteInviteService {
    fun execute(userId: Long, teamId: Long, inviteCode: String)
}
