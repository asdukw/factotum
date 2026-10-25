package com.factotum.app.ui.chat

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.Markdown
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.highlightedCodeBlock
import com.mikepenz.markdown.compose.elements.highlightedCodeFence
import com.mikepenz.markdown.m3.elements.MarkdownCheckBox
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownDimens
import com.mikepenz.markdown.model.markdownPadding

/**
 * Markdown 渲染：交给 multiplatform-markdown-renderer（JetBrains 解析器 + GFM），
 * 输出 Compose 原生组件，不是 WebView。
 *
 * 覆盖模型实际会写的那一档：标题 / 段落 / 嵌套列表 / 任务列表 / 表格 / 引用 / 代码围栏
 * （带语法高亮）/ 粗斜体 / 删除线 / 行内代码 / 链接。图片不加载：默认的
 * `NoOpImageTransformer` 让它不渲染，避免模型输出把任意 URL 变成设备上的网络请求。
 *
 * 链接走 [MarkdownLinks] 白名单：只有 http/https/mailto 能交给系统打开。
 * 流式时 [Markdown] 用 conflate 合并解析请求，并保留上一版渲染结果（`retainState`）。
 */
@Composable
fun MarkdownText(
  markdown: String,
  modifier: Modifier = Modifier,
  style: TextStyle = MaterialTheme.typography.bodyMedium,
) {
  val baseStyle = style.copy(color = style.color.takeIf { it.isSpecified } ?: MaterialTheme.colorScheme.onSurface)
  val typography =
    markdownTypography(
      text = baseStyle,
      paragraph = baseStyle,
      ordered = baseStyle,
      bullet = baseStyle,
      list = baseStyle,
      table = baseStyle,
      h1 = MaterialTheme.typography.titleLarge.copy(color = baseStyle.color),
      h2 = MaterialTheme.typography.titleMedium.copy(color = baseStyle.color),
      h3 = MaterialTheme.typography.titleSmall.copy(color = baseStyle.color),
      h4 = baseStyle.copy(fontWeight = FontWeight.SemiBold),
      h5 = baseStyle.copy(fontWeight = FontWeight.SemiBold),
      h6 = baseStyle.copy(fontWeight = FontWeight.SemiBold),
      quote = baseStyle.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
      code = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, color = baseStyle.color),
      inlineCode = baseStyle.copy(fontFamily = FontFamily.Monospace),
      textLink =
        TextLinkStyles(
          style = SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline),
        ),
    )
  val colors =
    markdownColor(
      text = baseStyle.color,
      codeBackground = MaterialTheme.colorScheme.surfaceContainerHighest,
      inlineCodeBackground = MaterialTheme.colorScheme.surfaceContainerHighest,
      dividerColor = MaterialTheme.colorScheme.outlineVariant,
      tableBackground = MaterialTheme.colorScheme.surfaceContainerLow,
    )
  // 组件对象整棵树共用；highlighted* 是库里的顶层 val，引用稳定。
  val components =
    remember {
      markdownComponents(
        codeFence = highlightedCodeFence,
        codeBlock = highlightedCodeBlock,
        checkbox = { MarkdownCheckBox(it.content, it.node, it.typography.text) },
      )
    }
  val uriHandler = safeUriHandler()

  SelectionContainer(modifier) {
    CompositionLocalProvider(LocalUriHandler provides uriHandler) {
      Markdown(
        content = markdown,
        colors = colors,
        typography = typography,
        modifier = Modifier.fillMaxWidth(),
        padding = markdownPadding(block = 4.dp, listItemTop = 2.dp, listItemBottom = 2.dp),
        dimens = markdownDimens(),
        components = components,
        retainState = true,
      )
    }
  }
}

@Composable
private fun safeUriHandler(): UriHandler {
  val delegate = LocalUriHandler.current
  return remember(delegate) { AllowlistedUriHandler(delegate) }
}

/** 模型给的链接一律先过白名单，再交给系统打开。 */
private class AllowlistedUriHandler(private val delegate: UriHandler) : UriHandler {
  override fun openUri(uri: String) {
    if (MarkdownLinks.isAllowed(uri)) delegate.openUri(uri)
  }
}
