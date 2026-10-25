package com.erdem.fiyatavcisi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.NumberFormat
import java.util.Locale

/** Fiyat / stok alarmı: bir ilanın linki, fiyatı ve ne zaman haber verileceği */
data class Alarm(
    val id: Long,
    var title: String,
    val site: String,
    var link: String,
    var image: String,
    val startPrice: Double,
    var lastPrice: Double,
    var lowest: Double,
    var target: Double,          // 0 = her düşüşte haber ver
    var wantStock: Boolean,      // stoğa girince haber ver
    var inStock: Boolean?,       // son kontrolde stokta mıydı (bilinmiyorsa null)
    var lastCheck: Long,
    var status: String,
)

object Alarms {
    private const val CHANNEL = "alarms"
    private const val JOB_ID = 4242
    /** Kontrol sıklığı (saat), ayarlardan seçilir */
    fun hours(ctx: Context) = prefs(ctx).getInt("hours", 2)
    fun setHours(ctx: Context, h: Int) {
        prefs(ctx).edit().putInt("hours", h).apply()
        schedule(ctx, load(ctx).isNotEmpty(), force = true)
    }

    private val ua = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    // ---------- Kayıt ----------

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("alarms", Context.MODE_PRIVATE)

    fun load(ctx: Context): MutableList<Alarm> {
        val arr = try { JSONArray(prefs(ctx).getString("list", "[]")) } catch (e: Exception) { JSONArray() }
        return (0 until arr.length()).mapNotNull { i ->
            try {
                val j = arr.getJSONObject(i)
                Alarm(
                    j.getLong("id"), j.optString("title"), j.optString("site"), j.getString("link"), j.optString("image"),
                    j.optDouble("start", 0.0), j.optDouble("last", 0.0), j.optDouble("low", 0.0), j.optDouble("target", 0.0),
                    j.optBoolean("wantStock"), if (j.has("inStock")) j.optBoolean("inStock") else null,
                    j.optLong("checked"), j.optString("status"),
                )
            } catch (e: Exception) { null }
        }.toMutableList()
    }

    fun save(ctx: Context, list: List<Alarm>) {
        val arr = JSONArray()
        list.forEach { a ->
            arr.put(JSONObject().apply {
                put("id", a.id); put("title", a.title); put("site", a.site); put("link", a.link); put("image", a.image)
                put("start", a.startPrice); put("last", a.lastPrice); put("low", a.lowest); put("target", a.target)
                put("wantStock", a.wantStock); a.inStock?.let { put("inStock", it) }
                put("checked", a.lastCheck); put("status", a.status)
            })
        }
        prefs(ctx).edit().putString("list", arr.toString()).apply()
        schedule(ctx, list.isNotEmpty())
    }

    fun add(ctx: Context, a: Alarm) {
        val list = load(ctx)
        list.removeAll { it.link.substringBefore('?') == a.link.substringBefore('?') }
        list.add(0, a)
        save(ctx, list)
    }

    fun remove(ctx: Context, id: Long) {
        save(ctx, load(ctx).filter { it.id != id })
    }

    // ---------- Arka plan zamanlama ----------

