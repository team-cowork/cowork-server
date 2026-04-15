package com.cowork.user.domain

import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.OneToOne
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.LocalDateTime

@Entity
@Table(name = "tb_profiles")
class Profile(

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    val account: Account,

    @Column(name = "profile_image_key", length = 500)
    var profileImageKey: String?,

    @Column(length = 50)
    var nickname: String?,

    @Column(length = 500)
    var description: String?,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now(),

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
        name = "tb_profile_roles",
        joinColumns = [JoinColumn(name = "profile_id")],
    )
    @Column(name = "role", nullable = false, length = 50)
    var roles: MutableSet<String> = mutableSetOf(),
) {
    fun updateProfile(
        nickname: String?,
        description: String?,
        roles: List<String>?,
    ) {
        this.nickname = nickname
        this.description = description
        this.roles.clear()
        this.roles.addAll(
            roles.orEmpty()
                .map(String::trim)
                .filter(String::isNotBlank)
                .toSet(),
        )
    }

    fun updateProfileImageKey(key: String?) {
        profileImageKey = key
    }
}
