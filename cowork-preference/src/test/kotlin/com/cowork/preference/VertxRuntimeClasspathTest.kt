package com.cowork.preference

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VertxRuntimeClasspathTest {

    @Test
    fun `packages Vertx Web runtime dependencies`() {
        assertDoesNotThrow {
            Class.forName("io.vertx.ext.auth.audit.SecurityAudit")
        }
    }

    @Test
    fun `uses the Vertx managed SCRAM client`() {
        val scramClient = Class.forName("com.ongres.scram.client.ScramClient")
        val codeSource = scramClient.protectionDomain.codeSource.location.toExternalForm()

        assertTrue(
            codeSource.contains("scram-client-"),
            "Expected Vert.x managed scram-client artifact, but loaded $codeSource",
        )
    }
}
