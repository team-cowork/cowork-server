package com.cowork.user.repository

import com.cowork.user.domain.User
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface UserRepository : JpaRepository<User, Long> {

    fun findByDataGsmId(dataGsmId: Long): User?

    @Query(
        """
        SELECT u FROM User u
        WHERE (:keyword IS NULL
               OR u.name LIKE CONCAT('%', :keyword, '%')
               OR u.nickname LIKE CONCAT('%', :keyword, '%')
               OR u.email LIKE CONCAT('%', :keyword, '%'))
          AND (:major IS NULL OR u.major = :major)
          AND (:grade IS NULL OR u.grade = :grade)
        """,
    )
    fun search(keyword: String?, major: String?, grade: Int?, pageable: Pageable): Page<User>
}
