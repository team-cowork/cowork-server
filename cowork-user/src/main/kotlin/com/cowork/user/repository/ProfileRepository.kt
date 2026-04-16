package com.cowork.user.repository

import com.cowork.user.domain.Profile
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import java.util.Optional

interface ProfileRepository : JpaRepository<Profile, Long>, JpaSpecificationExecutor<Profile> {
    fun findByAccountId(accountId: Long): Optional<Profile>
    fun existsByAccountId(accountId: Long): Boolean
}
