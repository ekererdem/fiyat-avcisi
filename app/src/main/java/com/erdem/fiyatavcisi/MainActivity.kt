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
        var badge: String = "",          // "Resmi Satıcı", "Yetkili Satıcı"
        var exact: Boolean = false,      // barkodla sitede birebir bulundu
        var dupCount: Int = 0,           // aynı ilanı satan başka satıcı sayısı
        var note: String = "",           // "★ birim fiyatı en uygun", "⚠ birim fiyatı 5 kat"
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
    private var identity: Identity? = null
    private var userInput = ""
    private var resolveType = ""     // barkod | kod | ad
    private var resolveQuery = ""    // kimlik aranırken kullanılan yazı (barkoddan bulunan ad olabilir)
    private var googleTried = false
    private val barcodeHits = mutableListOf<Offer>()   // barkodla sitelerde birebir bulunan ilanlar
    private var bookMode = false      // ISBN (978/979) = kitap: muadil yok, garanti yok
    private val resolveLog = StringBuilder()
    private val prefs by lazy { getSharedPreferences("kimlik", Context.MODE_PRIVATE) }
    private val detailQueue = mutableListOf<Offer>()

    /** Ürün sayfası okuyucu: garanti tipi ve satıcı. Siteye özel değil, sayfadaki yazılara bakar. */
    private val detailJs = """
        (function(){
          var t = document.body ? document.body.innerText : '';
          var fullLen = t.length;
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
          var badge = '';
          var bm = t.match(/resmi satıcı|yetkili satıcı|resmi mağaza|marka mağazası/i);
          if (bm) badge = bm[0];
          for (var i = 0; i < lines.length && !seller; i++) {
            var L = lines[i];
            if ((/^ödeme fırsatları$/i.test(L) || (/^teslimat seçenekleri$/i.test(L) && location.host.indexOf('hepsiburada') >= 0)) && i > 0) {
              for (var hb = i - 1; hb >= Math.max(0, i - 4); hb--) {
                var cand = lines[hb];
                if (/^[\d.,\s]+$/.test(cand) || /resmi satıcı|yetkili satıcı/i.test(cand) || junk.test(cand) || notName.test(cand)) continue;
                if (cand.length >= 2 && cand.length <= 40) { seller = cand; break; }
              }
              sline = ctx(i, 4, 0);
              if (seller) break;
            }
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
          // Satıcı bulunamadıysa teşhis için fiyat satırının çevresini ve "satıcı/mağaza/garanti" geçen satırları raporla
          if (!seller && !sline) {
            var parts = [];
            for (var pi = 0; pi < lines.length; pi++) { if (/[0-9][0-9.,]*\s?TL/.test(lines[pi])) { parts.push(ctx(pi, 4, 8)); break; } }
            for (var qi = 0; qi < lines.length && parts.length < 4; qi++) {
              if (/satıcı|mağaza|garanti/i.test(lines[qi])) parts.push(lines[qi].slice(0, 80));
            }
            sline = 'TEŞHİS: ' + parts.join(' || ');
          }
          var sc = (sline.match(/(?:^|[\s|])((?:10|[0-9])[.,]\d)(?=[\s|]|$)/) || [])[1] || '';
          var dbg = [];
          for (var j = 0; j < lines.length && dbg.length < 3; j++) {
            if (/mağazaya git|satıcı puanı|mağaza puanı|satıcıya sor|gönderici/i.test(lines[j])) dbg.push(ctx(j, 3, 1));
          }
          return JSON.stringify({ w: wm ? wm[1] : '', wt: wt ? wt[1].trim() : '', wd: wd ? wd[1].trim() : '',
            seller: seller.trim(), score: sc, badge: badge, wl: wl, sl: (sline || dbg.join(' || ')).slice(0, 700),
            len: fullLen,
            page: document.title.slice(0, 80) + ' | metin ' + fullLen + ' karakter | ' + location.href.slice(0, 90) });
        })()
    """.trimIndent()

    private lateinit var input: EditText
    private lateinit var resultsScroll: ScrollView
    private lateinit var results: LinearLayout
    private lateinit var status: TextView
    private lateinit var browserBtn: Button

    private val handler = Handler(Looper.getMainLooper())
    private val report = StringBuilder()
    private val offers = mutableListOf<Offer>()
    private val siteSummary = mutableListOf<String>()

    private var running = false
    private var query = ""

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
              // "(510,89 TL/100 ml)", "1,89 TL/adet" gibi birim fiyat yazıları ürün fiyatı değildir
              .replace(/\([^)]*\/[^)]*\)/g, ' ')
              .replace(/(?:₺\s?)?[\d.,]+\s*(?:TL|₺)\s*\/\s*[0-9]*\s*[a-zçğıöşü]+/gi, ' ')
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
          // (Sepet/favori kutusundaki "ürün bulunamadı" yazıları sayılmaz)
          var noresRe = /sonuç bulunamadı|ürün bulunamadı|bulamadık|sonuç bulunmamaktadır|ürün bulunmamaktadır|sonuç bulunmadı|eşleşen (bir )?(ürün|sonuç) (bulunamadı|yok)|için sonuç yok|(^|[^0-9])0 (ürün|sonuç)/g;
          var nores = false, nm;
          while ((nm = noresRe.exec(head))) {
            if (!/sepet|favori|liste/.test(head.slice(Math.max(0, nm.index - 40), nm.index))) { nores = true; break; }
          }
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
              var pt = p.innerText || '';
              if (n > 1 || pt.length > 1200) break;
              // Tek sonuçlu sayfada kart sınırı bulunamaz: sayfa başlığına/filtrelere ulaşınca dur
              if (n === 1 && /(^|\n)\s*(sırala|filtrele)\s*(\n|$)|ile ilgili\s*[0-9]+\s*ürün|sponsorlu ürünler|teslimat konumu|giriş yap|hesabım|sepetim|kategoriler/i.test(pt)) break;
              el = p;
            }
            var text = el.innerText || '';
            var ps = (clean(text).match(priceRe) || []).map(num).filter(function(x){ return x > 0; });
            if (!ps.length) continue;
            seen[key] = 1;
            var max = Math.max.apply(null, ps);
            var ok = ps.filter(function(x){ return x >= max * 0.4; });
            var price = Math.min.apply(null, ok);

            var notTitle = /teslimat|kargo|sepete|stokta|kupon|favoriledi|değerlendirilen|ziyaret edilen|en çok|satıldı|ürün puanı|son [0-9]+ gün|kişi ekledi|yıldız üzerinden|satın alma seçenekleri|ürün sayfalarını|^yaş:|ile ilgili|ürün bulduk|sonuç bulduk|sponsorlu|reklam|uygulamada aç|kapılarını|daha iyi bir deneyim|amazon.un seçimi|çok satan|en beğenilen|öne çıkan|fırsat ürünü/i;
            function okTitle(c){ c = (c || '').replace(/\s+/g, ' ').trim(); return (c.length >= 10 && c.length <= 250 && !notTitle.test(c) && !priceOne.test(c)) ? c : ''; }
            // Ürün adresindeki ad: /Tekerlekli-Kitap-Minik-Itfaiye/dp/... ya da /marka/urun-adi-p-123
            var slug = '';
            try {
              var segs = a.pathname.split('/').filter(function(x){ return x; });
              var di = segs.indexOf('dp');
              var sg = decodeURIComponent(di > 0 ? segs[di - 1] : (segs.pop() || ''));
              sg = sg.replace(/\.html?/i, '').replace(/^_+/, '').replace(/-p(m)?-[A-Za-z0-9]+.*$/, '').replace(/-[0-9]{5,}$/, '').replace(/[-_]+/g, ' ').trim();
              if (sg.length >= 10 && /[a-zçğıöşü]/i.test(sg)) slug = sg;
            } catch (e) {}
            var h = el.querySelector('h2,h3,h4');
            var title = okTitle(a.getAttribute('title')) || okTitle(a.innerText) || okTitle(a.getAttribute('aria-label')) || (h ? okTitle(h.innerText) : '');
            if (!title) text.split('\n').forEach(function(s){ var c = okTitle(s); if (c.length > title.length) title = c; });
            // Seçilen başlık adresteki adla hiç örtüşmüyorsa (rozet, slogan vb.) adresteki adı tercih et
            if (slug && title) {
              var sw = slug.toLowerCase().split(' ').filter(function(w){ return w.length >= 4; });
              var hitw = sw.filter(function(w){ return title.toLowerCase().indexOf(w) >= 0; }).length;
              if (sw.length >= 2 && hitw === 0) title = slug;
            }
            if (!title) title = slug;
            if (!title) { var img = el.querySelector('img[alt]'); if (img) title = (img.getAttribute('alt') || '').trim(); }

            var rm = text.match(/(?:^|\s)([1-4][.,]\d|5[.,]0)(?=\s|\()/);
            var cm = text.match(/\((\d{1,3}(?:[.,]\d{1,3})?\s?B|\d{1,3}(?:\.\d{3})+|\d{1,6})(?:\s*[Dd]eğerlendirme)?\)/);
            cards.push({ t: title, p: price, lp: max, sepet: /sepette/i.test(text),
              r: rm ? rm[1] : '', rc: cm ? cm[1] : '', link: a.href,
              badge: ((/yetkili satıcı|resmi satıcı|resmi mağaza|marka mağazası/i.exec(text) || [''])[0]),
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

        root.addView(label("Fiyat Avcısı · Deneme 25", 18f, gold, true).apply {
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

        status = label("Ürünü yaz ya da barkod numarasını gir, Ara'ya bas. 6 site aynı anda taranır.", 14f, ink).apply {
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        root.addView(status)

        // Tarayıcı arkada tam boyutta durur, sonuç listesi üstünü kapatır
        val stack = FrameLayout(this)
        repeat(poolSize) {
            val v = createWeb()
            pool.add(v)
            stack.addView(v, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ))
        }
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

    // ---------- Tarayıcı havuzu (her site için ayrı, aynı anda çalışır) ----------

    /** Bir tarayıcının o anki iş numarası ve ana sayfa hataları */
    private class Slot {
        var gen = 0
        var httpStatus = 0
        var loadError = ""
    }

    private val pool = mutableListOf<WebView>()
    private val slots = HashMap<WebView, Slot>()
    private var phaseGen = 0
    private var startedAt = 0L
    private var resolvedAt = 0L
    private var finished = false
    private var savedExact: Set<String> = emptySet()
    private val poolSize = 6

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWeb(): WebView {
        val v = WebView(this)
        v.settings.apply {
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
        CookieManager.getInstance().setAcceptThirdPartyCookies(v, true)
        val slot = Slot()
        slots[v] = slot
        v.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme ?: ""
                return scheme != "http" && scheme != "https"
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame) slot.httpStatus = response.statusCode
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) slot.loadError = error.description?.toString() ?: "hata"
            }
        }
        return v
    }

    private fun parseJson(raw: String?): JSONObject? = try {
        JSONObject(JSONTokener(raw).nextValue() as String)
    } catch (e: Exception) {
        null
    }

    private fun elapsedText(ms: Long) = String.format(Locale("tr", "TR"), "%.1f sn", ms / 1000.0)

    /**
     * Sabit bekleme yerine: sayfayı açar, [js]'i kısa aralıklarla çalıştırır;
     * [ready] "tamam" deyince ya da [maxMs] dolunca [done] çağrılır.
     */
    private fun loadAndPoll(
        v: WebView, url: String, js: String, maxMs: Long, scroll: Boolean,
        ready: (JSONObject?, Int) -> Boolean, done: (JSONObject?) -> Unit,
    ) {
        val slot = slots.getValue(v)
        val my = ++slot.gen
        slot.httpStatus = 0
        slot.loadError = ""
        v.loadUrl(url)
        val t0 = android.os.SystemClock.elapsedRealtime()
        var n = 0
        lateinit var tick: () -> Unit
        tick = {
            if (slot.gen == my) {
                n++
                if (scroll) v.evaluateJavascript("window.scrollBy(0, 1600);", null)
                v.evaluateJavascript(js) { raw ->
                    if (slot.gen == my) {
                        val d = parseJson(raw)
                        val elapsed = android.os.SystemClock.elapsedRealtime() - t0
                        if (ready(d, n) || elapsed >= maxMs) {
                            slot.gen++
                            done(d)
                        } else {
                            handler.postDelayed({ tick() }, 700)
                        }
                    }
                }
            }
        }
        handler.postDelayed({ tick() }, 1000)
    }

    /** Bir sitede arama: ürünler yüklenip sayı sabitlenince okur; boş sayfada yeniden yükler, sonuç yoksa sade arama dener */
    private fun runSite(
        v: WebView, site: Site, q: String, simpleUsed: Boolean, reloaded: Boolean,
        onDone: (JSONObject?, Boolean) -> Unit,
    ) {
        var last = -1
        var stable = 0
        val url = site.url(URLEncoder.encode(q, "UTF-8").replace("+", "%20"))
        loadAndPoll(v, url, extractJs(site.linkPattern), maxMs = 13000, scroll = true, ready = { d, n ->
            val c = d?.optJSONArray("cards")?.length() ?: 0
            if (c > 0 && c == last) stable++ else stable = 0
            last = c
            d?.optBoolean("noResults") == true || (c > 0 && stable >= 2 && n >= 3)
        }) { d ->
            val cards = d?.optJSONArray("cards")?.length() ?: 0
            val isBarcodeQ = q.replace(" ", "").all { it.isDigit() }
            when {
                // Sayfa hiç gelmediyse bir kez yeniden yükle
                d != null && cards == 0 && d.optInt("links") == 0 && !reloaded ->
                    runSite(v, site, q, simpleUsed, true, onDone)
                // Sonuç yok ya da öneri listesi: bir kez sade aramayla (tür + ölçü) sor
                !simpleUsed && !isBarcodeQ && (d?.optBoolean("noResults") == true || isIrrelevant(d, q)) &&
                    simplifiedQuery(q).let { it.isNotEmpty() && norm(it) != norm(q) } ->
                    runSite(v, site, simplifiedQuery(q), true, reloaded, onDone)
                else -> onDone(d, simpleUsed)
            }
        }
    }

    /** Siteleri aynı anda tarar; her site bitince [onEach], hepsi bitince [onAll] */
    private fun runSites(list: List<Site>, q: String, label: String, onEach: () -> Unit, onAll: () -> Unit) {
        val gen = ++phaseGen
        var left = list.size
        status.text = "$label: ${list.size} site aynı anda taranıyor…"
        list.forEachIndexed { i, site ->
            val v = pool[i]
            runSite(v, site, q, simpleUsed = false, reloaded = false) { d, simpleUsed ->
                if (gen == phaseGen) {
                    collect(site.name, d, q, simpleUsed, slots.getValue(v))
                    left--
                    status.text = "$label: ${list.size - left}/${list.size} site tamam · " +
                        elapsedText(android.os.SystemClock.elapsedRealtime() - startedAt)
                    onEach()
                    if (left == 0) onAll()
                }
            }
        }
    }

    private fun stopAll() {
        phaseGen++
        pool.forEach { v ->
            slots.getValue(v).gen++
            v.stopLoading()
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
        stopAll()
        running = true
        finished = false
        startedAt = android.os.SystemClock.elapsedRealtime()
        resolvedAt = 0L
        results.removeAllViews()
        report.setLength(0)
        resolveLog.setLength(0)
        identity = null
        barcodeHits.clear()
        detailQueue.clear()
        savedExact = emptySet()
        offers.clear()
        siteSummary.clear()
        val digits = userInput.replace(" ", "")
        bookMode = digits.length == 13 && (digits.startsWith("978") || digits.startsWith("979"))
        resolveType = inputType(userInput)
        resolveQuery = userInput

        // Hafıza 1: bu giriş için daha önce bir paket seçildiyse sormadan onu kullan
        val saved = prefs.getString("q:" + norm(userInput), null)
        if (saved != null) {
            identity = identityFromJson(saved)
            resolveLog.append("Kimlik hafızadan alındı.\n")
            startSearch()
            return
        }
        // Hafıza 2: barkoddan/addan daha önce bulunan ürün adı
        prefs.getString("n:" + norm(userInput), null)?.let { s ->
            try {
                val j = JSONObject(s)
                resolveQuery = j.getString("name")
                bookMode = j.optBoolean("book", bookMode)
                val ex = j.optJSONArray("exact")
                savedExact = (0 until (ex?.length() ?: 0)).map { ex!!.getString(it) }.toSet()
                resolveLog.append("Ürün adı hafızadan alındı: ").append(resolveQuery).append("\n")
                startSearch()
                return
            } catch (e: Exception) {
            }
        }
        // 1. adım: kimlik bul (3 site aynı anda)
        googleTried = false
        query = userInput
        runSites(resolverSites, userInput, "Ürün tanınıyor", onEach = {}) { finishResolve() }
    }

    /** 2. adım: 6 siteyi aynı anda, kimliğin sabit aramasıyla tara; sonuçlar geldikçe ekrana basılır */
    private fun startSearch() {
        resolvedAt = android.os.SystemClock.elapsedRealtime()
        query = identity?.searchQuery ?: resolveQuery
        offers.clear()
        siteSummary.clear()
        runSites(sites, query, "Fiyatlar", onEach = { render(final = false) }) { startDetails() }
    }

    /** Aynı ilanın farklı linklerle (takip ekleri) iki kez sayılmaması için anahtar */
    private fun offerKey(o: Offer): String {
        Regex("/dp/([A-Z0-9]{10})").find(o.link)?.let { return "amazon:" + it.groupValues[1] }
        return o.link.substringBefore('#').substringBefore('?')
    }

    /** Sitenin kartlarının hiçbiri aranan kelimelerden en az ikisini içermiyorsa: öneri listesi, sonuç değil */
    private fun isIrrelevant(d: JSONObject?, q: String = query): Boolean {
        val cards = d?.optJSONArray("cards") ?: return false
        if (cards.length() == 0) return false
        val words = requiredWords(q) + codesIn(q).map { it.second }
        if (words.isEmpty()) return false
        return (0 until cards.length()).none { i ->
            val c = cards.getJSONObject(i)
            val nt = norm(c.optString("t") + " " + c.optString("raw"))
            words.count { nt.contains(it) } >= minOf(2, words.size)
        }
    }

    private fun collect(name: String, d: JSONObject?, q: String, simpleUsed: Boolean, slot: Slot) {
        val cards = d?.optJSONArray("cards")
        val count = cards?.length() ?: 0
        val isBarcode = q.replace(" ", "").let { s -> s.length in 8..14 && s.all { it.isDigit() } }
        if (count > 0 && !isBarcode && isIrrelevant(d, q)) {
            siteSummary.add("$name: sonuçlar aramayla ilgisiz (site muhtemelen öneri gösteriyor)")
            return
        }
        val seen = offers.map { offerKey(it) }.toHashSet()
        for (i in 0 until count) {
            val c = cards!!.getJSONObject(i)
            val o = Offer(
                site = name,
                title = c.optString("t"),
                price = c.optDouble("p"),
                listPrice = c.optDouble("lp"),
                basket = c.optBoolean("sepet"),
                rating = c.optString("r"),
                ratingCount = c.optString("rc"),
                link = c.optString("link"),
                raw = c.optString("raw"),
                badge = c.optString("badge"),
            )
            if (seen.add(offerKey(o))) offers.add(o)
        }
        val block = d?.optString("block").orEmpty()
        val verdict = when {
            d == null -> "AÇILMADI${if (slot.loadError.isNotEmpty()) " (${slot.loadError})" else ""}"
            d.optBoolean("noResults") -> "bu aramada sonuç yok (site \"bulunamadı\" dedi)"
            count == 0 && (block.isNotEmpty() || slot.httpStatus == 403 || slot.httpStatus == 429) ->
                "ENGEL ($block ${slot.httpStatus})".trim()
            count == 0 -> {
                val links = d.optJSONArray("sampleLinks")
                val sample = (0 until (links?.length() ?: 0)).joinToString("\n    ") { links!!.optString(it) }
                "ÜRÜN KARTI BULUNAMADI (ürün linki ${d.optInt("anchors")}, toplam link ${d.optInt("links")})" +
                    (if (sample.isNotEmpty()) "\n    Örnek linkler:\n    $sample" else "") +
                    "\n    Sayfa başlığı: ${d.optString("title")}" +
                    "\n    Sayfa metni: ${d.optString("pageSample").ifEmpty { "(boş)" }}"
            }
            else -> "$count ürün okundu" + (if (d.optBoolean("fallback")) " (yedek yöntemle)" else "") +
                (if (simpleUsed) " (sade aramayla: \"${simplifiedQuery(q)}\")" else "")
        }
        siteSummary.add("$name: $verdict")
    }

    // ---------- Ürün kimliği ----------

    private fun isAccessory(lowerTitle: String): Boolean {
        val t = lowerTitle
            .replace("kablosuz", " ")
            .replace(Regex("[0-9]+\\s*(adet\\s*)?yedek (bıçak|başlık)"), " ") // "1 Yedek Bıçak" = kutudan çıkan yedek
        // Başlığında 3+ farklı model sayan ilan ("QP2520/QP2530/QP2834 için") uyumlu aksesuardır
        if (codesIn(lowerTitle).map { it.second }.distinct().size >= 3) return true
        // Kısa kelimeler tam kelime olarak (Türkçe ekleriyle) aranır: "standı" evet, "baskılı" hayır
        val whole = mapOf("askı" to "askı(sı)?", "stand" to "stand(ı|lar|ları)?", "kapak" to "(kapak|kapağı)")
        if (whole.values.any { Regex("(?<![\\p{L}])$it(?![\\p{L}])").containsMatchIn(t) }) return true
        return accessoryWords.any { w -> w !in whole && t.contains(w) } ||
            listOf("tutucu", "şarj tabanı", "jilet").any { t.contains(it) }
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
                t.length >= 3 -> words.add(if (t.length > 5) t.removeSuffix("lar").removeSuffix("ler") else t)
            }
        }
        return words to hard
    }

    /** Sade arama: markasız, miktarsız; ürün türü + ölçü ("ataş no 6") */
    private fun simplifiedQuery(q: String): String {
        val (words, hard) = querySpec(q)
        val original = q.split(Regex("[\\s:]+")).filter { it.isNotBlank() }
        val pick = { ws: List<String> -> ws.mapNotNull { w -> original.firstOrNull { norm(it).startsWith(w) } ?: w } }
        val parts = when {
            hard.isNotEmpty() -> pick(listOfNotNull(words.drop(1).firstOrNull() ?: words.firstOrNull())) +
                hard.map { it.replace(Regex("([a-z]+)([0-9]+)"), "$1 $2") }
            bookMode -> pick(words.take(4))
            else -> pick(words.drop(1).take(3))
        }
        // Tek kelimelik sade arama ("Kitap") alakasız sonuç getirir
        return if (parts.size >= 2) parts.joinToString(" ") else ""
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
        val cands = buildCandidates()
        resolveLog.append("Tanıma: ").append(resolveQuery).append(" (").append(resolveType).append(")\n")
        siteSummary.forEach { resolveLog.append("  tanıma — ").append(it).append("\n") }
        cands.forEach {
            resolveLog.append("  aday: ").append(it.label).append(" · ").append(it.count).append(" ilan · ör: ")
                .append(it.sample.take(70)).append("\n")
        }
        // Barkodla aramada siteler ilan döndürdüyse (öneri değil), o ilanlar ürünün kendisidir
        val hits = if (resolveType == "barkod") offers.filter { !isAccessory(it.title.lowercase(Locale("tr", "TR"))) } else emptyList()
        // Ortak bir ad çıkmıyorsa ve çok sayıda ilan varsa bunlar muhtemelen öneri listesidir; Google'a sor
        val hitName = commonName(hits.map { it.title }).ifEmpty {
            if (hits.size in 1..2) hits.minByOrNull { it.title.length }!!.title.split(" ").take(6).joinToString(" ") else ""
        }
        if (cands.isEmpty() && hitName.isNotEmpty() && barcodeHits.isEmpty()) {
            hits.forEach { it.exact = true }
            barcodeHits.addAll(hits)
            val name = hitName
            resolveLog.append("Barkod ").append(hits.size).append(" ilanda birebir bulundu; ad: ").append(name).append("\n")
            resolveQuery = name
            startSearch()
            return
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
          function add(x){ x = (x || '').replace(/\s+/g, ' ').trim(); if (x.length > 8 && x.length < 200 && t.indexOf(x) < 0) t.push(x); }
          document.querySelectorAll('h3, h2, [role="heading"]').forEach(function(h){ add(h.innerText); });
          if (t.length < 2) {
            document.querySelectorAll('a[href]').forEach(function(a){
              var host = a.host || '';
              if (host && host.indexOf('google') < 0 && host.indexOf('bing') < 0 && host.indexOf('microsoft') < 0) add(a.innerText);
            });
          }
          var body = (document.body ? document.body.innerText : '').replace(/\s+/g, ' ').slice(0, 200);
          return JSON.stringify({ titles: t.slice(0, 10), page: document.title, body: body });
        })()
    """.trimIndent()

    private val engines = listOf(
        "Google" to "https://www.google.com/search?hl=tr&gl=tr&q=",
        "Bing" to "https://www.bing.com/search?setlang=tr&cc=TR&q=",
    )
    private var engineIndex = 0

    private fun googleLookup() {
        googleTried = true
        engineIndex = 0
        loadEngine()
    }

    private fun loadEngine() {
        val gen = ++phaseGen
        val (engine, url) = engines[engineIndex]
        status.text = "Barkod sitelerde yok, ürünün adı $engine'dan öğreniliyor…"
        loadAndPoll(pool[0], url + URLEncoder.encode(userInput.replace(" ", ""), "UTF-8"), googleJs,
            maxMs = 9000, scroll = false,
            ready = { d, _ -> (d?.optJSONArray("titles")?.length() ?: 0) >= 3 }) { d ->
            if (gen == phaseGen) googleDone(d)
        }
    }

    private fun googleDone(d: JSONObject?) {
        val pageInfo = (d?.optString("page") ?: "") + " | " + (d?.optString("body") ?: "")
        val a = d?.optJSONArray("titles")
        val titles = (0 until (a?.length() ?: 0)).map { a!!.optString(it) }
        val engine = engines[engineIndex].first
        resolveLog.append(engine).append(" sonuçları:\n")
        titles.take(6).forEach { resolveLog.append("  · ").append(it.take(90)).append("\n") }
        val name = commonName(titles).ifEmpty { barcodeName(titles) }
        if (name.isEmpty()) {
            resolveLog.append("  ").append(engine).append("'dan ad çıkarılamadı. Sayfa: ").append(pageInfo.take(160)).append("\n")
            if (engineIndex + 1 < engines.size) {
                engineIndex++
                loadEngine()
            } else {
                startSearch()
            }
            return
        }
        resolveLog.append("Barkoddan bulunan ad: ").append(name).append("\n")
        // Bulunan adla kimlik adımını tekrarla
        resolveType = "ad"
        resolveQuery = name
        query = name
        offers.clear()
        siteSummary.clear()
        runSites(resolverSites, name, "Ürün tanınıyor", onEach = {}) { finishResolve() }
    }

    /** Başlıkların en az yarısında geçen kelimeler, ilk başlıktaki sırasıyla: "Tekerlekli Kitaplar Minik İtfaiye" */
    private fun commonName(titles: List<String>): String {
        val skip = setOf("fiyatı", "fiyatları", "fiyat", "satın", "yorumları", "özellikleri", "web", "sonuçları", "için", "ile")
            .map { norm(it) }.toSet()
        val clean = titles.map { it.replace(Regex("[0-9]{8,14}"), " ") }.filter { it.any { c -> c.isLetter() } }
        if (clean.size < 2) return ""
        val stem = { w: String -> norm(w).let { if (it.length > 5) it.removeSuffix("lar").removeSuffix("ler") else it } }
        val sets = clean.map { t -> t.split(Regex("[^\\p{L}\\p{N}]+")).map(stem).filter { it.length >= 3 && it !in skip }.toSet() }
        val counts = sets.flatten().groupingBy { it }.eachCount()
        val common = counts.filter { it.value * 2 >= clean.size }.keys
        val base = clean.maxByOrNull { t -> t.split(" ").count { stem(it) in common } } ?: return ""
        val out = mutableListOf<String>()
        for (w in base.split(Regex("[^\\p{L}\\p{N}'’]+"))) {
            if (stem(w) in common && out.none { stem(it) == stem(w) }) out.add(w)
        }
        return if (out.size >= 2) out.take(8).joinToString(" ") else ""
    }

    /** Google başlıklarından ürün adı: site adı ve "fiyatı" gibi ekleri at, en sık geçen başlığı al */
    private fun barcodeName(titles: List<String>): String {
        val cleaned = titles.map { t ->
            t.split(Regex("\\s[-|–—:]\\s")).first()
                .replace(Regex("(?i)\\b(fiyatı|fiyatları|fiyat|en ucuz|satın al|barkod|ürün bilgisi)\\b"), " ")
                .replace(Regex("[0-9]{8,14}"), " ")
                .replace(Regex("\\s+"), " ").trim()
        }.filter { it.length >= 8 && it.any { c -> c.isLetter() } }
            .filterNot {
                Regex("(?i)^(görseller|videolar|diğer sorular|ilgili aramalar|haberler|alışveriş|harita|tümü|sonuçlar|" +
                    "web sonuçları|people also ask|images|videos|related searches|news|shopping)").containsMatchIn(it)
            }
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
        prefs.edit().remove("q:" + norm(userInput)).remove("n:" + norm(userInput)).apply()
        input.setText(userInput)
        startTest()
    }

    // ---------- Eşleştirme ----------

    // Aynı şeyin farklı yazımları tek biçime iner (ataç = ataş)
    // Türkçe harfler tek biçime iner (Itfaiye = İtfaiye, Clınıcal = Clinical); yazım eşleri birleşir (ataç = ataş)
    private fun norm(s: String) = s.lowercase(Locale("tr", "TR"))
        .replace('ı', 'i').replace('ş', 's').replace('ç', 'c').replace('ğ', 'g').replace('ö', 'o').replace('ü', 'u')
        .replace("atac", "atas").replace(Regex("[^a-z0-9]"), "")

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

    private fun genderOf(t: String) = when {
        Regex("(?<![\\p{L}])(kadın|women|woman|bayan)(?![\\p{L}])").containsMatchIn(t) -> "Kadın"
        Regex("(?<![\\p{L}])(erkek|men|man)(?![\\p{L}])").containsMatchIn(t) -> "Erkek"
        else -> ""
    }

    private fun classifyByWords() {
        val tokens = query.lowercase(Locale("tr", "TR")).split(Regex("\\s+")).filter { it.isNotBlank() }
        // Model kodu: hem harf hem rakam içeren kelime (ör. qp2834)
        val modelTokens = codesIn(query).map { it.second }.distinct()
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
                !bookMode && modelTokens.isEmpty() && typeWords.isNotEmpty() && specWords.isNotEmpty() &&
                    !nt.contains(specWords.first()) && specHard.all { nt.contains(it) } &&
                    typeWords.all { nt.contains(it) } -> Kind.MUADIL
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
                if (o.variant.isEmpty()) o.variant = genderOf(t)
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

    /** Paket çarpanı ("x 6", "6 kutu", "6'lı paket"); sadece miktardan SONRA yazılmışsa uygulanır.
     *  "10 Kutu 1.000 Adet" gibi toplam yazılan ilanlarda tekrar çarpılmaz. */
    private fun packMultiplier(t: String, after: Int): Double {
        val m = Regex("x\\s*([0-9]+)|([0-9]+)\\s*(?:kutu|paket)|([0-9]+)\\s*'?\\s*l[ıiuü]\\s*paket").findAll(t)
            .firstOrNull { it.range.first > after } ?: return 1.0
        return (m.groupValues[1].ifEmpty { m.groupValues[2] }.ifEmpty { m.groupValues[3] }).toDoubleOrNull() ?: 1.0
    }

    private fun numberOf(s: String) = s.replace(".", "").replace(",", ".").toDoubleOrNull() ?: 0.0

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
            if (amount > 0) return o.price / (amount * packMultiplier(t, m.range.last)) to unit
        }
        Regex("([0-9]{1,3}(?:\\.[0-9]{3})+|[0-9]+)\\s*(?:adet|'?\\s?l[iıuü]\\b)").find(t)?.let { m ->
            val n = numberOf(m.groupValues[1])
            if (n > 1) return o.price / (n * packMultiplier(t, m.range.last)) to "adet"
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

    /** Barkod ilanlarını sonuçlara katar, sınıflandırır, birebir eşleşmeleri işaretler (tekrar çağrılabilir) */
    private fun prepareResults() {
        val keys = offers.map { offerKey(it) }.toHashSet()
        barcodeHits.filter { keys.add(offerKey(it)) }.forEach { offers.add(it) }
        classify()
        val exactKeys = barcodeHits.map { offerKey(it) }.toSet() + savedExact
        offers.filter { offerKey(it) in exactKeys || it.exact }.forEach { it.kind = Kind.MATCH; it.exact = true }
    }

    private fun startDetails() {
        prepareResults()
        detailQueue.clear()
        // Ürün sayfası (satıcı + garanti) sadece model kodlu ya da 1.000 TL üstü eşleşmelerde, hepsi aynı anda açılır
        detailQueue.addAll(
            offers.filter { it.kind == Kind.MATCH && !bookMode && (codesIn(it.title).isNotEmpty() || it.price >= 1000) }
                .sortedBy { it.price }.take(5)
        )
        if (detailQueue.isEmpty()) {
            finishSearch()
            return
        }
        val gen = ++phaseGen
        var left = detailQueue.size
        status.text = "Satıcı ve garanti: ${detailQueue.size} ürün sayfası aynı anda açılıyor…"
        detailQueue.forEachIndexed { i, o ->
            openDetail(pool[i], o, retried = false) {
                if (gen == phaseGen) {
                    left--
                    render(final = false)
                    status.text = "Satıcı ve garanti: ${detailQueue.size - left}/${detailQueue.size} · " +
                        elapsedText(android.os.SystemClock.elapsedRealtime() - startedAt)
                    if (left == 0) finishSearch()
                }
            }
        }
    }

    private fun openDetail(v: WebView, o: Offer, retried: Boolean, done: () -> Unit) {
        var lastLen = -1
        var steady = 0
        loadAndPoll(v, o.link, detailJs, maxMs = 10000, scroll = true, ready = { d, n ->
            val len = d?.optInt("len") ?: 0
            if (len > 0 && Math.abs(len - lastLen) < 200) steady++ else steady = 0
            lastLen = len
            d != null && n >= 3 && steady >= 2 && (d.optString("seller").isNotEmpty() || len >= 6000)
        }) { d ->
            val broken = d == null || d.optString("page").contains("chrome-error") || d.optInt("len") < 1000
            if (broken && !retried) {
                openDetail(v, o, true, done)
                return@loadAndPoll
            }
            if (d != null) {
                o.warranty = d.optString("w")
                o.warrantyLine = d.optString("wl")
                o.seller = d.optString("seller").trim()
                o.sellerLine = d.optString("sl")
                o.warrantyType = d.optString("wt")
                o.warrantyDuration = d.optString("wd")
                o.sellerScore = d.optString("score")
                o.detailPage = d.optString("page")
                if (o.badge.isEmpty()) o.badge = d.optString("badge")
            } else {
                o.detailPage = "okunamadı"
            }
            o.detailChecked = true
            done()
        }
    }

    private fun finishSearch() {
        if (finished) return
        finished = true
        running = false
        stopAll()
        render(final = true)
        // Barkoddan bulunan adı hatırla: aynı barkod bir daha tanıma adımına girmez
        if (identity == null && norm(resolveQuery) != norm(userInput)) {
            val exact = offers.filter { it.exact }.map { offerKey(it) }
            prefs.edit().putString("n:" + norm(userInput), JSONObject().apply {
                put("name", resolveQuery)
                put("book", bookMode)
                put("exact", org.json.JSONArray(exact))
            }.toString()).apply()
        }
    }

    /** Garanti yazısını renkli bir etikete çevirir */
    private fun warrantyBadge(o: Offer): Pair<String, Int>? {
        if (!o.detailChecked) return null
        // Kitap, kozmetik, kırtasiye gibi garantisiz ürünlerde "garanti bulunamadı" yazmanın anlamı yok
        val hasInfo = o.warranty.isNotEmpty() || o.warrantyType.isNotEmpty() || o.warrantyDuration.isNotEmpty()
        if (!hasInfo && (bookMode || codesIn(o.title).isEmpty())) return null
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

    private fun render(final: Boolean) {
        prepareResults()
        val y = resultsScroll.scrollY
        results.removeAllViews()
        val id = identity
        results.addView(label(
            if (id != null) "Ürün: ${id.label} · aranan: ${id.searchQuery}\nYanlış ürünse değiştirmek için dokun"
            else "Aranan: $query\nYanlış ürünse yeniden tanımak için dokun",
            13f, ink, true
        ).apply {
            setBackgroundColor(Color.parseColor("#FFF6DD"))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setOnClickListener { forgetIdentity() }
        })

        val matches = offers.filter { it.kind == Kind.MATCH }.sortedBy { it.price }
        val others = offers.filter { it.kind != Kind.MATCH }.sortedBy { it.price }

        val took = elapsedText(android.os.SystemClock.elapsedRealtime() - startedAt)
        if (final) {
            status.text = (if (matches.isEmpty()) "Eşleşen ürün bulunamadı. Aşağıda benzer sonuçlar var."
            else "${matches.size} eşleşen teklif · en ucuz ${money(matches.first().price)} (${matches.first().site})") +
                " · $took"
        } else if (matches.isNotEmpty()) {
            status.text = "Taranıyor (${siteSummary.size}/${sites.size} site) · şimdilik en ucuz " +
                "${money(matches.first().price)} (${matches.first().site}) · $took"
        }

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
                    "Dikkat: farklı paket/versiyonlar var (${variants.joinToString(", ")}). Bunlar ayrı ürünler; " +
                        "içerik, garanti ya da hedef kitle farklı olabilir, karşılaştırırken etikete bak.",
                    13f, orange
                ).apply { setPadding(dp(4), dp(8), dp(4), 0) })
            }
            sectionTitle("Eşleşen ürünler", green)
            markUnitPrices(matches)
            matches.forEachIndexed { i, o -> offerRow(o, i == 0) }
        }
        val muadils = collapse(others.filter { it.kind == Kind.MUADIL })
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
            collapse(rest).sortedBy { it.price }.forEach { offerRow(it, false) }
        }
        sectionTitle("Site durumu", ink)
        siteSummary.forEach { results.addView(label("• $it", 13f, ink).apply { setPadding(dp(4), dp(2), dp(4), dp(2)) }) }

        buildReport(matches, others)
        resultsScroll.post { resultsScroll.scrollTo(0, y) }
    }

    /** Eşleşenlerde: birim fiyatı en uygun olanı ve medyanın 3 katından pahalı olanları işaretle */
    private fun markUnitPrices(matches: List<Offer>) {
        val withUnit = matches.mapNotNull { o -> unitPrice(o)?.let { o to it.first } }
        if (withUnit.size < 2) return
        val sorted = withUnit.map { it.second }.sorted()
        val median = sorted[sorted.size / 2]
        val firstUnit = unitPrice(matches.first())?.first ?: Double.MAX_VALUE
        val best = withUnit.filter { it.second < firstUnit * 0.99 }.minByOrNull { it.second }?.first
        withUnit.forEach { (o, u) ->
            o.note = when {
                o === best -> "★ birim fiyatı en uygun"
                u > median * 3 -> "⚠ birim fiyatı diğerlerinin ${(u / median).toInt()} katı"
                else -> ""
            }
        }
    }

    /** Aynı ilanı satan birçok satıcıyı tek satıra indirir: en ucuzu kalır, diğerleri sayılır */
    private fun collapse(list: List<Offer>): List<Offer> =
        list.groupBy { norm(it.title) }.values.map { g ->
            g.minByOrNull { it.price }!!.also { it.dupCount = g.size - 1 }
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
            if (o.dupCount > 0) append("  · +${o.dupCount} satıcı daha")
        }
        val tag = when {
            o.kind == Kind.MATCH && (o.variant == "Kadın" || o.variant == "Erkek") -> "Versiyon: ${o.variant}"
            o.kind == Kind.MATCH && o.variant.isNotEmpty() -> "Paket: ${o.variant}"
            o.kind != Kind.MATCH -> kindLabel(o)
            else -> ""
        }
        card.addView(label(head, 16f, ink, true))
        if (tag.isNotEmpty()) card.addView(label(tag, 12f, if (o.kind == Kind.MATCH) green else orange, true))
        if (o.origin.isNotEmpty()) card.addView(label(o.origin, 12f, if (o.origin.startsWith("Türkiye")) green else red, true))
        warrantyBadge(o)?.let { (txt, col) -> card.addView(label(txt, 12f, col, true)) }
        if (o.exact) card.addView(label("Barkodla birebir bulundu", 12f, green, true))
        if (o.note.isNotEmpty()) card.addView(label(o.note, 12f, if (o.note.startsWith("★")) green else red, true))
        if (o.badge.isNotEmpty()) card.addView(label("✓ ${o.badge}", 12f, green, true))
        if (o.seller.isNotEmpty()) card.addView(label(
            "Satıcı: ${o.seller}" + if (o.sellerScore.isNotEmpty()) " (puan ${o.sellerScore})" else "", 12f, ink))
        if (o.listPrice > o.price) {
            card.addView(label("Liste fiyatı ${money(o.listPrice)}", 12f, muted))
        }
        unitText(o).takeIf { it.isNotEmpty() }?.let { card.addView(label(it, 12f, ink, true)) }
            ?: run { if (o.kind == Kind.MUADIL) card.addView(label("Miktar yazmıyor, birim fiyat hesaplanamadı", 12f, muted)) }
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
        val total = android.os.SystemClock.elapsedRealtime() - startedAt
        report.append("FİYAT AVCISI · DENEME 25\nGiriş: ").append(userInput)
            .append("\nDurum: ").append(if (finished) "tamamlandı" else "HÂLÂ SÜRÜYORDU (rapor erken kopyalandı)")
            .append("\nSüre: ").append(elapsedText(total))
            .append(if (resolvedAt > 0) " (tanıma ${elapsedText(resolvedAt - startedAt)})" else "")
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
            if (o.variant.isNotEmpty()) report.append(if (o.variant == "Kadın" || o.variant == "Erkek") " | versiyon " else " | paket ").append(o.variant)
            if (o.note.isNotEmpty()) report.append(" | ").append(o.note)
            unitText(o).takeIf { it.isNotEmpty() }?.let { report.append(" | ").append(it) }
            if (o.exact) report.append(" | barkodla birebir")
            if (o.badge.isNotEmpty()) report.append(" | ").append(o.badge)
            if (o.origin.isNotEmpty()) report.append(" | ").append(o.origin)
            if (o.detailChecked && warrantyBadge(o) != null) report.append(" | garanti: ").append(o.warranty.ifEmpty { o.warrantyType.ifEmpty { "?" } })
                .append(" / süre: ").append(o.warrantyDuration.ifEmpty { "?" })
            if (o.detailChecked) report.append(" | satıcı: ").append(o.seller.ifEmpty { "?" })
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
        val mu = collapse(others.filter { it.kind == Kind.MUADIL }).sortedBy { unitPrice(it)?.first ?: Double.MAX_VALUE }
        if (mu.isEmpty()) report.append("(yok)\n")
        mu.forEach { o ->
            report.append("- ").append(money(o.price)).append(" ").append(unitText(o).ifEmpty { "(miktar yok)" }).append(" | ").append(o.site)
                .append(if (o.dupCount > 0) " (+${o.dupCount} satıcı)" else "")
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
        if (running) Toast.makeText(this, "Arama henüz bitmedi; rapor yarım olabilir", Toast.LENGTH_LONG).show()
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
        pool.forEach { it.destroy() }
        super.onDestroy()
    }
}
