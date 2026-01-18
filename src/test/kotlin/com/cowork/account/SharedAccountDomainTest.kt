package com.cowork.account

import com.cowork.channel.account.domain.AccountProvider
import com.cowork.channel.account.domain.SharedAccount
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
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

    @Test
    fun `빈 라벨로 수정하면 라벨을 비우고 자격 증명은 유지한다`() {
        val account = account("encrypted")

        account.update(" ", null, null)

        assertNull(account.providerLabel)
        assertEquals("admin", account.accountIdentifier)
        assertEquals("encrypted", account.encryptedCredential)
    }

    @Test
    fun `OAuth 지원 여부는 제공자마다 정해져 있다`() {
        assertTrue(AccountProvider.GITHUB.oauthSupported)
        assertFalse(AccountProvider.AWS.oauthSupported)
        assertNull(AccountProvider.CUSTOM.loginUrl)
    }
}
