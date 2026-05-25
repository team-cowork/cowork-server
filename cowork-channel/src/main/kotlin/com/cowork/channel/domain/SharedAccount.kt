package com.cowork.channel.domain

import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.LocalDateTime

@Entity
@Table(name = "tb_channel_accounts")
class SharedAccount(

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "channel_id", nullable = false)
    val channelId: Long,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    val provider: AccountProvider,

    @Column(name = "provider_label", length = 100)
    var providerLabel: String?,

    @Column(name = "account_identifier", length = 255)
    var accountIdentifier: String?,

    @Column(columnDefinition = "TEXT")
    var credential: String?,

    @Column(name = "connected_via_oauth", nullable = false)
    val connectedViaOAuth: Boolean = false,

    @Column(name = "created_by", nullable = false)
    val createdBy: Long,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now(),
) {
    fun update(accountIdentifier: String?, credential: String?, providerLabel: String?) {
        accountIdentifier?.let { this.accountIdentifier = it }
        credential?.let { this.credential = it }
        providerLabel?.let { this.providerLabel = it }
    }
}