    fun schedule(ctx: Context, on: Boolean, force: Boolean = false) {
        val js = ctx.getSystemService(JobScheduler::class.java) ?: return
        if (!on || force) js.cancel(JOB_ID)
        if (!on) return
        if (js.allPendingJobs.any { it.id == JOB_ID }) return
        val b = JobInfo.Builder(JOB_ID, ComponentName(ctx, AlarmJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(hours(ctx) * 3_600_000L)
        // Telefon yeniden başlayınca da sürsün (izin yoksa bu kısım atlanır)
        try { js.schedule(b.setPersisted(true).build()) } catch (e: Exception) {
            try { js.schedule(b.setPersisted(false).build()) } catch (x: Exception) {}
        }
    }

    // ---------- Sayfadan fiyat okuma ----------

    data class PageInfo(val price: Double?, val inStock: Boolean?, val title: String, val image: String, val finalUrl: String, val note: String)

    /** "1.234,56" (Türkçe) ya da "1234.56" (veri) yazımını sayıya çevirir */
    private fun parseNum(s: String): Double? {
        val t = s.trim()
        return when {
            t.contains(',') && t.contains('.') ->
                if (t.lastIndexOf(',') > t.lastIndexOf('.')) t.replace(".", "").replace(",", ".").toDoubleOrNull()
                else t.replace(",", "").toDoubleOrNull()
            t.contains(',') -> t.replace(",", ".").toDoubleOrNull()
            Regex("""^\d{1,3}(\.\d{3})+$""").matches(t) -> t.replace(".", "").toDoubleOrNull()
            else -> t.toDoubleOrNull()
        }
    }

    private fun unescape(s: String) = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
        .replace("&#x27;", "'").replace("\\u0026", "&").replace("\\/", "/")

    /** Ürün sayfasını indirip fiyatı, stok durumunu, adı ve resmi okur (sitelerin arama motorları için koyduğu veriden) */
    fun fetch(url: String): PageInfo {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = 15000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", ua)
        c.setRequestProperty("Accept", "text/html,application/xhtml+xml")
        c.setRequestProperty("Accept-Language", "tr-TR,tr;q=0.9")
        try { CookieManager.getInstance().getCookie(url)?.let { c.setRequestProperty("Cookie", it) } } catch (e: Exception) {}
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val html = stream?.bufferedReader()?.use { r ->
            val sb = StringBuilder(); val buf = CharArray(16384)
            while (sb.length < 4_000_000) { val n = r.read(buf); if (n < 0) break; sb.append(buf, 0, n) }
            sb.toString()
        } ?: ""
        val finalUrl = c.url.toString()
        if (code !in 200..299) return PageInfo(null, null, "", "", finalUrl, "site yanıtı $code")

        // Fiyat: önce sitenin ürün verisi (JSON-LD, meta), sonra siteye özel alanlar
        val priceRes = listOf(
            Regex(""""@type"\s*:\s*"(?:Offer|AggregateOffer)"[^{}]{0,400}?"(?:price|lowPrice)"\s*:\s*"?([0-9]+(?:[.,][0-9]+)?)"""),
            Regex(""""(?:price|lowPrice)"\s*:\s*"?([0-9]+(?:\.[0-9]+)?)"?\s*,[^{}]{0,300}?"priceCurrency"\s*:\s*"TRY""""),
            Regex(""""priceCurrency"\s*:\s*"TRY"[^{}]{0,300}?"(?:price|lowPrice)"\s*:\s*"?([0-9]+(?:\.[0-9]+)?)"""),
            Regex("""(?:product:price:amount|og:price:amount)"\s+content="([0-9.,]+)""""),
            Regex("""content="([0-9.,]+)"\s+(?:property|name)="(?:product:price:amount|og:price:amount)""""),
            Regex(""""discountedPrice"\s*:\s*\{[^{}]{0,120}?"value"\s*:\s*([0-9]+(?:\.[0-9]+)?)"""),
            Regex(""""sellingPrice"\s*:\s*\{[^{}]{0,120}?"value"\s*:\s*([0-9]+(?:\.[0-9]+)?)"""),
            Regex(""""priceToPay"[^<]{0,400}?a-offscreen">\s*([0-9.]+,[0-9]{2})"""),
            Regex("""class="a-offscreen">\s*([0-9.]+,[0-9]{2})\s*TL"""),
        )
        var price: Double? = null
        for (re in priceRes) {
            val m = re.find(html) ?: continue
            val v = parseNum(m.groupValues[1])
            if (v != null && v > 0) { price = v; break }
        }

        // Stok
        val low = html.lowercase(Locale("tr", "TR"))
        val avail = Regex(""""availability"\s*:\s*"[^"]*?(InStock|OutOfStock|SoldOut|PreOrder|Discontinued|LimitedAvailability)"""").find(html)?.groupValues?.get(1)
        val inStock: Boolean? = when {
            avail == "InStock" || avail == "LimitedAvailability" -> true
            avail != null -> false
            Regex(""""(?:isSoldOut|soldOut)"\s*:\s*true""").containsMatchIn(html) -> false
            Regex(""""(?:inStock|isInStock|hasStock)"\s*:\s*false""").containsMatchIn(html) -> false
            Regex(""""(?:inStock|isInStock|hasStock)"\s*:\s*true""").containsMatchIn(html) -> true
            low.contains("şu anda mevcut değil") || low.contains("stokta yok") || low.contains("tükendi") -> false
            price != null -> true
            else -> null
        }

        val title = unescape(
            Regex("""<meta[^>]+property="og:title"[^>]+content="([^"]{3,300})"""").find(html)?.groupValues?.get(1)
                ?: Regex("""<title[^>]*>([^<]{3,300})</title>""").find(html)?.groupValues?.get(1) ?: ""
        ).trim()
        val image = unescape(
            Regex("""<meta[^>]+property="og:image"[^>]+content="([^"]+)"""").find(html)?.groupValues?.get(1) ?: ""
        )
        val note = if (price == null) "fiyat okunamadı (sayfa ${html.length} karakter)" else "fiyat okundu"
        return PageInfo(price, inStock, title, image, finalUrl, note)
    }

    // ---------- Kontrol ve bildirim ----------

    private val tl = NumberFormat.getNumberInstance(Locale("tr", "TR")).apply { maximumFractionDigits = 2; minimumFractionDigits = 0 }
    fun money(v: Double) = "${tl.format(v)} TL"

    /** Bütün alarmları kontrol eder; fiyat düştüyse ya da stoğa girdiyse bildirim gönderir */
    fun checkAll(ctx: Context): Int {
        val list = load(ctx)
        var notified = 0
        for (a in list) {
            try {
                val r = fetch(a.link)
                a.lastCheck = System.currentTimeMillis()
                if (a.image.isEmpty() && r.image.isNotEmpty()) a.image = r.image
                val p = r.price
                if (p != null) {
                    val dropped = a.lastPrice > 0 && p < a.lastPrice - 0.5
                    val hitTarget = a.target > 0 && p <= a.target && (a.lastPrice <= 0 || a.lastPrice > a.target)
                    if ((a.target <= 0 && dropped) || hitTarget) {
                        notify(ctx, a, "Fiyat düştü: ${money(p)}",
                            "${a.title.take(60)} · ${a.site} · önce ${money(a.lastPrice)}" +
                                if (a.target > 0) " · hedefin ${money(a.target)}" else "")
                        notified++
                    }
                    a.lastPrice = p
                    if (a.lowest <= 0 || p < a.lowest) a.lowest = p
                }
                if (a.wantStock && r.inStock == true && a.inStock == false) {
                    notify(ctx, a, "Stoğa girdi" + (p?.let { ": ${money(it)}" } ?: ""), "${a.title.take(70)} · ${a.site}")
                    notified++
                }
                if (r.inStock != null) a.inStock = r.inStock
                a.status = r.note + when (r.inStock) { true -> " · stokta"; false -> " · stokta yok"; else -> "" }
            } catch (e: Exception) {
                a.lastCheck = System.currentTimeMillis()
                a.status = "kontrol edilemedi (${e.javaClass.simpleName})"
            }
        }
        // Kontrol sırasında eklenen/silinen alarmlar kaybolmasın: güncel listeyle birleştir
        val current = load(ctx)
        val byId = list.associateBy { it.id }
        save(ctx, current.map { byId[it.id] ?: it })
        return notified
    }

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Fiyat alarmları", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Takip ettiğin ürünün fiyatı düşünce ya da stoğa girince"
            })
        }
    }

    private fun notify(ctx: Context, a: Alarm, title: String, text: String) {
        ensureChannel(ctx)
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        val pi = PendingIntent.getActivity(ctx, a.id.toInt(),
            Intent(Intent.ACTION_VIEW, Uri.parse(a.link)), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try { nm.notify(a.id.toInt(), n) } catch (e: SecurityException) {}
    }
}

/** Android'in birkaç saatte bir çalıştırdığı arka plan işi: alarmları kontrol eder */
class AlarmJobService : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        Thread {
            try { Alarms.checkAll(applicationContext) } catch (e: Exception) {}
            jobFinished(params, false)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean = true
}
