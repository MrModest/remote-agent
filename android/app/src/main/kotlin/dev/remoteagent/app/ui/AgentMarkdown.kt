package dev.remoteagent.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import coil3.compose.AsyncImage
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.compose.elements.MarkdownParagraph
import com.mikepenz.markdown.compose.elements.MarkdownTable
import com.mikepenz.markdown.compose.elements.MarkdownTableHeader
import com.mikepenz.markdown.compose.elements.MarkdownTableRow
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.ImageData
import com.mikepenz.markdown.model.ImageTransformer
import com.mikepenz.markdown.model.markdownAnnotator
import com.mikepenz.markdown.model.markdownDimens
import com.mikepenz.markdown.model.markdownPadding
import com.mikepenz.markdown.model.rememberMarkdownState
import com.mikepenz.markdown.utils.getUnescapedTextInNode
import com.mikepenz.markdown.utils.resolveImageAlt
import com.mikepenz.markdown.utils.resolveImageLink
import dev.remoteagent.core.protocol.ImageRef
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.getTextInNode

/**
 * The transcript's Markdown: GitHub-flavoured, in the palette's colours, at
 * the message size from Settings. Raw HTML is shown as text, never rendered.
 * `images` are the ones rad keeps that the text links to as `rad-image:<id>`.
 * Mirrors AgentMarkdown in apple/Shared/Views/AgentMarkdown.swift.
 */
@Composable
fun AgentMarkdown(text: String, modifier: Modifier = Modifier, images: List<ImageRef> = emptyList()) {
    val p = palette
    val body = messageStyle().copy(color = p.text)
    val code = codeStyle(0.95f).copy(color = p.text)
    fun heading(scale: Float) = body.copy(
        fontSize = body.fontSize * scale, lineHeight = body.fontSize * scale * 1.25f, fontWeight = FontWeight.SemiBold,
    )
    // Parsed off the main thread; while a reply streams, the last parse stays up
    // until the next is ready. Short texts parse at once, so a list keeps its place.
    val state = rememberMarkdownState(content = text, retainState = true, immediate = text.length < 4000)
    Markdown(
        markdownState = state,
        modifier = modifier.fillMaxWidth(),
        colors = markdownColor(
            text = p.text, codeBackground = p.crust, inlineCodeBackground = p.surface0, dividerColor = p.surface1,
            tableBackground = p.mantle,
        ),
        typography = markdownTypography(
            h1 = heading(1.4f), h2 = heading(1.25f), h3 = heading(1.1f), h4 = heading(1f), h5 = heading(0.9f), h6 = heading(0.85f),
            text = body, paragraph = body, ordered = body, bullet = body, list = body, table = body,
            code = code, inlineCode = body.copy(fontFamily = FontFamily.Monospace, fontSize = body.fontSize * 0.95f),
            quote = body.copy(color = p.subtext, fontStyle = FontStyle.Normal),
            textLink = TextLinkStyles(SpanStyle(color = p.blue)),
        ),
        padding = markdownPadding(block = 4.dp, list = 2.dp, listItemTop = 2.dp, listItemBottom = 0.dp, codeBlock = androidx.compose.foundation.layout.PaddingValues(8.dp)),
        dimens = markdownDimens(codeBackgroundCornerSize = Metrics.Corner.inset, tableCornerSize = Metrics.Corner.inset, blockQuoteThickness = 3.dp),
        imageTransformer = InlineImages,
        annotator = markdownAnnotator { content, child ->
            // Inline HTML stays as written, as on the web and iOS.
            if (child.type == MarkdownTokenTypes.HTML_TAG) {
                append(child.getTextInNode(content).toString())
                true
            } else {
                false
            }
        },
        components = markdownComponents(
            // The defaults ellipsize every cell after one line, even when the table scrolls.
            table = { model ->
                MarkdownTable(
                    model.content, model.node, model.typography.table,
                    headerBlock = { content, node, width, style ->
                        MarkdownTableHeader(content, node, width, style, maxLines = Int.MAX_VALUE)
                    },
                    rowBlock = { content, node, width, style ->
                        MarkdownTableRow(content, node, width, style, maxLines = Int.MAX_VALUE)
                    },
                )
            },
            codeFence = { CodeFence { style -> MarkdownCodeFence(it.content, it.node, style) { c, _, s -> CodeSnippet(c, s) } } },
            codeBlock = { CodeFence { style -> MarkdownCodeBlock(it.content, it.node, style) { c, _, s -> CodeSnippet(c, s) } } },
            paragraph = { model ->
                // A paragraph of only images: the images rad keeps, drawn as reply images.
                val parts = model.node.children.filter { it.type != MarkdownTokenTypes.EOL && it.type != MarkdownTokenTypes.WHITE_SPACE }
                val links = parts.takeIf { list -> list.isNotEmpty() && list.all { it.type == MarkdownElementTypes.IMAGE } }
                    ?.map { it.resolveImageLink(model.content, null) to it.resolveImageAlt(model.content) }
                if (links != null) {
                    Column(Modifier.padding(vertical = 4.dp)) {
                        for ((link, alt) in links) {
                            val ref = link?.let(ImageRef::idLinkedBy)?.let { id -> images.firstOrNull { it.id == id } }
                            when {
                                ref != null -> ReplyImage(ref, images)
                                // Other images load as they would on the web.
                                link != null && (link.startsWith("https://") || link.startsWith("http://")) -> AsyncImage(
                                    model = link, contentDescription = alt,
                                    modifier = Modifier.heightIn(max = 320.dp).clip(RoundedCornerShape(Metrics.Corner.card)),
                                )
                                else -> Text(alt ?: link ?: "", style = body.copy(color = p.subtext))
                            }
                        }
                    }
                } else {
                    MarkdownParagraph(model.content, model.node, style = model.typography.paragraph)
                }
            },
            custom = { type, model ->
                if (type == MarkdownElementTypes.HTML_BLOCK) {
                    Text(model.node.getUnescapedTextInNode(model.content).trimEnd(), style = code.copy(color = p.subtext), modifier = Modifier.padding(vertical = 2.dp))
                }
            },
        ),
    )
}

@Composable
private fun CodeFence(content: @Composable (TextStyle) -> Unit) {
    content(codeStyle(0.95f).copy(color = palette.text))
}

/** A fenced code block: scrolls sideways, on crust. */
@Composable
private fun CodeSnippet(code: String, style: TextStyle) {
    SelectionContainer {
        Text(
            code, style = style, softWrap = false,
            modifier = Modifier.padding(vertical = 4.dp).fillMaxWidth()
                .background(palette.crust, RoundedCornerShape(Metrics.Corner.inset))
                .horizontalScroll(rememberScrollState()).padding(8.dp),
        )
    }
}

/** Images inside a line of text: not drawn (iOS shows a small thumbnail; the reply's images show below it). */
private object InlineImages : ImageTransformer {
    @Composable
    override fun transform(link: String): ImageData? = null
}
