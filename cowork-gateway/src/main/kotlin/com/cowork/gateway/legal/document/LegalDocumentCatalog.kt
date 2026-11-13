package com.cowork.gateway.legal.document

import org.commonmark.node.Heading
import org.commonmark.parser.Parser
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component

/** Renders every document in every format once at startup, so a missing or broken document stops the boot. */
@Component
class LegalDocumentCatalog {
    private val documents: Map<LegalDocumentType, Map<LegalDocumentFormat, String>> =
        LegalDocumentType.entries.associateWith { type ->
            val markdown = ClassPathResource(type.resourcePath).inputStream.use {
                it.readBytes().toString(Charsets.UTF_8)
            }
            render(type, markdown)
        }

    fun get(type: LegalDocumentType, format: LegalDocumentFormat): String = documents.getValue(type).getValue(format)

    companion object {
        private val parser = Parser.builder().build()

        internal fun render(type: LegalDocumentType, markdown: String): Map<LegalDocumentFormat, String> {
            val document = parser.parse(markdown)
            check((document.firstChild as? Heading)?.level == 1) {
                "Legal document ${type.resourcePath} must start with a level-1 heading"
            }
            return mapOf(
                LegalDocumentFormat.MARKDOWN to markdown.trim() + "\n",
                LegalDocumentFormat.TEXT to LegalDocumentRenderer.text(document),
                LegalDocumentFormat.XML to LegalDocumentRenderer.xml(type, document),
            )
        }
    }
}
