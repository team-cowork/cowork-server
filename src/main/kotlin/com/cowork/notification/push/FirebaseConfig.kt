package com.cowork.notification.push

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.io.FileInputStream

@Configuration
class FirebaseConfig {

    @Bean
    @ConditionalOnExpression(FCM_ENABLED)
    fun firebaseMessaging(@Value("\${cowork.firebase.credentials-path}") credentialsPath: String): FirebaseMessaging {
        val app = FirebaseApp.getApps().firstOrNull() ?: FileInputStream(credentialsPath).use { input ->
            FirebaseApp.initializeApp(
                FirebaseOptions.builder().setCredentials(GoogleCredentials.fromStream(input)).build(),
            )
        }
        return FirebaseMessaging.getInstance(app)
    }

    @Bean
    @ConditionalOnExpression(FCM_ENABLED)
    fun fcmPushSender(firebaseMessaging: FirebaseMessaging): PushSender = FcmPushSender(firebaseMessaging)

    @Bean
    @ConditionalOnMissingBean(PushSender::class)
    fun noOpPushSender(): PushSender = NoOpPushSender()

    companion object {
        private const val FCM_ENABLED = "'\${cowork.firebase.credentials-path:}' != ''"
    }
}
