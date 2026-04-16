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
    var name: String,

    @Column(nullable = false, unique = true)
    var email: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    var sex: Sex,

    @Column(length = 100, unique = true)
    var github: String?,

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
) {
    fun updateFromSync(
        name: String,
        email: String,
        sex: Sex,
        github: String?,
        description: String?,
        studentRole: String?,
        studentNumber: String?,
        major: String?,
        specialty: String?,
        status: String?,
    ) {
        this.name = name
        this.email = email
        this.sex = sex
        this.github = github
        this.description = description
        this.studentRole = studentRole
        this.studentNumber = studentNumber
        this.major = major
        this.specialty = specialty
        this.status = status ?: this.status
    }
}
