package com.openclaw.android.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.util.LruCache
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class LinkPreviewData(
    val url: String,
    val title: String?,
    val description: String?,
    val imageUrl: String?,
    val siteName: String?,
)

object LinkPreviewFetcher {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private const val MAX_CACHE_SIZE = 100
    private val cache = LruCache<String, LinkPreviewData>(MAX_CACHE_SIZE)
    private val negativeCache = object : LinkedHashMap<String, Boolean>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) =
            size > MAX_CACHE_SIZE
    }

    suspend fun fetch(url: String): LinkPreviewData? = withContext(Dispatchers.IO) {
        cache.get(url)?.let { return@withContext it }
        if (negativeCache.containsKey(url)) return@withContext null

        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (compatible; OpenClawBot/1.0)")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                negativeCache[url] = true
                return@withContext null
            }

            val body = response.body?.string()?.take(50_000) ?: return@withContext null
            val data = parseOgMeta(url, body)
            if (data != null) {
                cache.put(url, data)
            } else {
                negativeCache[url] = true
            }
            data
        } catch (_: Exception) {
            negativeCache[url] = true
            null
        }
    }

    private fun parseOgMeta(url: String, html: String): LinkPreviewData? {
        fun extractMeta(property: String): String? {
            val regex = Regex(
                """<meta[^>]*(?:property|name)\s*=\s*["']$property["'][^>]*content\s*=\s*["']([^"']*)["']""",
                RegexOption.IGNORE_CASE,
            )
            regex.find(html)?.let { return it.groupValues[1] }

            val regexReverse = Regex(
                """<meta[^>]*content\s*=\s*["']([^"']*)["'][^>]*(?:property|name)\s*=\s*["']$property["']""",
                RegexOption.IGNORE_CASE,
            )
            return regexReverse.find(html)?.groupValues?.get(1)
        }

        val title = extractMeta("og:title")
            ?: Regex("""<title[^>]*>([^<]+)</title>""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.get(1)?.trim()
        val description = extractMeta("og:description") ?: extractMeta("description")
        val imageUrl = extractMeta("og:image")
        val siteName = extractMeta("og:site_name")

        if (title == null && description == null && imageUrl == null) return null

        return LinkPreviewData(
            url = url,
            title = title,
            description = description?.take(200),
            imageUrl = imageUrl,
            siteName = siteName,
        )
    }
}

@Composable
fun LinkPreviewCard(url: String) {
    var preview by remember(url) { mutableStateOf<LinkPreviewData?>(null) }
    var loaded by remember(url) { mutableStateOf(false) }

    LaunchedEffect(url) {
        preview = LinkPreviewFetcher.fetch(url)
        loaded = true
    }

    if (!loaded || preview == null) return

    val uriHandler = LocalUriHandler.current
    val data = preview!!

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { uriHandler.openUri(data.url) },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(modifier = Modifier.padding(8.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                data.siteName?.let { site ->
                    Text(
                        text = site,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                }
                data.title?.let { title ->
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                data.description?.let { desc ->
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = desc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            data.imageUrl?.let { imgUrl ->
                Spacer(modifier = Modifier.width(8.dp))
                AsyncImage(
                    model = imgUrl,
                    contentDescription = data.title,
                    modifier = Modifier
                        .size(60.dp)
                        .clip(RoundedCornerShape(6.dp)),
                    contentScale = ContentScale.Crop,
                )
            }
        }
    }
}

private val URL_REGEX = Regex("""https?://[^\s<>"{}|\\^`\[\]]+""")

fun extractUrls(text: String): List<String> = URL_REGEX.findAll(text).map { it.value }.toList()
