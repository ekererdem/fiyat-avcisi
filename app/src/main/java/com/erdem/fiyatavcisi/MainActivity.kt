package com.erdem.fiyatavcisi

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
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

/**
 * Okuma testi: aynı aramayı 6 sitede, telefonun içindeki gizli bir tarayıcıyla açar
 * ve her sitenin sayfasından fiyat okunabiliyor mu, engel var mı raporlar.
 */
class MainActivity : Activity() {

    private data class Site(val name: String, val url: (String) -> String)

    private val sites = listOf(
        Site("Trendyol") { "https://www.trendyol.com/sr?q=$it" },
        Site("Hepsiburada") { "https://www.hepsiburada.com/ara?q=$it" },
        Site("Amazon") { "https://www.amazon.com.tr/s?k=$it" },
        Site("MediaMarkt") { "https://www.mediamarkt.com.tr/tr/search.html?query=$it" },
        Site("Teknosa") { "https://www.teknosa.com/arama/?s=$it" },
        Site("N11") { "https://www.n11.com/arama?q=$it" },
    )

    private val gold = Color.parseColor("#C8961E")
    private val ink = Color.parseColor("#1D1B16")
    private val paper = Color.parseColor("#FAF7F0")

    private lateinit var input: EditText
    private lateinit var web: WebView
    private lateinit var resultsScroll: ScrollView
    private lateinit var results: LinearLayout
    private lateinit var status: TextView
    private lateinit var browserBtn: Button

    private val handler = Handler(Looper.getMainLooper())
    private val report = StringBuilder()

    private var running = false
    private var index = 0
    private var query = ""
    private var token = 0
    private var extractScheduled = false
    private var httpStatus = 0
    private var loadError = ""

