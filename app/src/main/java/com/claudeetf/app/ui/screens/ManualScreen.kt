package com.claudeetf.app.ui.screens

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import com.claudeetf.app.data.remote.Http
import com.claudeetf.app.ui.Card
import com.claudeetf.app.ui.Label
import com.claudeetf.app.ui.Routes
import com.claudeetf.app.ui.TopBar
import com.claudeetf.app.ui.theme.C
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** 說明書：每版 APK 內建（CI 由 docs/manual/manual.html 填入版本打包），PDF 版放在固定的「manual」Release */
object Manual {
    const val PDF_URL = "https://github.com/locopy0121-hub/ClaudeETF/releases/download/manual/ClaudeETF-Manual.pdf"
    private const val API = "https://api.github.com/repos/locopy0121-hub/ClaudeETF/releases/tags/manual"
    const val ASSET = "manual.html"

    fun bundled(ctx: Context) = runCatching { ctx.assets.open(ASSET).close(); true }.getOrDefault(false)

    /** 內建說明書的版本與日期（從 HTML 內文擷取「v1.x.y」與「yyyy/mm/dd」） */
    fun bundledInfo(ctx: Context): String? = runCatching {
        val t = ctx.assets.open(ASSET).bufferedReader().use { it.readText() }
        val v = Regex("v\\d+\\.\\d+\\.\\d+").find(t)?.value
        val d = Regex("20\\d{2}/\\d{2}/\\d{2}").find(t)?.value
        listOfNotNull(v, d).joinToString(" · ").ifBlank { null }
    }.getOrNull()

    /** PDF 版最後更新日期（台灣時間） */
    suspend fun pdfDate(): String? = withContext(Dispatchers.IO) {
        runCatching {
            val o = JSONObject(Http.get(API, mapOf("Accept" to "application/vnd.github+json")))
            val a = o.optJSONArray("assets")
            val ts = (0 until (a?.length() ?: 0)).mapNotNull { a?.optJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".pdf") }?.optString("updated_at")
                ?: o.optString("published_at")
            val inst = java.time.Instant.parse(ts)
            java.time.format.DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm")
                .withZone(java.time.ZoneId.of("Asia/Taipei")).format(inst)
        }.getOrNull()
    }

    /** 下載 PDF 到「下載」資料夾；失敗時改用瀏覽器開啟 */
    fun downloadPdf(ctx: Context) {
        val ok = runCatching {
            val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(
                DownloadManager.Request(Uri.parse(PDF_URL))
                    .setTitle("ClaudeETF 使用說明書")
                    .setDescription("PDF")
                    .setMimeType("application/pdf")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "ClaudeETF-Manual.pdf")
            )
        }.isSuccess
        if (ok) Toast.makeText(ctx, "開始下載說明書，完成後可在通知或「下載」資料夾開啟", Toast.LENGTH_LONG).show()
        else openPdf(ctx)
    }

    fun openPdf(ctx: Context) {
        runCatching {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PDF_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { Toast.makeText(ctx, "無法開啟瀏覽器", Toast.LENGTH_SHORT).show() }
    }
}

/** 設置 › 關於與更新：使用說明書卡片 */
@Composable
fun ManualCard(nav: NavController) {
    val ctx = LocalContext.current
    val bundled = remember { Manual.bundled(ctx) }
    val info = remember { Manual.bundledInfo(ctx) }
    var pdfDate by remember { mutableStateOf<String?>(null) }
    var pdfLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { pdfDate = Manual.pdfDate(); pdfLoaded = true }
    Card {
        Text("📖 使用說明書", color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Label(
            if (bundled) "已隨本版本內建（${info ?: "目前版本"}），可離線閱讀；每次更新 App 自動同步。"
            else "本版本未內建說明書，請改用 PDF 版。"
        )
        Label(
            "PDF 版：" + when {
                pdfDate != null -> "最後更新 $pdfDate"
                pdfLoaded -> "無法取得更新日期（可能尚未發布或無網路）"
                else -> "讀取中…"
            }
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ManualBtn("閱讀說明書", Modifier.weight(1f), enabled = bundled) { nav.navigate(Routes.MANUAL) }
            ManualBtn("下載 PDF", Modifier.weight(1f)) { Manual.downloadPdf(ctx) }
        }
        Text(
            "以瀏覽器開啟 PDF ›", color = C.Muted, fontSize = 12.sp,
            modifier = Modifier.clickable { Manual.openPdf(ctx) }.padding(vertical = 2.dp)
        )
    }
}

@Composable
private fun ManualBtn(text: String, modifier: Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier.height(42.dp).clip(RoundedCornerShape(12.dp))
            .background(if (enabled) C.Text.copy(alpha = 0.10f) else C.Line)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (enabled) C.Text else C.Muted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
}

/** App 內閱讀說明書（內建 HTML，離線可用） */
@Composable
fun ManualScreen(nav: NavController) {
    val ctx = LocalContext.current
    val bundled = remember { Manual.bundled(ctx) }
    var web by remember { mutableStateOf<WebView?>(null) }
    Column(Modifier.fillMaxSize().background(C.Bg)) {
        TopBar("使用說明書", onBack = {
            val w = web
            if (w != null && w.canGoBack()) w.goBack() else nav.popBackStack()
        }) {
            Text("PDF", color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { Manual.downloadPdf(ctx) }.padding(12.dp))
        }
        if (!bundled) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Label("本版本未內建說明書。")
                ManualBtn("以瀏覽器開啟 PDF", Modifier.fillMaxWidth()) { Manual.openPdf(ctx) }
            }
        } else {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { c ->
                    WebView(c).apply {
                        settings.javaScriptEnabled = false
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        settings.allowFileAccess = true
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean {
                                val u = request.url
                                if (u.scheme == "file") return false
                                runCatching { c.startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                                return true
                            }
                        }
                        loadUrl("file:///android_asset/${Manual.ASSET}")
                        web = this
                    }
                },
            )
        }
    }
}
