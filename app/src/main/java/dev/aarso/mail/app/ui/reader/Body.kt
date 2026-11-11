package dev.aarso.mail.app.ui.reader

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.viewinterop.AndroidView
import dev.aarso.mail.app.ui.theme.Palette
import dev.aarso.mail.app.ui.theme.Type

private val URL = Regex("(https?://[^\\s<>\"')\\]]+[^\\s<>\"')\\].,;:!?])|([A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,})")

/** Plain-text mail with tappable links and addresses, quoted lines dimmed. */
@Composable
fun PlainBody(text: String, modifier: Modifier = Modifier) {
    val styles = TextLinkStyles(SpanStyle(color = Palette.Navy, textDecoration = TextDecoration.Underline))
    val annotated = remember(text) {
        buildAnnotatedString {
            text.lines().forEachIndexed { i, line ->
                if (i > 0) append('\n')
                val quoted = line.trimStart().startsWith(">")
                val start = length
                var last = 0
                for (m in URL.findAll(line)) {
                    append(line.substring(last, m.range.first))
                    val v = m.value
                    val target = if (v.contains('@') && !v.startsWith("http")) "mailto:$v" else v
                    withLink(LinkAnnotation.Url(target, styles)) { append(v) }
                    last = m.range.last + 1
                }
                append(line.substring(last))
                if (quoted) addStyle(SpanStyle(color = Palette.SheetInkDim), start, length)
            }
        }
    }
    Text(annotated, style = Type.body.copy(color = Palette.SheetInk), modifier = modifier.fillMaxWidth())
}

/**
 * HTML mail in a WebView that can do nothing but draw: no JavaScript, no file or content access,
 * no network unless the person allowed images, and every link handed to the browser (or to our own
 * composer for mailto:). The HTML arrives already sanitised — scripts, forms and trackers removed.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HtmlBody(html: String, allowNetwork: Boolean, onLink: (Uri) -> Unit, modifier: Modifier = Modifier) {
    val page = remember(html) {
        """<!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1">
        <style>
          html,body{margin:0;padding:0;background:#fff;color:#000;font-family:sans-serif;font-size:16px;line-height:1.45;word-wrap:break-word;overflow-wrap:anywhere}
          img{max-width:100%;height:auto} table{max-width:100%!important} pre{white-space:pre-wrap}
          blockquote{margin:0 0 0 .6em;padding-left:.8em;border-left:2px solid #c1c1c1;color:#555}
          a{color:#1F3354}
        </style></head><body>$html</body></html>"""
    }
    AndroidView(
        modifier = modifier.fillMaxWidth(),
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                isVerticalScrollBarEnabled = false
                setBackgroundColor(android.graphics.Color.WHITE)
                settings.apply {
                    javaScriptEnabled = false
                    allowFileAccess = false
                    allowContentAccess = false
                    domStorageEnabled = false
                    setGeolocationEnabled(false)
                    javaScriptCanOpenWindowsAutomatically = false
                    setSupportMultipleWindows(false)
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    builtInZoomControls = true
                    displayZoomControls = false
                    safeBrowsingEnabled = true
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        onLink(request.url)
                        return true
                    }
                }
            }
        },
        update = { wv ->
            wv.settings.blockNetworkLoads = !allowNetwork
            wv.settings.loadsImagesAutomatically = true
            if (wv.tag != page) {
                wv.tag = page
                wv.loadDataWithBaseURL("about:blank", page, "text/html", "utf-8", null)
            }
        },
    )
}

fun linkIntent(uri: Uri): Intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
