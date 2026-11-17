package com.cowork.project.domain.githubBoard.service

import com.cowork.project.domain.githubBoard.repository.ProjectGithubBoardRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.Locale

/** 프로젝트 삭제와 GitHub 조직 이름 변경을 보드 연결에 반영한다. */
@Component
class ProjectGithubBoardLifecycleSupport(private val boardRepository: ProjectGithubBoardRepository) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun deleteByProjectIds(projectIds: Collection<Long>) {
        if (projectIds.isEmpty()) return
        val boards = boardRepository.findAllByProjectIdInForUpdate(projectIds.distinct())
        if (boards.isNotEmpty()) boardRepository.deleteAll(boards)
    }

    /**
     * 조직 이름이 바뀌면 옛 이름으로 저장한 연결을 새 이름으로 바꾼다. 그대로 두면 팀 조직 검사가 모든 연결을 막는다.
     * 새 이름의 연결이 이미 있으면 유니크 제약을 지키려고 옛 연결을 지운다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun renameOwner(teamId: Long, previousOwner: String, newOwner: String) {
        val previous = previousOwner.lowercase(Locale.ROOT)
        val renamed = newOwner.lowercase(Locale.ROOT)
        if (previous == renamed) return
        val boards = boardRepository.findAllByTeamIdForUpdate(teamId)
        val taken = boards.filter { it.ownerLogin == renamed }.mapTo(HashSet()) { it.projectId to it.projectNumber }
        boards.filter { it.ownerLogin == previous }.forEach { board ->
            if (taken.add(board.projectId to board.projectNumber)) {
                board.ownerLogin = renamed
                boardRepository.save(board)
            } else {
                boardRepository.delete(board)
            }
        }
    }
}
