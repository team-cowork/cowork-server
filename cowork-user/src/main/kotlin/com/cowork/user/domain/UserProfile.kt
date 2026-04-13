package com.cowork.user.domain

import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "tb_user_profiles")
class UserProfile(

    @Id
    val id: Long,

    @Column(nullable = false, length = 50)
    val name: String,

    @Column(nullable = false, unique = true)
    val email: String,

    @Column(nullable = false, length = 10)
    val sex: String,

    @Column
    val grade: Byte?,

    @Column(name = "class")
    val `class`: Byte?,

    @Column(name = "class_num")
    val classNum: Byte?,

    @Column(nullable = false, length = 20)
    val major: String,

    @Column(length = 255)
    var specialty: String?,

    @Column(name = "github_id", length = 100, unique = true)
    val githubId: String?,

    @Column(name = "profile_image_key", length = 500)
    var profileImageKey: String?,

    @Column(nullable = false, length = 30)
    val role: String,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now(),
) {
    fun updateSpecialty(newSpecialty: String?) {
        specialty = newSpecialty
        updatedAt = LocalDateTime.now()
    }

    fun updateProfileImageKey(key: String?) {
        profileImageKey = key
        updatedAt = LocalDateTime.now()
    }
}
