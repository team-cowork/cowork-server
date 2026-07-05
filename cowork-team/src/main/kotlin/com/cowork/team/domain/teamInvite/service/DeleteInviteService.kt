package com.cowork.team.domain.teamInvite.service

interface DeleteInviteService {
    fun deleteInvite(userId: Long, teamId: Long, inviteCode: String)
}
