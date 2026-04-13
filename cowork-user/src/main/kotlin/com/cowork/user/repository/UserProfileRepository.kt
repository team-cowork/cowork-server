package com.cowork.user.repository

import com.cowork.user.domain.UserProfile
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor

interface UserProfileRepository : JpaRepository<UserProfile, Long>, JpaSpecificationExecutor<UserProfile>
