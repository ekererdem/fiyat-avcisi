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
    // Adet / kg / L başı takip: birim fiyat = fiyat / unitAmount (0 = birim yok)
    var unitAmount: Double = 0.0,
    var unitName: String = "",
    var unitTarget: Double = 0.0,     // birim başı hedef (0 = yok)
    // Ürün alarmı: aynı ürünün farklı sitelerdeki ilanları tek grup
    var groupId: Long = 0,
    var groupName: String = "",
    var groupQuery: String = "",
    // Sadece takip: fiyat ve geçmiş tutulur, bildirim gönderilmez
    var watch: Boolean = false,
) {
    fun unitPrice(p: Double = lastPrice): Double? = if (unitAmount > 0 && p > 0) p / unitAmount else null
}

object Alarms {
    private const val CHANNEL = "alarms"
    private const val JOB_ID = 4242
    /** Kontrol sıklığı (saat), ayarlardan seçilir */
    // Sabit 3 saat: günde 8 kontrol; fiyat değişimini kaçırmaz, pili ve siteleri yormaz
    fun hours(ctx: Context) = 3
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
                    j.optDouble("unitAmount", 0.0), j.optString("unitName"), j.optDouble("unitTarget", 0.0),
                    j.optLong("groupId", 0), j.optString("groupName"), j.optString("groupQuery"),
                    j.optBoolean("watch", false),
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
                put("unitAmount", a.unitAmount); put("unitName", a.unitName); put("unitTarget", a.unitTarget)
                put("groupId", a.groupId); put("groupName", a.groupName); put("groupQuery", a.groupQuery); put("watch", a.watch)
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

    /** Bir ürün alarmının bütün ilanlarını ekler (aynı gruptaki eskilerin yerine) */
    fun addGroup(ctx: Context, items: List<Alarm>) {
        if (items.isEmpty()) return
        val gid = items.first().groupId
        val links = items.map { it.link.substringBefore('?') }.toSet()
        val list = load(ctx)
        list.removeAll { it.groupId == gid || it.link.substringBefore('?') in links }
        list.addAll(0, items)
        save(ctx, list)
    }

    fun removeGroup(ctx: Context, gid: Long) {
        prefs(ctx).edit().remove("gbest_$gid").apply()
        save(ctx, load(ctx).filter { it.groupId != gid })
    }

    /** Ölçü: birim fiyat varsa o, yoksa fiyat */
    fun metric(a: Alarm): Double? = a.unitPrice() ?: a.lastPrice.takeIf { it > 0 }

    /** Gruptaki en uygun ilan; diğerlerinin yarısından ucuz görünen (yanlış okunmuş) ilan sayılmaz */
    fun groupBest(items: List<Alarm>): Alarm? {
        val ms = items.mapNotNull { metric(it) }.sorted()
        val med = if (ms.isEmpty()) 0.0 else ms[ms.size / 2]
        return items.filter { metric(it) != null && (ms.size < 3 || metric(it)!! >= med * 0.5) }.minByOrNull { metric(it)!! }
    }

