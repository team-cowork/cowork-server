package com.cowork.project.domain.githubBoard.service.impl

import com.cowork.project.domain.githubBoard.entity.ProjectGithubBoard
import com.cowork.project.domain.githubBoard.event.GithubAddItemPayload
import com.cowork.project.domain.githubBoard.event.GithubClearFieldPayload
import com.cowork.project.domain.githubBoard.event.GithubConvertDraftPayload
import com.cowork.project.domain.githubBoard.event.GithubDraftPayload
import com.cowork.project.domain.githubBoard.event.GithubProjectItemStateEvent
import com.cowork.project.domain.githubBoard.event.GithubProjectItemTarget
import com.cowork.project.domain.githubBoard.event.GithubProjectItemWriteCommandPublisher
import com.cowork.project.domain.githubBoard.event.GithubProjectItemWriteCommandType
import com.cowork.project.domain.githubBoard.event.GithubSetAssigneesPayload
import com.cowork.project.domain.githubBoard.event.GithubSetFieldPayload
import com.cowork.project.domain.githubBoard.model.GithubBoard
import com.cowork.project.domain.githubBoard.model.GithubBoardFieldEditor
import com.cowork.project.domain.githubBoard.presentation.data.request.AddGithubBoardItemReqDto
import com.cowork.project.domain.githubBoard.presentation.data.request.ConvertGithubBoardDraftReqDto
import com.cowork.project.domain.githubBoard.presentation.data.request.CreateGithubBoardDraftReqDto
import com.cowork.project.domain.githubBoard.presentation.data.request.SetGithubBoardAssigneesReqDto
import com.cowork.project.domain.githubBoard.presentation.data.request.SetGithubBoardFieldReqDto
import com.cowork.project.domain.githubBoard.presentation.data.request.UpdateGithubBoardDraftReqDto
import com.cowork.project.domain.githubBoard.presentation.data.response.GithubBoardOperationAcceptedResDto
import com.cowork.project.domain.githubBoard.service.GithubBoardAccess
import com.cowork.project.domain.githubBoard.service.GithubBoardAccessResolver
import com.cowork.project.domain.githubBoard.service.GithubBoardReader
import com.cowork.project.domain.githubBoard.service.GithubBoardRepositoryResolver
import com.cowork.project.domain.githubBoard.service.WriteGithubBoardItemService
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException
import java.time.Instant
import java.util.Locale

