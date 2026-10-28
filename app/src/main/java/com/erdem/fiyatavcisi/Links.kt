package com.erdem.fiyatavcisi

import android.net.Uri
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

/**
 * Uygulamaların "Paylaş" ile verdiği kısa/takip linklerini gerçek ürün sayfasına çevirir.
 *  - Trendyol: https://xxxx.adj.st/...?Page=Product&ContentId=123  ->  trendyol.com/...-p-123
 *  - ty.gl, amzn.eu, hb.link, app.link, onelink.me, bit.ly gibi kısa linkler: yönlendirme izlenir
 *  - Link içinde gömülü gerçek adres varsa (adj_redirect, url, deep_link…) o kullanılır
 */
object Links {
    private val shops = listOf(
        "trendyol.com", "hepsiburada.com", "amazon.com.tr", "n11.com", "mediamarkt.com.tr", "teknosa.com",
        "pazarama.com", "migros.com.tr", "carrefoursa.com", "a101.com.tr", "sokmarket.com.tr",
        "vatanbilgisayar.com", "boyner.com.tr", "flo.com.tr", "decathlon.com.tr", "amazon.com",
    )
    private val desktopUa = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

    fun isShop(url: String): Boolean {
        val h = try { Uri.parse(url).host.orEmpty().lowercase() } catch (e: Exception) { "" }
        return shops.any { h == it || h.endsWith(".$it") }
    }

    /** Ürün sayfası mı (sitenin ana sayfası ya da kısa link değil) */
    fun isProductPage(url: String): Boolean = isShop(url) && (
        Regex("-p-[0-9]+|/dp/[A-Z0-9]{10}|/gp/product/|-pm?-[A-Za-z0-9]+|/urun/|urun\\.n11|/product/|_p-[0-9]+|\\.html").containsMatchIn(url))

    /** Gerçek ürün linkini bulur; bulamazsa verilen linki döndürür */
    fun resolve(url: String): String {
        var cur = url.trim()
        repeat(6) {
            if (isProductPage(cur)) return clean(cur)
            embedded(cur)?.let { cur = it; return@repeat }
            val next = try { hop(cur) } catch (e: Exception) { null } ?: return clean(cur)
            if (next == cur) return clean(cur)
            cur = next
        }
        return clean(cur)
    }

    /** Linkin içine gömülü ürün adresi: Trendyol ContentId ya da yönlendirme parametresi */
    fun embedded(url: String): String? {
        val params = queryParams(url)
        // Trendyol uygulama linki: Page=Product&ContentId=...
        val content = params["contentid"] ?: params["productcontentid"]
        if (content != null && content.all { it.isDigit() } && (params["page"]?.equals("product", true) != false)) {
            // Satıcı numarası bilerek atılır: ürün sayfası izlenir, en ucuz satıcı okunur
            return "https://www.trendyol.com/urun/urun-p-$content"
        }
        for (k in listOf("adj_redirect", "adj_fallback", "redirect", "redirect_url", "url", "link", "deep_link",
            "deeplink", "fallback", "\$fallback_url", "target", "u", "af_web_dp", "af_dp")) {
            val v = params[k] ?: continue
            if (v.startsWith("http") && v != url) return v
            // ty://?Page=Product&ContentId=… gibi uygulama linki
            if (v.contains("ContentId", true)) embedded("https://x/?" + v.substringAfter('?'))?.let { return it }
        }
        return null
    }

    private fun queryParams(url: String): Map<String, String> {
        val q = url.substringAfter('?', "").substringBefore('#')
        if (q.isEmpty()) return emptyMap()
        return q.split('&').mapNotNull { kv ->
            val k = kv.substringBefore('=')
            val v = kv.substringAfter('=', "")
            if (k.isEmpty()) null else k.lowercase() to try { URLDecoder.decode(v, "UTF-8") } catch (e: Exception) { v }
        }.toMap()
    }

    /** Bir adım yönlendirme: Location başlığı, sayfadaki meta yenileme ya da canonical/og:url */
    private fun hop(url: String): String? {
        val c = URL(url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false
        c.connectTimeout = 8000
        c.readTimeout = 10000
        c.setRequestProperty("User-Agent", desktopUa)
        c.setRequestProperty("Accept", "text/html,application/xhtml+xml")
        c.setRequestProperty("Accept-Language", "tr-TR,tr;q=0.9")
        val code = c.responseCode
        if (code in 300..399) {
            val loc = c.getHeaderField("Location") ?: return null
            c.disconnect()
            return URL(URL(url), loc).toString()
        }
        val html = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { r ->
            val sb = StringBuilder(); val buf = CharArray(8192)
            while (sb.length < 400_000) { val n = r.read(buf); if (n < 0) break; sb.append(buf, 0, n) }
            sb.toString()
        } ?: return null
        val found = listOf(
            Regex("""http-equiv=["']refresh["'][^>]*url=([^"'>\s]+)""", RegexOption.IGNORE_CASE),
            Regex("""<link[^>]+rel=["']canonical["'][^>]+href=["']([^"']+)""", RegexOption.IGNORE_CASE),
            Regex("""<meta[^>]+property=["']og:url["'][^>]+content=["']([^"']+)""", RegexOption.IGNORE_CASE),
            Regex("""(?:window\.)?location(?:\.href)?\s*=\s*["']([^"']+)["']"""),
        ).firstNotNullOfOrNull { it.find(html)?.groupValues?.get(1) } ?: return null
        val abs = URL(URL(url), found.replace("&amp;", "&")).toString()
        return if (abs == url) null else abs
    }

    /** Takip eklerini at (utm, adjust…), ürün için gereken parametreleri koru */
    fun clean(url: String): String {
        if (!url.contains('?')) return url
        // Satıcıya kilitleyen ekler (merchantId, magaza, smid) atılır; sadece ürün çeşidini belirleyenler kalır
        val keep = setOf("th", "psc", "v", "variant")
        val base = url.substringBefore('?')
        val q = url.substringAfter('?').substringBefore('#').split('&')
            .filter { it.substringBefore('=').lowercase() in keep }
        return if (q.isEmpty()) base else base + "?" + q.joinToString("&")
    }

    /** Linkten site adı: "Trendyol", "Hepsiburada", "Amazon"… */
    fun siteName(url: String): String {
        val h = try { Uri.parse(url).host.orEmpty().lowercase() } catch (e: Exception) { "" }
        val names = mapOf("trendyol" to "Trendyol", "hepsiburada" to "Hepsiburada", "amazon" to "Amazon", "n11" to "N11",
            "mediamarkt" to "MediaMarkt", "teknosa" to "Teknosa", "pazarama" to "Pazarama", "migros" to "Migros",
            "carrefoursa" to "CarrefourSA", "a101" to "A101", "sokmarket" to "Şok", "vatanbilgisayar" to "Vatan",
            "boyner" to "Boyner", "flo" to "Flo", "decathlon" to "Decathlon")
        val key = h.removePrefix("www.").substringBefore('.')
        return names[key] ?: key.replaceFirstChar { it.uppercase() }
    }

    /** Ad yerine link yazılmışsa ya da boşsa düzgün bir yedek ad */
    fun looksLikeUrl(s: String) = s.isBlank() || s.startsWith("http") || Regex("^[\\w.-]+\\.[a-z]{2,}/").containsMatchIn(s)
}
