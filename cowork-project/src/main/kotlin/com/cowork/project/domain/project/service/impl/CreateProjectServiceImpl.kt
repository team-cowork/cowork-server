package com.cowork.project.domain.project.service.impl

import com.cowork.project.domain.project.entity.Project
import com.cowork.project.domain.project.event.ProjectEventPublisher
import com.cowork.project.domain.project.presentation.data.request.CreateProjectReqDto
import com.cowork.project.domain.project.presentation.data.response.ProjectResDto
import com.cowork.project.domain.project.repository.ProjectRepository
import com.cowork.project.domain.project.service.CreateProjectService
import com.cowork.project.domain.project.service.ProjectAccessGuard
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException

@Service
class CreateProjectServiceImpl(
    private val projectRepository: ProjectRepository,
    private val projectEventPublisher: ProjectEventPublisher,
    private val projectAccessGuard: ProjectAccessGuard,
) : CreateProjectService {

    /**
     * 재정렬과 같은 팀 프로젝트 행 잠금 아래에서 다음 position을 정한다. READ COMMITTED로 실행해 빈 팀에서도 gap 잠금 교착 없이
     * 동시 생성 경합을 (team_id, position) 유일성 제약 충돌로 끝낸다.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    override fun execute(userId: Long, request: CreateProjectReqDto): ProjectResDto {
        val initialReadAuthorization = projectAccessGuard.requireProjectCreation(request.teamId, userId, request.isPrivate)
        val lockedProjects = projectRepository.findAllByTeamIdForUpdate(request.teamId)

        val project = try {
            projectRepository.save(
                Project(
                    teamId = request.teamId,
                    name = request.name,
                    description = request.description,
                    position = (lockedProjects.maxOfOrNull { it.position } ?: -1) + 1,
                    createdBy = userId,
                    isPrivate = request.isPrivate,
                    initialReadMembershipVersion = initialReadAuthorization?.membershipVersion,
                    initialReadAuthorizationVersion = initialReadAuthorization?.authorizationVersion,
                ),
            )
        } catch (e: DataIntegrityViolationException) {
            throw ExpectedException("다른 요청과 프로젝트 순서가 충돌했습니다. 다시 시도해 주세요.", HttpStatus.CONFLICT)
        }

        projectEventPublisher.publishCreated(project)

        return ProjectResDto.of(project)
    }
}
