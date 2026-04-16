package com.cowork.user.domain

import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.LocalDateTime

@Entity
@Table(name = "tb_user_profiles")
class UserProfile(

    @Id
    val id: Long,

    @Column(nullable = false, length = 50)
    var name: String,

    @Column(nullable = false, unique = true)
    var email: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    var sex: Sex,

    @Column
    var grade: Byte?,

    @Column(name = "class")
    var `class`: Byte?,

    @Column(name = "class_num")
    var classNum: Byte?,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var major: Major,

    @Column(length = 255)
    var specialty: String?,

    @Column(name = "github_id", length = 100, unique = true)
    var githubId: String?,

    @Column(name = "profile_image_key", length = 500)
    var profileImageKey: String?,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    var role: Role,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now(),
) {
    fun updateSpecialty(newSpecialty: String?) {
        specialty = newSpecialty
    }

    fun updateProfileImageKey(key: String?) {
        profileImageKey = key
    }

    fun updateFromSync(
        name: String,
        email: String,
        sex: Sex,
        grade: Byte?,
        `class`: Byte?,
        classNum: Byte?,
        major: Major,
        role: Role,
        githubId: String?,
    ) {
        this.name = name
        this.email = email
        this.sex = sex
        this.grade = grade
        this.`class` = `class`
        this.classNum = classNum
        this.major = major
        this.role = role
        this.githubId = githubId
    }
}
