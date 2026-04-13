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
    val name: String,

    @Column(nullable = false, unique = true)
    val email: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    val sex: Sex,

    @Column
    val grade: Byte?,

    @Column(name = "class")
    val `class`: Byte?,

    @Column(name = "class_num")
    val classNum: Byte?,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val major: Major,

    @Column(length = 255)
    var specialty: String?,

    @Column(name = "github_id", length = 100, unique = true)
    val githubId: String?,

    @Column(name = "profile_image_key", length = 500)
    var profileImageKey: String?,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    val role: Role,

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
}