@Service
class WriteGithubBoardItemServiceImpl(
    private val accessResolver: GithubBoardAccessResolver,
    private val boardReader: GithubBoardReader,
    private val repositoryResolver: GithubBoardRepositoryResolver,
    private val commandPublisher: GithubProjectItemWriteCommandPublisher,
) : WriteGithubBoardItemService {
    @Transactional
    override fun setField(
        userId: Long,
        projectId: Long,
        boardId: Long,
        itemId: String,
        fieldId: String,
        request: SetGithubBoardFieldReqDto,
    ): GithubBoardOperationAcceptedResDto =
        submit(userId, projectId, boardId, itemId, GithubProjectItemWriteCommandType.SET_FIELD) { _, board ->
            val field = GithubBoardFieldEditor.requireEditable(board, fieldId)
            GithubSetFieldPayload(fieldId, GithubBoardFieldEditor.value(field, request))
        }

    @Transactional
    override fun clearField(
        userId: Long,
        projectId: Long,
        boardId: Long,
        itemId: String,
        fieldId: String,
    ): GithubBoardOperationAcceptedResDto =
        submit(userId, projectId, boardId, itemId, GithubProjectItemWriteCommandType.CLEAR_FIELD) { _, board ->
            GithubBoardFieldEditor.requireEditable(board, fieldId)
            GithubClearFieldPayload(fieldId)
        }

    @Transactional
    override fun createDraft(
        userId: Long,
        projectId: Long,
        boardId: Long,
        request: CreateGithubBoardDraftReqDto,
    ): GithubBoardOperationAcceptedResDto =
        submit(userId, projectId, boardId, null, GithubProjectItemWriteCommandType.CREATE_DRAFT) { _, _ ->
            requireTitle(request.title)
            // GitHub는 제목으로 이슈 URL을 받으면 draft 대신 해당 이슈를 추가한다.
            if (Regex("https?://github\\.com/\\S+", RegexOption.IGNORE_CASE).matches(request.title.trim())) {
                throw ExpectedException("이슈 URL은 기존 카드 추가 기능으로 사용해 주십시오.", HttpStatus.BAD_REQUEST)
            }
            GithubDraftPayload(request.title, request.body)
        }

    @Transactional
    override fun updateDraft(
        userId: Long,
        projectId: Long,
        boardId: Long,
        itemId: String,
        request: UpdateGithubBoardDraftReqDto,
    ): GithubBoardOperationAcceptedResDto =
        submit(userId, projectId, boardId, itemId, GithubProjectItemWriteCommandType.UPDATE_DRAFT) { _, board ->
            requireType(board, itemId, "DRAFT_ISSUE")
            request.title?.let(::requireTitle)
            if (request.title == null && request.body == null) {
                throw ExpectedException("수정할 제목 또는 본문을 지정해 주십시오.", HttpStatus.BAD_REQUEST)
            }
            GithubDraftPayload(request.title, request.body)
        }

    @Transactional
    override fun addItem(
        userId: Long,
        projectId: Long,
        boardId: Long,
        request: AddGithubBoardItemReqDto,
    ): GithubBoardOperationAcceptedResDto =
        submit(userId, projectId, boardId, null, GithubProjectItemWriteCommandType.ADD_ITEM) { link, _ ->
            val repo = repositoryResolver.resolve(link, request.repoId)
            GithubAddItemPayload(repo.repo, request.number)
        }

    @Transactional
    override fun removeItem(
        userId: Long,
        projectId: Long,
        boardId: Long,
        itemId: String,
    ): GithubBoardOperationAcceptedResDto =
        submit(userId, projectId, boardId, itemId, GithubProjectItemWriteCommandType.REMOVE_ITEM) { _, _ ->
            emptyMap<String, String>()
        }

    @Transactional
    override fun convertDraft(
        userId: Long,
        projectId: Long,
        boardId: Long,
        itemId: String,
        request: ConvertGithubBoardDraftReqDto,
    ): GithubBoardOperationAcceptedResDto =
        submit(userId, projectId, boardId, itemId, GithubProjectItemWriteCommandType.CONVERT_DRAFT) { link, board ->
            requireType(board, itemId, "DRAFT_ISSUE")
            GithubConvertDraftPayload(repositoryResolver.resolve(link, request.repoId).repo)
        }

    @Transactional
    override fun setAssignees(
        userId: Long,
        projectId: Long,
        boardId: Long,
        itemId: String,
        request: SetGithubBoardAssigneesReqDto,
    ): GithubBoardOperationAcceptedResDto =
        submit(userId, projectId, boardId, itemId, GithubProjectItemWriteCommandType.SET_ASSIGNEES) { link, board ->
            val item = requireType(board, itemId, "ISSUE")
            val repo = repositoryResolver.resolveContent(link, item.content?.repository)
            val names = request.assignees
            if (names.size > 10 || names.any { it.isNullOrBlank() || it.any(Char::isWhitespace) } ||
                names.map { it?.lowercase(Locale.ROOT) }.distinct().size != names.size
            ) {
                throw ExpectedException("담당자는 중복 없이 최대 10명까지 지정할 수 있습니다.", HttpStatus.BAD_REQUEST)
            }
            GithubSetAssigneesPayload(repo.repo, names.filterNotNull())
        }

    private fun submit(
        userId: Long,
        projectId: Long,
        boardId: Long,
        itemId: String?,
        type: GithubProjectItemWriteCommandType,
        payload: (ProjectGithubBoard, GithubBoard) -> Any,
    ): GithubBoardOperationAcceptedResDto {
        val link = accessResolver.resolve(userId, projectId, boardId, GithubBoardAccess.WRITE)
        val board = boardReader.read(link)
        if (itemId != null && board.item(itemId) == null) {
            throw ExpectedException("보드에서 카드를 찾을 수 없습니다.", HttpStatus.NOT_FOUND)
        }
        val commandPayload = payload(link, board)
        val target = GithubProjectItemTarget(link.id, link.projectId, link.ownerLogin, board.projectNodeId, itemId)
        return GithubBoardOperationAcceptedResDto(
            commandPublisher.publish(target, type, commandPayload, userId, Instant.now()),
        )
    }

    private fun requireType(board: GithubBoard, itemId: String, type: String): GithubProjectItemStateEvent {
        val item = board.item(itemId)
        if (item?.type != type || item.contentNodeId.isNullOrBlank()) {
            throw ExpectedException("이 카드에서는 요청한 작업을 할 수 없습니다.", HttpStatus.BAD_REQUEST)
        }
        return item
    }

    private fun requireTitle(title: String) {
        if (title.isBlank()) throw ExpectedException("제목을 입력해 주십시오.", HttpStatus.BAD_REQUEST)
    }
}
