package com.cowork.gateway.legal.document

/**
 * The privacy policy and terms of service in `resources/legal` are the source of truth for clients.
 * The license copies the repository `LICENSE`; update its resource when that source changes.
 */
enum class LegalDocumentType(val slug: String) {
    LICENSE("license"),
    OPEN_SOURCE("open-source"),
    PRIVACY_POLICY("privacy-policy"),
    TERMS_OF_SERVICE("terms-of-service"),
    ;

    val resourcePath: String get() = "legal/$slug.md"
}
