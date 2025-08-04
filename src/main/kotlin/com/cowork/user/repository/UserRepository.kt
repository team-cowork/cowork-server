package com.cowork.user.repository

import com.cowork.user.domain.User
import org.springframework.data.jpa.repository.JpaRepository

interface UserRepository : JpaRepository<User, Long> {

    fun findByDataGsmId(dataGsmId: Long): User?
}
