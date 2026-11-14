package com.cowork.gateway.legal.document

/**
 * The privacy policy and terms of service copy `cowork-promotion/src/html/{privacy,terms}.html`,
 * and the license copies the repository `LICENSE`. Update the copies in `resources/legal` when a source changes.
 */
enum class LegalDocumentType(val slug: String) {
    LICENSE("license"),
    OPEN_SOURCE("open-source"),
    PRIVACY_POLICY("privacy-policy"),
    TERMS_OF_SERVICE("terms-of-service"),
    ;

    val resourcePath: String get() = "legal/$slug.md"
}
