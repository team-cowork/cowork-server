package com.cowork.project.domain.calendar.service.impl

import com.cowork.project.domain.calendar.entity.ProjectCalendarCategory
import com.cowork.project.domain.calendar.event.ProjectCalendarEventPublisher
import com.cowork.project.domain.calendar.model.CalendarAction
import com.cowork.project.domain.calendar.presentation.data.request.SaveProjectCalendarCategoryReqDto
import com.cowork.project.domain.calendar.presentation.data.response.ProjectCalendarCategoryResDto
import com.cowork.project.domain.calendar.repository.ProjectCalendarCategoryRepository
import com.cowork.project.domain.calendar.repository.ProjectCalendarEventRepository
import com.cowork.project.domain.calendar.service.ManageProjectCalendarCategoryService
import com.cowork.project.domain.calendar.service.ProjectCalendarAccessGuard
import com.cowork.project.domain.calendar.service.QueryProjectCalendarCategoriesService
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException

/** 카테고리는 일정 권한을 따른다. 만들 때 일정 만들기 권한, 다른 사람의 카테고리를 고칠 때 수정·삭제 권한이 필요하다. */
@Service
class ManageProjectCalendarCategoryServiceImpl(
    private val projectCalendarAccessGuard: ProjectCalendarAccessGuard,
    private val projectCalendarCategoryRepository: ProjectCalendarCategoryRepository,
    private val projectCalendarEventRepository: ProjectCalendarEventRepository,
    private val projectCalendarEventPublisher: ProjectCalendarEventPublisher,
) : ManageProjectCalendarCategoryService {

    @Transactional
    override fun create(
        userId: Long,
        projectId: Long,
        request: SaveProjectCalendarCategoryReqDto,
    ): ProjectCalendarCategoryResDto {
        val project = projectCalendarAccessGuard.requireCreator(projectId, userId)
        val name = request.name.trim()
        if (projectCalendarCategoryRepository.existsByProjectIdAndName(projectId, name)) throw duplicateName()
        val category = projectCalendarCategoryRepository.save(
            ProjectCalendarCategory(
                projectId = project.id,
                teamId = project.teamId,
                name = name,
                colorHex = request.colorHex.uppercase(),
                createdBy = userId,
            ),
        )
        return ProjectCalendarCategoryResDto.of(category)
    }

    @Transactional
    override fun update(
        userId: Long,
        projectId: Long,
        categoryId: Long,
        request: SaveProjectCalendarCategoryReqDto,
    ): ProjectCalendarCategoryResDto {
        val project = projectCalendarAccessGuard.requireReader(projectId, userId)
        val category = findCategory(categoryId, projectId)
        projectCalendarAccessGuard.requireOwnerOrPermission(project, category.createdBy, userId, CalendarAction.UPDATE)
        val name = request.name.trim()
        if (projectCalendarCategoryRepository.existsByProjectIdAndNameAndIdNot(projectId, name, categoryId)) {
            throw duplicateName()
        }
        category.name = name
        category.colorHex = request.colorHex.uppercase()
        // 일정 상태에는 카테고리 이름과 색이 들어 있으므로 이 카테고리를 쓰는 일정을 다시 발행한다.
        projectCalendarEventRepository.findActiveByCategoryIdForUpdate(categoryId).forEach {
            projectCalendarEventPublisher.publishChanged(it, category)
        }
        return ProjectCalendarCategoryResDto.of(category)
    }

    @Transactional
    override fun delete(userId: Long, projectId: Long, categoryId: Long) {
        val project = projectCalendarAccessGuard.requireReader(projectId, userId)
        val category = findCategory(categoryId, projectId)
        projectCalendarAccessGuard.requireOwnerOrPermission(project, category.createdBy, userId, CalendarAction.DELETE)
        projectCalendarEventRepository.findActiveByCategoryIdForUpdate(categoryId).forEach {
            it.categoryId = null
            projectCalendarEventPublisher.publishChanged(it, null)
        }
        projectCalendarCategoryRepository.delete(category)
    }

    private fun findCategory(categoryId: Long, projectId: Long): ProjectCalendarCategory =
        projectCalendarCategoryRepository.findByIdAndProjectId(categoryId, projectId)
            ?: throw ExpectedException("일정 카테고리를 찾을 수 없습니다.", HttpStatus.NOT_FOUND)

    private fun duplicateName() = ExpectedException("같은 이름의 일정 카테고리가 이미 있습니다.", HttpStatus.CONFLICT)
}

@Service
class QueryProjectCalendarCategoriesServiceImpl(
    private val projectCalendarAccessGuard: ProjectCalendarAccessGuard,
    private val projectCalendarCategoryRepository: ProjectCalendarCategoryRepository,
) : QueryProjectCalendarCategoriesService {

    @Transactional(readOnly = true)
    override fun execute(userId: Long, projectId: Long): List<ProjectCalendarCategoryResDto> {
        projectCalendarAccessGuard.requireReader(projectId, userId)
        return projectCalendarCategoryRepository.findAllByProjectIdOrderByIdAsc(projectId)
            .map(ProjectCalendarCategoryResDto::of)
    }
}
