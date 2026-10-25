package com.erdem.fiyatavcisi

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import org.json.JSONArray

/**
 * Fiyat geçmişi: her aramada ve alarm kontrolünde görülen fiyatlar gün gün saklanır.
 *  - ilan geçmişi: bir ilanın (link) fiyatı
 *  - ürün geçmişi: bir aramanın (ürünün) o günkü en ucuz fiyatı
 * Her gün için en düşük fiyat tutulur, en fazla 120 gün.
 */
object History {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("history", Context.MODE_PRIVATE)

    fun today() = System.currentTimeMillis() / 86_400_000L

    /** İlanın kalıcı anahtarı: Amazon'da ürün numarası, diğerlerinde soru işaretsiz link */
    fun key(link: String): String {
        Regex("/dp/([A-Z0-9]{10})").find(link)?.let { return "amazon:" + it.groupValues[1] }
        return link.substringBefore('#').substringBefore('?')
    }

    fun get(ctx: Context, k: String): List<Pair<Long, Double>> = try {
        val arr = JSONArray(prefs(ctx).getString(k, "[]"))
        (0 until arr.length()).map { i -> arr.getJSONArray(i).let { it.getLong(0) to it.getDouble(1) } }
    } catch (e: Exception) { emptyList() }

    fun record(ctx: Context, k: String, price: Double) {
        if (price <= 0 || k.isBlank()) return
        val d = today()
        val list = get(ctx, k).toMutableList()
        val i = list.indexOfFirst { it.first == d }
        if (i >= 0) { if (price < list[i].second) list[i] = d to price } else list.add(d to price)
        val keep = list.sortedBy { it.first }.takeLast(120)
        val arr = JSONArray()
        keep.forEach { arr.put(JSONArray().put(it.first).put(it.second)) }
        prefs(ctx).edit().putString(k, arr.toString()).apply()
    }

    fun recordOffer(ctx: Context, link: String, price: Double) = record(ctx, "o:" + key(link), price)
    fun offer(ctx: Context, link: String) = get(ctx, "o:" + key(link))
    fun recordProduct(ctx: Context, productKey: String, price: Double) = record(ctx, "p:$productKey", price)
    fun product(ctx: Context, productKey: String) = get(ctx, "p:$productKey")
}

/** Küçük fiyat grafiği: günlere göre çizgi, son nokta vurgulu */
class Sparkline(ctx: Context, private val points: List<Pair<Long, Double>>, private val color: Int, private val gridColor: Int) : View(ctx) {
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2.5f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; this.color = color
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; this.color = color }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1f; this.color = gridColor }

    override fun onDraw(c: Canvas) {
        if (points.size < 2) return
        val pad = 6f * resources.displayMetrics.density
        val w = width - 2 * pad
        val h = height - 2 * pad
        val minX = points.first().first.toFloat()
        val maxX = points.last().first.toFloat().let { if (it == minX) minX + 1 else it }
        val minY = points.minOf { it.second }
        val maxY = points.maxOf { it.second }.let { if (it == minY) minY + 1 else it }
        fun x(d: Long) = pad + (d - minX) / (maxX - minX) * w
        fun y(v: Double) = (pad + h - ((v - minY) / (maxY - minY)).toFloat() * h)
        c.drawLine(pad, pad + h, pad + w, pad + h, grid)
        val p = Path()
        points.forEachIndexed { i, (d, v) -> if (i == 0) p.moveTo(x(d), y(v)) else p.lineTo(x(d), y(v)) }
        c.drawPath(p, line)
        val last = points.last()
        c.drawCircle(x(last.first), y(last.second), 4f * resources.displayMetrics.density, dot)
    }
}
