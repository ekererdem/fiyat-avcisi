package com.erdem.fiyatavcisi

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URLEncoder
import java.text.NumberFormat
import java.util.Locale

/**
 * 2. deneme: aynı aramayı 6 sitede telefonun içindeki gizli tarayıcıyla açar,
 * sayfadaki ürün kartlarını tek tek okur (ad, gerçek fiyat, puan, link),
 * aksesuarları ayırır ve hepsini tek listede fiyata göre sıralar.
 */
class MainActivity : Activity() {

    /** linkPattern: o sitede bir ürün sayfası linkini tanıyan kalıp */
    private data class Site(val name: String, val linkPattern: String, val url: (String) -> String)

    private data class Offer(
        val site: String,
        val title: String,
        val price: Double,
        val listPrice: Double,
        val basket: Boolean,
        val rating: String,
        val ratingCount: String,
        val link: String,
        val raw: String,
        var kind: Kind = Kind.OTHER,
        var variant: String = "",
        var otherModel: String = "",
    )

    private enum class Kind { MATCH, POSSIBLE, ACCESSORY, DIFFERENT_MODEL, OTHER }

    private val sites = listOf(
        Site("Trendyol", "-p-[0-9]") { "https://www.trendyol.com/sr?q=$it" },
        Site("Hepsiburada", "-pm?-[A-Za-z0-9]") { "https://www.hepsiburada.com/ara?q=$it" },
        Site("Amazon", "/dp/") { "https://www.amazon.com.tr/s?k=$it" },
        Site("MediaMarkt", "/product/") { "https://www.mediamarkt.com.tr/tr/search.html?query=$it" },
        Site("Teknosa", "-p-[0-9]") { "https://www.teknosa.com/arama/?s=$it" },
        Site("N11", "urun[.]n11[.]com|n11[.]com/urun/") { "https://www.n11.com/arama?q=$it" },
    )

    // Başlıkta geçerse "aksesuar olabilir" sayılan kelimeler
    private val accessoryWords = listOf(
        "uyumlu", "yedek bıçak", "yedek başlık", "yedek bicak", "kablo", "kılıf", "kilif", "adaptör", "adaptor",
        "koruyucu", "stand", "şarj aleti", "sarj aleti", "şarj cihazı", "çanta", "sticker",
    )

    private val gold = Color.parseColor("#C8961E")
    private val ink = Color.parseColor("#1D1B16")
    private val paper = Color.parseColor("#FAF7F0")
    private val muted = Color.parseColor("#6B6458")
    private val green = Color.parseColor("#2E7D32")
    private val red = Color.parseColor("#B3261E")
    private val orange = Color.parseColor("#E07B00")

    private val tl = NumberFormat.getNumberInstance(Locale("tr", "TR")).apply {
        maximumFractionDigits = 2
        minimumFractionDigits = 2
    }
    private val tlWhole = NumberFormat.getNumberInstance(Locale("tr", "TR")).apply {
        maximumFractionDigits = 0
    }
    private var attempt = 0

    private lateinit var input: EditText
    private lateinit var web: WebView
    private lateinit var resultsScroll: ScrollView
    private lateinit var results: LinearLayout
    private lateinit var status: TextView
    private lateinit var browserBtn: Button

    private val handler = Handler(Looper.getMainLooper())
    private val report = StringBuilder()
    private val offers = mutableListOf<Offer>()
    private val siteSummary = mutableListOf<String>()

    private var running = false
    private var index = 0
    private var query = ""
    private var token = 0
    private var extractScheduled = false
    private var httpStatus = 0
    private var loadError = ""

