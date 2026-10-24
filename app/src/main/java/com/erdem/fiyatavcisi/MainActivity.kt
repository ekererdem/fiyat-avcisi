package com.erdem.fiyatavcisi

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.LruCache
import android.view.Gravity
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.ProgressBar
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
        var brandHint: String = "",      // kartta addan önce yazan kısa metin (Amazon'da marka)
        var image: String = "",          // ürün fotoğrafı adresi
    )

    private enum class Kind { MATCH, MUADIL, POSSIBLE, SUSPICIOUS, OTHER_PACKAGE, USED, ACCESSORY, DIFFERENT_MODEL, OTHER }

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
        "cüzdan", "kartlık", "anahtarlık", "kordon", "kayış", "bağcık", "çorap", "tişört", "şapka", "poster",
        "lego", "oyuncak", "maket", "puzzle", "figür",
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
    private var queryAmount: Amount? = null   // aramadaki miktar: 200 g, 80 adet, 100 ml
    private var acceptedMods: Set<String> = emptySet()
    private var requiredSeries: Set<String> = emptySet()
    private val testMode = true          // test sürümü: seçimler hatırlanmaz
    private var familyTried = false      // model bulunamayınca aynı serinin diğer yılları arandı mı   // kullanıcının seçtiği seri: bu seriyi yazmayan ilan "olası"   // seçilen seri: "Aktif Bebek" gibi

    /** Ürün sayfası okuyucu: garanti tipi ve satıcı. Siteye özel değil, sayfadaki yazılara bakar. */
    private val detailJs = """
        (function(){
          var t = document.body ? document.body.innerText : '';
          var fullLen = t.length;
          var lines = t.split('\n').map(function(s){ return s.trim(); }).filter(function(s){ return s.length > 0; });
          // Üst menü ve site linkleri satıcı adı sanılmasın
          var junk = /satış yap|satıcı ol|mesajlarım|giriş yap|üye ol|kampanya|popüler|yardım|sipariş|sepet|favori/i;
          // Satıcı adının çevresindeki buton ve sayaçlar (ad sanılmasın)
          var notName = /mağazaya sor|satıcıya sor|hızlı gönderim|takip|kazan|detayları gör|takipçi|değerlendirme|soru|ürün|kargo|gönderim|başarılı mağaza|güvenilir|onaylı|rozet|yıldızlı|^\d+([.,]\d)?$/i;
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
          // Sadece ürünün kendi "Garanti ..." satırları (sayfa altındaki "son gezilen ürünler" sayılmasın)
          for (var q = 0; q < lines.length; q++) { if (/^garanti (tipi|türü|süresi|bilgisi)/i.test(lines[q])) { wl = ctx(q, 0, 1); break; } }
          if (!wl && wm && h1) wl = 'başlıktan: ' + (h1.innerText || '').slice(0, 120);

          // Satıcı: birkaç farklı yerleşimi sırayla dene
          var seller = '', sline = '', pageScore = '';
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
          // Ekranda yoksa sayfanın arka plan verisinde ara (Trendyol satıcıyı kaydırınca yükler ama veride baştan yazar)
          if (!seller) {
            try {
              var html = document.documentElement.innerHTML;
              var hm = html.match(/"(?:merchantName|sellerName|storeName|shopName|merchantTitle)"\s*:\s*"([^"\\]{2,40})"/) ||
                       html.match(/"merchant"\s*:\s*\{[^{}]{0,300}?"name"\s*:\s*"([^"\\]{2,40})"/) ||
                       html.match(/"seller"\s*:\s*\{[^{}]{0,300}?"name"\s*:\s*"([^"\\]{2,40})"/);
              if (hm && !junk.test(hm[1])) {
                seller = hm[1];
                if (!sline || sline.indexOf('TEŞHİS') === 0) sline = 'sayfa verisinden: ' + hm[0].slice(0, 120);
                // Satıcı puanı: aynı veri bloğunda "score"/"rating" alanı
                var near = html.slice(Math.max(0, hm.index - 300), hm.index + 400);
                var sm = near.match(/"(?:sellerScore|merchantScore|storeScore|score|rating)"\s*:\s*"?([0-9]{1,2}(?:[.,][0-9])?)"?/);
                if (sm) pageScore = sm[1].replace('.', ',');
              }
            } catch (e) {}
          }
          // Garanti ekranda yoksa sayfa verisinde ara
          if (!wm) {
            try {
              var html2 = document.documentElement.innerHTML;
              var gw = html2.match(/[Gg]aranti[ _]?[Tt]ipi[^{}]{0,160}?(İthalatçı Garantili|İthalatçı Firma Garantili|Resmi Distribütör Garantili|Distribütör Garantili|Apple Türkiye Garantili|Türkiye Garantili|Üretici Garantili|Uluslararası Garanti)/);
              if (gw) { wm = [gw[1], gw[1]]; if (!wl) wl = 'sayfa verisinden: ' + gw[0].slice(0, 120); }
            } catch (e) {}
          }
          var sc = pageScore || (sline.match(/(?:^|[\s|])((?:10|[0-9])[.,]\d)(?=[\s|]|$)/) || [])[1] || '';
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
    private lateinit var siteChips: LinearLayout
    private lateinit var chipsScroll: HorizontalScrollView
    private lateinit var progress: ProgressBar
    private val expanded = HashMap<String, Boolean>()
    private lateinit var filterBar: LinearLayout
    private lateinit var filterInput: EditText
    private lateinit var sortBtn: TextView
    private lateinit var siteToggles: LinearLayout
    private var filterText = ""
    private var sortMode = 0
    private val hiddenSites = HashSet<String>()
    private val sortNames = listOf("Önerilen", "En ucuz", "En pahalı", "Birim fiyat", "Puan", "Değerlendirme sayısı")
    private val refilter = Runnable { if (offers.isNotEmpty()) render(final = finished) }

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
    private fun extractJs(linkPattern: String, q: String = "") = """
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
              // "300 TL ve üzeri siparişlerde ücretsiz teslimat" ürün fiyatı değildir
              .replace(/(?:₺\s?)?[\d.,]+\s*(?:TL|₺)['’]?\s*(?:ve\s+)?(?:üzeri|üstü|altı|ye kadar|ya kadar|lik|lık)[^\n]{0,40}/gi, ' ')
              .replace(/(?:teslimat|gönderim)[^\n]{0,40}?(?:₺\s?)?[\d.,]+\s*(?:TL|₺)/gi, ' ')
              // "ilk siparişinizde 300 TL", "300 TL üzeri siparişlerde"
              .replace(/sipariş[^\n]{0,50}?(?:₺\s?)?[\d.,]+\s*(?:TL|₺)/gi, ' ')
              .replace(/(?:₺\s?)?[\d.,]+\s*(?:TL|₺)[^\n\d]{0,30}sipariş/gi, ' ')
              // "(510,89 TL/100 ml)", "1,89 TL/adet" gibi birim fiyat yazıları ürün fiyatı değildir
              .replace(/\([^)]*\/[^)]*\)/g, ' ')
              .replace(/(?:₺\s?)?[\d.,]+\s*(?:TL|₺)\s*\/\s*[0-9]*\s*[a-zçğıöşü]+/gi, ' ')
              .replace(/\([^)]*başına[^)]*\)/gi, ' ')
              .replace(/en düşük fiyat\s*\([^)]*\)\s*:?\s*(?:₺\s?)?[\d.,]+[,–\-]*(?:\s*TL)?/gi, ' ');
          }
          // Kartın ürün fotoğrafı (tembel yüklenen sitelerde data-src)
          function imgOf(box){
            var ims = box.querySelectorAll('img');
            for (var q = 0; q < ims.length; q++) {
              var im = ims[q];
              var cands = [im.currentSrc, im.src, im.getAttribute('data-src'), im.getAttribute('data-original'),
                (im.getAttribute('srcset') || '').split(' ')[0]];
              for (var z = 0; z < cands.length; z++) {
                var u = cands[z] || '';
                if (/^https?:/.test(u) && !/sprite|logo|icon|badge|placeholder|\.svg/i.test(u)) return u;
              }
            }
            return '';
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

            var notTitle = /teslimat|kargo|sepete|stokta|kupon|favoriledi|değerlendirilen|ziyaret edilen|en çok|satıldı|ürün puanı|son [0-9]+ gün|kişi ekledi|yıldız üzerinden|satın alma seçenekleri|ürün sayfalarını|^yaş:|ile ilgili|ürün bulduk|sonuç bulduk|sponsorlu|reklam|uygulamada aç|kapılarını|daha iyi bir deneyim|amazon.un seçimi|çok satan|en beğenilen|öne çıkan|fırsat ürünü|ek destek|\bTL\b|₺/i;
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
            // Seçilen ad aranan kelimeleri içermiyorsa ("4000 TL Ek Destek !"), kartta aranan kelimeleri en çok içeren satırı al
            var qw = '__Q__'.split(' ').filter(function(w){ return w.length >= 3; });
            function qhits(c){ var l = (c || '').toLowerCase(); return qw.filter(function(w){ return l.indexOf(w) >= 0; }).length; }
            if (qw.length && qhits(title) < Math.min(2, qw.length)) {
              var bestLine = '', bestHits = qhits(title);
              text.split('\n').forEach(function(s){ var c = okTitle(s); var hh = qhits(c); if (hh > bestHits) { bestHits = hh; bestLine = c; } });
              if (bestLine) title = bestLine;
            }
            if (!title) { var img = el.querySelector('img[alt]'); if (img) title = (img.getAttribute('alt') || '').trim(); }

            var rm = text.match(/(?:^|\s)([1-4][.,]\d|5[.,]0)(?=\s|\()/);
            var cm = text.match(/\((\d{1,3}(?:[.,]\d{1,3})?\s?B|\d{1,3}(?:\.\d{3})+|\d{1,6})(?:\s*[Dd]eğerlendirme)?\)/);
            cards.push({ t: title, p: price, lp: max, sepet: /sepette/i.test(text),
              r: rm ? rm[1] : '', rc: cm ? cm[1] : '', link: a.href,
              badge: ((/yetkili satıcı|resmi satıcı|resmi mağaza|marka mağazası/i.exec(text) || [''])[0]),
              img: imgOf(el), alts: ok, raw: text.replace(/\s+/g, ' ').slice(0, 260) });
          }
          // Farklı ürünlerin çoğunda aynı fiyat varsa ("300 TL üzeri ücretsiz kargo" gibi bir afiş): o sayı fiyat değildir
          if (cards.length >= 4) {
            var freq = {};
            cards.forEach(function(c){ freq[c.p] = (freq[c.p] || 0) + 1; });
            Object.keys(freq).forEach(function(k){
              var v = parseFloat(k);
              var titles = {};
              cards.forEach(function(c){ if (c.p === v) titles[c.t] = 1; });
              if (freq[k] >= 4 && freq[k] >= cards.length * 0.4 && Object.keys(titles).length >= 3) {
                cards.forEach(function(c){
                  if (c.p !== v) return;
                  var rest = c.alts.filter(function(x){ return x !== v; });
                  if (rest.length) { c.p = Math.min.apply(null, rest); c.banner = v; }
                });
              }
            });
          }
          cards.forEach(function(c){ delete c.alts; });
          return JSON.stringify({ title: document.title, url: location.href, block: hit,
            anchors: anchors.length, fallback: usedFallback, links: all.length,
            sampleLinks: cards.length ? [] : sampleLinks, cards: cards,
            pageSample: cards.length ? '' : (document.body ? document.body.innerText : '').replace(/\s+/g, ' ').slice(0, 200) });
        })()
    """.trimIndent().replace("__PAT__", linkPattern)
        .replace("__Q__", q.lowercase(Locale("tr", "TR")).replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = ink
        setContentView(buildUi())
    }

    // ---------- Arayüz ----------

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    private fun label(text: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    // ---------- Görsel yardımcılar ----------

    private val line = Color.parseColor("#E7E2D8")
    private val greenBg = Color.parseColor("#E3F4E6")
    private val redBg = Color.parseColor("#FCE4E2")
    private val orangeBg = Color.parseColor("#FDEBD8")
    private val greyBg = Color.parseColor("#EFECE6")
    private val amberBg = Color.parseColor("#FFF3D6")

    private val siteColors = mapOf(
        "Trendyol" to Color.parseColor("#F27A1A"), "Hepsiburada" to Color.parseColor("#FF6000"),
        "Amazon" to Color.parseColor("#232F3E"), "MediaMarkt" to Color.parseColor("#D7000F"),
        "Teknosa" to Color.parseColor("#1E3A8A"), "N11" to Color.parseColor("#6D28D9"),
    )

    private fun rounded(fill: Int, radius: Int = 14, stroke: Int = 0, strokeW: Int = 1) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
        if (stroke != 0) setStroke(dp(strokeW), stroke)
    }

    private fun pill(text: String, fg: Int, bg: Int, bold: Boolean = false, size: Float = 11f) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(fg)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        background = rounded(bg, 20)
        setPadding(dp(8), dp(3), dp(8), dp(3))
    }

    /** Çipleri satıra sığdıkça dizen, sığmayanı alt satıra geçiren kutu */
    private class FlowLayout(ctx: Context, private val gap: Int) : ViewGroup(ctx) {
        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            val maxW = MeasureSpec.getSize(widthSpec) - paddingLeft - paddingRight
            var x = 0; var y = 0; var rowH = 0
            for (i in 0 until childCount) {
                val c = getChildAt(i)
                measureChild(c, MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
                if (x > 0 && x + c.measuredWidth > maxW) { x = 0; y += rowH + gap; rowH = 0 }
                x += c.measuredWidth + gap
                rowH = maxOf(rowH, c.measuredHeight)
            }
            setMeasuredDimension(MeasureSpec.getSize(widthSpec), y + rowH + paddingTop + paddingBottom)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val maxW = r - l - paddingLeft - paddingRight
            var x = 0; var y = 0; var rowH = 0
            for (i in 0 until childCount) {
                val c = getChildAt(i)
                if (x > 0 && x + c.measuredWidth > maxW) { x = 0; y += rowH + gap; rowH = 0 }
                c.layout(paddingLeft + x, paddingTop + y, paddingLeft + x + c.measuredWidth, paddingTop + y + c.measuredHeight)
                x += c.measuredWidth + gap
                rowH = maxOf(rowH, c.measuredHeight)
            }
        }
    }

    // Ürün fotoğrafları: arka planda indirilir, küçültülüp bellekte tutulur
    private val imgCache = LruCache<String, Bitmap>(120)
    private val imgPool = java.util.concurrent.Executors.newFixedThreadPool(4)

    private fun loadImage(url: String, iv: ImageView) {
        iv.tag = url
        imgCache.get(url)?.let { iv.setImageBitmap(it); return }
        imgPool.execute {
            try {
                val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 5000
                c.readTimeout = 7000
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36")
                val bytes = c.inputStream.use { it.readBytes() }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= 200) sample *= 2
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                    BitmapFactory.Options().apply { inSampleSize = sample }) ?: return@execute
                imgCache.put(url, bmp)
                handler.post { if (iv.tag == url) iv.setImageBitmap(bmp) }
            } catch (e: Exception) {
            }
        }
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(paper)
        }

        // Başlık
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(ink)
            setPadding(dp(18), dp(14), dp(8), dp(14))
        }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(label("Fiyat Avcısı", 21f, gold, true))
        titles.addView(label("6 sitede aynı anda · test 0.36", 11f, Color.parseColor("#A8A29E")))
        header.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val menuBtn = label("⋮", 24f, Color.WHITE, true).apply {
            setPadding(dp(14), dp(2), dp(14), dp(2))
        }
        menuBtn.setOnClickListener { showMenu(menuBtn) }
        header.addView(menuBtn)
        root.addView(header)

        // Arama kutusu
        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(6))
        }
        input = EditText(this).apply {
            hint = "Ürün adı, model kodu ya da barkod"
            setSingleLine()
            textSize = 16f
            setTextColor(ink)
            setHintTextColor(Color.parseColor("#A8A29E"))
            background = rounded(Color.WHITE, 14, line)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, id, _ ->
                if (id == EditorInfo.IME_ACTION_SEARCH) { startTest(); true } else false
            }
        }
        searchRow.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val go = label("Ara", 16f, ink, true).apply {
            gravity = Gravity.CENTER
            background = rounded(gold, 14)
            setPadding(dp(20), dp(12), dp(20), dp(12))
            setOnClickListener { startTest() }
        }
        searchRow.addView(go, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(8) })
        root.addView(searchRow)

        // Site durumu çipleri ve ilerleme çubuğu
        siteChips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(2), dp(12), dp(2))
        }
        chipsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
            addView(siteChips)
        }
        root.addView(chipsScroll)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(gold)
            visibility = View.GONE
        }
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)).apply {
            setMargins(dp(12), dp(2), dp(12), 0)
        })

        status = label("", 13f, muted).apply {
            setPadding(dp(16), dp(4), dp(16), dp(6))
            visibility = View.GONE
        }
        root.addView(status)

        // Sonuç filtresi: kelimeyle ara, sırala, site gizle (sonuç listesinin üstünde sabit durur)
        filterBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(0), dp(12), dp(6))
            visibility = View.GONE
        }
        val fRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        filterInput = EditText(this).apply {
            hint = "Sonuçlarda ara (ör. siyah, 6'lı)"
            setSingleLine()
            textSize = 14f
            setTextColor(ink)
            setHintTextColor(Color.parseColor("#A8A29E"))
            background = rounded(Color.WHITE, 12, line)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(e: android.text.Editable?) {
                    filterText = e?.toString().orEmpty()
                    handler.removeCallbacks(refilter)
                    handler.postDelayed(refilter, 250)
                }
            })
        }
        fRow.addView(filterInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        sortBtn = label("Sırala ▾", 14f, ink, true).apply {
            background = rounded(Color.WHITE, 12, line)
            setPadding(dp(12), dp(9), dp(12), dp(9))
        }
        sortBtn.setOnClickListener {
            PopupMenu(this, sortBtn).apply {
                sortNames.forEachIndexed { i, n -> menu.add(0, i, i, (if (i == sortMode) "✓ " else "") + n) }
                setOnMenuItemClickListener {
                    sortMode = it.itemId
                    sortBtn.text = if (sortMode == 0) "Sırala ▾" else "${sortNames[sortMode]} ▾"
                    render(final = finished)
                    true
                }
                show()
            }
        }
        fRow.addView(sortBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(8) })
        filterBar.addView(fRow)
        siteToggles = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
        }
        filterBar.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(siteToggles)
        })
        root.addView(filterBar)

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
            setPadding(dp(12), dp(4), dp(12), dp(32))
        }
        resultsScroll.addView(results)
        stack.addView(resultsScroll, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        root.addView(stack, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        handler.post { showHome() }
        return root
    }

    private fun toggleBrowser() {
        val showing = resultsScroll.visibility != View.VISIBLE
        resultsScroll.visibility = if (showing) View.VISIBLE else View.GONE
    }

    private fun showMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, if (resultsScroll.visibility == View.VISIBLE) "Tarayıcıyı göster" else "Sonuçları göster")
            menu.add(0, 2, 1, "Raporu kopyala")
            menu.add(0, 3, 2, "Raporu paylaş")
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> toggleBrowser()
                    2 -> copyReport()
                    3 -> shareReport()
                }
                true
            }
            show()
        }
    }

    /** Açılış ekranı: kısa açıklama, son aramalar */
    private fun showHome() {
        if (running) return
        results.removeAllViews()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.WHITE, 16, line)
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        card.addView(label("Ne arıyorsun?", 20f, ink, true))
        card.addView(label(
            "Ürünün adını, model kodunu ya da barkod numarasını yaz. Trendyol, Hepsiburada, Amazon, MediaMarkt, " +
                "Teknosa ve N11 aynı anda taranır; aynı ürünü ucuzdan pahalıya sıralar, başka paket ve modelleri ayırır.",
            14f, muted
        ).apply { setPadding(0, dp(6), 0, 0) })
        results.addView(card, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })

        val recent = recentSearches()
        results.addView(label(if (recent.isNotEmpty()) "Son aramalar" else "Örnek aramalar", 14f, ink, true)
            .apply { setPadding(dp(4), dp(18), dp(4), dp(8)) })
        val flow = FlowLayout(this, dp(8))
        (recent.ifEmpty { listOf("iphone 16 128gb", "nescafe gold 200 gr", "dior sauvage edt 100 ml", "prima 4 numara") })
            .forEach { q ->
                flow.addView(pill(q, ink, Color.WHITE, size = 14f).apply {
                    background = rounded(Color.WHITE, 20, line)
                    setPadding(dp(14), dp(8), dp(14), dp(8))
                    setOnClickListener { input.setText(q); startTest() }
                })
            }
        results.addView(flow)
    }

    private fun recentSearches(): List<String> =
        prefs.getString("recent", "").orEmpty().split("\n").filter { it.isNotBlank() }

    private fun rememberSearch(q: String) {
        val list = (listOf(q) + recentSearches().filter { norm(it) != norm(q) }).take(8)
        prefs.edit().putString("recent", list.joinToString("\n")).apply()
    }

    /** Arama sırasında her sitenin durumu: bekliyor / bitti (ürün sayısı) / ilgisiz / sorun */
    private fun updateSiteChips() {
        siteChips.removeAllViews()
        for (s in sites) {
            val line = siteSummary.lastOrNull { it.startsWith(s.name + ":") }
            val (txt, fg, bg) = when {
                line == null -> Triple("${s.name} …", muted, greyBg)
                line.contains("ürün okundu") -> Triple("${s.name} ✓ " +
                    (Regex("([0-9]+) ürün okundu").find(line)?.groupValues?.get(1) ?: ""), green, greenBg)
                line.contains("sonuç yok") || line.contains("ilgisiz") || line.contains("KARTI BULUNAMADI") ->
                    Triple("${s.name} —", muted, greyBg)
                else -> Triple("${s.name} !", red, redBg)
            }
            siteChips.addView(pill(txt, fg, bg, bold = true, size = 12f).apply {
                setPadding(dp(10), dp(5), dp(10), dp(5))
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(6) })
        }
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
    private var resolveSearchQuery = ""   // tanıma adımında sitelere sorulan yazı
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
        // Eski sayfaya işaret koy: yeni sayfa gelene kadar okuyucu "eski" der, önceki aramanın ürünleri okunmaz
        val wrapped = "(function(){ if (window.__faOld) return JSON.stringify({stale:true}); return $js; })()"
        var loaded = false
        val go = {
            if (!loaded && slot.gen == my) {
                loaded = true
                v.loadUrl(url)
            }
        }
        v.evaluateJavascript("window.__faOld=1;") { go() }
        handler.postDelayed({ go() }, 400)
        val t0 = android.os.SystemClock.elapsedRealtime()
        var n = 0
        lateinit var tick: () -> Unit
        tick = {
            if (slot.gen == my) {
                if (scroll && n > 0) v.evaluateJavascript("window.scrollBy(0, 1600);", null)
                v.evaluateJavascript(wrapped) { raw ->
                    if (slot.gen == my) {
                        val d0 = parseJson(raw)
                        val stale = d0?.optBoolean("stale") == true
                        val d = if (stale) null else d0
                        val elapsed = android.os.SystemClock.elapsedRealtime() - t0
                        if (!stale) n++
                        if ((!stale && ready(d, n)) || elapsed >= maxMs) {
                            slot.gen++
                            done(d)
                        } else {
                            handler.postDelayed({ tick() }, if (stale) 300 else 700)
                        }
                    }
                }
            }
        }
        handler.postDelayed({ tick() }, 900)
    }

    /** Bir sitede arama: ürünler yüklenip sayı sabitlenince okur; boş sayfada yeniden yükler, sonuç yoksa sade arama dener */
    private fun runSite(
        v: WebView, site: Site, q: String, simpleUsed: Boolean, reloaded: Boolean,
        onDone: (JSONObject?, Boolean) -> Unit,
    ) {
        var last = -1
        var stable = 0
        // Bazı siteler (Hepsiburada) sadece ekrandaki ürünleri tutar: her ölçümde görülen kartlar biriktirilir
        val acc = LinkedHashMap<String, JSONObject>()
        val url = site.url(URLEncoder.encode(q, "UTF-8").replace("+", "%20"))
        val isBarcodeSearch = q.replace(" ", "").all { it.isDigit() }
        loadAndPoll(v, url, extractJs(site.linkPattern, q), maxMs = 9000, scroll = true, ready = { d, n ->
            d?.optJSONArray("cards")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val c = arr.getJSONObject(i)
                    val k = c.optString("link").substringBefore('#').substringBefore('?')
                    if (k.isNotEmpty() && k !in acc) acc[k] = c
                    // Fotoğraf sonradan yüklendiyse ekle
                    else if (k.isNotEmpty() && acc[k]?.optString("img").isNullOrEmpty() && c.optString("img").isNotEmpty())
                        acc[k]?.put("img", c.optString("img"))
                }
            }
            val c = acc.size
            if (c > 0 && c == last) stable++ else stable = 0
            last = c
            // Az kart görünüyorsa sayfa hâlâ yükleniyor olabilir: biraz daha beklenir
            d?.optBoolean("noResults") == true || (c > 0 && stable >= 2 && n >= (if (c < 6) 6 else 3)) ||
                // Ürünler gelmiş ama hiçbiri aramayla ilgili değilse beklemeye gerek yok
                // (Önce üstteki öneri/sponsorlu kartlar gelebilir: sayfa oturmadan "ilgisiz" denmez)
                (c > 0 && n >= 5 && stable >= 2 && !isBarcodeSearch && isIrrelevant(d, q)) ||
                // Sayfa açıldı ama hiç ürün kartı yok (site bu ürünü satmıyor): sonuna kadar bekleme
                (c == 0 && n >= 5 && (d?.optInt("links") ?: 0) > 30)
        }) { d0 ->
            // Sonuç: son ölçümün sayfa bilgisi + biriken bütün kartlar
            val d = d0?.also { if (acc.isNotEmpty()) it.put("cards", org.json.JSONArray(acc.values.toList())) }
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
                // Çok az sonuç geldiyse (site tam ifadeyi arıyor olabilir): sade aramayı da yap, sonuçları birleştir
                !simpleUsed && !isBarcodeQ && d != null && cards in 1..4 &&
                    simplifiedQuery(q).let { it.isNotEmpty() && norm(it) != norm(q) } ->
                    runSite(v, site, simplifiedQuery(q), true, reloaded) { d2, _ ->
                        val merged = org.json.JSONArray()
                        val seenLinks = HashSet<String>()
                        listOfNotNull(d.optJSONArray("cards"), d2?.optJSONArray("cards")).forEach { arr ->
                            for (i in 0 until arr.length()) {
                                val c = arr.getJSONObject(i)
                                if (seenLinks.add(c.optString("link").substringBefore('?'))) merged.put(c)
                            }
                        }
                        d.put("extra", merged.length() - cards)
                        d.put("cards", merged)
                        onDone(d, false)
                    }
                else -> onDone(d, simpleUsed)
            }
        }
    }

    /** Siteleri aynı anda tarar; her site bitince [onEach], hepsi bitince [onAll] */
    private fun runSites(list: List<Site>, q: String, label: String, onEach: () -> Unit, onAll: () -> Unit) {
        val gen = ++phaseGen
        var left = list.size
        val t0 = android.os.SystemClock.elapsedRealtime()
        status.text = "$label: ${list.size} site aynı anda taranıyor…"
        val done = BooleanArray(list.size)
        var phaseDone = false
        val finish = { if (!phaseDone) { phaseDone = true; onAll() } }
        // Mağazada hız: siteler 12 saniyede bitmediyse ve en az 4'ü bittiyse yavaş olanlar beklenmez
        handler.postDelayed({
            if (gen == phaseGen && !phaseDone && list.size - left >= 4) {
                list.forEachIndexed { i, s ->
                    if (!done[i]) {
                        slots.getValue(pool[i]).gen++
                        pool[i].stopLoading()
                        siteSummary.add("${s.name}: yavaş — beklenmedi (12 sn)")
                    }
                }
                finish()
            }
        }, 12000)
        list.forEachIndexed { i, site ->
            val v = pool[i]
            runSite(v, site, q, simpleUsed = false, reloaded = false) { d, simpleUsed ->
                if (gen == phaseGen && !phaseDone) {
                    done[i] = true
                    collect(site.name, d, q, simpleUsed, slots.getValue(v))
                    updateSiteChips()
                    if (siteSummary.isNotEmpty()) {
                        siteSummary[siteSummary.size - 1] = siteSummary.last() + " · " +
                            elapsedText(android.os.SystemClock.elapsedRealtime() - t0)
                    }
                    left--
                    status.text = "$label: ${list.size - left}/${list.size} site tamam · " +
                        elapsedText(android.os.SystemClock.elapsedRealtime() - startedAt)
                    onEach()
                    if (left == 0) finish()
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
        expanded.clear()
        hiddenSites.clear()
        filterText = ""
        filterInput.setText("")
        filterBar.visibility = View.GONE
        rememberSearch(userInput)
        siteSummary.clear()
        progress.visibility = View.VISIBLE
        chipsScroll.visibility = View.VISIBLE
        status.visibility = View.VISIBLE
        status.text = "Siteler taranıyor…"
        updateSiteChips()
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
        // Sondaki nokta, soru işareti gibi işaretler sitelerin aramasını bozar ("200 gr." )
        val cleanInput = userInput.replace(Regex("(?<![0-9])[.,]|[.,](?![0-9])|[!?;:()\\[\\]\"“”]"), " ")
            .replace(Regex("\\s+"), " ").trim().ifEmpty { userInput }
        resolveType = inputType(cleanInput)
        resolveQuery = cleanInput
        familyTried = false
        // Test sürümünde hafıza okunmaz: her arama sıfırdan (sarı satıra basmaya gerek yok)
        val mem = { k: String -> if (testMode) null else prefs.getString(k + norm(userInput), null) }
        acceptedMods = mem("m:")?.split("|")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
        requiredSeries = acceptedMods

        // Hafıza 1: bu giriş için daha önce bir paket seçildiyse sormadan onu kullan
        val saved = mem("q:")
        if (saved != null) {
            identity = identityFromJson(saved)
            resolveLog.append("Kimlik hafızadan alındı.\n")
            startSearch()
            return
        }
        // Hafıza 2: barkoddan/addan daha önce bulunan ürün adı
        mem("n:")?.let { s ->
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
        query = cleanInput
        resolveSearchQuery = cleanInput
        // Barkodu bilmeyen siteler öneri listesi gösterebildiği için barkod tanıması 3 güvenilir sitede yapılır
        val resolveOn = if (resolveType == "barkod") resolverSites else sites
        runSites(resolveOn, cleanInput, "Ürün tanınıyor", onEach = {}) { finishResolve() }
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
        val qCodes = codesIn(q).map { it.first }
        val words = requiredWords(q)
        if (words.isEmpty() && qCodes.isEmpty()) return false
        val relevant = (0 until cards.length()).count { i ->
            val c = cards.getJSONObject(i)
            val text = c.optString("t") + " " + c.optString("raw")
            val nt = canonNorm(text)
            // Model kodu (bölge ekli/eksiz) geçen kart her zaman ilgilidir
            qCodes.any { titleHasCode(text, it) } ||
                (words.isNotEmpty() && words.count { hasWord(text, nt, it) } >= minOf(2, words.size))
        }
        // Hiçbiri ya da 8+ kart içinde sadece biri (ayakkabı ararken klavye, mouse ve tek bir LEGO) ilgiliyse
        return relevant == 0 || (cards.length() >= 8 && relevant <= 1)
    }

    private fun collect(name: String, d: JSONObject?, q: String, simpleUsed: Boolean, slot: Slot) {
        val cards = d?.optJSONArray("cards")
        val count = cards?.length() ?: 0
        val isBarcode = q.replace(" ", "").let { s -> s.length in 8..14 && s.all { it.isDigit() } }
        if (count > 0 && !isBarcode && isIrrelevant(d, q)) {
            val sample = (0 until minOf(2, count)).joinToString(" | ") { cards!!.getJSONObject(it).optString("t").take(60) }
            siteSummary.add("$name: sonuçlar aramayla ilgisiz (site muhtemelen öneri gösteriyor) — ör: $sample")
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
                image = c.optString("img"),
            )
            // Amazon markayı ad satırının üstünde ayrı yazar: kart metninde addan önceki kısa yazı marka olabilir
            val idx = o.raw.indexOf(o.title.take(20))
            if (idx in 1..40) o.brandHint = o.raw.substring(0, idx).trim()
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
                (if (simpleUsed) " (sade aramayla: \"${simplifiedQuery(q)}\")" else "") +
                (if (d.optInt("extra") > 0) " (+${d.optInt("extra")} sade aramadan: \"${simplifiedQuery(q)}\")" else "")
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
    // TV/beyaz eşya tipi uzun kodlar: QE55Q60D, 55Q60D, QE55Q60DAUXTK, UE43CU7000
    private val longCode = Regex("\\b(?=[a-z0-9]*[a-z])(?=[a-z0-9]*[0-9])[a-z0-9]{6,16}\\b")
    private val unitTail = Regex(".*[0-9](ml|gr|g|kg|lt|l|cl|gb|tb|mb|cm|mm|li|lu|lı|lü|w|mah|hz|mp|x|inc|inch)$")

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
        longCode.findAll(t).forEach { m ->
            val c = m.value
            val covered = out.any { it.second == c || it.first == c }
            val letters = c.takeWhile { it.isLetter() }.length
            val skip = covered || unitTail.matches(c) ||
                Regex("^[0-9]+[a-z]{1,3}$").matches(c) ||           // 128gb, 200gr
                Regex("^[0-9]+x[0-9]+$").matches(c) ||              // 1x100
                (letters >= 5 && c.length - letters <= 2)            // iphone16
            if (!skip) out.add(c to c)
        }
        return out
    }

    /** Kodun özü: baştaki 1-3 harflik bölge/ülke öneki atılır (QE55Q60D -> 55Q60D) */
    private fun codeCore(c: String): String {
        val lead = c.takeWhile { it.isLetter() }.length
        val core = c.drop(lead)
        return if (lead in 1..3 && core.length >= 5 && core.first().isDigit()) core else c
    }

    /** Aynı model mi: QE55Q60D = 55Q60D = QE55Q60DAUXTK (bölge eki). Paket kodlarında (/31) sadece birebir. */
    private fun sameCode(a: String, b: String): Boolean {
        if (a == b) return true
        if ('/' in a || '/' in b || '-' in a || '-' in b) return false
        val x = codeCore(a)
        val y = codeCore(b)
        if (x.length < 5 || y.length < 5) return false
        val (s, l) = if (x.length <= y.length) x to y else y to x
        return l.startsWith(s) && l.length - s.length <= 6 && l.substring(s.length).all { it.isLetter() }
    }

    /** Başlıkta bu kod (ya da bölge ekli/eksiz hali) geçiyor mu */
    private fun titleHasCode(title: String, code: String): Boolean =
        codesIn(title).any { sameCode(it.first, code) || sameCode(it.second, code) } || norm(title).contains(code.replace("/", ""))

    private val unitWords = setOf("adet", "li", "lı", "lu", "lü", "kg", "gr", "g", "ml", "lt", "l", "litre", "cl", "mm", "cm",
        "paket", "kutu", "rulo", "yaprak", "parça", "gram", "set", "takım")
    // Karşılaştırma sadeleştirilmiş kelimelerle yapılır ("parça" -> "parca")
    private val unitNorm by lazy { unitWords.map { norm(it) }.toSet() }

    // Arama kelimesi olarak şart sayılmayan kelimeler: renk, cinsiyet, konsantrasyon, genel tür adı
    private val colorWords = setOf("beyaz", "siyah", "kirmizi", "mavi", "lacivert", "gri", "yesil", "sari", "pembe", "mor",
        "turuncu", "kahverengi", "kahve rengi", "bej", "lila", "bordo", "haki", "gumus", "altin", "rose", "white", "black",
        "red", "blue", "grey", "gray", "green", "pink", "silver", "gold rengi")
    private val softWords = setOf("erkek", "kadin", "bayan", "unisex", "edt", "edp", "edc", "parfum", "extrait", "numara", "beden")
    private val concWords = setOf("edt", "edp", "edc", "extrait", "parfum")

    /** Yazım eşlerini tek biçime indirir: "Eau de Toilette" = EDT, "Ultra Fırsat" bir seri değil */
    private fun canonText(s: String): String = s.lowercase(Locale("tr", "TR"))
        .replace(Regex("eau\\s+de\\s+toilette"), " edt ")
        .replace(Regex("eau\\s+de\\s+parfum"), " edp ")
        .replace(Regex("eau\\s+de\\s+cologne"), " edc ")
        .replace(Regex("(ultra|mega|süper|super|jumbo)\\s*(fırsat|firsat|ekonomik|paket)"), " fırsatpaketi ")

    private fun tokensOf(s: String) = canonText(s).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }

    // Seriyi/modeli değiştiren kelimeler: başlıkta olup aramada yoksa o ilan başka bir üründür
    private val modMap = mapOf(
        "pro" to "Pro", "plus" to "Plus", "max" to "Max", "mini" to "Mini", "ultra" to "Ultra", "lite" to "Lite", "fe" to "FE",
        "mid" to "Mid", "high" to "High", "shadow" to "Shadow", "sculpt" to "Sculpt", "lv8" to "LV8", "craft" to "Craft",
        "utility" to "Utility", "react" to "React", "platform" to "Platform",
        "eau" to "Eau", "elixir" to "Elixir", "extreme" to "Extreme", "intense" to "Intense", "absolu" to "Absolu", "forte" to "Forte",
        "aktif" to "Aktif Bebek", "premium" to "Premium", "kulot" to "Külot", "pants" to "Külot", "harmonie" to "Harmonie",
        "stick" to "Stick", "kavanoz" to "Kavanoz", "cam" to "Kavanoz",
        "ps" to "Çocuk", "gs" to "Çocuk", "td" to "Çocuk", "cocuk" to "Çocuk", "kids" to "Çocuk", "junior" to "Çocuk",
        "plt" to "Platform", "jewel" to "Jewel", "lx" to "LX", "se" to "SE", "flyease" to "Flyease", "retro" to "Retro",
        "nature" to "Next Nature", "nn" to "Next Nature", "fontanka" to "Fontanka",
        // Aynı isimli başka ürün türleri (parfüm ararken tıraş sonrası losyon vb.)
        "after" to "After Shave", "aftershave" to "After Shave", "balm" to "Balm", "losyon" to "Losyon",
        "deodorant" to "Deodorant", "deo" to "Deodorant", "jeli" to "Duş Jeli", "sampuan" to "Şampuan",
        "le" to "Çocuk", "havlu" to "Islak Havlu", "mendil" to "Islak Havlu",
        // Kahve: aynı markanın başka ürünleri
        "crema" to "Crema", "filtre" to "Filtre Kahve", "kafeinsiz" to "Kafeinsiz", "decaf" to "Kafeinsiz",
        "aromali" to "Aromalı", "karamel" to "Aromalı", "vanilya" to "Aromalı", "findik" to "Aromalı",
        "roastery" to "Roastery", "icimlik" to "Stick",
    )

    // Renkler tek biçime iner: white = beyaz; "panda" siyah-beyazdır
    private val colorMap = mapOf(
        "beyaz" to "beyaz", "white" to "beyaz", "siyah" to "siyah", "black" to "siyah", "kirmizi" to "kırmızı", "red" to "kırmızı",
        "mavi" to "mavi", "blue" to "mavi", "lacivert" to "lacivert", "navy" to "lacivert", "gri" to "gri", "grey" to "gri",
        "gray" to "gri", "yesil" to "yeşil", "green" to "yeşil", "sari" to "sarı", "yellow" to "sarı", "pembe" to "pembe",
        "pink" to "pembe", "mor" to "mor", "purple" to "mor", "turuncu" to "turuncu", "orange" to "turuncu",
        "kahverengi" to "kahverengi", "brown" to "kahverengi", "bej" to "bej", "beige" to "bej", "krem" to "krem",
        "cream" to "krem", "lila" to "lila", "bordo" to "bordo", "haki" to "haki", "gumus" to "gümüş", "silver" to "gümüş",
        "altin" to "altın", "rose" to "pembe",
    )

    private fun traitsOf(s: String): Set<String> = tokensOf(s).mapNotNull { modMap[norm(it)] }.toSet()

    /** Aramadaki model numarasına harf eklenmiş hali: "16" ararken "16e", "16s" başka modeldir */
    private fun suffixTraits(q: String, title: String): Set<String> {
        val nums = tokensOf(q).filter { it.all { c -> c.isDigit() } && it.length <= 3 }.toSet()
        if (nums.isEmpty()) return emptySet()
        val units = setOf("gb", "tb", "mb", "gr", "g", "ml", "li", "lu", "kg", "lt", "l", "mm", "cm", "w", "x", "mp", "hz", "k", "inc", "cl")
        return tokensOf(title).map { norm(it) }.mapNotNull { tk ->
            val n = nums.firstOrNull { tk.startsWith(it) && tk.length > it.length } ?: return@mapNotNull null
            val suf = tk.substring(n.length)
            if (suf.length in 1..2 && suf.all { it.isLetter() } && suf !in units) tk.uppercase() else null
        }.toSet()
    }

    private fun colorsIn(s: String): Set<String> {
        val out = tokensOf(s).mapNotNull { colorMap[norm(it)] }.toMutableSet()
        if (tokensOf(s).any { norm(it) == "panda" }) { out.add("siyah"); out.add("beyaz") }
        return out
    }

    /** Parfüm konsantrasyonu. Türkçe "parfüm" genel addır; sadece "parfum" (EDP'den yoğun) sayılır. */
    private fun concOf(s: String) = tokensOf(s).filter { it in concWords || it == "elixir" }.toSet()

    private val usedRe = Regex("yenilenmiş|yenilenmis|ikinci el|2\\. ?el\\b|teşhir|refurbished|renewed|kullanılmış|outlet ürün|(?<![\\p{L}])[abc] (kalite|grade)(?![\\p{L}])|yenilio")
    private val knockoffRe = Regex("muadil|esans|alternatif|inspired|esintili|kokusunda|tipi parfüm|benzeri")

    /** Türkçe küçük harfte İngilizce "I" noktasız "ı" olur ("ınternational"): kalıplar iki biçimde de denenir */
    private fun hit(re: Regex, t: String) = re.containsMatchIn(t) || re.containsMatchIn(t.replace('ı', 'i'))

    /** Miktar: paket başına miktar (g / ml / adet) ve paket sayısı. "200 gr x 3", "2 x 200 gr", "80'li * 2" */
    private data class Amount(val per: Double, val unit: String, val mult: Double) {
        val total get() = per * mult
    }

    private fun amountOf(title: String, prefer: String? = null): Amount? {
        val raw = title.lowercase(Locale("tr", "TR"))
        val t = raw.replace('×', 'x').replace('*', 'x')
        fun weight(): Amount? {
            for (m in Regex("([0-9]+(?:[.,][0-9]+)?)\\s*(kg|gram|gr|g|ml|cl|lt|litre|l)\\b").findAll(t)) {
                // "7-14 kg" gibi aralıklar miktar değildir
                if (Regex("[0-9]\\s*-\\s*$").containsMatchIn(t.substring(0, m.range.first))) continue
                val v = m.groupValues[1].replace(",", ".").toDoubleOrNull() ?: continue
                val u = m.groupValues[2]
                // "5G" telefon bağlantısıdır, gram değil
                if (u == "g" && v in 3.0..5.0 && title.getOrNull(m.range.last) == 'G') continue
                val (per, unit) = when (u) {
                    "kg" -> v * 1000 to "g"
                    "gram", "gr", "g" -> v to "g"
                    "ml" -> v to "ml"
                    "cl" -> v * 10 to "ml"
                    else -> v * 1000 to "ml"
                }
                if (per <= 0) continue
                return Amount(per, unit, packMultiplier(t, m.range.first, m.range.last))
            }
            return null
        }
        fun count(): Amount? {
            val m = Regex("([0-9]{1,3}(?:\\.[0-9]{3})+|[0-9]+)\\s*(?:adet|parça|parca|prç|pcs|['’]?\\s?l[iıuü]\\b)").find(t) ?: return null
            val n = numberOf(m.groupValues[1])
            if (n <= 1) return null
            return Amount(n, "adet", packMultiplier(t, m.range.first, m.range.last, countUnit = true))
        }
        return if (prefer == "adet") count() ?: weight() else weight() ?: count()
    }

    private fun amountLabel(a: Amount): String {
        val per = if (a.per == Math.floor(a.per)) tlWhole.format(a.per) else tl.format(a.per)
        return "$per ${a.unit}" + if (a.mult > 1) " × ${a.mult.toInt()}" else ""
    }

    /**
     * Aramayı parçalar:
     *  words = ürün türü/marka kelimeleri, hard = şart olan ölçüler ("no 6" -> no6, "pro 2" -> pro2).
     *  "100 adet", "6 kg" gibi miktarlar şart değildir; birim fiyatla karşılaştırılır.
     *  Renk, cinsiyet ve EDT gibi kelimeler de şart değildir (ilanların çoğu yazmaz).
     */
    private fun querySpec(q: String): Pair<List<String>, List<String>> {
        val tokens = canonText(q).split(Regex("[\\s:]+")).map { norm(it) }.filter { it.isNotEmpty() }
        val words = mutableListOf<String>()
        val hard = mutableListOf<String>()
        val qty = Regex("^[0-9]+(li|lu|adet|kg|gr|g|ml|lt|l|cl|gram|parca)$")
        val sizeWords = setOf("no", "numara", "beden")
        tokens.forEachIndexed { i, t ->
            val next = tokens.getOrNull(i + 1)
            val prev = tokens.getOrNull(i - 1)
            val isNum = t.all { it.isDigit() }
            when {
                t in unitNorm || qty.matches(t) -> {}
                t in colorWords || t in softWords -> {}
                isNum && next != null && next in unitNorm -> {}
                isNum && ((prev != null && prev in sizeWords) || (next != null && next in sizeWords)) -> hard.add("no$t")
                isNum && prev != null -> hard.add(prev + t)
                t.length >= 3 -> words.add(if (t.length > 5) t.removeSuffix("lar").removeSuffix("ler") else t)
            }
        }
        return words to hard
    }

    /** Başlıktaki numara/beden değerleri: "4 Numara", "Beden:4", "No:6" */
    private fun sizeNos(title: String): Set<String> {
        val t = title.lowercase(Locale("tr", "TR"))
        val out = mutableSetOf<String>()
        Regex("(?<![\\p{L}])(?:no|numara|beden|size)\\s*[:.]?\\s*([0-9]{1,2})(?![0-9x×*])").findAll(t).forEach { out.add(it.groupValues[1].trimStart('0')) }
        Regex("(?<![0-9x×*=])([0-9]{1,2})\\s*(?:no|numara|beden)(?![\\p{L}])").findAll(t).forEach { out.add(it.groupValues[1].trimStart('0')) }
        return out
    }

    /** Aranan kelime başlıkta var mı (numara/beden yazım farklarıyla birlikte) */
    private fun hasWord(title: String, nt: String, w: String): Boolean {
        Regex("^no([0-9]{1,2})$").find(w)?.let { m -> return m.groupValues[1].trimStart('0') in sizeNos(title) || nt.contains(w) }
        return nt.contains(w)
    }

    /** Başlığın karşılaştırma için sade hali (EDT yazım eşleri dahil) */
    private fun canonNorm(s: String) = norm(canonText(s))

    /** Sade arama: miktar ve renk atılır ("prima 4 numara 80 adet" -> "prima 4 numara"); olmazsa ürün türü + ölçü */
    private fun simplifiedQuery(q: String): String {
        val toks = q.split(Regex("\\s+")).filter { it.isNotBlank() }
        val qty = Regex("^[0-9]+(li|lu|adet|kg|gr|g|ml|lt|l|cl|gram|parca)$")
        val keep = toks.filterIndexed { i, w ->
            val n = norm(w)
            val next = toks.getOrNull(i + 1)?.let { norm(it) }
            n.isNotEmpty() && n !in unitNorm && !qty.matches(n) && n !in colorWords &&
                !(n.all { it.isDigit() } && next != null && next in unitNorm)
        }
        val s1 = keep.joinToString(" ")
        if (keep.size >= 2 && norm(s1) != norm(q)) return s1
        val (words, hard) = querySpec(q)
        val original = q.split(Regex("[\\s:]+")).filter { it.isNotBlank() }
        val pick = { ws: List<String> -> ws.mapNotNull { w -> original.firstOrNull { norm(it).startsWith(w) } ?: w } }
        val parts = when {
            hard.isNotEmpty() -> pick(listOfNotNull(words.drop(1).firstOrNull() ?: words.firstOrNull())) +
                hard.map { it.replace(Regex("([a-z]+)([0-9]+)"), "$1 $2") }
            bookMode -> pick(words.take(4))
            else -> pick(words.drop(1).take(3)).ifEmpty { pick(words.take(3)) }
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
                // Seri grupları: aramada yazmayan seri kelimeleri (Aktif Bebek / Premium). Ana grup yoksa sor.
                val groups = seriesGroups()
                val baseCount = groups[emptySet()]?.size ?: 0
                val bigGroups = groups.filter { it.key.isNotEmpty() && it.value.size >= 2 }
                    .toList().sortedByDescending { it.second.size }.take(5)
                // Seri yazmayan birkaç ilan, seri yazan çoğunluğu gölgelemesin
                val named = groups.filter { it.key.isNotEmpty() }.values.sumOf { it.size }
                when {
                    bigGroups.isEmpty() || baseCount * 2 >= named -> continueWithoutIdentity()
                    bigGroups.size == 1 -> {
                        acceptedMods = acceptedMods + bigGroups[0].first
                        requiredSeries = bigGroups[0].first
                        resolveLog.append("Tek seri bulundu: ").append(bigGroups[0].first.joinToString(" ")).append("\n")
                        continueWithoutIdentity()
                    }
                    else -> showSeriesChooser(bigGroups) { continueWithoutIdentity() }
                }
            }
            cands.size == 1 || (resolveType == "barkod" && cands[0].count >= 2 * cands[1].count) -> {
                chooseIdentity(cands[0])
            }
            else -> showChooser(cands)
        }
    }

    /** Kimliksiz devam: tanıma bu aramayla zaten yapıldıysa sonuçları tekrar kullan, aramayı tekrarlama */
    private fun continueWithoutIdentity() {
        if (norm(resolveSearchQuery) == norm(resolveQuery)) {
            resolvedAt = android.os.SystemClock.elapsedRealtime()
            query = resolveQuery
            startDetails()
        } else {
            startSearch()
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
        resolveSearchQuery = name
        runSites(sites, name, "Ürün tanınıyor", onEach = {}) { finishResolve() }
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
        val qMods = traitsOf(resolveQuery) + acceptedMods
        val inputCodes = codesIn(resolveQuery).flatMap { listOf(it.first, it.second) }.toSet()
        val count = mutableMapOf<String, Int>()
        val baseOf = mutableMapOf<String, String>()
        val titles = mutableMapOf<String, MutableList<String>>()
        val prices = mutableMapOf<String, MutableList<Double>>()
        val perSite = mutableMapOf<String, Int>()
        var eligible = 0
        for (o in offers) {
            val t = o.title.lowercase(Locale("tr", "TR"))
            if (isAccessory(t) || hit(usedRe, t)) continue
            val nt = canonNorm(o.title)
            val codes = codesIn(o.title)
            val pick = when (type) {
                // Adla aramada: renk kodlu Apple parça numaraları ve çocuk/Pro gibi başka seriler aday olmaz
                "ad" -> if (need.isNotEmpty() && need.all { hasOfferWord(o, nt, it, need.firstOrNull()) } &&
                    (traitsOf(o.title) + suffixTraits(resolveQuery, o.title) - qMods).isEmpty()) {
                    eligible++
                    codes.firstOrNull { !it.first.endsWith("/a") }
                } else null
                "kod" -> codes.firstOrNull { it.first in inputCodes || it.second in inputCodes }
                    ?: codes.firstOrNull { c -> inputCodes.any { sameCode(c.first, it) || sameCode(c.second, it) } }?.let {
                        // QE55Q60DAUXTK, 55Q60D gibi yazımlar aranan kodun kendisidir: ayrı aday olmasın
                        val ic = inputCodes.first { x -> sameCode(it.first, x) || sameCode(it.second, x) }
                        ic to ic
                    }
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
        // Adla aramada kodlar dağınıksa (her kod 1-2 ilanda) bunlar renk/stil kodudur, sormaya değmez
        // ya da en sık kod eşleşen ilanların dörtte birinde bile geçmiyorsa (ayakkabı stil/renk kodları)
        if (type == "ad" && (top < 3 || top * 4 < eligible)) return emptyList()
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

    /** Adla aramada seri grupları: "Prima 4 numara" -> Aktif Bebek / Premium / Külot. Anahtar: aramada olmayan seri kelimeleri */
    private fun seriesGroups(): Map<Set<String>, List<Offer>> {
        val need = requiredWords(resolveQuery)
        if (need.isEmpty()) return emptyMap()
        val qMods = traitsOf(resolveQuery) + acceptedMods
        return offers.filter { o ->
            val t = o.title.lowercase(Locale("tr", "TR"))
            !isAccessory(t) && !hit(usedRe, t) && !hit(knockoffRe, t) &&
                need.all { hasWord(o.title, canonNorm(o.title), it) }
        }.groupBy { o -> traitsOf(o.title) + suffixTraits(resolveQuery, o.title) - qMods }
    }

    private fun showSeriesChooser(groups: List<Pair<Set<String>, List<Offer>>>, then: () -> Unit) {
        status.text = "Birden fazla seri bulundu, hangisini kastettin?"
        val brand = resolveQuery.split(" ").firstOrNull().orEmpty().replaceFirstChar { it.titlecase(Locale("tr", "TR")) }
        val items = groups.map { (k, list) ->
            "$brand ${k.sorted().joinToString(" ")}\n${list.minByOrNull { it.title.length }!!.title.take(80)}\n" +
                "${list.size} ilan · ${money(list.minOf { it.price })}'den başlıyor"
        } + "Hepsini göster (seri ayırmadan)"
        AlertDialog.Builder(this)
            .setTitle("Hangisini kastettin?")
            .setItems(items.toTypedArray<CharSequence>()) { _, which ->
                if (which < groups.size) {
                    acceptedMods = acceptedMods + groups[which].first
                    requiredSeries = groups[which].first
                    prefs.edit().putString("m:" + norm(userInput), acceptedMods.joinToString("|")).apply()
                    resolveLog.append("Seçilen seri: ").append(groups[which].first.joinToString(" ")).append("\n")
                } else {
                    acceptedMods = acceptedMods + groups.flatMap { it.first }
                    requiredSeries = emptySet()
                    resolveLog.append("Seri seçilmedi; hepsi gösteriliyor.\n")
                }
                then()
            }
            .setCancelable(false)
            .show()
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
                    continueWithoutIdentity()
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
        prefs.edit().remove("q:" + norm(userInput)).remove("n:" + norm(userInput)).remove("m:" + norm(userInput)).apply()
        input.setText(userInput)
        startTest()
    }

    // ---------- Eşleştirme ----------

    // Aynı şeyin farklı yazımları tek biçime iner (ataç = ataş)
    // Türkçe harfler tek biçime iner (Itfaiye = İtfaiye, Clınıcal = Clinical); yazım eşleri birleşir (ataç = ataş)
    private fun norm(s: String) = java.text.Normalizer.normalize(
        s.lowercase(Locale("tr", "TR"))
            .replace('ı', 'i').replace('ş', 's').replace('ç', 'c').replace('ğ', 'g').replace('ö', 'o').replace('ü', 'u'),
        java.text.Normalizer.Form.NFD)
        // Nescafé = Nescafe, Crème = Creme
        .replace(Regex("\\p{Mn}+"), "")
        .replace("atac", "atas").replace(Regex("[^a-z0-9]"), "")

    private fun classify() {
        queryAmount = amountOf(identity?.searchQuery ?: query)
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
            val sameBase = codes.filter { it.second == id.base || sameCode(it.second, id.base) }
            o.kind = when {
                isAccessory(t) -> Kind.ACCESSORY
                hit(usedRe, t) && (sameBase.isNotEmpty() || codes.any { sameCode(it.first, id.code) }) -> Kind.USED
                codes.any { it.first == id.code || sameCode(it.first, id.code) } -> { o.variant = id.label; Kind.MATCH }
                sameBase.any { it.first != it.second && !sameCode(it.first, id.code) } -> {
                    o.otherModel = sameBase.first { it.first != it.second && !sameCode(it.first, id.code) }.first.uppercase()
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
        Regex("(?<![\\p{L}])unisex(?![\\p{L}])").containsMatchIn(t) -> "Unisex"
        Regex("(?<![\\p{L}])(kadın|women|woman|bayan)(?![\\p{L}])").containsMatchIn(t) &&
            Regex("(?<![\\p{L}])(erkek|men|man)(?![\\p{L}])").containsMatchIn(t) -> "Erkek & Kadın"
        Regex("(?<![\\p{L}])(kadın|women|woman|bayan)(?![\\p{L}])").containsMatchIn(t) -> "Kadın"
        Regex("(?<![\\p{L}])(erkek|men|man)(?![\\p{L}])").containsMatchIn(t) -> "Erkek"
        else -> ""
    }

    private fun classifyByWords() {
        val tokens = query.lowercase(Locale("tr", "TR")).split(Regex("\\s+")).filter { it.isNotBlank() }
        // Model kodu: hem harf hem rakam içeren kelime (ör. qp2834)
        val modelTokens = codesIn(query).map { it.second }.distinct()
        val (specWords, specHard) = querySpec(query)
        val required = modelTokens.ifEmpty { specWords + specHard }
        val qMods = traitsOf(query) + acceptedMods
        val qColors = colorsIn(query)
        val qConc = concOf(query)
        val qAmt = queryAmount
        // Parfüm gibi isimli ürünlerde "muadil" taklit demektir: gösterilmez
        val namedProduct = qConc.isNotEmpty()

        // Model kodu dışındaki kelimeler (ör. philips, oneblade): kodu yazmayan ilanları yakalamak için
        val otherWords = tokens.filter { t -> !(t.any { it.isDigit() } && t.any { it.isLetter() }) && t.length >= 3 }
            .map { norm(it) }.filter { it !in colorWords && it !in softWords }
        // Muadil: marka (ilk kelime) farklı olabilir; ölçü şartları ve ürün türü kelimelerinden en az biri tutmalı
        val typeWords = specWords.drop(1)
        for (o in offers) {
            val t = o.title.lowercase(Locale("tr", "TR"))
            val nt = canonNorm(o.title)
            val titleModels = codesIn(o.title).map { it.first }.filter { c -> modelTokens.none { sameCode(c, it) } }
            val hasAll = required.isNotEmpty() && (
                if (modelTokens.isNotEmpty()) modelTokens.all { titleHasCode(o.title, it) }
                else required.all { hasOfferWord(o, nt, it, specWords.firstOrNull()) })
            // Numara/beden hiç yazmayan ilan: aynı ürün olabilir ("Prima Premium Care Ekonomik Paket 46 Adet")
            val sizeMissing = !hasAll && modelTokens.isEmpty() && specHard.any { it.matches(Regex("no[0-9]+")) } &&
                sizeNos(o.title).isEmpty() &&
                required.filter { !it.matches(Regex("no[0-9]+")) }.all { hasOfferWord(o, nt, it, specWords.firstOrNull()) }
            o.kind = when {
                isAccessory(t) -> Kind.ACCESSORY
                hasAll && hit(knockoffRe, t) -> Kind.OTHER
                // Yenilenmiş ilan da önce model kontrolünden geçer: "Yenilenmiş iPhone 16 Pro" farklı modeldir
                hasAll && hit(usedRe, t) -> matchOrVariant(o, t, qMods, qColors, qConc, null).let {
                    if (it == Kind.MATCH || it == Kind.POSSIBLE || it == Kind.OTHER_PACKAGE) Kind.USED else it
                }
                hasAll -> matchOrVariant(o, t, qMods, qColors, qConc, qAmt)
                // Numara yazmayan ilan da seri/tür kontrolünden geçer (ıslak havlu, başka seri)
                sizeMissing -> matchOrVariant(o, t, qMods, qColors, qConc, null).let {
                    if (it == Kind.MATCH || it == Kind.POSSIBLE) { o.otherModel = "numara/beden yazmıyor"; Kind.POSSIBLE } else it
                }
                !bookMode && !namedProduct && modelTokens.isEmpty() && typeWords.isNotEmpty() && specWords.isNotEmpty() &&
                    !nt.contains(specWords.first()) && specHard.all { hasWord(o.title, nt, it) } &&
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
                codesIn(o.title).firstOrNull { !it.first.endsWith("/a") }?.let { o.variant = it.first.uppercase() }
                if (o.variant.isEmpty()) o.variant = genderOf(t)
            }
            if (o.kind == Kind.MATCH) {
                for (m in modelTokens) {
                    Regex("$m\\s*/\\s*([0-9]{2,3})").find(t)?.let { o.variant = "${m.uppercase()}/${it.groupValues[1]}" }
                }
            }
        }
    }

    /** Kelime ilanda var mı; marka için Amazon'un ad üstündeki marka satırına da bakılır */
    private fun hasOfferWord(o: Offer, nt: String, w: String, brand: String?): Boolean =
        hasWord(o.title, nt, w) || (w == brand && o.brandHint.isNotEmpty() && canonNorm(o.brandHint).contains(w))

    /** Aranan kelimelerin hepsi var: aynı ürün mü, başka seri/renk/miktar mı? */
    private fun matchOrVariant(o: Offer, t: String, qMods: Set<String>, qColors: Set<String>, qConc: Set<String>, qAmt: Amount?): Kind {
        val tMods = traitsOf(o.title) + suffixTraits(query, o.title)
        val extra = tMods - qMods
        val missing = traitsOf(query) - tMods
        if (extra.isNotEmpty() || missing.isNotEmpty()) {
            o.otherModel = if (extra.isNotEmpty()) extra.sorted().joinToString(" ") else "${missing.sorted().joinToString(" ")} değil"
            return Kind.DIFFERENT_MODEL
        }
        // Seçilen seriyi ("Aktif Bebek") yazmayan ilan: aynı seri olabilir, kontrol edilmeli
        if (requiredSeries.isNotEmpty() && (requiredSeries intersect tMods).isEmpty()) {
            o.otherModel = "seri yazmıyor"
            return Kind.POSSIBLE
        }
        val tConc = concOf(o.title)
        if (qConc.isNotEmpty() && tConc.isNotEmpty() && (tConc intersect qConc).isEmpty()) {
            o.otherModel = tConc.joinToString("/") { it.uppercase() }
            return Kind.DIFFERENT_MODEL
        }
        val tColors = colorsIn(o.title)
        // Aranan renge başka renk eklenmişse ("Siyah-Beyaz", "Beyaz-Turuncu") farklı renktir
        if (qColors.isNotEmpty() && tColors.isNotEmpty() && (tColors - qColors).isNotEmpty()) {
            o.otherModel = "renk: " + tColors.joinToString("-")
            return Kind.DIFFERENT_MODEL
        }
        if (isSet(o)) {
            o.otherModel = "set: başka ürünle birlikte"
            return Kind.OTHER_PACKAGE
        }
        if (qAmt != null) {
            val a = amountOf(o.title, qAmt.unit)
            return when {
                a == null -> { o.otherModel = "miktar yazmıyor"; Kind.POSSIBLE }
                a.unit != qAmt.unit -> { o.otherModel = amountLabel(a); Kind.POSSIBLE }
                Math.abs(a.per - qAmt.per) / qAmt.per < 0.03 && a.mult <= 1 -> Kind.MATCH
                else -> { o.otherModel = amountLabel(a); Kind.OTHER_PACKAGE }
            }
        }
        return Kind.MATCH
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
        // Yurtdışı sürümü / ithal ürün yazan ilanlar
        val abroad = Regex("global vers|international vers|global sürüm|uluslararası vers|yurt ?dışı|ithal ürün|avrupa versiyon|eu version")
        // "Menşei: Almanya" ya da "Almanya Menşeli"; "Türkiye Menşeli" yerlidir
        val origin1 = Regex("menşei(?![\\p{L}])\\s*:?\\s*([\\p{L}]+)")
        val origin2 = Regex("([\\p{L}]+)\\s+menşeli")
        for (o in offers) {
            if (o.origin.isNotEmpty()) continue
            val t = o.title.lowercase(Locale("tr", "TR"))
            val country = (origin1.find(t) ?: origin2.find(t))?.groupValues?.get(1)
            o.origin = when {
                hit(abroad, t) -> "Yurtdışı sürümü — garanti ve içerik farklı olabilir"
                country != null && !country.startsWith("türk") -> "İthal ürün (menşei $country)"
                else -> ""
            }
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

    /** Paket sayısı: miktardan önce "2 x 200 gr" ya da hemen sonra "x 6", "2 adet", "3'lü", "2 paket".
     *  "10 Kutu 1.000 Adet" gibi toplam yazılan ilanlarda tekrar çarpılmaz. */
    private fun packMultiplier(t: String, first: Int, last: Int, countUnit: Boolean = false): Double {
        // Önce: "2 x 200 gr", "100 x 2 gr"
        val before = Regex("(?<![0-9.,])([0-9]{1,3})\\s*x\\s*$").find(t.substring(0, first))
            ?.groupValues?.get(1)?.toDoubleOrNull()?.takeIf { it >= 2 } ?: 1.0
        // Sonra: "x 6", "2 adet", "3'lü", "100'lü", "2 paket"
        val after = t.substring(minOf(t.length, last + 1), minOf(t.length, last + 31)).substringBefore('+').substringBefore('&')
        val m = Regex("x\\s*([0-9]{1,3})(?![0-9])|(?<![0-9.,])([0-9]{1,3})\\s*(?:adet|kutu|paket|koli)|(?<![0-9.,])([0-9]{1,3})\\s*['’]?\\s?l[iıuü](?![\\p{L}])")
            .findAll(after)
            // Adetle satılanda "216 Adet 4 Numara 4'lü": toplam zaten yazılı, "4'lü" ile tekrar çarpılmaz
            .firstOrNull { !(countUnit && it.groupValues[3].isNotEmpty()) }
        val n = m?.let { (it.groupValues[1].ifEmpty { it.groupValues[2] }.ifEmpty { it.groupValues[3] }).toDoubleOrNull() } ?: 1.0
        return before * (if (n >= 2) n else 1.0)
    }

    private fun numberOf(s: String) = s.replace(".", "").replace(",", ".").toDoubleOrNull() ?: 0.0

    /** Birim fiyat: kg/litre/adet başına. Başlıkta miktar yoksa ya da setse null */
    private fun unitPrice(o: Offer): Pair<Double, String>? {
        if (isSet(o)) return null
        val a = amountOf(o.title, queryAmount?.unit) ?: return null
        val total = a.total
        if (total <= 0) return null
        return when (a.unit) {
            "g" -> o.price / (total / 1000) to "kg"
            "ml" -> o.price / (total / 1000) to "L"
            else -> o.price / total to "adet"
        }
    }

    private fun unitText(o: Offer): String = unitPrice(o)?.let { (v, u) ->
        "≈ ${if (v < 10) tl.format(v) else tlWhole.format(v)} TL/$u"
    } ?: ""

    private fun kindLabel(o: Offer) = when (o.kind) {
        Kind.ACCESSORY -> "aksesuar"
        Kind.DIFFERENT_MODEL -> "farklı model/seri (${o.otherModel})"
        Kind.OTHER -> "farklı ürün"
        Kind.POSSIBLE -> "olası eşleşme (${o.otherModel.ifEmpty { "paket/model kodu yazmıyor" }})"
        Kind.OTHER_PACKAGE -> "başka paket/miktar (${o.otherModel})"
        Kind.USED -> "yenilenmiş / ikinci el / teşhir"
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
        // Model koduyla hiçbir sitede bulunamadıysa: aynı serinin başka yılları (QE55Q60D -> "Samsung 55Q60": Q60C, Q60F...)
        if (identity == null && resolveType == "kod" && !familyTried &&
            offers.none { it.kind == Kind.MATCH || it.kind == Kind.POSSIBLE }) {
            familyTried = true
            val code = codesIn(query).firstOrNull()?.first
            val fam = code?.let { codeCore(it).dropLastWhile { c -> c.isLetter() } }
            if (code != null && fam != null && fam.length >= 4 && fam != code && fam.any { it.isLetter() }) {
                val brand = query.split(" ").firstOrNull { w -> w.isNotBlank() && codesIn(w).isEmpty() }.orEmpty()
                val fq = "$brand ${fam.uppercase()}".trim()
                resolveLog.append("Model sitelerde bulunamadı; aynı seri aranıyor: ").append(fq).append("\n")
                runSites(sites, fq, "Model bulunamadı, aynı seri aranıyor", onEach = { render(final = false) }) { startDetails() }
                return
            }
        }
        detailQueue.clear()
        // Ürün sayfası (satıcı + garanti) sadece model kodlu ya da 1.000 TL üstü eşleşmelerde, hepsi aynı anda açılır
        detailQueue.addAll(
            offers.filter { it.kind == Kind.MATCH && !bookMode && !isSet(it) && (codesIn(it.title).isNotEmpty() || it.price >= 1000) }
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
        val t0 = android.os.SystemClock.elapsedRealtime()
        var lastLen = -1
        var steady = 0
        loadAndPoll(v, o.link, detailJs, maxMs = 9000, scroll = false, ready = { d, n ->
            // Satıcı kutusu genelde ilk ekranın hemen altında: sayfa bir kez, o yüksekliğe kaydırılır
            if (n == 2) v.evaluateJavascript("window.scrollTo(0, 1200);", null)
            val len = d?.optInt("len") ?: 0
            if (len > 0 && Math.abs(len - lastLen) < 200) steady++ else steady = 0
            lastLen = len
            d != null && n >= 2 && (d.optString("seller").isNotEmpty() || (steady >= 2 && len >= 3000))
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
                o.detailPage = d.optString("page") + " | " + elapsedText(android.os.SystemClock.elapsedRealtime() - t0)
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
        progress.visibility = View.GONE
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
        updateSiteChips()
        val y = resultsScroll.scrollY
        results.removeAllViews()
        val id = identity

        // Aranan ürün satırı: yanlışsa dokunup yeniden tanıt
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }
        bar.addView(label(if (id != null) "Ürün: ${id.label}" else "Aranan: $query", 13f, muted).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (!running) bar.addView(label("Yeniden tanı", 13f, gold, true).apply {
            setPadding(dp(10), dp(4), 0, dp(4))
            setOnClickListener { forgetIdentity() }
        })
        results.addView(bar)

        val matches = offers.filter { it.kind == Kind.MATCH }.sortedBy { it.price }
        val others = offers.filter { it.kind != Kind.MATCH }.sortedBy { it.price }
        markUnitPrices(matches)

        val took = elapsedText(android.os.SystemClock.elapsedRealtime() - startedAt)
        if (final) {
            status.text = (if (matches.isEmpty()) "Aynı ürün bulunamadı; benzerleri aşağıda"
            else "${matches.size} teklif · ${siteSummary.count { it.contains("ürün okundu") }} site") + " · $took"
        } else if (matches.isNotEmpty()) {
            status.text = "Taranıyor · şimdilik en ucuz ${money(matches.first().price)} (${matches.first().site}) · $took"
        }

        // Filtre (site, kelime) sadece gösterimi etkiler; rapor ve hesaplar bütün ilanlarla yapılır
        filterBar.visibility = if (offers.isNotEmpty()) View.VISIBLE else View.GONE
        updateSiteToggles()
        val fm = matches.filter { shown(it) }
        val fo = others.filter { shown(it) }
        if (fm.size < matches.size || fo.size < others.size) {
            results.addView(label("Filtre: ${fm.size + fo.size}/${matches.size + others.size} ilan gösteriliyor", 12f, gold, true)
                .apply { setPadding(dp(6), dp(2), dp(6), dp(4)) })
        }

        // En ucuz teklif kutusu
        if (fm.isNotEmpty()) heroCard(fm)
        if (familyTried && matches.isEmpty()) {
            noticeCard("Bu model sitelerde bulunamadı (satıştan kalkmış olabilir). Aşağıda aynı serinin başka yılları ve modelleri var.",
                orange, orangeBg)
        }
        // Aranan miktarda ilan yoksa: aynı ürünün birim fiyatı en ucuz paketi
        bestOtherPackage(fm, fo)?.let { b ->
            noticeCard("Aranan miktarda ilan yok. Birim fiyatı en ucuz paket:", green, greenBg)
            offerRow(b, true)
        }
        // Aynı model farklı paketlerle satılıyorsa uyar
        val variants = fm.map { it.variant }.filter { it.isNotEmpty() && it != "Kadın" && it != "Erkek" && it != "Unisex" }.distinct()
        if (variants.size > 1) {
            noticeCard("Farklı paketler var (${variants.joinToString(", ")}). İçerik ya da garanti farklı olabilir.", orange, orangeBg)
        }

        val cheapest = fm.firstOrNull()
        if (fm.isNotEmpty()) section("match", "Aynı ürün · " + (if (sortMode == 0) "ucuzdan pahalıya" else sortNames[sortMode].lowercase(Locale("tr", "TR"))),
            fm.size, green, true) {
            applySort(fm).forEach { o -> offerRow(o, o === cheapest) }
        }
        // Eşleşen ürün varsa muadil ancak birim fiyatı ondan ucuzsa anlamlıdır (pahalı taklitler gösterilmez)
        val bestMatchUnit = matches.filter { !isSet(it) }.mapNotNull { unitPrice(it)?.first }.minOrNull()
        val muadils = collapse(fo.filter { it.kind == Kind.MUADIL })
            .filter { m -> bestMatchUnit == null || (unitPrice(m)?.first ?: Double.MAX_VALUE) < bestMatchUnit }
            .sortedBy { unitPrice(it)?.first ?: Double.MAX_VALUE }
        if (muadils.isNotEmpty()) section("muadil", "Daha ucuz muadiller", muadils.size, green, true) {
            applySort(muadils).forEach { offerRow(it, false) }
        }
        val otherPackages = sortedByUnit(fo.filter { it.kind == Kind.OTHER_PACKAGE })
        if (otherPackages.isNotEmpty()) section("pack", "Diğer paketler · birim fiyata göre", otherPackages.size, ink, matches.isEmpty()) {
            applySort(otherPackages).forEach { offerRow(it, false) }
        }
        val possibles = fo.filter { it.kind == Kind.POSSIBLE }
        if (possibles.isNotEmpty()) section("possible", "Olası eşleşmeler · kontrol et", possibles.size, orange, matches.isEmpty()) {
            applySort(possibles).forEach { offerRow(it, false) }
        }
        val suspicious = fo.filter { it.kind == Kind.SUSPICIOUS }
        if (suspicious.isNotEmpty()) section("suspicious", "Şüpheli ucuz ilanlar", suspicious.size, red, false) {
            applySort(suspicious).forEach { offerRow(it, false) }
        }
        val used = fo.filter { it.kind == Kind.USED }
        if (used.isNotEmpty()) section("used", "Yenilenmiş / ikinci el / teşhir", used.size, ink, false) {
            applySort(used).forEach { offerRow(it, false) }
        }
        val diffModels = fo.filter { it.kind == Kind.DIFFERENT_MODEL }
        if (diffModels.isNotEmpty()) section("diff", "Farklı model / seri", diffModels.size, ink, familyTried && matches.isEmpty()) {
            applySort(diffModels).forEach { offerRow(it, false) }
        }
        val rest = fo.filter {
            it.kind != Kind.DIFFERENT_MODEL && it.kind != Kind.POSSIBLE && it.kind != Kind.SUSPICIOUS &&
                it.kind != Kind.OTHER_PACKAGE && it.kind != Kind.MUADIL && it.kind != Kind.USED
        }
        // Hiçbir şey eşleşmediyse en azından bu liste açık gelsin (filtreyle aranıyorsa da)
        val nothingElse = fm.isEmpty() && possibles.isEmpty() && otherPackages.isEmpty() && diffModels.isEmpty() && muadils.isEmpty()
        if (rest.isNotEmpty()) section("rest", "Aksesuar / farklı ürün", collapse(rest).size, muted,
            nothingElse || filterText.isNotBlank()) {
            applySort(collapse(rest).sortedBy { it.price }).forEach { offerRow(it, false) }
        }
        if (siteSummary.isNotEmpty()) section("sites", "Site ayrıntıları", siteSummary.size, muted, false) {
            siteSummary.forEach { results.addView(label("• $it", 12f, muted).apply { setPadding(dp(6), dp(3), dp(6), dp(3)) }) }
        }

        buildReport(matches, others)
        resultsScroll.post { resultsScroll.scrollTo(0, y) }
    }

    /** Filtreye uyuyor mu: site gizli değil ve yazılan kelimelerin hepsi adda/satıcıda geçiyor */
    private fun shown(o: Offer): Boolean {
        if (o.site in hiddenSites) return false
        val words = filterText.trim().split(Regex("\\s+")).map { norm(it) }.filter { it.isNotEmpty() }
        if (words.isEmpty()) return true
        val nt = canonNorm(o.title + " " + o.seller + " " + o.site)
        return words.all { nt.contains(it) }
    }

    private fun ratingNum(o: Offer) = o.rating.replace(',', '.').toDoubleOrNull() ?: 0.0

    /** Seçilen sıralama; "Önerilen" bölümün kendi sırasını korur */
    private fun applySort(list: List<Offer>): List<Offer> = when (sortMode) {
        1 -> list.sortedBy { it.price }
        2 -> list.sortedByDescending { it.price }
        3 -> list.sortedWith(compareBy<Offer> { unitPrice(it)?.first ?: Double.MAX_VALUE }.thenBy { it.price })
        4 -> list.sortedWith(compareByDescending<Offer> { ratingNum(it) }.thenByDescending { countNum(it.ratingCount) })
        5 -> list.sortedWith(compareByDescending<Offer> { countNum(it.ratingCount) }.thenByDescending { ratingNum(it) })
        else -> list
    }

    /** Site çipleri: dokununca o sitenin ilanları gizlenir/gösterilir */
    private fun updateSiteToggles() {
        siteToggles.removeAllViews()
        for (s in sites) {
            val n = offers.count { it.site == s.name }
            if (n == 0) continue
            val hidden = s.name in hiddenSites
            val p = pill("${s.name} $n", if (hidden) muted else Color.WHITE,
                if (hidden) greyBg else (siteColors[s.name] ?: ink), bold = true, size = 12f).apply {
                setPadding(dp(10), dp(5), dp(10), dp(5))
                if (hidden) paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                setOnClickListener {
                    if (hidden) hiddenSites.remove(s.name) else hiddenSites.add(s.name)
                    render(final = finished)
                }
            }
            siteToggles.addView(p, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(6) })
        }
    }

    /** En üstte büyük kutu: en ucuz aynı ürün */
    private fun heroCard(matches: List<Offer>) {
        val o = matches.first()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(Color.WHITE, 18, gold, 2)
            elevation = dp(2).toFloat()
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setOnClickListener { openLink(o) }
        }
        card.addView(photo(o, 92), LinearLayout.LayoutParams(dp(92), dp(92)).apply { marginEnd = dp(14) })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(label("EN UCUZ", 11f, gold, true))
        col.addView(label(money(o.price), 28f, ink, true))
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(sitePill(o))
            if (o.seller.isNotEmpty()) addView(label("  ${o.seller}", 12f, muted).apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            })
        })
        col.addView(label(o.title, 13f, ink).apply {
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(6), 0, 0)
        })
        val top = matches.last().price
        if (matches.size > 1) col.addView(label(
            "${matches.size} teklif · en pahalısı ${money(top)}" + if (top > o.price) " (+${money(top - o.price)})" else "",
            12f, muted
        ).apply { setPadding(0, dp(4), 0, 0) })
        card.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        results.addView(card, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(dp(2), dp(4), dp(2), dp(6)) })
    }

    private fun noticeCard(text: String, fg: Int, bg: Int) {
        results.addView(label(text, 13f, fg, true).apply {
            background = rounded(bg, 12)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(dp(2), dp(6), dp(2), dp(2)) })
    }

    /** Açılıp kapanan bölüm başlığı; açıksa içeriği ekler */
    private fun section(key: String, title: String, count: Int, color: Int, defaultOpen: Boolean, body: () -> Unit) {
        val open = expanded[key] ?: defaultOpen
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(16), dp(6), dp(6))
            setOnClickListener {
                expanded[key] = !open
                render(final = finished)
            }
        }
        head.addView(label(title, 15f, color, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(pill("$count", muted, greyBg, bold = true))
        head.addView(label(if (open) "  ▾" else "  ▸", 16f, muted, true))
        results.addView(head)
        if (open) body()
    }

    private fun openLink(o: Offer) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(o.link))) } catch (e: Exception) {}
    }

    private fun sitePill(o: Offer) = pill(o.site, Color.WHITE, siteColors[o.site] ?: ink, bold = true)

    private fun photo(o: Offer, size: Int) = ImageView(this).apply {
        background = rounded(Color.WHITE, 12, line)
        setPadding(dp(4), dp(4), dp(4), dp(4))
        scaleType = ImageView.ScaleType.FIT_CENTER
        if (o.image.isNotEmpty()) loadImage(o.image, this)
        minimumWidth = dp(size)
        minimumHeight = dp(size)
    }

    /** Eşleşenlerde: birim fiyatı en uygun olanı ve medyanın 3 katından pahalı olanları işaretle */
    /** "Deodorant 150 ml + Stick 45 ml" gibi birden fazla ürün içeren set */
    private fun isSet(o: Offer): Boolean {
        val t = o.title.lowercase(Locale("tr", "TR"))
        // "Deodorant 150 ml + Stick 45 ml": artının iki yanında ürün var. "1+1" gibi kampanya yazıları set değil.
        // "+ Coffee Mate", "+ 1 Adet Termos Hediye"; "1+1" kampanyası set değil
        if (Regex("(?<![0-9])\\+\\s*(?!cellular|lte)(?:[0-9]+\\s*['’]?\\s*(?:l[iıuü]\\s*|adet\\s*)?)?[\\p{L}]").containsMatchIn(t)) return true
        // "84 Adet Bez & Külot Bez 44 Adet": & ile miktarlı ikinci bir ürün
        if (Regex("&\\s*[\\p{L}][^&]*?[0-9]+\\s*(adet|ml|gr|g|kg)(?![\\p{L}])").containsMatchIn(t)) return true
        // "Setli ürün", "hediyeli" (aranan ürün zaten set değilse)
        if (!norm(query).contains("set") && Regex("hediye|setli").containsMatchIn(t)) return true
        // "200 gr x2 ve Nestle Coffee Mate 500 gr": "ve"den sonra miktarıyla başka bir ürün
        return Regex("(?<![\\p{L}])ve\\s+[\\p{L}]+(?:\\s+[\\p{L}]+){0,3}\\s+[0-9]+(?:[.,][0-9]+)?\\s*(ml|gr|g|kg|lt|l)(?![\\p{L}])").containsMatchIn(t)
    }

    private fun markUnitPrices(matches: List<Offer>) {
        matches.filter { isSet(it) }.forEach { it.note = "⚠ Set: başka ürünlerle birlikte satılıyor" }
        val withUnit = matches.filter { !isSet(it) }.mapNotNull { o -> unitPrice(o)?.let { o to it.first } }
        // Karşılaştırma ölçüsü: en çok değerlendirilen ilan (çok satan gerçek fiyat); yoksa ortanca.
        // (Pahalı ilanlar çoksa ortanca yukarı kayar ve uyarı kaçardı.)
        val refOffer = matches.filter { !isSet(it) && countNum(it.ratingCount) >= 30 }.maxByOrNull { countNum(it.ratingCount) }
        // Birim fiyatı olmayan ürünler (telefon, ayakkabı): fiyatı diğerlerinin 2 katından fazlaysa uyar
        val plain = matches.filter { !isSet(it) }.map { it.price }.sorted()
        if (plain.size >= 3) {
            val med = refOffer?.price ?: plain[plain.size / 2]
            matches.filter { !isSet(it) && unitPrice(it) == null && it.price > med * 2 }.forEach {
                it.note = "⚠ fiyatı diğerlerinin ${String.format(Locale("tr", "TR"), "%.1f", it.price / med)} katı"
            }
        }
        if (withUnit.size < 2) return
        val sorted = withUnit.map { it.second }.sorted()
        val median = refOffer?.let { unitPrice(it)?.first } ?: sorted[sorted.size / 2]
        val firstUnit = unitPrice(matches.first())?.first ?: Double.MAX_VALUE
        val best = withUnit.filter { it.second < firstUnit * 0.99 }.minByOrNull { it.second }?.first
        withUnit.forEach { (o, u) ->
            o.note = when {
                o === best -> "★ birim fiyatı en uygun"
                u > median * 2 -> "⚠ birim fiyatı diğerlerinin ${String.format(Locale("tr", "TR"), "%.1f", u / median)} katı"
                else -> ""
            }
        }
    }

    private fun bestOtherPackage(matches: List<Offer>, others: List<Offer>): Offer? =
        if (matches.isNotEmpty()) null
        else others.filter { it.kind == Kind.OTHER_PACKAGE && !isSet(it) && unitPrice(it) != null }
            .minByOrNull { unitPrice(it)!!.first }

    /** Birim fiyatı olanlar birim fiyata göre önce, olmayanlar fiyata göre sonra */
    private fun sortedByUnit(list: List<Offer>) =
        list.sortedWith(compareBy<Offer> { unitPrice(it)?.first ?: Double.MAX_VALUE }.thenBy { it.price })

    /** Aynı ilanı satan birçok satıcıyı tek satıra indirir: en ucuzu kalır, diğerleri sayılır */
    private fun collapse(list: List<Offer>): List<Offer> =
        list.groupBy { norm(it.title) }.values.map { g ->
            g.minByOrNull { it.price }!!.also { it.dupCount = g.size - 1 }
        }

    private fun offerRow(o: Offer, best: Boolean) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(Color.WHITE, 14, if (best) gold else line, if (best) 2 else 1)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener { openLink(o) }
        }
        card.addView(photo(o, 64), LinearLayout.LayoutParams(dp(64), dp(64)).apply { marginEnd = dp(12) })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // Site ve puan
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(sitePill(o))
        if (o.dupCount > 0) top.addView(label("  +${o.dupCount} satıcı", 11f, muted))
        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        if (o.rating.isNotEmpty()) top.addView(label(
            "★ ${o.rating}" + if (o.ratingCount.isNotEmpty()) " (${countText(o.ratingCount)})" else "", 12f, muted))
        col.addView(top)

        col.addView(label(o.title.ifEmpty { "(ad okunamadı)" }, 14f, ink).apply {
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(6), 0, dp(4))
        })

        // Fiyat
        val priceRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }
        priceRow.addView(label(money(o.price), 18f, if (best) green else ink, true))
        if (o.basket) priceRow.addView(label("  sepette", 12f, muted))
        if (o.listPrice > o.price) priceRow.addView(label("  ${money(o.listPrice)}", 12f, muted).apply {
            paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        })
        col.addView(priceRow)

        // Etiketler
        val chips = FlowLayout(this, dp(6)).apply { setPadding(0, dp(6), 0, 0) }
        unitText(o).takeIf { it.isNotEmpty() }?.let { chips.addView(pill(it.removePrefix("≈ "), ink, greyBg)) }
        when {
            o.kind == Kind.MATCH && o.variant.isNotEmpty() -> chips.addView(pill(o.variant, ink, greyBg))
            o.kind != Kind.MATCH && o.kind != Kind.MUADIL -> chips.addView(pill(shortKind(o), orange, orangeBg))
        }
        if (o.note.isNotEmpty()) chips.addView(pill(o.note, if (o.note.startsWith("★")) green else red,
            if (o.note.startsWith("★")) greenBg else redBg, bold = true))
        if (o.exact) chips.addView(pill("Barkodla birebir", green, greenBg, bold = true))
        if (o.badge.isNotEmpty()) chips.addView(pill("✓ ${o.badge}", green, greenBg))
        if (o.origin.isNotEmpty()) chips.addView(pill(o.origin.substringBefore(" —"),
            if (o.origin.startsWith("Türkiye")) green else red, if (o.origin.startsWith("Türkiye")) greenBg else redBg))
        warrantyBadge(o)?.let { (txt, col2) -> chips.addView(pill(txt.substringBefore(" —"), col2,
            if (col2 == green) greenBg else if (col2 == red) redBg else greyBg)) }
        if (chips.childCount > 0) col.addView(chips)

        if (o.seller.isNotEmpty()) col.addView(label(
            "Satıcı: ${o.seller}" + if (o.sellerScore.isNotEmpty()) " · ${o.sellerScore}" else "", 12f, muted
        ).apply { setPadding(0, dp(6), 0, 0) })

        card.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        results.addView(card, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(dp(2), dp(4), dp(2), dp(4)) })
    }

    /** Kartta kısa açıklama: "60 ml", "Pro", "olası: miktar yazmıyor" */
    private fun shortKind(o: Offer) = when (o.kind) {
        Kind.OTHER_PACKAGE -> o.otherModel.ifEmpty { "başka paket" }
        Kind.DIFFERENT_MODEL -> o.otherModel.ifEmpty { "farklı model" }
        Kind.POSSIBLE -> o.otherModel.ifEmpty { "kontrol et" }
        Kind.SUSPICIOUS -> "çok ucuz — taklit ya da yanlış ilan olabilir"
        Kind.USED -> "yenilenmiş / ikinci el"
        Kind.ACCESSORY -> "aksesuar"
        else -> "farklı ürün"
    }

    // ---------- Rapor ----------

    private fun buildReport(matches: List<Offer>, others: List<Offer>) {
        report.setLength(0)
        val total = android.os.SystemClock.elapsedRealtime() - startedAt
        report.append("FİYAT AVCISI · DENEME 36\nGiriş: ").append(userInput)
            .append("\nDurum: ").append(if (finished) "tamamlandı" else "HÂLÂ SÜRÜYORDU (rapor erken kopyalandı)")
            .append("\nSüre: ").append(elapsedText(total))
            .append(if (resolvedAt > 0) " (tanıma ${elapsedText(resolvedAt - startedAt)})" else "")
            .append("\nÜrün: ").append(identity?.label ?: "(kimlik yok)")
            .append("\nSitelere sorulan: ").append(query).append("\n\nKİMLİK ADIMI\n").append(resolveLog).append("\n")
        report.append("SİTE DURUMU\n")
        siteSummary.forEach { report.append("- ").append(it).append("\n") }

        report.append("\nEŞLEŞENLER (ucuzdan pahalıya)\n")
        if (matches.isEmpty()) report.append("(yok)\n")
        bestOtherPackage(matches, others)?.let { b ->
            report.append("ÖZET: aranan miktarda ilan yok; birim fiyatı en ucuz paket: ").append(money(b.price)).append(" | ")
                .append(b.site).append(" | ").append(unitText(b)).append("\n  ").append(b.title).append("\n")
        }
        matches.forEach { o ->
            report.append("- ").append(money(o.price))
            if (o.basket) report.append(" sepette")
            if (o.listPrice > o.price) report.append(" (liste ").append(money(o.listPrice)).append(")")
            report.append(" | ").append(o.site)
            if (o.variant.isNotEmpty()) report.append(if (o.variant == "Kadın" || o.variant == "Erkek" || o.variant == "Unisex") " | versiyon " else " | paket ").append(o.variant)
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
        val bestUnit = matches.filter { !isSet(it) }.mapNotNull { unitPrice(it)?.first }.minOrNull()
        val mu = collapse(others.filter { it.kind == Kind.MUADIL })
            .filter { m -> bestUnit == null || (unitPrice(m)?.first ?: Double.MAX_VALUE) < bestUnit }
            .sortedBy { unitPrice(it)?.first ?: Double.MAX_VALUE }
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

        report.append("\nOLASI EŞLEŞMELER (kod ya da miktar yazmıyor)\n")
        val possibles = others.filter { it.kind == Kind.POSSIBLE }
        if (possibles.isEmpty()) report.append("(yok)\n")
        possibles.forEach { o ->
            report.append("- ").append(money(o.price)).append(" | ").append(o.site)
                .append(if (o.otherModel.isNotEmpty()) " | ${o.otherModel}" else "").append("\n  ").append(o.title).append("\n")
        }

        report.append("\nDİĞER PAKETLER / MİKTARLAR (birim fiyata göre)\n")
        val op = sortedByUnit(others.filter { it.kind == Kind.OTHER_PACKAGE })
        if (op.isEmpty()) report.append("(yok)\n")
        op.forEach { o ->
            report.append("- ").append(money(o.price)).append(" | ").append(o.site).append(" | ").append(o.otherModel)
            unitText(o).takeIf { it.isNotEmpty() }?.let { report.append(" | ").append(it) }
            report.append("\n  ").append(o.title).append("\n")
        }

        report.append("\nYENİLENMİŞ / İKİNCİ EL / TEŞHİR\n")
        val us = others.filter { it.kind == Kind.USED }
        if (us.isEmpty()) report.append("(yok)\n")
        us.forEach { o -> report.append("- ").append(money(o.price)).append(" | ").append(o.site).append("\n  ").append(o.title).append("\n") }

        report.append("\nFARKLI MODEL / SERİ\n")
        val dm = others.filter { it.kind == Kind.DIFFERENT_MODEL }
        if (dm.isEmpty()) report.append("(yok)\n")
        dm.forEach { o ->
            report.append("- ").append(money(o.price)).append(" | ").append(o.site).append(" | ").append(o.otherModel)
                .append("\n  ").append(o.title).append("\n")
        }

        report.append("\nAKSESUAR / FARKLI ÜRÜN\n")
        others.filter { it.kind == Kind.ACCESSORY || it.kind == Kind.OTHER }.take(20).forEach { o ->
            report.append("- ").append(money(o.price)).append(" | ").append(o.site).append(" | ")
                .append(kindLabel(o)).append("\n  ").append(o.title).append("\n")
        }

        // Okuyucuyu iyileştirmem için: her siteden ilk 2 kartın ham metni
        report.append("\nHAM KART ÖRNEKLERİ\n")
        sites.forEach { s ->
            offers.filter { it.site == s.name }.take(2).forEach { o ->
                report.append("[").append(s.name).append("] ad: ").append(o.title).append(" || ").append(o.raw).append("\n")
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
