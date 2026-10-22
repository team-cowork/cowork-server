package com.cowork.project.domain.github.repository

import com.cowork.project.domain.github.entity.GithubIssueCommentProjection
import org.springframework.data.jpa.repository.JpaRepository

interface GithubIssueCommentProjectionRepository : JpaRepository<GithubIssueCommentProjection, String> {
    // 레포 안에서 이슈 번호와 PR 번호는 번호 공간을 공유해 겹치지 않으므로, parentNumber만으로
    // 특정 댓글 스레드를 유일하게 특정할 수 있다 — parentType은 조회 조건이 아니라 부가 정보다.
    // githubCreatedAt은 ISO-8601 문자열이라 사전식 정렬이 시간 순서와 같다.
    fun findByOwnerAndRepoAndParentNumberAndDeletedFalseOrderByGithubCreatedAtAsc(
        owner: String,
        repo: String,
        parentNumber: Int,
    ): List<GithubIssueCommentProjection>

    fun findByOwnerAndRepoAndCommentId(owner: String, repo: String, commentId: Long): GithubIssueCommentProjection?
}
