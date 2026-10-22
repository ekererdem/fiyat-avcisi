package com.erdem.fiyatavcisi

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
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
        var origin: String = "",
        var detailChecked: Boolean = false,
        var warranty: String = "",
        var warrantyLine: String = "",
        var seller: String = "",
        var sellerLine: String = "",
        var warrantyType: String = "",
        var warrantyDuration: String = "",
        var sellerScore: String = "",
        var detailPage: String = "",
    )

    private enum class Kind { MATCH, MUADIL, POSSIBLE, SUSPICIOUS, OTHER_PACKAGE, ACCESSORY, DIFFERENT_MODEL, OTHER }

    /** Ürün kimliği: ne yazılırsa yazılsın (barkod, ad, kod) aramanın dayandığı tek ürün */
    private data class Identity(
        val code: String,        // küçük harf paket kodu: "qp2834/31", "mtjv3tu/a"
        val base: String,        // temel model: "qp2834", "mtjv3"
        val label: String,       // ekranda: "QP2834/31"
        val searchQuery: String, // sitelere sorulacak sabit arama: "Philips QP2834"
        val family: String = "",  // aile adı: "Philips OneBlade 360"
        val sample: String = "",
        val count: Int = 0,
        val price: Double = 0.0,
    )

    private val sites = listOf(
        Site("Trendyol", "-p-[0-9]") { "https://www.trendyol.com/sr?q=$it" },
        Site("Hepsiburada", "-pm?-[A-Za-z0-9]") { "https://www.hepsiburada.com/ara?q=$it" },
        Site("Amazon", "/dp/") { "https://www.amazon.com.tr/s?k=$it" },
        Site("MediaMarkt", "/product/") { "https://www.mediamarkt.com.tr/tr/search.html?query=$it" },
        Site("Teknosa", "-p-[0-9]") { "https://www.teknosa.com/arama/?s=$it" },
        Site("N11", "urun[.]n11[.]com|n11[.]com/urun/") { "https://www.n11.com/arama?q=$it" },
    )

    // Kimlik bulmak için hızlı taranan siteler (barkod ve kodla aramada en iyi sonuç verenler)
    private val resolverSites by lazy { listOf(sites[0], sites[2], sites[1]) }

    // Başlıkta geçerse "aksesuar olabilir" sayılan kelimeler
    private val accessoryWords = listOf(
        "uyumlu", "kapak", "kulaklık ucu", "uçları", "askı", "temizleme", "kalemi", "yedek bıçak", "yedek başlık", "yedek bicak", "kablo", "kılıf", "kilif", "adaptör", "adaptor",
        "koruyucu", "stand", "şarj aleti", "sarj aleti", "şarj cihazı", "çanta", "sticker",
        "replacement", "compatible", "blades", "blade kit", "charger", "cable", "case for",
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
    private var identity: Identity? = null
    private var userInput = ""
    private var resolvePhase = false
    private var resolveType = ""     // barkod | kod | ad
    private var resolveQuery = ""    // kimlik aranırken kullanılan yazı (barkoddan bulunan ad olabilir)
    private var googleTried = false
    private var googlePhase = false
    private var activeSites: List<Site> = emptyList()
    private val resolveLog = StringBuilder()
    private val prefs by lazy { getSharedPreferences("kimlik", Context.MODE_PRIVATE) }
    private var detailPhase = false
    private val detailQueue = mutableListOf<Offer>()
    private var detailIndex = 0
    private var detailAttempt = 0

    /** Ürün sayfası okuyucu: garanti tipi ve satıcı. Siteye özel değil, sayfadaki yazılara bakar. */
    private val detailJs = """
        (function(){
          var t = document.body ? document.body.innerText : '';
          var fullLen = t.length;
          // Sayfanın altındaki "benzer ürünler" gibi bölümler başka ürünlere ait; garanti/satıcıyı onlardan okuma
          var cut = t.search(/benzer ürünler|bunlar da ilgini|ilgini(zi)? çekebilir|birlikte al|önerilen ürünler|sizin için seçtik|son gezdiğin|tamamlayıcı ürünler|bu ürünü alanlar/i);
          if (cut > 500) t = t.slice(0, cut);
          var lines = t.split('\n').map(function(s){ return s.trim(); }).filter(function(s){ return s.length > 0; });
          // Üst menü ve site linkleri satıcı adı sanılmasın
          var junk = /satış yap|satıcı ol|mesajlarım|giriş yap|üye ol|kampanya|popüler|yardım|sipariş|sepet|favori/i;
          // Satıcı adının çevresindeki buton ve sayaçlar (ad sanılmasın)
          var notName = /mağazaya sor|satıcıya sor|hızlı gönderim|takip|kazan|detayları gör|takipçi|değerlendirme|soru|ürün|kargo|gönderim/i;
          function ctx(i, b, a){ return lines.slice(Math.max(0, i - b), i + a + 1).join(' | ').slice(0, 240); }

          // Garanti: kesin ifade, "Garanti Tipi: ..." özelliği ve "Garanti Süresi: ..." özelliği
          // Kesin garanti ifadesini sadece ürünün kendi başlığında ve "Garanti ..." özellik satırlarında ara.
          // (Önerilen başka ürünlerin başlıkları ya da "Apple Türkiye garantili mi?" gibi sorular sayılmasın.)
          var own = [];
          var h1 = document.querySelector('h1'); if (h1) own.push(h1.innerText || '');
          for (var g = 0; g < lines.length; g++) {
            if (/^(garanti tipi|garanti türü|garanti bilgisi|garanti durumu|garanti)\s*:?/i.test(lines[g]) && lines[g].indexOf('?') < 0) {
              own.push(lines[g]); if (lines[g + 1]) own.push(lines[g + 1]);
            }
          }
          var wm = own.join('\n').match(/(ithalatçı firma garantili|ithalatçı garantili|resmi distribütör garantili|distribütör garantili|apple türkiye garantili|türkiye garantili|üretici garantili|marka garantili|uluslararası garanti)/i);
          var wt = t.match(/garanti tipi\s*:?\s*\|?\s*([^,|\n]{3,40})/i);
          var wd = t.match(/garanti süresi\s*:?\s*\|?\s*(\d+\s*(?:yıl|ay))/i);
          var wl = '';
          for (var q = 0; q < lines.length; q++) { if (/garanti (tipi|türü|süresi)|garantili/i.test(lines[q])) { wl = ctx(q, 0, 1); break; } }

          // Satıcı: birkaç farklı yerleşimi sırayla dene
          var seller = '', sline = '';
          var by = t.match(/bu ürün\s+([^\n]{2,40}?)\s+tarafından/i);
          if (by && !junk.test(by[1])) seller = by[1];
          for (var i = 0; i < lines.length && !seller; i++) {
            var L = lines[i];
            var m1 = L.match(/^(?:satıcı|mağaza|satan)\s*:\s*(.{2,40})$/i);
            if (m1 && !junk.test(m1[1])) { seller = m1[1]; sline = ctx(i, 1, 2); break; }
            if (/^(?:gönderici\s*\/\s*satıcı|satıcı|satıcı adı|satan)\s*:?$/i.test(L) && lines[i + 1] && !junk.test(lines[i + 1])) {
              seller = lines[i + 1]; sline = ctx(i, 0, 3); break;
            }
            if (/^(?:mağazaya git|satıcıya git|mağazayı ziyaret et|satıcıya sor|mağazayı takip et|takip et)$/i.test(L) && i > 0) {
              for (var k = i - 1; k >= Math.max(0, i - 5); k--) {
                var c = lines[k];
                if (c.length >= 2 && c.length <= 40 && !/^[\d.,\s]+[BbKk]?$/.test(c) && !junk.test(c) && !notName.test(c)) { seller = c; break; }
              }
              sline = ctx(i, 5, 1);
              if (seller) break;
            }
          }
          var sc = (sline.match(/(?:^|[\s|])((?:10|[0-9])[.,]\d)(?=[\s|]|$)/) || [])[1] || '';
          var dbg = [];
          for (var j = 0; j < lines.length && dbg.length < 3; j++) {
            if (/mağazaya git|satıcı puanı|mağaza puanı|satıcıya sor|gönderici/i.test(lines[j])) dbg.push(ctx(j, 3, 1));
          }
          return JSON.stringify({ w: wm ? wm[1] : '', wt: wt ? wt[1].trim() : '', wd: wd ? wd[1].trim() : '',
            seller: seller.trim(), score: sc, wl: wl, sl: (sline || dbg.join(' || ')).slice(0, 320),
            len: fullLen,
            page: document.title.slice(0, 80) + ' | metin ' + fullLen + ' karakter | ' + location.href.slice(0, 90) });
        })()
    """.trimIndent()

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
              .replace(/\([^)]*başına[^)]*\)/gi, ' ')
              .replace(/en düşük fiyat\s*\([^)]*\)\s*:?\s*(?:₺\s?)?[\d.,]+[,–\-]*(?:\s*TL)?/gi, ' ');
          }
          function num(s){ return parseFloat(s.replace(/[^\d.,]/g, '').replace(/\./g, '').replace(',', '.')); }

          var low = (document.body ? document.body.innerText : '').toLowerCase();
          var words = ['captcha','robot olmad','robot değil','access denied','erişim engellendi','are you a human',
            'verify you are human','güvenlik doğrulaması','pardon our interruption','request blocked','just a moment',
            'karakterleri girin','characters you see'];
          var hit = '';
          for (var w = 0; w < words.length; w++) { if (low.indexOf(words[w]) >= 0) { hit = words[w]; break; } }
          // Site "bulunamadı" diyorsa altındaki öneri/popüler ürünleri sonuç sanma
          var head = low.slice(0, 5000);
          var nores = /sonuç bulunamadı|ürün bulunamadı|bulamadık|eşleşen (bir )?(ürün|sonuç) (bulunamadı|yok)|için sonuç yok|sonuç yok|(^|[^0-9])0 (ürün|sonuç)/.test(head);
          if (nores) return JSON.stringify({ title: document.title, url: location.href, block: hit, noResults: true,
            anchors: 0, links: document.querySelectorAll('a[href]').length, cards: [] });

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
          for (var i = 0; i < anchors.length && cards.length < 40; i++) {
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
            var h = el.querySelector('h2,h3,h4'); if (h) cands.push(h.innerText || '');
            text.split('\n').forEach(function(s){ s = s.trim(); if (!priceOne.test(s)) cands.push(s); });
            var title = '';
            // Kargo, rozet, puan gibi satırlar ürün adı değildir
            var notTitle = /teslimat|kargo|sepete|stokta|kupon|favoriledi|değerlendirilen|ziyaret edilen|en çok|satıldı|ürün puanı|son [0-9]+ gün|kişi ekledi|yıldız üzerinden/i;
            cands.forEach(function(c){
              c = (c || '').replace(/\s+/g, ' ').trim();
              if (c.length >= 10 && c.length <= 250 && c.length > title.length && !notTitle.test(c)) title = c;
            });
            // Ad bulunamazsa linkin içindeki ürün adını kullan; resim açıklaması ("siyah zemin üzerinde...") en son çare
            if (!title) {
              try {
                var seg = decodeURIComponent(a.pathname.split('/').filter(function(x){ return x; }).pop() || '');
                seg = seg.replace(/\.html?/i, '').replace(/^_+/, '').replace(/-p(m)?-[A-Za-z0-9]+/, '').replace(/-[0-9]{5,}/, '').replace(/[-_]+/g, ' ').trim();
                if (seg.length >= 10) title = seg;
              } catch (e) {}
            }
            if (!title) { var img = el.querySelector('img[alt]'); if (img) title = (img.getAttribute('alt') || '').trim(); }

            var rm = text.match(/(?:^|\s)([1-4][.,]\d|5[.,]0)(?=\s|\()/);
            var cm = text.match(/\((\d{1,3}(?:[.,]\d{1,3})?\s?B|\d{1,3}(?:\.\d{3})+|\d{1,6})(?:\s*[Dd]eğerlendirme)?\)/);
            cards.push({ t: title, p: price, lp: max, sepet: /sepette/i.test(text),
              r: rm ? rm[1] : '', rc: cm ? cm[1] : '', link: a.href,
              raw: text.replace(/\s+/g, ' ').slice(0, 160) });
          }
          return JSON.stringify({ title: document.title, url: location.href, block: hit,
            anchors: anchors.length, fallback: usedFallback, links: all.length,
            sampleLinks: cards.length ? [] : sampleLinks, cards: cards,
            pageSample: cards.length ? '' : (document.body ? document.body.innerText : '').replace(/\s+/g, ' ').slice(0, 200) });
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

        root.addView(label("Fiyat Avcısı · Deneme 14", 18f, gold, true).apply {
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
                if (googlePhase) {
                    handler.postDelayed({ if (my == token) googleExtract() }, 3000)
                    return
                }
                if (detailPhase) {
                    handler.postDelayed({ if (my == token) view.evaluateJavascript("window.scrollTo(0, 1500);", null) }, 2500)
                    handler.postDelayed({ if (my == token) detailExtract() }, 5000)
                    return
                }
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
        userInput = input.text.toString().trim()
        if (userInput.isEmpty()) return
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(input.windowToken, 0)
        if (resultsScroll.visibility != View.VISIBLE) toggleBrowser()
        running = true
        results.removeAllViews()
        report.setLength(0)
        resolveLog.setLength(0)
        identity = null

        // Bu giriş için daha önce bir ürün seçildiyse sormadan onu kullan
        val saved = prefs.getString("q:" + norm(userInput), null)
        if (saved != null) {
            resolveQuery = userInput
            identity = identityFromJson(saved)
            resolveLog.append("Kimlik hafızadan alındı.\n")
            startSearch()
            return
        }
        // 1. adım: kimlik bul
        resolveType = inputType(userInput)
        resolveQuery = userInput
        googleTried = false
        googlePhase = false
        resolvePhase = true
        activeSites = resolverSites
        query = userInput
        index = 0
        offers.clear()
        siteSummary.clear()
        runNext()
    }

    /** 2. adım: 6 siteyi her zaman kimliğin sabit aramasıyla tara */
    private fun startSearch() {
        resolvePhase = false
        activeSites = sites
        query = identity?.searchQuery ?: resolveQuery
        index = 0
        offers.clear()
        siteSummary.clear()
        runNext()
    }

    private fun runNext() {
        if (index >= activeSites.size) {
            if (resolvePhase) finishResolve() else startDetails()
            return
        }
        val site = activeSites[index]
        token++
        val my = token
        extractScheduled = false
        httpStatus = 0
        loadError = ""
        attempt = 0
        status.text = (if (resolvePhase) "Ürün tanınıyor " else "Fiyatlar: ") + "${index + 1}/${activeSites.size}: ${site.name}…"
        val q = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        web.loadUrl(site.url(q))
        handler.postDelayed({ if (my == token) extract() }, 25000)
    }

    private fun extract() {
        val site = activeSites[index]
        val my = ++token
        web.evaluateJavascript(extractJs(site.linkPattern)) { raw ->
            if (my != token) return@evaluateJavascript
            val data = try {
                JSONObject(JSONTokener(raw).nextValue() as String)
            } catch (e: Exception) {
                null
            }
            val found = data?.optJSONArray("cards")?.length() ?: 0
            if (data?.optBoolean("noResults") == true) {
                collect(site.name, data)
                index++
                handler.postDelayed({ if (my == token) runNext() }, 600)
                return@evaluateJavascript
            }
            // Ürünler geç yüklenen sitelerde (ör. Teknosa) biraz daha kaydırıp 2 kez daha dene
            if (found == 0 && data != null && data.optInt("links") == 0 && attempt < 2) {
                attempt++
                status.text = "${site.name}: sayfa boş geldi, yeniden yükleniyor (${attempt}. tekrar)…"
                extractScheduled = false
                web.reload()
                handler.postDelayed({ if (my == token) extract() }, 25000)
                return@evaluateJavascript
            }
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
            d?.optBoolean("noResults") == true -> "bu aramada sonuç yok (site \"bulunamadı\" dedi)"
            count == 0 && (block.isNotEmpty() || httpStatus == 403 || httpStatus == 429) -> "ENGEL ($block $httpStatus)".trim()
            count == 0 -> {
                val links = d?.optJSONArray("sampleLinks")
                val sample = (0 until (links?.length() ?: 0)).joinToString("\n    ") { links!!.optString(it) }
                "ÜRÜN KARTI BULUNAMADI (ürün linki ${d?.optInt("anchors") ?: 0}, toplam link ${d?.optInt("links") ?: 0})" +
                    (if (sample.isNotEmpty()) "\n    Örnek linkler:\n    $sample" else "") +
                    "\n    Sayfa başlığı: ${d?.optString("title").orEmpty()}" +
                    "\n    Sayfa metni: ${d?.optString("pageSample").orEmpty().ifEmpty { "(boş)" }}"
            }
            else -> "$count ürün okundu" + if (d?.optBoolean("fallback") == true) " (yedek yöntemle)" else ""
        }
        siteSummary.add("$name: $verdict")
        status.text = "$name: $verdict"
    }

    // ---------- Ürün kimliği ----------

    private fun isAccessory(lowerTitle: String): Boolean {
        val t = lowerTitle
            .replace("kablosuz", " ")
            .replace(Regex("[0-9]+\\s*(adet\\s*)?yedek (bıçak|başlık)"), " ") // "1 Yedek Bıçak" = kutudan çıkan yedek
        return accessoryWords.any { t.contains(it) }
    }

    private val appleCode = Regex("\\b([a-z0-9]{4,5})([a-z]{1,2})/a\\b")
    private val genericCode = Regex("\\b([a-z]{1,4}[0-9]{3,5})(?:\\s*/\\s*([0-9]{2,3}))?\\b")

    /** Başlıktaki paket kodları: (paket kodu, temel model) çiftleri */
    private fun codesIn(title: String): List<Pair<String, String>> {
        val t = title.lowercase(Locale("tr", "TR"))
        val out = mutableListOf<Pair<String, String>>()
        appleCode.findAll(t).forEach { m ->
            val b = m.groupValues[1]
            if (b.any { it.isDigit() } && b.any { it.isLetter() }) out.add("$b${m.groupValues[2]}/a" to b)
        }
        genericCode.findAll(t).forEach { m ->
            val b = m.groupValues[1]
            val v = m.groupValues[2]
            out.add((if (v.isNotEmpty()) "$b/$v" else b) to b)
        }
        return out
    }

    private val unitWords = setOf("adet", "li", "lı", "lu", "lü", "kg", "gr", "g", "ml", "lt", "l", "litre", "cl", "mm", "cm",
        "paket", "kutu", "rulo", "yaprak", "parça")

    /**
     * Aramayı parçalar:
     *  words = ürün türü/marka kelimeleri, hard = şart olan ölçüler ("no 6" -> no6, "pro 2" -> pro2).
     *  "100 adet", "6 kg" gibi miktarlar şart değildir; birim fiyatla karşılaştırılır.
     */
    private fun querySpec(q: String): Pair<List<String>, List<String>> {
        val tokens = q.split(Regex("[\\s:]+")).map { norm(it) }.filter { it.isNotEmpty() }
        val words = mutableListOf<String>()
        val hard = mutableListOf<String>()
        val qty = Regex("^[0-9]+(li|lı|lu|lü|adet|kg|gr|g|ml|lt|l)$")
        tokens.forEachIndexed { i, t ->
            val next = tokens.getOrNull(i + 1)
            val prev = tokens.getOrNull(i - 1)
            val isNum = t.all { it.isDigit() }
            when {
                t in unitWords || qty.matches(t) -> {}
                isNum && next != null && next in unitWords -> {}
                isNum && (prev == "no" || prev == "numara") -> hard.add("no$t")
                isNum && prev != null -> hard.add(prev + t)
                t.length >= 3 -> words.add(t)
            }
        }
        return words to hard
    }

    /** Aramadaki zorunlu kelimeler (tür kelimeleri + şart olan ölçüler) */
    private fun requiredWords(q: String): List<String> = querySpec(q).let { it.first + it.second }

    private fun inputType(s: String): String {
        val c = s.replace(" ", "")
        return when {
            c.length in 8..14 && c.all { it.isDigit() } -> "barkod"
            codesIn(s).isNotEmpty() -> "kod"
            else -> "ad"
        }
    }

    /** Başlıktan sitelere sorulacak sade ürün adı: kodu çıkarıp ilk 5 kelime */
    private fun familyQuery(title: String, base: String): String {
        val t = title.replace(Regex("(?i)\\b$base[a-z]{0,2}/a\\b"), " ")
        return t.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }.take(5).joinToString(" ")
    }

    private fun finishResolve() {
        resolvePhase = false
        val cands = buildCandidates()
        resolveLog.append("Tanıma: ").append(resolveQuery).append(" (").append(resolveType).append(")\n")
        siteSummary.forEach { resolveLog.append("  tanıma — ").append(it).append("\n") }
        cands.forEach {
            resolveLog.append("  aday: ").append(it.label).append(" · ").append(it.count).append(" ilan · ör: ")
                .append(it.sample.take(70)).append("\n")
        }
        when {
            // Barkod sitelerde yoksa ürünün adını Google'dan öğren
            cands.isEmpty() && resolveType == "barkod" && !googleTried -> googleLookup()
            cands.isEmpty() -> {
                resolveLog.append("Kimlik bulunamadı; \"").append(resolveQuery).append("\" ile arandı.\n")
                startSearch()
            }
            cands.size == 1 || (resolveType == "barkod" && cands[0].count >= 2 * cands[1].count) -> {
                chooseIdentity(cands[0])
            }
            else -> showChooser(cands)
        }
    }

    private val googleJs = """
        (function(){
          var t = [];
          document.querySelectorAll('h3').forEach(function(h){ var s = (h.innerText || '').trim(); if (s.length > 5) t.push(s); });
          return JSON.stringify({ titles: t.slice(0, 8), page: document.title });
        })()
    """.trimIndent()

    private fun googleLookup() {
        googleTried = true
        googlePhase = true
        token++
        val my = token
        extractScheduled = false
        status.text = "Barkod sitelerde yok, ürünün adı Google'dan öğreniliyor…"
        web.loadUrl("https://www.google.com/search?hl=tr&q=" + URLEncoder.encode(userInput.replace(" ", ""), "UTF-8"))
        handler.postDelayed({ if (my == token) googleExtract() }, 20000)
    }

    private fun googleExtract() {
        val my = ++token
        web.evaluateJavascript(googleJs) { raw ->
            if (my != token) return@evaluateJavascript
            googlePhase = false
            val titles = try {
                val a = JSONObject(JSONTokener(raw).nextValue() as String).optJSONArray("titles")
                (0 until (a?.length() ?: 0)).map { a!!.optString(it) }
            } catch (e: Exception) {
                emptyList()
            }
            resolveLog.append("Google sonuçları:\n")
            titles.take(5).forEach { resolveLog.append("  · ").append(it.take(90)).append("\n") }
            val name = barcodeName(titles)
            if (name.isEmpty()) {
                resolveLog.append("Google'dan ürün adı çıkarılamadı.\n")
                startSearch()
                return@evaluateJavascript
            }
            resolveLog.append("Barkoddan bulunan ad: ").append(name).append("\n")
            // Bulunan adla kimlik adımını tekrarla
            resolveType = "ad"
            resolveQuery = name
            resolvePhase = true
            activeSites = resolverSites
            query = name
            index = 0
            offers.clear()
            siteSummary.clear()
            runNext()
        }
    }

    /** Google başlıklarından ürün adı: site adı ve "fiyatı" gibi ekleri at, en sık geçen başlığı al */
    private fun barcodeName(titles: List<String>): String {
        val cleaned = titles.map { t ->
            t.split(Regex("\\s[-|–—:]\\s")).first()
                .replace(Regex("(?i)\\b(fiyatı|fiyatları|fiyat|en ucuz|satın al|barkod|ürün bilgisi)\\b"), " ")
                .replace(Regex("[0-9]{8,14}"), " ")
                .replace(Regex("\\s+"), " ").trim()
        }.filter { it.length >= 8 && it.any { c -> c.isLetter() } }
        if (cleaned.isEmpty()) return ""
        // İlk 3 kelimesi en çok tekrar eden başlık daha güvenilir
        val key = { s: String -> s.lowercase(Locale("tr", "TR")).split(" ").take(3).joinToString(" ") }
        val best = cleaned.groupingBy(key).eachCount().maxByOrNull { it.value }?.key
        return (cleaned.firstOrNull { key(it) == best } ?: cleaned.first()).split(" ").take(8).joinToString(" ")
    }

    private fun buildCandidates(): List<Identity> {
        val type = resolveType
        val need = requiredWords(resolveQuery)
        val inputCodes = codesIn(resolveQuery).flatMap { listOf(it.first, it.second) }.toSet()
        val count = mutableMapOf<String, Int>()
        val baseOf = mutableMapOf<String, String>()
        val titles = mutableMapOf<String, MutableList<String>>()
        val prices = mutableMapOf<String, MutableList<Double>>()
        val perSite = mutableMapOf<String, Int>()
        for (o in offers) {
            val t = o.title.lowercase(Locale("tr", "TR"))
            if (isAccessory(t)) continue
            val nt = norm(o.title)
            val codes = codesIn(o.title)
            val pick = when (type) {
                "ad" -> if (need.isNotEmpty() && need.all { nt.contains(it) }) codes.firstOrNull() else null
                "kod" -> codes.firstOrNull { it.first in inputCodes || it.second in inputCodes }
                else -> {
                    // Barkod aramasında sitenin ilk birkaç sonucu yeterli; gerisi "benzer ürün" olabilir
                    val n = (perSite[o.site] ?: 0) + 1
                    perSite[o.site] = n
                    if (n <= 5) codes.firstOrNull() else null
                }
            } ?: continue
            count[pick.first] = (count[pick.first] ?: 0) + 1
            baseOf[pick.first] = pick.second
            titles.getOrPut(pick.first) { mutableListOf() }.add(o.title)
            prices.getOrPut(pick.first) { mutableListOf() }.add(o.price)
        }
        // Paketi yazılmamış kod (QP2834), aynı modelin paketli hali (QP2834/31) varsa ayrı aday olmasın
        val withVariant = count.keys.filter { it != baseOf[it] }.map { baseOf[it] }.toSet()
        val keys = count.keys.filter { !(it == baseOf[it] && it in withVariant) }
        val top = keys.maxOfOrNull { count[it] ?: 0 } ?: return emptyList()
        return keys.filter { (count[it] ?: 0) * 4 >= top }
            .sortedWith(compareByDescending<String> { count[it] ?: 0 }.thenBy { prices[it]?.minOrNull() ?: 0.0 })
            .take(5)
            .map { code ->
                val base = baseOf[code] ?: code
                val fams = titles[code].orEmpty().map { familyQuery(it, base) }
                val fam = fams.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key.orEmpty()
                val apple = code.endsWith("/a")
                val brand = fam.split(" ").firstOrNull().orEmpty()
                Identity(
                    code = code,
                    base = base,
                    label = code.uppercase(),
                    searchQuery = if (apple) fam else "$brand ${base.uppercase()}".trim(),
                    family = fam.split(" ").take(3).joinToString(" "),
                    sample = titles[code].orEmpty().minByOrNull { it.length }.orEmpty(),
                    count = count[code] ?: 0,
                    price = prices[code].orEmpty().minOrNull() ?: 0.0,
                )
            }
    }

    private fun showChooser(cands: List<Identity>) {
        status.text = "Birden fazla ürün bulundu, hangisini kastettin?"
        val items = cands.map { "${it.label}\n${it.sample.take(80)}\n${it.count} ilan · ${money(it.price)}'den başlıyor" } +
            "Hepsini göster (paket ayırmadan)"
        AlertDialog.Builder(this)
            .setTitle("Hangisini kastettin?")
            .setItems(items.toTypedArray<CharSequence>()) { _, which ->
                if (which < cands.size) chooseIdentity(cands[which]) else {
                    resolveLog.append("Paket seçilmedi; yazılan kelimelerle arandı.\n")
                    startSearch()
                }
            }
            .setCancelable(false)
            .show()
    }

    private fun chooseIdentity(id: Identity) {
        identity = id
        // Aynı giriş bir daha yazılınca sormadan bu ürünle arasın
        prefs.edit().putString("q:" + norm(userInput), identityToJson(id)).apply()
        resolveLog.append("Seçilen ürün: ").append(id.label).append(" · arama: ").append(id.searchQuery).append("\n")
        startSearch()
    }

    private fun identityToJson(id: Identity) = JSONObject().apply {
        put("code", id.code); put("base", id.base); put("label", id.label)
        put("q", id.searchQuery); put("sample", id.sample); put("family", id.family)
    }.toString()

    private fun identityFromJson(s: String): Identity? = try {
        val j = JSONObject(s)
        Identity(j.getString("code"), j.getString("base"), j.getString("label"), j.getString("q"),
            family = j.optString("family"), sample = j.optString("sample"))
    } catch (e: Exception) {
        null
    }

    private fun forgetIdentity() {
        prefs.edit().remove("q:" + norm(userInput)).apply()
        input.setText(userInput)
        startTest()
    }

    // ---------- Eşleştirme ----------

    // Aynı şeyin farklı yazımları tek biçime iner (ataç = ataş)
    private fun norm(s: String) = s.lowercase(Locale("tr", "TR")).replace("ataç", "ataş").replace(Regex("[^a-z0-9çğıöşü]"), "")

    private fun classify() {
        val id = identity
        if (id != null) classifyByIdentity(id) else classifyByWords()
        markSuspiciousAndOrigin()
    }

    /** Kimlikle eşleştirme: aynı paket kodu = eşleşme; aynı model başka paket = ayrı başlık */
    private fun classifyByIdentity(id: Identity) {
        // Olası eşleşme için aile adının tamamı aranır ("philips" tek başına yetmez)
        val nameWords = requiredWords(id.family.ifEmpty { id.searchQuery }).filter { it != id.base }
            .takeIf { it.size >= 2 } ?: emptyList()
        for (o in offers) {
            val t = o.title.lowercase(Locale("tr", "TR"))
            val nt = norm(o.title)
            val codes = codesIn(o.title)
            val sameBase = codes.filter { it.second == id.base }
            o.kind = when {
                isAccessory(t) -> Kind.ACCESSORY
                codes.any { it.first == id.code } -> { o.variant = id.label; Kind.MATCH }
                sameBase.any { it.first != it.second } -> {
                    o.otherModel = sameBase.first { it.first != it.second }.first.uppercase()
                    Kind.OTHER_PACKAGE
                }
                // Model yazıyor ama paket kodu yazmıyor: muhtemelen aynı, kontrol edilmeli
                sameBase.isNotEmpty() -> Kind.POSSIBLE
                codes.isNotEmpty() -> { o.otherModel = codes.first().first.uppercase(); Kind.DIFFERENT_MODEL }
                nameWords.isNotEmpty() && nameWords.all { nt.contains(it) } -> Kind.POSSIBLE
                else -> Kind.OTHER
            }
        }
    }

    private fun classifyByWords() {
        val tokens = query.lowercase(Locale("tr", "TR")).split(Regex("\\s+")).filter { it.isNotBlank() }
        // Model kodu: hem harf hem rakam içeren kelime (ör. qp2834)
        val modelTokens = tokens.filter { t -> t.any { it.isDigit() } && t.any { it.isLetter() } }.map { norm(it) }
        val required = modelTokens.ifEmpty { requiredWords(query) }

        val modelCode = Regex("\\b[a-z]{1,4}[0-9]{3,5}\\b")
        // Model kodu dışındaki kelimeler (ör. philips, oneblade): kodu yazmayan ilanları yakalamak için
        val otherWords = tokens.filter { t -> !(t.any { it.isDigit() } && t.any { it.isLetter() }) && t.length >= 3 }.map { norm(it) }
        // Muadil: marka (ilk kelime) farklı olabilir; ölçü şartları ve ürün türü kelimelerinden en az biri tutmalı
        val (specWords, specHard) = querySpec(query)
        val typeWords = specWords.drop(1)
        for (o in offers) {
            val t = o.title.lowercase(Locale("tr", "TR"))
            val nt = norm(o.title)
            val titleModels = modelCode.findAll(t).map { it.value }.toSet()
            o.kind = when {
                isAccessory(t) -> Kind.ACCESSORY
                required.isNotEmpty() && required.all { nt.contains(it) } -> Kind.MATCH
                modelTokens.isEmpty() && typeWords.isNotEmpty() && specHard.all { nt.contains(it) } &&
                    typeWords.any { nt.contains(it) } -> Kind.MUADIL
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
            if (o.kind == Kind.MATCH && modelTokens.isEmpty()) {
                codesIn(o.title).firstOrNull()?.let { o.variant = it.first.uppercase() }
            }
            if (o.kind == Kind.MATCH) {
                for (m in modelTokens) {
                    Regex("$m\\s*/\\s*([0-9]{2,3})").find(t)?.let { o.variant = "${m.uppercase()}/${it.groupValues[1]}" }
                }
            }
        }
    }

    private fun markSuspiciousAndOrigin() {
        // Referans fiyat: en çok değerlendirilmiş eşleşme (çok satan gerçek ürün). Yoksa medyan.
        val matched = offers.filter { it.kind == Kind.MATCH }
        val ref = matched.filter { countNum(it.ratingCount) >= 30 }.maxByOrNull { countNum(it.ratingCount) }?.price
            ?: matched.map { it.price }.sorted().let { if (it.size >= 3) it[it.size / 2] else null }
        if (ref != null) {
            // Gerçek ürünün %40'ından ucuz "eşleşme": kılıf, taklit ya da yanlış ilan olabilir
            matched.filter { it.price < ref * 0.4 }.forEach { it.kind = Kind.SUSPICIOUS }
        }

        // Apple gibi markaların parça numarasındaki bölge kodu: TU/A = Türkiye, diğerleri yurtdışı cihazı
        val partNo = Regex("\\b([A-Z0-9]{4,5})([A-Z]{1,2})/A\\b", RegexOption.IGNORE_CASE)
        for (o in offers) {
            val m = partNo.find(o.title) ?: continue
            val region = m.groupValues[2].uppercase()
            o.origin = if (region == "TU") "Türkiye cihazı (TU/A)" else "Yurtdışı cihazı ($region/A) — garanti farklı olabilir"
            if (o.kind == Kind.MATCH && o.variant.isEmpty()) o.variant = m.groupValues[1].uppercase() + region + "/A"
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

    private fun countNum(s: String): Double {
        if (s.isBlank()) return 0.0
        Regex("([0-9]+(?:[.,][0-9]+)?)\\s?B").find(s)?.let { return (it.groupValues[1].replace(",", ".").toDoubleOrNull() ?: 0.0) * 1000 }
        return s.replace(".", "").replace(",", "").toDoubleOrNull() ?: 0.0
    }

    /** Paket çarpanı: "x 6", "6 kutu", "6'lı paket" */
    private fun packMultiplier(t: String): Double {
        val m = Regex("x\\s*([0-9]+)|([0-9]+)\\s*(?:kutu|paket)|([0-9]+)\\s*'?\\s*l[ıiuü]\\s*paket").find(t) ?: return 1.0
        return (m.groupValues[1].ifEmpty { m.groupValues[2] }.ifEmpty { m.groupValues[3] }).toDoubleOrNull() ?: 1.0
    }

    /** Birim fiyat: kg/litre/adet başına. Başlıkta miktar yoksa null */
    private fun unitPrice(o: Offer): Pair<Double, String>? {
        val t = o.title.lowercase(Locale("tr", "TR"))
        Regex("([0-9]+(?:[.,][0-9]+)?)\\s*(kg|gr|g|ml|lt|l|litre)\\b").find(t)?.let { m ->
            val v = m.groupValues[1].replace(",", ".").toDoubleOrNull() ?: 0.0
            val (amount, unit) = when (m.groupValues[2]) {
                "kg" -> v to "kg"
                "gr", "g" -> v / 1000 to "kg"
                "ml" -> v / 1000 to "L"
                else -> v to "L"
            }
            if (amount > 0) return o.price / (amount * packMultiplier(t)) to unit
        }
        Regex("([0-9]+)\\s*(?:adet|'?\\s?l[iıuü]\\b)").find(t)?.let { m ->
            val n = m.groupValues[1].toDoubleOrNull() ?: 0.0
            if (n > 1) return o.price / (n * packMultiplier(t)) to "adet"
        }
        return null
    }

    private fun unitText(o: Offer): String = unitPrice(o)?.let { (v, u) ->
        "≈ ${if (v < 10) tl.format(v) else tlWhole.format(v)} TL/$u"
    } ?: ""

    private fun kindLabel(o: Offer) = when (o.kind) {
        Kind.ACCESSORY -> "aksesuar"
        Kind.DIFFERENT_MODEL -> "farklı model (${o.otherModel})"
        Kind.OTHER -> "farklı ürün"
        Kind.POSSIBLE -> "olası eşleşme (paket/model kodu yazmıyor)"
        Kind.OTHER_PACKAGE -> "aynı modelin başka paketi (${o.otherModel})"
        Kind.SUSPICIOUS -> "çok ucuz — kılıf, taklit ya da yanlış ilan olabilir"
        Kind.MATCH -> "eşleşme"
        Kind.MUADIL -> "muadil (aynı tür ve ölçü)"
    }

    // ---------- Ürün sayfaları: garanti ve satıcı ----------

    private fun startDetails() {
        classify()
        detailQueue.clear()
        // En ucuz 6 eşleşmenin sayfasını aç (en çok bunlar önemli)
        detailQueue.addAll(offers.filter { it.kind == Kind.MATCH }.sortedBy { it.price }.take(6))
        detailIndex = 0
        detailPhase = true
        nextDetail()
    }

    private fun nextDetail() {
        if (detailIndex >= detailQueue.size) {
            detailPhase = false
            running = false
            web.stopLoading()
            showFinal()
            return
        }
        val o = detailQueue[detailIndex]
        token++
        val my = token
        extractScheduled = false
        detailAttempt = 0
        status.text = "Garanti ve satıcı kontrolü ${detailIndex + 1}/${detailQueue.size}: ${o.site}…"
        web.loadUrl(o.link)
        handler.postDelayed({ if (my == token) detailExtract() }, 20000)
    }

    private fun detailExtract() {
        val o = detailQueue[detailIndex]
        val my = ++token
        web.evaluateJavascript(detailJs) { raw ->
            if (my != token) return@evaluateJavascript
            try {
                val d = JSONObject(JSONTokener(raw).nextValue() as String)
                val broken = d.optString("page").contains("chrome-error") || d.optInt("len") < 1000
                if (broken && detailAttempt < 2) {
                    detailAttempt++
                    status.text = "${o.site}: ürün sayfası açılamadı, yeniden deneniyor (${detailAttempt}. tekrar)…"
                    extractScheduled = false
                    web.loadUrl(o.link)
                    handler.postDelayed({ if (my == token) detailExtract() }, 20000)
                    return@evaluateJavascript
                }
                if (d.optInt("len") < 3000 && detailAttempt < 2) {
                    detailAttempt++
                    status.text = "${o.site}: ürün sayfası yükleniyor (${detailAttempt}. tekrar)…"
                    web.evaluateJavascript("window.scrollTo(0, ${2000 + detailAttempt * 2000});", null)
                    handler.postDelayed({ if (my == token) detailExtract() }, 3500)
                    return@evaluateJavascript
                }
                o.warranty = d.optString("w")
                o.warrantyLine = d.optString("wl")
                o.seller = d.optString("seller").trim()
                o.sellerLine = d.optString("sl")
                o.warrantyType = d.optString("wt")
                o.warrantyDuration = d.optString("wd")
                o.sellerScore = d.optString("score")
                o.detailPage = d.optString("page")
            } catch (e: Exception) {
                o.detailPage = "okunamadı: " + raw.take(80)
            }
            o.detailChecked = true
            detailIndex++
            handler.postDelayed({ if (my == token) nextDetail() }, 500)
        }
    }

    /** Garanti yazısını renkli bir etikete çevirir */
    private fun warrantyBadge(o: Offer): Pair<String, Int>? {
        if (!o.detailChecked) return null
        val tr = Locale("tr", "TR")
        val w = (o.warranty + " " + o.warrantyType).lowercase(tr)
        val d = o.warrantyDuration.lowercase(tr)
        // Türkiye'de yeni ürünlerde yasal asgari garanti 2 yıl; 1 yıl yazması ithal ürün işareti olabilir
        val shortWarranty = Regex("^\\s*(1\\s*yıl|12\\s*ay|6\\s*ay)").containsMatchIn(d)
        return when {
            w.contains("ithalatçı") -> "İthalatçı garantili" to red
            w.contains("uluslararası") -> "Uluslararası garanti — Türkiye'de geçmeyebilir" to red
            w.isNotBlank() -> "Garanti: ${o.warranty.ifEmpty { o.warrantyType }}" +
                (if (d.isNotEmpty()) " · ${o.warrantyDuration}" else "") to green
            shortWarranty -> "Garanti süresi ${o.warrantyDuration} — yasal asgari 2 yıl, ithal ürün olabilir" to orange
            d.isNotEmpty() -> "Garanti süresi: ${o.warrantyDuration} (tipi yazmıyor)" to muted
            else -> "Garanti tipi sayfada bulunamadı" to muted
        }
    }

    private fun showFinal() {
        results.removeAllViews()
        val id = identity
        results.addView(label(
            if (id != null) "Ürün: ${id.label} · aranan: ${id.searchQuery}\nYanlış ürünse değiştirmek için dokun"
            else "Ürün kimliği yok, yazdığın kelimelerle arandı. Tekrar sormak için dokun",
            13f, ink, true
        ).apply {
            setBackgroundColor(Color.parseColor("#FFF6DD"))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setOnClickListener { forgetIdentity() }
        })

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
        val muadils = others.filter { it.kind == Kind.MUADIL }
            .sortedBy { unitPrice(it)?.first ?: Double.MAX_VALUE }
        if (muadils.isNotEmpty()) {
            sectionTitle("Muadiller — aynı tür ve ölçü, birim fiyata göre", green)
            muadils.forEach { offerRow(it, false) }
        }
        val suspicious = others.filter { it.kind == Kind.SUSPICIOUS }
        if (suspicious.isNotEmpty()) {
            sectionTitle("Şüpheli ucuz ilanlar — gerçek ürünün yarısından ucuz", red)
            suspicious.forEach { offerRow(it, false) }
        }
        val possibles = others.filter { it.kind == Kind.POSSIBLE }
        if (possibles.isNotEmpty()) {
            sectionTitle("Olası eşleşmeler — model kodu yazmıyor, kontrol et", orange)
            possibles.forEach { offerRow(it, false) }
        }
        val otherPackages = others.filter { it.kind == Kind.OTHER_PACKAGE }
        if (otherPackages.isNotEmpty()) {
            sectionTitle("Aynı modelin diğer paketleri — içerik/garanti farklı", orange)
            otherPackages.forEach { offerRow(it, false) }
        }
        val diffModels = others.filter { it.kind == Kind.DIFFERENT_MODEL }
        val rest = others.filter {
            it.kind != Kind.DIFFERENT_MODEL && it.kind != Kind.POSSIBLE && it.kind != Kind.SUSPICIOUS &&
                it.kind != Kind.OTHER_PACKAGE && it.kind != Kind.MUADIL
        }
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
        if (o.origin.isNotEmpty()) card.addView(label(o.origin, 12f, if (o.origin.startsWith("Türkiye")) green else red, true))
        warrantyBadge(o)?.let { (txt, col) -> card.addView(label(txt, 12f, col, true)) }
        if (o.seller.isNotEmpty()) card.addView(label(
            "Satıcı: ${o.seller}" + if (o.sellerScore.isNotEmpty()) " (puan ${o.sellerScore})" else "", 12f, ink))
        if (o.listPrice > o.price) {
            card.addView(label("Liste fiyatı ${money(o.listPrice)}", 12f, muted))
        }
        unitText(o).takeIf { it.isNotEmpty() }?.let { card.addView(label(it, 12f, ink, true)) }
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
        report.append("FİYAT AVCISI · DENEME 14\nGiriş: ").append(userInput)
            .append("\nÜrün: ").append(identity?.label ?: "(kimlik yok)")
            .append("\nSitelere sorulan: ").append(query).append("\n\nKİMLİK ADIMI\n").append(resolveLog).append("\n")
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
            if (o.origin.isNotEmpty()) report.append(" | ").append(o.origin)
            if (o.detailChecked) report.append(" | garanti: ").append(o.warranty.ifEmpty { o.warrantyType.ifEmpty { "?" } })
                .append(" / süre: ").append(o.warrantyDuration.ifEmpty { "?" })
                .append(" | satıcı: ").append(o.seller.ifEmpty { "?" })
                .append(if (o.sellerScore.isNotEmpty()) " (${o.sellerScore})" else "")
            if (o.rating.isNotEmpty()) report.append(" | puan ").append(o.rating).append(" (").append(countText(o.ratingCount)).append(")")
            report.append("\n  ").append(o.title).append("\n")
        }

        report.append("\nÜRÜN SAYFASI HAM SATIRLAR (garanti / satıcı)\n")
        detailQueue.forEach { o ->
            report.append("[").append(o.site).append(" ").append(money(o.price)).append("]\n")
                .append("  garanti satırı: ").append(o.warrantyLine.ifEmpty { "-" }).append("\n")
                .append("  satıcı satırı: ").append(o.sellerLine.ifEmpty { "-" }).append("\n")
                .append("  sayfa: ").append(o.detailPage.ifEmpty { "-" }).append("\n")
        }

        report.append("\nMUADİLLER (birim fiyata göre)\n")
        val mu = others.filter { it.kind == Kind.MUADIL }.sortedBy { unitPrice(it)?.first ?: Double.MAX_VALUE }
        if (mu.isEmpty()) report.append("(yok)\n")
        mu.forEach { o ->
            report.append("- ").append(money(o.price)).append(" ").append(unitText(o)).append(" | ").append(o.site)
                .append("\n  ").append(o.title).append("\n")
        }

        report.append("\nŞÜPHELİ UCUZ İLANLAR\n")
        val susp = others.filter { it.kind == Kind.SUSPICIOUS }
        if (susp.isEmpty()) report.append("(yok)\n")
        susp.forEach { o -> report.append("- ").append(money(o.price)).append(" | ").append(o.site).append("\n  ").append(o.title).append("\n") }

        report.append("\nOLASI EŞLEŞMELER (model kodu yazmıyor)\n")
        val possibles = others.filter { it.kind == Kind.POSSIBLE }
        if (possibles.isEmpty()) report.append("(yok)\n")
        possibles.forEach { o ->
            report.append("- ").append(money(o.price)).append(" | ").append(o.site).append("\n  ").append(o.title).append("\n")
        }

        report.append("\nAKSESUAR / FARKLI\n")
        others.filter { it.kind != Kind.POSSIBLE && it.kind != Kind.SUSPICIOUS && it.kind != Kind.MUADIL }.take(20).forEach { o ->
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
