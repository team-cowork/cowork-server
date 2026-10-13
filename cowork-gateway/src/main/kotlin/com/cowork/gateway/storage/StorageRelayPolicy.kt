package com.cowork.gateway.storage

import org.springframework.http.HttpMethod

class StorageRelayPolicy(private val bucket: String) {
    fun allows(method: HttpMethod, rawPath: String, queryNames: Set<String>): Boolean {
        if (!rawPath.startsWith("/api/storage/$bucket/")) return false
        if (!queryNames.all { it in allowedQueryNames }) return false
        val key = rawPath.removePrefix("/api/storage/$bucket/")
        return when (method) {
            HttpMethod.GET, HttpMethod.HEAD -> publicIcon.matches(key) ||
                (privateFile.matches(key) && "X-Amz-Signature" in queryNames)

            HttpMethod.PUT -> upload.matches(key) && "X-Amz-Signature" in queryNames

            else -> false
        }
    }

    companion object {
        const val MAX_UPLOAD_BYTES = 100L * 1024 * 1024
        private const val UUID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
        private val publicIcon = Regex("team-icons/$UUID\\.(jpg|png|webp)")
        private val privateFile = Regex("(profiles/[1-9][0-9]*|chat-files/[1-9][0-9]*/[1-9][0-9]*)/$UUID\\.[a-z0-9]+")
        private val upload =
            Regex("((team-icon|profile)-uploads/[1-9][0-9]*|chat-uploads/[1-9][0-9]*/[1-9][0-9]*)/$UUID\\.[a-z0-9]+")
        private val allowedQueryNames = setOf(
            "X-Amz-Algorithm", "X-Amz-Credential", "X-Amz-Date", "X-Amz-Expires",
            "X-Amz-SignedHeaders", "X-Amz-Signature", "X-Amz-Security-Token",
            "X-Amz-Content-Sha256", "x-amz-checksum-crc32", "x-amz-sdk-checksum-algorithm",
            "x-id", "response-cache-control", "response-content-disposition",
        )
    }
}
