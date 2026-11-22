package com.cowork.project.domain.githubBoard.service.impl

import com.cowork.project.domain.githubBoard.event.GithubMoveItemPayload
import com.cowork.project.domain.githubBoard.event.GithubProjectItemTarget
import com.cowork.project.domain.githubBoard.event.GithubProjectItemWriteCommandPublisher
import com.cowork.project.domain.githubBoard.event.GithubProjectItemWriteCommandType
import com.cowork.project.domain.githubBoard.model.GithubBoardKanbanBuilder
import com.cowork.project.domain.githubBoard.model.GithubBoardPositionResolver
import com.cowork.project.domain.githubBoard.model.GithubBoardPositionResolver.Placement
import com.cowork.project.domain.githubBoard.presentation.data.request.MoveGithubBoardItemReqDto
import com.cowork.project.domain.githubBoard.presentation.data.response.GithubBoardOperationAcceptedResDto
import com.cowork.project.domain.githubBoard.service.GithubBoardAccess
import com.cowork.project.domain.githubBoard.service.GithubBoardAccessResolver
import com.cowork.project.domain.githubBoard.service.GithubBoardReader
import com.cowork.project.domain.githubBoard.service.MoveGithubBoardItemService
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException
import java.time.Instant

/**
 * 카드를 Projection 기준으로 검증하고 작업으로 접수한다. 실제 변경은 `cowork-github`가 GitHub에 적용하며,
 * 보드에는 `projects_v2_item` 웹훅이 Projection을 바꾼 뒤에 보인다.
 */
@Service
class MoveGithubBoardItemServiceImpl(
    private val githubBoardAccessResolver: GithubBoardAccessResolver,
    private val githubBoardReader: GithubBoardReader,
    private val githubProjectItemWriteCommandPublisher: GithubProjectItemWriteCommandPublisher,
) : MoveGithubBoardItemService {

    @Transactional
    override fun execute(
        userId: Long,
        projectId: Long,
        boardId: Long,
        itemId: String,
        request: MoveGithubBoardItemReqDto,
    ): GithubBoardOperationAcceptedResDto {
        val link = githubBoardAccessResolver.resolve(userId, projectId, boardId, GithubBoardAccess.WRITE)
        val board = githubBoardReader.read(link)
        val view = GithubBoardKanbanBuilder.requireBoardView(board, request.viewNumber)
        val field = GithubBoardKanbanBuilder.columnField(board, view)
        board.item(itemId) ?: throw ExpectedException("보드에서 카드를 찾을 수 없습니다.", HttpStatus.NOT_FOUND)
        val columnId = request.columnId
        if (columnId != null && !GithubBoardKanbanBuilder.isColumn(field, columnId)) {
            throw ExpectedException("Board 뷰에 없는 컬럼입니다.", HttpStatus.BAD_REQUEST)
        }
        // 정렬이 있는 뷰는 GitHub에서도 컬럼 안 순서를 바꿀 수 없어 컬럼 값만 바꾼다.
        val placement = if (view.sortBy.isEmpty()) {
            GithubBoardPositionResolver.resolve(
                board.items.map { it.itemId },
                GithubBoardKanbanBuilder.columnItemIds(board, field, columnId),
                itemId,
                request.afterItemId,
            )
        } else {
            Placement.Unchanged
        }
        val payload = GithubMoveItemPayload(
            fieldId = field.id,
            singleSelectOptionId = columnId.takeIf { field.dataType == "SINGLE_SELECT" },
            iterationId = columnId.takeIf { field.dataType == "ITERATION" },
            reposition = placement is Placement.After,
            afterItemId = (placement as? Placement.After)?.afterId,
        )
        val target = GithubProjectItemTarget(link.id, link.projectId, link.ownerLogin, board.projectNodeId, itemId)
        val operationId = githubProjectItemWriteCommandPublisher.publish(
            target,
            GithubProjectItemWriteCommandType.MOVE_ITEM,
            payload,
            userId,
            Instant.now(),
        )
        return GithubBoardOperationAcceptedResDto(operationId)
    }
}
