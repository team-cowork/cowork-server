package com.cowork.channel.account.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import java.time.LocalDateTime

@Entity
@Table(name = "tb_credential_copy_histories")
class CredentialCopyHistory(
    @Column(name = "account_id", nullable = false)
    val accountId: Long,

    @Column(name = "user_id", nullable = false)
    val userId: Long,

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
) {
    @CreationTimestamp
    @Column(name = "copied_at", nullable = false, updatable = false)
    var copiedAt: LocalDateTime = LocalDateTime.now()
        protected set
}
