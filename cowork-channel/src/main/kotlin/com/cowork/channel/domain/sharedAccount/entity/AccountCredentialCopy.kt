package com.cowork.channel.domain.sharedAccount.entity

import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import java.time.LocalDateTime

@Entity
@Table(name = "tb_account_credential_copies")
class AccountCredentialCopy(

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "account_id", nullable = false)
    val accountId: Long,

    @Column(name = "user_id", nullable = false)
    val userId: Long,

    @CreationTimestamp
    @Column(name = "copied_at", nullable = false, updatable = false)
    val copiedAt: LocalDateTime = LocalDateTime.now(),
)
