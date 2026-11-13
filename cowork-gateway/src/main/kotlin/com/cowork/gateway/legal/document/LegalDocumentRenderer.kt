package com.cowork.gateway.legal.document

import org.commonmark.node.Heading
import org.commonmark.node.Link
import org.commonmark.node.ListBlock
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.renderer.NodeRenderer
import org.commonmark.renderer.text.LineBreakRendering
import org.commonmark.renderer.text.TextContentNodeRendererContext
import org.commonmark.renderer.text.TextContentRenderer
import java.io.StringWriter
import javax.xml.stream.XMLOutputFactory
import javax.xml.stream.XMLStreamWriter

internal object LegalDocumentRenderer {
    private const val INDENT = "  "

    private val textRenderer = TextContentRenderer.builder()
        .lineBreakRendering(LineBreakRendering.SEPARATE_BLOCKS)
        .nodeRendererFactory(::PlainLinkRenderer)
        .build()

    fun text(document: Node): String = textRenderer.render(document).trimEnd() + "\n"

    /**
     * The first level-1 heading becomes `title`. Each later heading opens a `section`
     * that holds the blocks up to the next heading of the same or a higher level.
     */
    fun xml(type: LegalDocumentType, document: Node): String {
        val output = StringWriter()
        val writer = XMLOutputFactory.newInstance().createXMLStreamWriter(output)
        val openSections = ArrayDeque<Int>()
        var hasTitle = false

        writer.writeStartDocument("UTF-8", "1.0")
        writer.writeCharacters("\n")
        writer.writeStartElement("document")
        writer.writeAttribute("type", type.slug)
        for (block in document.children()) {
            if (block is Heading) {
                if (block.level == 1 && !hasTitle && openSections.isEmpty()) {
                    hasTitle = true
                    writer.inlineElement("title", block, depth = 1)
                    continue
                }
                while (openSections.lastOrNull()?.let { it >= block.level } == true) {
                    openSections.removeLast()
                    writer.indent(openSections.size + 1)
                    writer.writeEndElement()
                }
                writer.indent(openSections.size + 1)
                writer.writeStartElement("section")
                openSections.addLast(block.level)
                writer.inlineElement("heading", block, depth = openSections.size + 1)
                continue
            }
            val depth = openSections.size + 1
            when (block) {
                is Paragraph -> writer.inlineElement("paragraph", block, depth)

                is ListBlock -> {
                    writer.indent(depth)
                    writer.writeStartElement("list")
                    writer.writeAttribute("ordered", (block is OrderedList).toString())
                    for (item in block.children()) {
                        val paragraph = (item as ListItem).children().singleOrNull() as? Paragraph
                            ?: unsupported(item)
                        writer.inlineElement("item", paragraph, depth + 1)
                    }
                    writer.indent(depth)
                    writer.writeEndElement()
                }

                else -> unsupported(block)
            }
        }
        while (openSections.isNotEmpty()) {
            openSections.removeLast()
            writer.indent(openSections.size + 1)
            writer.writeEndElement()
        }
        writer.writeCharacters("\n")
        writer.writeEndElement()
        writer.writeEndDocument()
        writer.close()
        return output.append('\n').toString()
    }

    private fun Node.children(): Sequence<Node> = generateSequence(firstChild) { it.next }

    private fun Node.label(): String = children().joinToString("") { (it as? Text)?.literal ?: it.label() }

    private fun unsupported(node: Node): Nothing =
        error("Legal documents do not support Markdown ${node::class.simpleName} nodes")

    private fun XMLStreamWriter.indent(depth: Int) = writeCharacters("\n" + INDENT.repeat(depth))

    private fun XMLStreamWriter.inlineElement(name: String, parent: Node, depth: Int) {
        indent(depth)
        writeStartElement(name)
        writeInline(parent)
        writeEndElement()
    }

    private fun XMLStreamWriter.writeInline(parent: Node) {
        for (node in parent.children()) {
            when (node) {
                is Text -> writeCharacters(node.literal)

                is SoftLineBreak -> writeCharacters(" ")

                is StrongEmphasis -> {
                    writeStartElement("strong")
                    writeInline(node)
                    writeEndElement()
                }

                is Link -> {
                    writeStartElement("link")
                    writeAttribute("href", node.destination)
                    writeInline(node)
                    writeEndElement()
                }

                else -> unsupported(node)
            }
        }
    }

    /** Writes a link as `text (url)`, and only the text when the text already shows the address. */
    private class PlainLinkRenderer(private val context: TextContentNodeRendererContext) : NodeRenderer {
        override fun getNodeTypes(): Set<Class<out Node>> = setOf(Link::class.java)

        override fun render(node: Node) {
            val link = node as Link
            val text = link.label()
            val writer = context.writer
            writer.write(text)
            if (link.destination != text && link.destination != "mailto:$text") {
                writer.write(" (${link.destination})")
            }
        }
    }
}
