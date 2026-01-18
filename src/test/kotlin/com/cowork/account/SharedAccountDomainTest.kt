package com.cowork.account

import com.cowork.channel.account.domain.AccountProvider
import com.cowork.channel.account.domain.SharedAccount
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SharedAccountDomainTest {

    private fun account(credential: String?) = SharedAccount(
        channelId = 20, provider = AccountProvider.CUSTOM, providerLabel = "사내 위키", accountIdentifier = "admin",
        encryptedCredential = credential, connectedViaOAuth = false, createdBy = 1,
    )

    @Test
    fun `빈 자격 증명은 없는 것으로 본다`() {
        assertFalse(account("").hasCredential)
        assertFalse(account(null).hasCredential)
        assertTrue(account("encrypted").hasCredential)
    }
}
