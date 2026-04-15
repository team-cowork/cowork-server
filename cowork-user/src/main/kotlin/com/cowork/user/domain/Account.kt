package com.cowork.user.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.LocalDateTime

@Entity
@Table(name = "tb_accounts")
class Account(

    @Id
    val id: Long,

    @Column(nullable = false, length = 50)
    val name: String,

    @Column(nullable = false, unique = true)
    val email: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    val sex: Sex,

    @Column(length = 100, unique = true)
    val github: String?,

    @Column(length = 500)
    var description: String?,

    @Column(name = "student_role", length = 50)
    var studentRole: String?,

    @Column(name = "student_number", length = 30)
    var studentNumber: String?,

    @Column(length = 50)
    var major: String?,

    @Column(name = "specialty", length = 255)
    var specialty: String?,

    @Column(nullable = false, length = 30)
    var status: String,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now(),
)
