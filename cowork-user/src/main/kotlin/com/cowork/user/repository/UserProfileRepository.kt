package com.cowork.user.repository

import com.cowork.user.domain.UserProfile
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor

interface UserProfileRepository : JpaRepository<UserProfile, Long>, JpaSpecificationExecutor<UserProfile>

fun UserProfileRepository.findByIdOrThrow(id: Long): UserProfile =
    findById(id).orElseThrow { NoSuchElementException("사용자를 찾을 수 없습니다. id=$id") }
