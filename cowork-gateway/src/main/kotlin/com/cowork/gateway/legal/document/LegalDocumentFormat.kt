package com.cowork.gateway.legal.document

import org.springframework.http.MediaType
import java.nio.charset.StandardCharsets

enum class LegalDocumentFormat(val contentType: MediaType, private val acceptedTypes: List<MediaType>) {
    MARKDOWN(MediaType("text", "markdown", StandardCharsets.UTF_8), listOf(MediaType("text", "markdown"))),
    TEXT(MediaType("text", "plain", StandardCharsets.UTF_8), listOf(MediaType.TEXT_PLAIN)),
    XML(
        MediaType("application", "xml", StandardCharsets.UTF_8),
        listOf(MediaType.APPLICATION_XML, MediaType.TEXT_XML),
    ),
    ;

    companion object {
        val supportedTypes: List<MediaType> = entries.flatMap { it.acceptedTypes }

        /**
         * Rates each format by the most specific Accept range that includes it. A higher quality wins,
         * then a more specific range, then the range listed first, then the declaration order.
         * Markdown is the default when Accept is empty or only has wildcards.
         */
        fun negotiate(accept: List<MediaType>): LegalDocumentFormat? {
            if (accept.isEmpty()) return MARKDOWN
            return entries.mapNotNull { format ->
                val match = accept.withIndex()
                    .filter { (_, range) -> format.acceptedTypes.any(range::includes) }
                    .maxWithOrNull(
                        compareBy<IndexedValue<MediaType>> { specificity(it.value) }.thenByDescending { it.index },
                    ) ?: return@mapNotNull null
                Candidate(format, match.value.qualityValue, specificity(match.value), match.index)
            }
                .filter { it.quality > 0.0 }
                .minWithOrNull(
                    compareByDescending<Candidate> { it.quality }
                        .thenByDescending { it.specificity }
                        .thenBy { it.index },
                )
                ?.format
        }

        private fun specificity(range: MediaType): Int = when {
            range.isWildcardType -> 0
            range.isWildcardSubtype -> 1
            else -> 2
        }
    }

    private data class Candidate(
        val format: LegalDocumentFormat,
        val quality: Double,
        val specificity: Int,
        val index: Int,
    )
}