    /**
     * Ürün kartı okuyucu. Her ürün linkinden yukarı doğru çıkıp yalnızca o ürünü içeren
     * en büyük kutuyu "kart" kabul eder; kartın içinden ad, fiyat ve puanı çeker.
     * Taksit, aylık ödeme, kupon ve kargo tutarlarını fiyat saymadan önce siler.
     */
    private fun extractJs(linkPattern: String) = """
        (function(){
          var pat = new RegExp('__PAT__');
          var priceRe = /(?:₺\s?[1-9]\d{0,2}(?:\.\d{3})*(?:,\d{1,2})?(?!\d))|(?:(?<![\d.,])(?<!,\s?)[1-9]\d{0,2}(?:\.\d{3})*(?:,\d{1,2})?\s?(?:TL|₺(?!\s?\d)))/g;
          var priceOne = new RegExp(priceRe.source);
          function keyOf(h){ var m = h.match(/\/dp\/([A-Z0-9]{10})/); if (m) return 'dp/' + m[1]; return h.split('#')[0].split('?')[0]; }
          function clean(t){
            return t.replace(/\d+\s*[xX×]\s*(?:₺\s?)?[1-9][\d.,]*\s*(?:TL|₺)?/g, ' ')
              .replace(/aylık\s*(?:₺\s?)?[\d.,]+\s*(?:TL|₺)?/gi, ' ')
              .replace(/(?:₺\s?)?[\d.,]+\s*(?:TL|₺)\s*(?:kupon|indirim|parafpara|puan|üzeri|kazan)/gi, ' ')
              .replace(/kargo[^\n]{0,15}?(?:₺\s?)?[\d.,]+\s*(?:TL|₺)/gi, ' ')
              .replace(/en düşük fiyat\s*\([^)]*\)\s*:?\s*(?:₺\s?)?[\d.,]+[,–\-]*(?:\s*TL)?/gi, ' ');
          }
          function num(s){ return parseFloat(s.replace(/[^\d.,]/g, '').replace(/\./g, '').replace(',', '.')); }

          var low = (document.body ? document.body.innerText : '').toLowerCase();
          var words = ['captcha','robot olmad','robot değil','access denied','erişim engellendi','are you a human',
            'verify you are human','güvenlik doğrulaması','pardon our interruption','request blocked','just a moment',
            'karakterleri girin','characters you see'];
          var hit = '';
          for (var w = 0; w < words.length; w++) { if (low.indexOf(words[w]) >= 0) { hit = words[w]; break; } }

          var all = Array.prototype.slice.call(document.querySelectorAll('a[href]'));
          var anchors = all.filter(function(a){ return pat.test(a.href); });
          var usedFallback = false;
          if (!anchors.length) {
            // Sitenin kalıbı tutmadıysa: sonu uzun bir ürün numarasıyla biten aynı-site linkleri dene
            pat = /-[0-9]{6,}(?:[/?#]|$)|[-/][Pp][0-9]{5,}(?:[/?#]|$)/;
            anchors = all.filter(function(a){ return a.host === location.host && pat.test(a.href); });
            usedFallback = anchors.length > 0;
          }
          var sampleLinks = [];
          all.forEach(function(a){
            var tx = (a.innerText || '').trim();
            if (sampleLinks.length < 8 && tx.length > 20 && sampleLinks.indexOf(a.href) < 0) sampleLinks.push(a.href.slice(0, 120));
          });
          var seen = {}, cards = [];
          for (var i = 0; i < anchors.length && cards.length < 20; i++) {
            var a = anchors[i], key = keyOf(a.href);
            if (seen[key]) continue;
            var el = a;
            for (var up = 0; up < 12 && el.parentElement; up++) {
              var p = el.parentElement, keys = {}, n = 0;
              var inner = p.querySelectorAll('a[href]');
              for (var j = 0; j < inner.length; j++) {
                if (pat.test(inner[j].href)) { var k = keyOf(inner[j].href); if (!keys[k]) { keys[k] = 1; n++; } }
              }
              // Başka ürün içeriyorsa ya da sayfanın büyük kısmıysa kartın dışına çıktık demektir
              if (n > 1 || (p.innerText || '').length > 1200) break;
              el = p;
            }
            var text = el.innerText || '';
            var ps = (clean(text).match(priceRe) || []).map(num).filter(function(x){ return x > 0; });
            if (!ps.length) continue;
            seen[key] = 1;
            var max = Math.max.apply(null, ps);
            var ok = ps.filter(function(x){ return x >= max * 0.4; });
            var price = Math.min.apply(null, ok);

            var cands = [a.getAttribute('title') || '', a.getAttribute('aria-label') || ''];
            var img = el.querySelector('img[alt]'); if (img) cands.push(img.getAttribute('alt') || '');
            var h = el.querySelector('h2,h3,h4'); if (h) cands.push(h.innerText || '');
            text.split('\n').forEach(function(s){ s = s.trim(); if (!priceOne.test(s)) cands.push(s); });
            var title = '';
            cands.forEach(function(c){
              c = (c || '').replace(/\s+/g, ' ').trim();
              if (c.length >= 10 && c.length <= 250 && c.length > title.length) title = c;
            });

            var rm = text.match(/(?:^|\s)([1-5][.,]\d)(?=\s|\()/);
            var cm = text.match(/\((\d{1,3}(?:[.,]\d{1,3})?\s?B|\d{1,3}(?:\.\d{3})+|\d{1,6})\)/);
            cards.push({ t: title, p: price, lp: max, sepet: /sepette/i.test(text),
              r: rm ? rm[1] : '', rc: cm ? cm[1] : '', link: a.href,
              raw: text.replace(/\s+/g, ' ').slice(0, 160) });
          }
          return JSON.stringify({ title: document.title, url: location.href, block: hit,
            anchors: anchors.length, fallback: usedFallback, links: all.length,
            sampleLinks: cards.length ? [] : sampleLinks, cards: cards });
        })()
    """.trimIndent().replace("__PAT__", linkPattern)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = ink
        setContentView(buildUi())
        setupWebView()
    }

    // ---------- Arayüz ----------

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setTextColor(ink)
        setBackgroundColor(gold)
        setPadding(dp(12), dp(6), dp(12), dp(6))
        setOnClickListener { onClick() }
    }

    private fun label(text: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(paper)
        }

        root.addView(label("Fiyat Avcısı · Deneme 4", 18f, gold, true).apply {
            setBackgroundColor(ink)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        })

        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(12), dp(12), dp(4))
        }
        input = EditText(this).apply {
            setText("philips oneblade qp2834")
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, id, _ ->
                if (id == EditorInfo.IME_ACTION_SEARCH) { startTest(); true } else false
            }
        }
        searchRow.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        searchRow.addView(button("Ara") { startTest() }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(8) })
        root.addView(searchRow)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(4), dp(12), dp(4))
        }
        browserBtn = button("Tarayıcıyı göster") { toggleBrowser() }
        val lp = { LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) } }
        actions.addView(browserBtn, lp())
        actions.addView(button("Kopyala") { copyReport() }, lp())
        actions.addView(button("Paylaş") { shareReport() }, lp())
        root.addView(actions)

        status = label("Ürünü yaz, Ara'ya bas. 6 site sırayla taranır (~1 dk).", 14f, ink).apply {
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        root.addView(status)

        // Tarayıcı arkada tam boyutta durur, sonuç listesi üstünü kapatır
        val stack = FrameLayout(this)
        web = WebView(this)
        stack.addView(web, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        resultsScroll = ScrollView(this).apply { setBackgroundColor(paper) }
        results = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(24))
        }
        resultsScroll.addView(results)
        stack.addView(resultsScroll, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        root.addView(stack, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun toggleBrowser() {
        val showing = resultsScroll.visibility != View.VISIBLE
        resultsScroll.visibility = if (showing) View.VISIBLE else View.GONE
        browserBtn.text = if (showing) "Tarayıcıyı göster" else "Sonuçları göster"
    }

    // ---------- Tarayıcı ----------

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = userAgentString
                .replace("; wv", "")
                .replace(Regex("Version/\\d+\\.\\d+ "), "")
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme ?: ""
                return scheme != "http" && scheme != "https"
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (!running || extractScheduled) return
                extractScheduled = true
                val my = token
                // Kartlar kaydırdıkça yüklendiği için iki kez aşağı in, sonra oku
                handler.postDelayed({ if (my == token) view.evaluateJavascript("window.scrollTo(0, 2000);", null) }, 2500)
                handler.postDelayed({ if (my == token) view.evaluateJavascript("window.scrollTo(0, 4000);", null) }, 4500)
                handler.postDelayed({ if (my == token) extract() }, 7500)
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame) httpStatus = response.statusCode
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) loadError = error.description?.toString() ?: "hata"
            }
        }
    }

    // ---------- Arama akışı ----------

    private fun startTest() {
        if (running) return
        query = input.text.toString().trim()
        if (query.isEmpty()) return
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(input.windowToken, 0)
        if (resultsScroll.visibility != View.VISIBLE) toggleBrowser()
        running = true
        index = 0
        offers.clear()
        siteSummary.clear()
        results.removeAllViews()
        report.setLength(0)
        runNext()
    }

    private fun runNext() {
        if (index >= sites.size) {
            running = false
            web.stopLoading()
            showFinal()
            return
        }
        val site = sites[index]
        token++
        val my = token
        extractScheduled = false
        httpStatus = 0
        loadError = ""
        attempt = 0
        status.text = "${index + 1}/${sites.size}: ${site.name} taranıyor…"
        val q = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        web.loadUrl(site.url(q))
        handler.postDelayed({ if (my == token) extract() }, 25000)
    }

    private fun extract() {
        val site = sites[index]
        val my = ++token
        web.evaluateJavascript(extractJs(site.linkPattern)) { raw ->
            if (my != token) return@evaluateJavascript
            val data = try {
                JSONObject(JSONTokener(raw).nextValue() as String)
            } catch (e: Exception) {
                null
            }
            val found = data?.optJSONArray("cards")?.length() ?: 0
            // Ürünler geç yüklenen sitelerde (ör. Teknosa) biraz daha kaydırıp 2 kez daha dene
            if (found == 0 && data != null && attempt < 2) {
                attempt++
                status.text = "${site.name}: ürünler bekleniyor (${attempt}. tekrar)…"
                web.evaluateJavascript("window.scrollTo(0, ${3000 + attempt * 2500});", null)
                handler.postDelayed({ if (my == token) extract() }, 3500)
                return@evaluateJavascript
            }
            collect(site.name, data)
            index++
            handler.postDelayed({ if (my == token) runNext() }, 600)
        }
    }

    private fun collect(name: String, d: JSONObject?) {
        val cards = d?.optJSONArray("cards")
        val count = cards?.length() ?: 0
        for (i in 0 until count) {
            val c = cards!!.getJSONObject(i)
            offers.add(
                Offer(
                    site = name,
                    title = c.optString("t"),
                    price = c.optDouble("p"),
                    listPrice = c.optDouble("lp"),
                    basket = c.optBoolean("sepet"),
                    rating = c.optString("r"),
                    ratingCount = c.optString("rc"),
                    link = c.optString("link"),
                    raw = c.optString("raw"),
                )
            )
        }
        val block = d?.optString("block").orEmpty()
        val verdict = when {
            d == null -> "AÇILMADI${if (loadError.isNotEmpty()) " ($loadError)" else ""}"
            count == 0 && (block.isNotEmpty() || httpStatus == 403 || httpStatus == 429) -> "ENGEL ($block $httpStatus)".trim()
            count == 0 -> {
                val links = d?.optJSONArray("sampleLinks")
                val sample = (0 until (links?.length() ?: 0)).joinToString("\n    ") { links!!.optString(it) }
                "ÜRÜN KARTI BULUNAMADI (ürün linki ${d?.optInt("anchors") ?: 0}, toplam link ${d?.optInt("links") ?: 0})" +
                    if (sample.isNotEmpty()) "\n    Örnek linkler:\n    $sample" else ""
            }
            else -> "$count ürün okundu" + if (d?.optBoolean("fallback") == true) " (yedek yöntemle)" else ""
        }
        siteSummary.add("$name: $verdict")
        status.text = "$name: $verdict"
    }

    // ---------- Eşleştirme ----------

    private fun norm(s: String) = s.lowercase(Locale("tr", "TR")).replace(Regex("[^a-z0-9çğıöşü]"), "")

    private fun classify() {
        val tokens = query.lowercase(Locale("tr", "TR")).split(Regex("\\s+")).filter { it.isNotBlank() }
        // Model kodu: hem harf hem rakam içeren kelime (ör. qp2834)
        val modelTokens = tokens.filter { t -> t.any { it.isDigit() } && t.any { it.isLetter() } }.map { norm(it) }
        val required = modelTokens.ifEmpty { tokens.filter { it.length >= 3 }.map { norm(it) } }

        val modelCode = Regex("\\b[a-z]{1,4}[0-9]{3,5}\\b")
        // Model kodu dışındaki kelimeler (ör. philips, oneblade): kodu yazmayan ilanları yakalamak için
        val otherWords = tokens.filter { t -> !(t.any { it.isDigit() } && t.any { it.isLetter() }) && t.length >= 3 }.map { norm(it) }
        for (o in offers) {
            val t = o.title.lowercase(Locale("tr", "TR"))
            val nt = norm(o.title)
            val titleModels = modelCode.findAll(t).map { it.value }.toSet()
            o.kind = when {
                accessoryWords.any { t.contains(it) } -> Kind.ACCESSORY
                required.isNotEmpty() && required.all { nt.contains(it) } -> Kind.MATCH
                // Başlıkta başka bir model kodu varsa: aynı ailenin farklı modeli
                modelTokens.isNotEmpty() && titleModels.isNotEmpty() -> {
                    o.otherModel = titleModels.first().uppercase()
                    Kind.DIFFERENT_MODEL
                }
                // Model kodu yazmıyor ama diğer kelimelerin hepsi var: muhtemelen aynı ürün, kontrol edilmeli
                modelTokens.isNotEmpty() && otherWords.isNotEmpty() && otherWords.all { nt.contains(it) } -> Kind.POSSIBLE
                else -> Kind.OTHER
            }
            // Paket/varyant kodu: QP2834/31 gibi eğik çizgiden sonraki kısım
            if (o.kind == Kind.MATCH) {
                for (m in modelTokens) {
                    Regex("$m\\s*/\\s*([0-9]{2,3})").find(t)?.let { o.variant = "${m.uppercase()}/${it.groupValues[1]}" }
                }
            }
        }
        // Eşleşenlerin medyanının çok altında kalan "eşleşme" büyük ihtimalle aksesuardır
        val matchPrices = offers.filter { it.kind == Kind.MATCH }.map { it.price }.sorted()
        if (matchPrices.size >= 3) {
            val median = matchPrices[matchPrices.size / 2]
            offers.filter { it.kind == Kind.MATCH && it.price < median * 0.35 }.forEach { it.kind = Kind.ACCESSORY }
        }
    }

    // ---------- Sonuç ekranı ----------

    private fun money(v: Double) =
        "${if (v == Math.floor(v)) tlWhole.format(v) else tl.format(v)} TL"

    /** "13,2 B" (bin) gibi yazımları sayıya çevirip okunur hale getirir */
    private fun countText(s: String): String {
        val b = Regex("([0-9]+(?:[.,][0-9]+)?)\\s?B").find(s) ?: return s
        val v = b.groupValues[1].replace(",", ".").toDoubleOrNull() ?: return s
        return tlWhole.format(v * 1000)
    }

    private fun kindLabel(o: Offer) = when (o.kind) {
        Kind.ACCESSORY -> "aksesuar"
        Kind.DIFFERENT_MODEL -> "farklı model (${o.otherModel})"
        Kind.OTHER -> "farklı ürün"
        Kind.POSSIBLE -> "olası eşleşme (model kodu yazmıyor)"
        Kind.MATCH -> "eşleşme"
    }

    private fun showFinal() {
        classify()
        results.removeAllViews()

        val matches = offers.filter { it.kind == Kind.MATCH }.sortedBy { it.price }
        val others = offers.filter { it.kind != Kind.MATCH }.sortedBy { it.price }

        status.text = if (matches.isEmpty()) "Eşleşen ürün bulunamadı. Aşağıda benzer sonuçlar var."
        else "${matches.size} eşleşen teklif · en ucuz ${money(matches.first().price)} (${matches.first().site})"

        if (matches.isNotEmpty()) {
            val cheapest = matches.first().price
            val top = matches.last().price
            if (top > cheapest) {
                results.addView(label(
                    "Fark: en pahalısı ${money(top)} — ${money(top - cheapest)} daha fazla", 13f, muted
                ).apply { setPadding(dp(4), dp(8), dp(4), 0) })
            }
            // Aynı model farklı paketlerle satılıyorsa uyar: kutu içeriği ve garanti farklı olabilir
            val variants = matches.map { it.variant }.filter { it.isNotEmpty() }.distinct()
            if (variants.size > 1) {
                results.addView(label(
                    "Dikkat: farklı paketler var (${variants.joinToString(", ")}). Kutu içeriği, garanti " +
                        "ya da satıldığı ülke farklı olabilir; karşılaştırırken paket koduna bak.",
                    13f, orange
                ).apply { setPadding(dp(4), dp(8), dp(4), 0) })
            }
            sectionTitle("Eşleşen ürünler", green)
            matches.forEachIndexed { i, o -> offerRow(o, i == 0) }
        }
        val possibles = others.filter { it.kind == Kind.POSSIBLE }
        if (possibles.isNotEmpty()) {
            sectionTitle("Olası eşleşmeler — model kodu yazmıyor, kontrol et", orange)
            possibles.forEach { offerRow(it, false) }
        }
        val diffModels = others.filter { it.kind == Kind.DIFFERENT_MODEL }
        val rest = others.filter { it.kind != Kind.DIFFERENT_MODEL && it.kind != Kind.POSSIBLE }
        if (diffModels.isNotEmpty()) {
            sectionTitle("Aynı aileden farklı modeller", orange)
            diffModels.forEach { offerRow(it, false) }
        }
        if (rest.isNotEmpty()) {
            sectionTitle("Aksesuar / farklı ürün", muted)
            rest.forEach { offerRow(it, false) }
        }
        sectionTitle("Site durumu", ink)
        siteSummary.forEach { results.addView(label("• $it", 13f, ink).apply { setPadding(dp(4), dp(2), dp(4), dp(2)) }) }

        buildReport(matches, others)
    }

    private fun sectionTitle(text: String, color: Int) {
        results.addView(label(text, 16f, color, true).apply { setPadding(dp(4), dp(16), dp(4), dp(4)) })
    }

    private fun offerRow(o: Offer, best: Boolean) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(if (best) Color.parseColor("#FFF6DD") else Color.WHITE)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setOnClickListener {
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(o.link))) } catch (e: Exception) {}
            }
        }
        val head = buildString {
            append(money(o.price))
            if (o.basket) append(" (sepette)")
            append("  ·  ").append(o.site)
            if (best) append("  ★ en ucuz")
        }
        val tag = when {
            o.kind == Kind.MATCH && o.variant.isNotEmpty() -> "Paket: ${o.variant}"
            o.kind != Kind.MATCH -> kindLabel(o)
            else -> ""
        }
        card.addView(label(head, 16f, ink, true))
        if (tag.isNotEmpty()) card.addView(label(tag, 12f, if (o.kind == Kind.MATCH) green else orange, true))
        if (o.listPrice > o.price) {
            card.addView(label("Liste fiyatı ${money(o.listPrice)}", 12f, muted))
        }
        card.addView(label(o.title.ifEmpty { "(ad okunamadı)" }, 13f, ink).apply { setPadding(0, dp(4), 0, 0) })
        val meta = buildString {
            if (o.rating.isNotEmpty()) append("Puan ${o.rating}")
            if (o.rating.isNotEmpty() && o.ratingCount.isNotEmpty()) append(" · ${countText(o.ratingCount)} değerlendirme")
            if (isEmpty()) append("Puan bilgisi okunamadı")
            append("  ·  dokun: sitede aç")
        }
        card.addView(label(meta, 12f, muted).apply { setPadding(0, dp(4), 0, 0) })
        results.addView(card, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })
    }

    // ---------- Rapor ----------

    private fun buildReport(matches: List<Offer>, others: List<Offer>) {
        report.setLength(0)
        report.append("FİYAT AVCISI · DENEME 4\nArama: ").append(query).append("\n\n")
        report.append("SİTE DURUMU\n")
        siteSummary.forEach { report.append("- ").append(it).append("\n") }

        report.append("\nEŞLEŞENLER (ucuzdan pahalıya)\n")
        if (matches.isEmpty()) report.append("(yok)\n")
        matches.forEach { o ->
            report.append("- ").append(money(o.price))
            if (o.basket) report.append(" sepette")
            if (o.listPrice > o.price) report.append(" (liste ").append(money(o.listPrice)).append(")")
            report.append(" | ").append(o.site)
            if (o.variant.isNotEmpty()) report.append(" | paket ").append(o.variant)
            if (o.rating.isNotEmpty()) report.append(" | puan ").append(o.rating).append(" (").append(countText(o.ratingCount)).append(")")
            report.append("\n  ").append(o.title).append("\n")
        }

        report.append("\nOLASI EŞLEŞMELER (model kodu yazmıyor)\n")
        val possibles = others.filter { it.kind == Kind.POSSIBLE }
        if (possibles.isEmpty()) report.append("(yok)\n")
        possibles.forEach { o ->
            report.append("- ").append(money(o.price)).append(" | ").append(o.site).append("\n  ").append(o.title).append("\n")
        }

        report.append("\nAKSESUAR / FARKLI\n")
        others.filter { it.kind != Kind.POSSIBLE }.take(20).forEach { o ->
            report.append("- ").append(money(o.price)).append(" | ").append(o.site).append(" | ")
                .append(kindLabel(o)).append("\n  ").append(o.title).append("\n")
        }

        // Okuyucuyu iyileştirmem için: her siteden ilk 2 kartın ham metni
        report.append("\nHAM KART ÖRNEKLERİ\n")
        sites.forEach { s ->
            offers.filter { it.site == s.name }.take(2).forEach { o ->
                report.append("[").append(s.name).append("] ").append(o.raw).append("\n")
            }
        }
    }

    private fun copyReport() {
        if (report.isEmpty()) return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Fiyat Avcısı raporu", report.toString()))
        Toast.makeText(this, "Rapor kopyalandı", Toast.LENGTH_SHORT).show()
    }

    private fun shareReport() {
        if (report.isEmpty()) return
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, report.toString())
        }, "Raporu gönder"))
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        web.destroy()
        super.onDestroy()
    }
}