    // Sayfa yüklendikten sonra çalışan okuyucu
    private val extractJs = """
        (function(){
          var t = document.body ? document.body.innerText : '';
          var low = t.toLowerCase();
          var re = /(?:₺\s?\d{1,3}(?:\.\d{3})*(?:,\d{1,2})?)|(?:\d{1,3}(?:\.\d{3})*(?:,\d{1,2})?\s?(?:TL|₺))/g;
          var prices = t.match(re) || [];
          var words = ['captcha','robot olmad','robot değil','access denied','erişim engellendi',
            'are you a human','verify you are human','güvenlik doğrulaması','pardon our interruption',
            'request blocked','just a moment','karakterleri girin','characters you see'];
          var hit = '';
          for (var i = 0; i < words.length; i++) { if (low.indexOf(words[i]) >= 0) { hit = words[i]; break; } }
          var ld = document.querySelectorAll('script[type="application/ld+json"]').length;
          var state = [];
          try { Object.keys(window).forEach(function(k){ if (/^__.*(STATE|DATA|PROPS).*__$/i.test(k)) state.push(k); }); } catch(e) {}
          if (document.getElementById('__NEXT_DATA__')) state.push('__NEXT_DATA__');
          return JSON.stringify({
            title: document.title, url: location.href, len: t.length,
            block: hit, prices: prices.slice(0, 6), priceCount: prices.length,
            ld: ld, state: state.slice(0, 4), sample: t.replace(/\s+/g, ' ').slice(0, 400)
          });
        })()
    """.trimIndent()

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

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(paper)
        }

        val header = TextView(this).apply {
            text = "Fiyat Avcısı · Okuma Testi"
            setTextColor(gold)
            setBackgroundColor(ink)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        root.addView(header)

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
        searchRow.addView(button("Test et") { startTest() }, LinearLayout.LayoutParams(
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

        status = TextView(this).apply {
            text = "Bir ürün yaz ve Test et'e bas. 6 site sırayla açılır (~1 dk)."
            setTextColor(ink)
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        root.addView(status)

        // Tarayıcı arkada tam boyutta durur (sayfalar gerçek ekran boyutunda yüklensin diye),
        // sonuç listesi onun üstünü kapatır.
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
            // "wv" etiketi sitelere bunun gömülü tarayıcı olduğunu söyler; normal Chrome gibi görünsün
            userAgentString = userAgentString
                .replace("; wv", "")
                .replace(Regex("Version/\\d+\\.\\d+ "), "")
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme ?: ""
                // Sitenin kendi uygulamasını açmaya çalışmasını engelle
                return scheme != "http" && scheme != "https"
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (!running || extractScheduled) return
                extractScheduled = true
                val my = token
                // Sayfa içeriği JavaScript ile sonradan dolduğu için biraz bekle, sonra aşağı kaydır
                handler.postDelayed({ if (my == token) view.evaluateJavascript("window.scrollTo(0, 1500);", null) }, 3000)
                handler.postDelayed({ if (my == token) extract() }, 7000)
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame) httpStatus = response.statusCode
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) loadError = error.description?.toString() ?: "hata"
            }
        }
    }

    // ---------- Test akışı ----------

    private fun startTest() {
        if (running) return
        query = input.text.toString().trim()
        if (query.isEmpty()) return
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(input.windowToken, 0)
        running = true
        index = 0
        results.removeAllViews()
        report.setLength(0)
        report.append("FİYAT AVCISI OKUMA TESTİ\nArama: ").append(query).append("\n\n")
        runNext()
    }

    private fun runNext() {
        if (index >= sites.size) {
            running = false
            web.stopLoading()
            status.text = "Bitti. Raporu Kopyala ya da Paylaş ile bana gönder."
            return
        }
        val site = sites[index]
        token++
        val my = token
        extractScheduled = false
        httpStatus = 0
        loadError = ""
        status.text = "${index + 1}/${sites.size}: ${site.name} açılıyor…"
        val q = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        web.loadUrl(site.url(q))
        // Hiç yüklenmezse 25 sn sonra yine de oku ve geç
        handler.postDelayed({ if (my == token) extract() }, 25000)
    }

    private fun extract() {
        val site = sites[index]
        val my = ++token // bu siteye ait bekleyen diğer zamanlayıcıları iptal eder
        web.evaluateJavascript(extractJs) { raw ->
            if (my != token) return@evaluateJavascript
            val data = try {
                JSONObject(JSONTokener(raw).nextValue() as String)
            } catch (e: Exception) {
                null
            }
            addResult(site.name, data)
            index++
            handler.postDelayed({ if (my == token) runNext() }, 600)
        }
    }

    private fun addResult(name: String, d: JSONObject?) {
        val priceCount = d?.optInt("priceCount") ?: 0
        val block = d?.optString("block").orEmpty()
        val verdict: String
        val color: Int
        when {
            loadError.isNotEmpty() && d == null -> { verdict = "AÇILMADI"; color = Color.parseColor("#B3261E") }
            (block.isNotEmpty() && priceCount < 3) || httpStatus == 403 || httpStatus == 429 ->
                { verdict = "ENGEL"; color = Color.parseColor("#B3261E") }
            priceCount > 0 -> { verdict = "OKUNDU"; color = Color.parseColor("#2E7D32") }
            else -> { verdict = "FİYAT YOK"; color = Color.parseColor("#E07B00") }
        }

        val prices = d?.optJSONArray("prices")
        val priceList = (0 until (prices?.length() ?: 0)).joinToString(" · ") { prices!!.optString(it) }
        val state = d?.optJSONArray("state")
        val stateList = (0 until (state?.length() ?: 0)).joinToString(", ") { state!!.optString(it) }

        val detail = buildString {
            if (httpStatus != 0) append("HTTP $httpStatus\n")
            if (loadError.isNotEmpty()) append("Hata: $loadError\n")
            if (block.isNotEmpty()) append("Engel işareti: \"$block\"\n")
            append("Bulunan fiyat sayısı: $priceCount\n")
            if (priceList.isNotEmpty()) append("İlk fiyatlar: $priceList\n")
            append("Yapısal veri: JSON-LD ${d?.optInt("ld") ?: 0}")
            if (stateList.isNotEmpty()) append(", $stateList")
            append("\nSayfa başlığı: ${d?.optString("title").orEmpty()}")
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        card.addView(TextView(this).apply {
            text = "$name   $verdict"
            setTextColor(color)
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
        })
        card.addView(TextView(this).apply {
            text = detail
            setTextColor(ink)
            textSize = 13f
            setPadding(0, dp(6), 0, 0)
        })
        results.addView(card, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })

        report.append("== ").append(name).append(": ").append(verdict).append(" ==\n")
            .append(detail).append("\n")
            .append("Adres: ").append(d?.optString("url").orEmpty()).append("\n")
            .append("Metin uzunluğu: ").append(d?.optInt("len") ?: 0).append("\n")
            .append("Örnek: ").append(d?.optString("sample").orEmpty().take(300)).append("\n\n")
    }

    // ---------- Rapor ----------

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
