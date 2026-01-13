package com.cowork.channel.account.domain

import com.cowork.global.entity.BaseTimeEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table

@Entity
@Table(name = "tb_shared_accounts")
class SharedAccount(
    @Column(name = "channel_id", nullable = false)
    val channelId: Long,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    val provider: AccountProvider,

    @Column(name = "provider_label", length = 100)
    var providerLabel: String?,

    @Column(name = "account_identifier")
    var accountIdentifier: String?,

    @Column(name = "encrypted_credential", columnDefinition = "TEXT")
    var encryptedCredential: String?,

    @Column(name = "connected_via_oauth", nullable = false)
    val connectedViaOAuth: Boolean,

    @Column(name = "created_by", nullable = false)
    val createdBy: Long,

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
) : BaseTimeEntity() {

    val hasCredential: Boolean get() = !encryptedCredential.isNullOrEmpty()

    fun update(providerLabel: String?, accountIdentifier: String?, encryptedCredential: String?) {
        providerLabel?.let { this.providerLabel = it }
        accountIdentifier?.let { this.accountIdentifier = it }
        encryptedCredential?.let { this.encryptedCredential = it }
    }
}
