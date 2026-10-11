package com.tappet.app.ui.chat

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/**
 * Markdown 渲染：块级结构交给 [Markdown] 解析，行内样式翻成 AnnotatedString。
 *
 * 和 WebView 相比这是「够用」档：标题 / 列表 / 引用 / 代码块 / 粗斜体 / 行内代码 / 链接。
 * 表格、公式、图片、HTML 走 Web 预览面板。
 */
@Composable
fun MarkdownText(
  markdown: String,
  modifier: Modifier = Modifier,
  style: TextStyle = MaterialTheme.typography.bodyMedium,
) {
  val blocks = remember(markdown) { Markdown.parse(markdown) }
  Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
    blocks.forEach { block -> MarkdownBlockView(block, style) }
  }
}

@Composable
private fun MarkdownBlockView(block: MarkdownBlock, style: TextStyle) {
  when (block) {
    is MarkdownBlock.Paragraph -> InlineMarkdown(block.text, style)

    is MarkdownBlock.Heading -> {
      val headingStyle =
        when (block.level) {
          1, 2 -> MaterialTheme.typography.titleMedium
          3 -> MaterialTheme.typography.titleSmall
          else -> MaterialTheme.typography.bodyLarge
        }
      InlineMarkdown(block.text, headingStyle.copy(fontWeight = FontWeight.SemiBold))
    }

    is MarkdownBlock.CodeBlock -> CodeBlock(block)

    is MarkdownBlock.BulletList ->
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        block.items.forEach { item -> ListRow(marker = "•", text = item, style = style) }
      }

    is MarkdownBlock.NumberedList ->
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        block.items.forEachIndexed { index, item -> ListRow(marker = "${index + 1}.", text = item, style = style) }
      }

    is MarkdownBlock.Quote ->
      Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
      ) {
        InlineMarkdown(
          text = block.text,
          style = style.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
          modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        )
      }

    MarkdownBlock.Divider -> HorizontalDivider(Modifier.padding(vertical = 4.dp))
  }
}

@Composable
private fun ListRow(marker: String, text: String, style: TextStyle) {
  Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    Text(text = marker, style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
    InlineMarkdown(text, style)
  }
}

@Composable
private fun CodeBlock(block: MarkdownBlock.CodeBlock) {
  Surface(
    color = MaterialTheme.colorScheme.surfaceContainerHighest,
    shape = RoundedCornerShape(10.dp),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
      block.language?.takeIf { it.isNotBlank() }?.let { language ->
        Text(
          text = language,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(bottom = 4.dp),
        )
      }
      SelectionContainer {
        Text(
          text = block.code,
          style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
          softWrap = false,
          modifier = Modifier.horizontalScroll(rememberScrollState()),
        )
      }
    }
  }
}

@Composable
private fun InlineMarkdown(text: String, style: TextStyle, modifier: Modifier = Modifier) {
  val linkColor = MaterialTheme.colorScheme.primary
  val codeBackground = MaterialTheme.colorScheme.surfaceContainerHighest
  val annotated = remember(text, linkColor, codeBackground) { text.toAnnotatedString(linkColor, codeBackground) }
  SelectionContainer(modifier) { Text(text = annotated, style = style) }
}

private fun String.toAnnotatedString(linkColor: Color, codeBackground: Color): AnnotatedString =
  buildAnnotatedString {
    Markdown.inline(this@toAnnotatedString).forEach { token ->
      when (token) {
        is InlineToken.Text -> append(token.text)

        is InlineToken.Code ->
          withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)) { append(token.text) }

        is InlineToken.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(token.text) }

        is InlineToken.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(token.text) }

        is InlineToken.Link ->
          withLink(
            LinkAnnotation.Url(
              url = token.url,
              styles = TextLinkStyles(style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
            )
          ) {
            append(token.text)
          }
      }
    }
  }
