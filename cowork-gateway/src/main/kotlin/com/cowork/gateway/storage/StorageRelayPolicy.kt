package com.cowork.gateway.storage

import org.springframework.http.HttpMethod

class StorageRelayPolicy(private val bucket: String) {
    fun allows(method: HttpMethod, rawPath: String, queryNames: Set<String>): Boolean {
        if (!rawPath.startsWith("/api/storage/$bucket/")) return false
        // 스토리지는 SigV4 presigned 요청의 모든 query를 서명 검증에 포함한다. 서명 요청에 붙은 x-amz-*와 response-*
        // 파라미터는 스토리지가 검증하므로, SDK가 새 파라미터를 추가해도 Gateway를 고치지 않는다.
        val signed = queryNames.containsAll(signatureQueryNames)
        val queryAllowed = if (signed) {
            queryNames.all(::isSignedQueryName)
        } else {
            queryNames.all {
                it in
                    unsignedQueryNames
            }
        }
        if (!queryAllowed) return false
        val key = rawPath.removePrefix("/api/storage/$bucket/")
        return when (method) {
            HttpMethod.GET, HttpMethod.HEAD -> publicIcon.matches(key) || (privateFile.matches(key) && signed)
            HttpMethod.PUT -> upload.matches(key) && signed
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
        private val signatureQueryNames = setOf("X-Amz-Algorithm", "X-Amz-Credential", "X-Amz-Signature")

        // S3 subresource(acl, uploads, versionId 등)는 접두사가 없으므로 서명 요청에서도 거부한다.
        private fun isSignedQueryName(name: String): Boolean = name.lowercase().let {
            it.startsWith("x-amz-") || it.startsWith("response-") || it == "x-id"
        }

        // 서명 없는 공개 아이콘 읽기는 스토리지가 query를 검증하지 않으므로 이름 목록으로 제한한다.
        private val unsignedQueryNames = setOf(
            "X-Amz-Algorithm", "X-Amz-Credential", "X-Amz-Date", "X-Amz-Expires",
            "X-Amz-SignedHeaders", "X-Amz-Signature", "X-Amz-Security-Token",
            "X-Amz-Content-Sha256", "x-amz-checksum-crc32", "x-amz-sdk-checksum-algorithm",
            "x-amz-checksum-mode", "x-id", "response-cache-control", "response-content-disposition",
        )
    }
}