    fun unitText(v: Double, unit: String) = "${tl.format(v)} TL/$unit"

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
        )
        var price: Double? = null
        for (re in priceRes) {
            val m = re.find(html) ?: continue
            val v = parseNum(m.groupValues[1])
            if (v != null && v > 0) { price = v; break }
        }
        // Amazon: sayfadaki ilk fiyat bazen "(16,13 TL / adet)" birim fiyatıdır; birim fiyatları atla
        if (price == null) {
            for (m in Regex("""class="a-offscreen">\s*([0-9.]+,[0-9]{2})\s*TL""").findAll(html)) {
                val before = html.substring(maxOf(0, m.range.first - 250), m.range.first)
                val after = html.substring(m.range.last + 1, minOf(html.length, m.range.last + 200))
                val perUnit = before.contains("pricePerUnit", true) || before.contains("unitPrice", true) ||
                    Regex("""^[^()]{0,160}?/\s*(?:sayım|adet|count|kg|g|lt|l|ml|100)""", RegexOption.IGNORE_CASE).containsMatchIn(after.replace(Regex("<[^>]+>"), " ")) ||
                    before.contains("a-text-price")
                if (perUnit) continue
                val v = parseNum(m.groupValues[1])
                if (v != null && v > 0) { price = v; break }
            }
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
                var p = r.price
                // Mantıksız okuma koruması: kurulduğu fiyatın yarısından çok aşağı bir fiyat büyük ihtimalle
                // sayfadaki başka bir sayıdır (birim fiyat, taksit, kargo). Kabul etme, eski doğru fiyatı koru.
                val ref = if (a.startPrice > 0) a.startPrice else 0.0
                if (ref > 0 && a.lastPrice > 0 && a.lastPrice < ref * 0.4) { a.lastPrice = ref; if (a.lowest < ref * 0.4) a.lowest = ref }
                var odd: Double? = null
                if (p != null && ref > 0 && p < ref * 0.4) { odd = p; p = null }
                if (p != null) {
                    val dropped = a.lastPrice > 0 && p < a.lastPrice - 0.5
                    // Hedef: adet başıysa birim fiyatla, değilse toplam fiyatla
                    val hitTarget = if (a.unitTarget > 0 && a.unitAmount > 0)
                        p / a.unitAmount <= a.unitTarget && (a.lastPrice <= 0 || a.lastPrice / a.unitAmount > a.unitTarget)
                    else a.target > 0 && p <= a.target && (a.lastPrice <= 0 || a.lastPrice > a.target)
                    val anyDrop = a.target <= 0 && a.unitTarget <= 0
                    // Ürün alarmındaki ilanlar tek tek bildirim göndermez; grup olarak aşağıda değerlendirilir
                    if (a.groupId == 0L && !a.watch && ((anyDrop && dropped) || hitTarget)) {
                        val unitNow = a.unitPrice(p)
                        notify(ctx, a, "Fiyat düştü: ${money(p)}" + (unitNow?.let { " (${unitText(it, a.unitName)})" } ?: ""),
                            "${a.title.take(60)} · ${a.site} · önce ${money(a.lastPrice)}" +
                                when {
                                    a.unitTarget > 0 -> " · hedefin ${unitText(a.unitTarget, a.unitName)}"
                                    a.target > 0 -> " · hedefin ${money(a.target)}"
                                    else -> ""
                                })
                        notified++
                    }
                    a.lastPrice = p
                    if (a.lowest <= 0 || p < a.lowest) a.lowest = p
                    History.recordOffer(ctx, a.link, p)
                }
                if (a.wantStock && !a.watch && r.inStock == true && a.inStock == false) {
                    notify(ctx, a, "Stoğa girdi" + (p?.let { ": ${money(it)}" } ?: ""), "${a.title.take(70)} · ${a.site}")
                    notified++
                }
                if (r.inStock != null) a.inStock = r.inStock
                a.status = (if (odd != null) "şüpheli fiyat okundu (${money(odd)}), yok sayıldı" else r.note) + when (r.inStock) { true -> " · stokta"; false -> " · stokta yok"; else -> "" }
            } catch (e: Exception) {
                a.lastCheck = System.currentTimeMillis()
                a.status = "kontrol edilemedi (${e.javaClass.simpleName})"
            }
        }
        // Ürün alarmları: gruptaki en uygun ilan hedefe indiyse ya da daha önceki en iyiden ucuzladıysa tek bildirim
        for ((gid, items) in list.filter { it.groupId != 0L }.groupBy { it.groupId }) {
            val best = groupBest(items) ?: continue
            val m = metric(best)!!
            History.record(ctx, "g:$gid", m)
            val key = "gbest_$gid"
            val prev = prefs(ctx).getFloat(key, 0f).toDouble()
            val target = items.first().let { if (it.unitTarget > 0) it.unitTarget else it.target }
            val unit = best.unitAmount > 0
            val fmt = { v: Double -> if (unit) unitText(v, best.unitName) else money(v) }
            val hit = if (target > 0) m <= target && (prev <= 0 || prev > target) else prev > 0 && m < prev * 0.995
            if (hit && !items.first().watch) {
                notify(ctx, best, "${best.site}'da ${fmt(m)}",
                    "${best.groupName.ifEmpty { best.title }.take(60)} · ${money(best.lastPrice)}" +
                        (if (prev > 0) " · önceki en iyi ${fmt(prev)}" else "") +
                        (if (target > 0) " · hedefin ${fmt(target)}" else ""))
                notified++
            }
            prefs(ctx).edit().putFloat(key, m.toFloat()).apply()
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
