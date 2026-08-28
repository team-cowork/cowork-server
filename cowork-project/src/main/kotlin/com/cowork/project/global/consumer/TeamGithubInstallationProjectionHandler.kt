package com.cowork.project.global.consumer

import com.cowork.project.domain.github.entity.TeamGithubInstallation
import com.cowork.project.domain.github.entity.TeamGithubInstallationEventState
import com.cowork.project.domain.github.entity.TeamGithubInstallationOwnershipFence
import com.cowork.project.domain.github.repository.TeamGithubInstallationEventStateRepository
import com.cowork.project.domain.github.repository.TeamGithubInstallationOwnershipFenceRepository
import com.cowork.project.domain.github.repository.TeamGithubInstallationRepository
import com.cowork.project.global.projection.toProjectionPrecision
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/** team.lifecycle full state를 GitHub installation 로컬 projection에 반영한다. */
@Component
class TeamGithubInstallationProjectionHandler(
    private val installationRepository: TeamGithubInstallationRepository,
    private val stateRepository: TeamGithubInstallationEventStateRepository,
    private val ownershipFenceRepository: TeamGithubInstallationOwnershipFenceRepository,
) {
    private val log = LoggerFactory.getLogger(TeamGithubInstallationProjectionHandler::class.java)

    @Transactional(propagation = Propagation.MANDATORY)
    fun apply(teamId: Long, installationId: Long?, orgLogin: String?, teamDeleted: Boolean, occurredAt: Instant) {
        require(teamId > 0) { "teamId must be positive" }
        require((installationId == null) == (orgLogin == null)) {
            "githubInstallationId and githubOrgLogin must both be present or absent"
        }
        require(installationId == null || installationId > 0) { "githubInstallationId must be positive" }
        require(orgLogin == null || orgLogin.isNotBlank()) { "githubOrgLogin must not be blank" }

        val version = occurredAt.toProjectionPrecision()
        val incomingDeleted = teamDeleted || installationId == null
        val state = stateRepository.findByTeamIdForUpdate(teamId)
        if (!shouldApplyTeamState(state, installationId, orgLogin, incomingDeleted, version)) return

        if (incomingDeleted) {
            applyDeleted(teamId, installationId, orgLogin, version, state)
        } else {
            applyClaim(teamId, requireNotNull(installationId), requireNotNull(orgLogin), version, state)
        }
    }

    private fun shouldApplyTeamState(
        state: TeamGithubInstallationEventState?,
        installationId: Long?,
        orgLogin: String?,
        incomingDeleted: Boolean,
        version: Instant,
    ): Boolean {
        if (state == null) return true
        val currentVersion = state.sourceOccurredAt.toProjectionPrecision()
        if (currentVersion.isAfter(version)) return false
        if (currentVersion != version) return true

        if (incomingDeleted) return !state.deleted
        if (state.deleted) return false
        if (state.installationId == installationId && state.orgLogin == orgLogin) return false
        throw IllegalStateException(
            "Conflicting team GitHub installation state at the same source version: teamId=${state.teamId}",
        )
    }

    private fun applyDeleted(
        teamId: Long,
        payloadInstallationId: Long?,
        payloadOrgLogin: String?,
        version: Instant,
        state: TeamGithubInstallationEventState?,
    ) {
        val active = installationRepository.findByTeamIdForUpdate(teamId)
        val retainedInstallationId = payloadInstallationId ?: state?.installationId ?: active?.installationId
        val retainedOrgLogin = payloadOrgLogin ?: state?.orgLogin ?: active?.orgLogin
        val installationIds = setOfNotNull(
            payloadInstallationId,
            state?.installationId,
            active?.installationId,
        ).sorted()

        installationIds.forEach { releaseOwnershipIfCurrent(it, teamId, version) }
        if (active != null) installationRepository.delete(active)

        val target = state ?: TeamGithubInstallationEventState(
            teamId = teamId,
            installationId = retainedInstallationId,
            orgLogin = retainedOrgLogin,
            deleted = true,
            sourceOccurredAt = version,
        )
        if (state != null) {
            target.applyDeleted(retainedInstallationId, retainedOrgLogin, version)
        }
        stateRepository.save(target)
        log.info("team.lifecycle GitHub installation 해제 반영 [teamId={}, version={}]", teamId, version)
    }

    private fun applyClaim(
        teamId: Long,
        installationId: Long,
        orgLogin: String,
        version: Instant,
        state: TeamGithubInstallationEventState?,
    ) {
        var fence = ownershipFenceRepository.findByInstallationIdForUpdate(installationId)
        if (fence != null) {
            val fenceVersion = fence.sourceOccurredAt.toProjectionPrecision()
            if (fenceVersion.isAfter(version)) {
                rejectClaimAtFence(teamId, installationId, orgLogin, fenceVersion, state)
                return
            }
            if (fenceVersion == version) {
                // 서로 다른 team partition에서 release와 새 claim이 같은 microsecond가 될 수 있다.
                // inactive release보다 active claim이 우선하고, active↔active만 모호하므로 fail-closed한다.
                if (fence.active && fence.ownerTeamId != teamId) {
                    throw IllegalStateException(
                        "Conflicting teams claimed installation $installationId at the same source version",
                    )
                }
            }
        }

        val claimedInstallation = installationRepository.findByInstallationIdForUpdate(installationId)
        if (claimedInstallation != null && claimedInstallation.teamId != teamId) {
            val oldOwnerState = stateRepository.findByTeamIdForUpdate(claimedInstallation.teamId)
            val oldOwnerVersion = oldOwnerState?.sourceOccurredAt?.toProjectionPrecision()
            if (oldOwnerVersion != null && oldOwnerVersion.isAfter(version)) {
                repairNewerOwnerFence(claimedInstallation, oldOwnerState, oldOwnerVersion, fence)
                rejectClaimAtFence(teamId, installationId, orgLogin, oldOwnerVersion, state)
                return
            }
            if (oldOwnerVersion == version && !oldOwnerState.deleted) {
                throw IllegalStateException(
                    "Conflicting teams claimed installation $installationId at the same source version",
                )
            }

            installationRepository.delete(claimedInstallation)
            // Hibernate는 insert를 delete보다 먼저 flush할 수 있으므로 unique installation_id를
            // 새 owner에게 넘기기 전에 이전 active row 삭제를 확정한다.
            installationRepository.flush()
            fenceOldOwner(claimedInstallation, oldOwnerState, version)
        }

        val currentTeamInstallation = if (claimedInstallation?.teamId == teamId) {
            claimedInstallation
        } else {
            installationRepository.findByTeamIdForUpdate(teamId)
        }
        if (currentTeamInstallation != null && currentTeamInstallation.installationId != installationId) {
            releaseOwnershipIfCurrent(currentTeamInstallation.installationId, teamId, version)
            installationRepository.delete(currentTeamInstallation)
            installationRepository.flush()
        }

        val targetInstallation = currentTeamInstallation
            ?.takeIf { it.installationId == installationId }
            ?: TeamGithubInstallation(teamId = teamId, installationId = installationId, orgLogin = orgLogin)
        targetInstallation.update(installationId, orgLogin)
        installationRepository.save(targetInstallation)

        val targetState = state ?: TeamGithubInstallationEventState(
            teamId = teamId,
            installationId = installationId,
            orgLogin = orgLogin,
            deleted = false,
            sourceOccurredAt = version,
        )
        if (state != null) targetState.applyActive(installationId, orgLogin, version)
        stateRepository.save(targetState)

        if (fence == null) {
            fence = TeamGithubInstallationOwnershipFence(
                installationId = installationId,
                ownerTeamId = teamId,
                active = true,
                sourceOccurredAt = version,
            )
        } else {
            fence.activate(teamId, version)
        }
        ownershipFenceRepository.save(fence)
        log.info(
            "team.lifecycle GitHub installation 연결 반영 [teamId={}, installationId={}, version={}]",
            teamId,
            installationId,
            version,
        )
    }

    private fun rejectClaimAtFence(
        teamId: Long,
        installationId: Long,
        orgLogin: String,
        fenceVersion: Instant,
        state: TeamGithubInstallationEventState?,
    ) {
        val active = installationRepository.findByTeamIdForUpdate(teamId)
        if (active != null) {
            releaseOwnershipIfCurrent(active.installationId, teamId, fenceVersion)
            installationRepository.delete(active)
        }
        val target = state ?: TeamGithubInstallationEventState(
            teamId = teamId,
            installationId = installationId,
            orgLogin = orgLogin,
            deleted = true,
            sourceOccurredAt = fenceVersion,
        )
        if (state != null) target.applyDeleted(installationId, orgLogin, fenceVersion)
        stateRepository.save(target)
        log.warn(
            "더 최신 installation 소유권 때문에 team claim을 무시합니다 " +
                "[teamId={}, installationId={}, fenceVersion={}]",
            teamId,
            installationId,
            fenceVersion,
        )
    }

    private fun fenceOldOwner(
        installation: TeamGithubInstallation,
        state: TeamGithubInstallationEventState?,
        version: Instant,
    ) {
        val target = state ?: TeamGithubInstallationEventState(
            teamId = installation.teamId,
            installationId = installation.installationId,
            orgLogin = installation.orgLogin,
            deleted = true,
            sourceOccurredAt = version,
        )
        if (state != null) {
            target.applyDeleted(installation.installationId, installation.orgLogin, version)
        }
        stateRepository.save(target)
    }

    private fun repairNewerOwnerFence(
        installation: TeamGithubInstallation,
        state: TeamGithubInstallationEventState,
        version: Instant,
        existingFence: TeamGithubInstallationOwnershipFence?,
    ) {
        if (state.deleted || state.installationId != installation.installationId) {
            throw IllegalStateException(
                "Active installation row conflicts with its newer team state: installationId=${installation.installationId}",
            )
        }
        val fence = existingFence ?: TeamGithubInstallationOwnershipFence(
            installationId = installation.installationId,
            ownerTeamId = installation.teamId,
            active = true,
            sourceOccurredAt = version,
        )
        fence.activate(installation.teamId, version)
        ownershipFenceRepository.save(fence)
    }

    private fun releaseOwnershipIfCurrent(installationId: Long, teamId: Long, version: Instant) {
        val fence = ownershipFenceRepository.findByInstallationIdForUpdate(installationId)
        if (fence == null) {
            ownershipFenceRepository.save(
                TeamGithubInstallationOwnershipFence(
                    installationId = installationId,
                    ownerTeamId = teamId,
                    active = false,
                    sourceOccurredAt = version,
                ),
            )
            return
        }

        val currentVersion = fence.sourceOccurredAt.toProjectionPrecision()
        if (fence.ownerTeamId != teamId || currentVersion.isAfter(version)) return
        fence.deactivate(teamId, version)
        ownershipFenceRepository.save(fence)
    }
}
