package com.cowork.project.domain.githubBoard.service.impl

import com.cowork.project.domain.githubBoard.event.GithubProjectItemTarget
import com.cowork.project.domain.githubBoard.event.GithubProjectItemWriteCommandPublisher
import com.cowork.project.domain.githubBoard.event.GithubProjectItemWriteCommandType
import com.cowork.project.domain.githubBoard.event.GithubReorderItemPayload
import com.cowork.project.domain.githubBoard.model.GithubBoardKanbanBuilder
import com.cowork.project.domain.githubBoard.model.GithubBoardPositionResolver
import com.cowork.project.domain.githubBoard.presentation.data.request.ReorderGithubBoardItemReqDto
import com.cowork.project.domain.githubBoard.presentation.data.response.GithubBoardOperationAcceptedResDto
import com.cowork.project.domain.githubBoard.service.GithubBoardAccess
import com.cowork.project.domain.githubBoard.service.GithubBoardAccessResolver
import com.cowork.project.domain.githubBoard.service.GithubBoardReader
import com.cowork.project.domain.githubBoard.service.ReorderGithubBoardItemService
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException
import java.time.Instant

@Service
class ReorderGithubBoardItemServiceImpl(
    private val accessResolver: GithubBoardAccessResolver,
    private val boardReader: GithubBoardReader,
    private val commandPublisher: GithubProjectItemWriteCommandPublisher,
) : ReorderGithubBoardItemService {

    @Transactional
    override fun execute(
        userId: Long,
        projectId: Long,
        boardId: Long,
        itemId: String,
        request: ReorderGithubBoardItemReqDto,
    ): GithubBoardOperationAcceptedResDto {
        val link = accessResolver.resolve(userId, projectId, boardId, GithubBoardAccess.WRITE)
        val board = boardReader.read(link)
        val view = GithubBoardKanbanBuilder.requireBoardView(board, request.viewNumber)
        if (view.sortBy.isNotEmpty()) {
            throw ExpectedException("정렬이 있는 뷰에서는 카드 순서를 바꿀 수 없습니다.", HttpStatus.CONFLICT)
        }
        val field = GithubBoardKanbanBuilder.columnField(board, view)
        val item = board.item(itemId) ?: throw ExpectedException("보드에서 카드를 찾을 수 없습니다.", HttpStatus.NOT_FOUND)
        val placement = GithubBoardPositionResolver.resolve(
            board.items.map { it.itemId },
            GithubBoardKanbanBuilder.columnItemIds(board, field, GithubBoardKanbanBuilder.columnOf(item, field)),
            itemId,
            request.afterItemId,
        )
        if (placement !is GithubBoardPositionResolver.Placement.After) {
            throw ExpectedException("같은 컬럼에 다른 카드가 없어 순서를 바꿀 수 없습니다.", HttpStatus.BAD_REQUEST)
        }
        val target = GithubProjectItemTarget(link.id, link.projectId, link.ownerLogin, board.projectNodeId, itemId)
        val operationId = commandPublisher.publish(
            target,
            GithubProjectItemWriteCommandType.REORDER_ITEM,
            GithubReorderItemPayload(placement.afterId),
            userId,
            Instant.now(),
        )
        return GithubBoardOperationAcceptedResDto(operationId)
    }
}
