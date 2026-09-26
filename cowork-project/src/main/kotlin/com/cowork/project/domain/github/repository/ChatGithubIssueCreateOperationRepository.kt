package com.cowork.project.domain.github.repository

import com.cowork.project.domain.github.entity.ChatGithubIssueCreateOperation
import org.springframework.data.jpa.repository.JpaRepository

interface ChatGithubIssueCreateOperationRepository : JpaRepository<ChatGithubIssueCreateOperation, String>
