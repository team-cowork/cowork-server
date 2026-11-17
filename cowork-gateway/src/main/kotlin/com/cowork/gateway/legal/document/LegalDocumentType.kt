package com.cowork.gateway.legal.document

// TODO(2026-11-17T02:31:33Z): When cowork-promotion is removed, check whether the promotion site stays in service.
// If it does not, remove the promotion site text from legal/open-source.md (the intro and "Promotion Site")
// and legal/privacy-policy.md (the promotion site and Vercel text). This note is here because the legal
// Markdown cannot hold comments: LegalDocumentRenderer rejects HTML nodes.

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
