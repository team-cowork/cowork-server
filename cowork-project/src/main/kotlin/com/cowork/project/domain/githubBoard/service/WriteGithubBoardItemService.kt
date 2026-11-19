package com.cowork.project.domain.githubBoard.service

import com.cowork.project.domain.githubBoard.presentation.data.request.AddGithubBoardItemReqDto
import com.cowork.project.domain.githubBoard.presentation.data.request.ConvertGithubBoardDraftReqDto
import com.cowork.project.domain.githubBoard.presentation.data.request.CreateGithubBoardDraftReqDto
import com.cowork.project.domain.githubBoard.presentation.data.request.SetGithubBoardAssigneesReqDto
import com.cowork.project.domain.githubBoard.presentation.data.request.SetGithubBoardFieldReqDto
import com.cowork.project.domain.githubBoard.presentation.data.request.UpdateGithubBoardDraftReqDto
import com.cowork.project.domain.githubBoard.presentation.data.response.GithubBoardOperationAcceptedResDto

interface WriteGithubBoardItemService {
    fun setField(
        userId: Long,
        projectId: Long,
        boardId: Long,
        itemId: String,
        fieldId: String,
        request: SetGithubBoardFieldReqDto,
    ): GithubBoardOperationAcceptedResDto

    fun clearField(
        userId: Long,
        projectId: Long,
        boardId: Long,
        itemId: String,
        fieldId: String,
    ): GithubBoardOperationAcceptedResDto

    fun createDraft(
        userId: Long,
        projectId: Long,
        boardId: Long,
        request: CreateGithubBoardDraftReqDto,
    ): GithubBoardOperationAcceptedResDto

    fun updateDraft(
        userId: Long,
        projectId: Long,
        boardId: Long,
        itemId: String,
        request: UpdateGithubBoardDraftReqDto,
    ): GithubBoardOperationAcceptedResDto

    fun addItem(
        userId: Long,
        projectId: Long,
        boardId: Long,
        request: AddGithubBoardItemReqDto,
    ): GithubBoardOperationAcceptedResDto

    fun removeItem(userId: Long, projectId: Long, boardId: Long, itemId: String): GithubBoardOperationAcceptedResDto

    fun convertDraft(
        userId: Long,
        projectId: Long,
        boardId: Long,
        itemId: String,
        request: ConvertGithubBoardDraftReqDto,
    ): GithubBoardOperationAcceptedResDto

    fun setAssignees(
        userId: Long,
        projectId: Long,
        boardId: Long,
        itemId: String,
        request: SetGithubBoardAssigneesReqDto,
    ): GithubBoardOperationAcceptedResDto
}
