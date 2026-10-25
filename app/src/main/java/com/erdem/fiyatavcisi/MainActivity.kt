package com.erdem.fiyatavcisi

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
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
    /** cats: sitenin tarandığı kategoriler (boş = hepsi). Market zinciri ayakkabı aramasında taranmaz. */
    private data class Site(val name: String, val linkPattern: String, val cats: Set<String> = emptySet(), val url: (String) -> String)

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
        var memberPrice: Double = 0.0,   // Plus / Premium / Prime üyelere özel fiyat
        var memberName: String = "",
        var shipFee: Double = -1.0,      // 0 = kargo bedava, -1 = bilinmiyor
        var freeOver: Double = 0.0,      // bu tutarın üzeri kargo bedava
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
        Site("MediaMarkt", "/product/", setOf("Elektronik", "Diğer")) { "https://www.mediamarkt.com.tr/tr/search.html?query=$it" },
        Site("Teknosa", "-p-[0-9]", setOf("Elektronik", "Diğer")) { "https://www.teknosa.com/arama/?s=$it" },
        Site("N11", "urun[.]n11[.]com|n11[.]com/urun/") { "https://www.n11.com/arama?q=$it" },
        Site("Pazarama", "pazarama[.]com/[^/?#]+-p-[A-Za-z0-9]+") { "https://www.pazarama.com/arama?q=$it" },
        // Market zincirleri
        Site("Migros", "migros[.]com[.]tr/[^/?#]+-p-[0-9a-f]+", setOf("Market", "Bebek", "Kozmetik", "Diğer")) { "https://www.migros.com.tr/arama?q=$it" },
        Site("CarrefourSA", "carrefoursa[.]com/[^/?#]+-p-[0-9]{5,}", setOf("Market", "Bebek", "Kozmetik", "Diğer")) { "https://www.carrefoursa.com/search/?text=$it" },
        Site("A101", "a101[.]com[.]tr/.+_p-[0-9]+", setOf("Market", "Bebek", "Kozmetik", "Diğer")) { "https://www.a101.com.tr/arama?k=$it" },
        Site("Şok", "sokmarket[.]com[.]tr/[^/?#]+-p-[0-9]+", setOf("Market", "Bebek", "Kozmetik", "Diğer")) { "https://www.sokmarket.com.tr/arama?q=$it" },
        // Elektronik
        Site("Vatan", "vatanbilgisayar[.]com/[a-z0-9-]+[.]html", setOf("Elektronik")) { "https://www.vatanbilgisayar.com/arama/$it/" },
        // Giyim, ayakkabı, spor
        Site("Boyner", "boyner[.]com[.]tr/[^/?#]+-p-[0-9]+", setOf("Giyim", "Kozmetik")) { "https://www.boyner.com.tr/search?q=$it" },
        Site("Flo", "flo[.]com[.]tr/(?:urun/)?[^/?#]+-[0-9]{6,}", setOf("Giyim")) { "https://www.flo.com.tr/arama?q=$it" },
        Site("Decathlon", "decathlon[.]com[.]tr/p/.+R-p-[0-9]+", setOf("Giyim")) { "https://www.decathlon.com.tr/search?Ntt=$it" },
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

    // Tema renkleri (açık / koyu), applyTheme() ayarlar. "gold" vurgu rengidir (C temasında yeşil).
    private var dark = false
    private var gold = 0
    private var accentOn = 0
    private var brand = 0
    private var ink = 0
    private var paper = 0
    private var surface = 0
    private var muted = 0
    private var hintColor = 0
    private var green = 0
    private var red = 0
    private var orange = 0
    private var line = 0
    private var greenBg = 0
    private var redBg = 0
    private var orangeBg = 0
    private var greyBg = 0
    private var amberBg = 0

    private fun c(hex: String) = Color.parseColor(hex)

    private fun applyTheme() {
        val mode = getSharedPreferences("ayarlar", Context.MODE_PRIVATE).getInt("theme", 0)
        dark = when (mode) {
            1 -> false
            2 -> true
            else -> (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        }
        if (dark) {
            paper = c("#0F0F0F"); surface = c("#1A1A1A"); greyBg = c("#242424"); ink = c("#F2F2F2"); muted = c("#A3A3A3")
            hintColor = c("#6E6E6E"); line = c("#2A2A2A"); gold = c("#4CC38A"); accentOn = c("#0F0F0F"); brand = c("#F2C14E")
            green = c("#4CC38A"); red = c("#F28B82"); orange = c("#F5A962")
            greenBg = c("#13301F"); redBg = c("#3B1A18"); orangeBg = c("#3A2814"); amberBg = c("#3A3016")
        } else {
            paper = c("#FFFFFF"); surface = c("#FFFFFF"); greyBg = c("#F2F2F2"); ink = c("#111111"); muted = c("#6B6B6B")
            hintColor = c("#9A9A9A"); line = c("#E8E8E8"); gold = c("#0F6B3A"); accentOn = c("#FFFFFF"); brand = c("#F2C14E")
            green = c("#0F6B3A"); red = c("#B42318"); orange = c("#B54708")
            greenBg = c("#EAF5EE"); redBg = c("#FDECEA"); orangeBg = c("#FDF0E3"); amberBg = c("#FFF6DB")
        }
    }

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
    private val settings by lazy { getSharedPreferences("ayarlar", Context.MODE_PRIVATE) }
    private fun flag(k: String, def: Boolean = false) = settings.getBoolean(k, def)
    private fun siteOn(name: String) = settings.getBoolean("site_$name", true)
    private var screen = "home"           // home / results / alarms / settings
    private val categories = listOf("Elektronik", "Market", "Kozmetik", "Giyim", "Bebek", "Diğer")
    private var currentCategory = "Diğer"
    private var editCat = "Genel"

    /** Aramanın kategorisi: aranan yazı ve eşleşen ilan adlarındaki kelimelerden */
    private fun detectCategory(): String {
        val t = (query + " " + offers.filter { it.kind == Kind.MATCH }.take(12).joinToString(" ") { it.title })
            .lowercase(Locale("tr", "TR"))
        fun has(re: String) = Regex(re).containsMatchIn(t)
        return when {
            has("bebek bezi|bebek bez|külot bez|prima|molfix|sleepy|biberon|emzik|bebek maması|ıslak havlu|ıslak mendil") -> "Bebek"
            has("parfüm|parfum|\\bedt\\b|\\bedp\\b|deodorant|şampuan|krem|ruj|maskara|serum|losyon|tıraş köpüğü|saç boyası|cilt") -> "Kozmetik"
            has("ayakkabı|sneaker|tişört|t-shirt|gömlek|pantolon|elbise|ceket|mont|eşofman|çanta|terlik|bot\\b|sweatshirt|beden") -> "Giyim"
            has("telefon|iphone|galaxy|televizyon|\\btv\\b|qled|oled|laptop|bilgisayar|tablet|ipad|kulaklık|airpods|monitör|kamera|" +
                "playstation|ps5|xbox|akıllı saat|şarj|tıraş makinesi|süpürge|buzdolabı|çamaşır makinesi|bulaşık makinesi|klima|fırın|" +
                "kahve makinesi|oneblade|ekran kartı|ssd|router|modem|yazıcı") -> "Elektronik"
            has("\\d+\\s*(gr|g|kg|ml|lt|l)\\b|kahve|çay|deterjan|yumuşatıcı|makarna|yağ|şeker|un\\b|süt|peynir|çikolata|bisküvi|kağıt havlu|tuvalet kağıdı|su\\b") -> "Market"
            else -> "Diğer"
        }
    }

    private fun ruleOn(k: String): Boolean = when (settings.getInt("cat_${currentCategory}_$k", 0)) {
        1 -> true
        2 -> false
        else -> flag(k)
    }

    private fun ruleMinRating(): Float =
        settings.getFloat("cat_${currentCategory}_minRating", -1f).let { if (it >= 0) it else settings.getFloat("minRating", 0f) }

    private fun ruleMinReviews(): Int =
        settings.getInt("cat_${currentCategory}_minReviews", -1).let { if (it >= 0) it else settings.getInt("minReviews", 0) }
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
    private lateinit var gearDot: View
    private var scanSites: Set<String> = emptySet()
    private var devTaps = 0
    private lateinit var chipsScroll: HorizontalScrollView
    private lateinit var progress: ProgressBar
    private val expanded = HashMap<String, Boolean>()
    private lateinit var filterBar: LinearLayout
    private lateinit var filterInput: EditText
    private lateinit var sortBtn: TextView
    private lateinit var sitesBtn: TextView
    private var finishedAt = 0L
    private var filterText = ""
    private var sortMode = 0
    private val hiddenSites = HashSet<String>()
    private val sortNames = listOf("Önerilen", "En ucuz", "En pahalı", "Tane / kg / L başı fiyat", "Puan", "Değerlendirme sayısı", "Kargo dahil en ucuz")
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
          function imgOf(box, ttl){
            // Ürün fotoğrafı: adı ilan adıyla örtüşen ya da ürün resmi sunucusundaki resim önce, sonra en büyüğü.
            // ("Avantajlı" gibi rozetler bazen üründen büyük çiziliyor.)
            var best = '', bestW = -1;
            var tw = (ttl || '').toLowerCase().split(/[^a-zçğıöşü0-9]+/).filter(function(x){ return x.length >= 4; });
            var bad = /sprite|logo|icon|badge|rozet|etiket|label|sticker|campaign|kampanya|avantaj|placeholder|blank|\.svg|\.gif/i;
            function pick(u, w){
              u = (u || '').trim().split(' ')[0];
              if (u.indexOf('//') === 0) u = 'https:' + u;
              if (!/^https?:/.test(u) || bad.test(u)) return;
              if (w > bestW) { bestW = w; best = u; }
            }
            var ims = box.querySelectorAll('img');
            for (var q = 0; q < ims.length; q++) {
              var im = ims[q];
              var w = im.naturalWidth || im.width || parseInt(im.getAttribute('width') || '0', 10) || 0;
              if (w > 0 && w < 50) continue;
              var alt = (im.getAttribute('alt') || '').toLowerCase();
              if (/avantaj|kampanya|rozet|badge|kargo|logo|indirim|kupon|fırsat/.test(alt)) continue;
              var hits = tw.filter(function(x){ return alt.indexOf(x) >= 0; }).length;
              var src0 = im.currentSrc || im.src || im.getAttribute('data-src') || '';
              if (hits >= 2) w += 10000;
              if (/productimages|\/product|\/urun|cdn\.dsmcdn|images-amazon|m\.media-amazon|\/mnresize\/|\/s\/\d+/i.test(src0)) w += 5000;
              [im.currentSrc, im.src, im.getAttribute('data-src'), im.getAttribute('data-original'), im.getAttribute('data-lazy'),
               im.getAttribute('data-image'), im.getAttribute('srcset'), im.getAttribute('data-srcset')]
                .forEach(function(u){ pick(u, w || 100); });
            }
            // <picture><source srcset=...> ile verilen resimler
            var srcs = box.querySelectorAll('source[srcset], source[data-srcset]');
            for (var r = 0; r < srcs.length; r++) pick(srcs[r].getAttribute('srcset') || srcs[r].getAttribute('data-srcset'), 90);
            // Arka plan resmi olarak verilenler
            if (!best) {
              var bgs = box.querySelectorAll('[style*="background-image"]');
              for (var b = 0; b < bgs.length; b++) {
                var m = /url\(["']?([^"')]+)["']?\)/.exec(bgs[b].getAttribute('style') || '');
                if (m) pick(m[1], 80);
              }
            }
            return best;
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
            // Üyelik fiyatı (Trendyol Plus, Hepsiburada Premium, Amazon Prime): herkesin ödediği fiyat değildir, ayrı tutulur
            var member = 0, memberName = '';
            var mm = /(trendyol\s*plus|premium|prime)\s*(?:ile|'?le|'?la|fiyatı)[^\d\n]{0,12}([\d.,]+)\s*(?:TL|₺)(?:\s*%\s*\d+\s*([\d.,]+)\s*(?:TL|₺))?/i.exec(text);
            var priceText = text;
            if (mm) {
              memberName = /premium/i.test(mm[1]) ? 'Premium' : (/prime/i.test(mm[1]) ? 'Prime' : 'Plus');
              member = num(mm[3] || mm[2]);
              // "Premium ile 1.899 TL %5 1.804 TL": 1.899 normal fiyat kalır, 1.804 çıkarılır
              priceText = text.replace(mm[0], mm[3] ? ' ' + mm[2] + ' TL ' : ' ');
            }
            // Kargo: bedava / ücreti / şu tutarın üzeri bedava
            var ship = -1, freeOver = 0;
            if (/kargo bedava|ücretsiz kargo|kargo ücretsiz|bedava kargo|ücretsiz teslimat|kargo dahil/i.test(text)) ship = 0;
            var fo = /([\d.,]+)\s*(?:TL|₺)\s*(?:ve\s+)?(?:üzeri|üstü)[^\n]{0,40}?(?:kargo|teslimat)/i.exec(text);
            if (fo) { freeOver = num(fo[1]); if (ship === 0 && /üzeri|üstü/i.test(fo[0])) ship = -1; }
            if (ship < 0) { var sf = /kargo(?:\s*ücreti)?\s*:?\s*(?:₺\s?)?([\d]{1,3}(?:[.,]\d{1,2})?)\s*(?:TL|₺)/i.exec(text); if (sf) ship = num(sf[1]); }
            var ps = (clean(priceText).match(priceRe) || []).map(num).filter(function(x){ return x > 0; });
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
              mp: member, mpn: memberName, ship: ship, fover: freeOver,
              img: imgOf(el, title), alts: ok, raw: text.replace(/\s+/g, ' ').slice(0, 260) });
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
        applyTheme()
        // Pencereler (diyalog, menü) de temaya uysun
        setTheme(if (dark) android.R.style.Theme_Material_NoActionBar else android.R.style.Theme_Material_Light_NoActionBar)
        super.onCreate(savedInstanceState)
        // Uygulama çökerse hatanın ayrıntısını sakla; bir sonraki açılışta göster (teşhis için)
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                getSharedPreferences("crash", Context.MODE_PRIVATE).edit()
                    .putString("last", android.util.Log.getStackTraceString(e).take(5000)).commit()
            } catch (x: Exception) {}
            prev?.uncaughtException(t, e)
        }
        window.statusBarColor = paper
        window.navigationBarColor = paper
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (dark) 0 else
            (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR)
        setContentView(buildUi())
        try {
            Alarms.ensureChannel(this)
            Alarms.schedule(this, Alarms.load(this).isNotEmpty())
        } catch (e: Exception) {}
        showLastCrash()
        handleShare(intent)
    }

    /** Kopilot'taki gibi: her açılışta / öne gelişte GitHub'a bakar (en sık 3 dakikada bir) */
    private var lastUpdateLook = 0L
    private fun maybeCheckUpdate() {
        updateGearDot()
        if (!flag("autoUpdate", true)) return
        val now = System.currentTimeMillis()
        if (now - lastUpdateLook < 3 * 60_000L) return
        lastUpdateLook = now
        Update.check(this) { _, _, _ ->
            updateGearDot()
            if (screen == "home" && !running) showHome()
        }
    }

    /** Ayarlar simgesindeki kırmızı nokta: yeni sürüm varken görünür */
    private var dotAnim: android.animation.ObjectAnimator? = null
    private fun updateGearDot() {
        if (!::gearDot.isInitialized) return
        val on = Update.pending(this) != null
        gearDot.visibility = if (on) View.VISIBLE else View.GONE
        if (on && dotAnim == null) {
            // Yanıp sönen nokta: göze çarpsın
            dotAnim = android.animation.ObjectAnimator.ofFloat(gearDot, "alpha", 1f, 0.15f).apply {
                duration = 650
                repeatCount = android.animation.ValueAnimator.INFINITE
                repeatMode = android.animation.ValueAnimator.REVERSE
                start()
            }
        } else if (!on) {
            dotAnim?.cancel(); dotAnim = null
        }
    }

    /** Güncellemeyi indirip kurar; ilerlemeyi verilen yazıda gösterir */
    private fun startUpdate(info: TextView?) {
        val started = Update.install(this) { pct ->
            info?.text = when {
                pct == -2 -> "İndirilemedi, tarayıcıdan indiriliyor"
                pct == -1 -> "İndiriliyor…"
                pct >= 100 -> "İndirildi, kurulum onayı açılıyor…"
                else -> "İndiriliyor… %$pct"
            }
        }
        if (started) info?.text = "İndirme başladı…"
    }

    private fun showLastCrash() {
        val cp = getSharedPreferences("crash", Context.MODE_PRIVATE)
        val trace = cp.getString("last", null) ?: return
        cp.edit().remove("last").apply()
        AlertDialog.Builder(this)
            .setTitle("Uygulama son kullanımda kapandı")
            .setMessage("Hatanın ayrıntısı aşağıda. Kopyala'ya basıp bana gönderirsen düzeltirim.\n\n" + trace.take(1500))
            .setPositiveButton("Kopyala") { _, _ ->
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("Fiyat Avcısı hata", "FİYAT AVCISI HATA\n" + trace))
                Toast.makeText(this, "Hata kopyalandı", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Kapat", null)
            .show()
    }

    /** Geri tuşu: ayarlar/alarmlar → sonuçlara ya da ana sayfaya; sonuçlar → ana sayfa; ana sayfa → çıkış */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            resultsScroll.visibility != View.VISIBLE -> toggleBrowser()
            running -> { stopAll(); running = false; progress.visibility = View.GONE; showHome() }
            screen == "settings" || screen == "alarms" -> {
                if (offers.isNotEmpty()) {
                    filterBar.visibility = View.VISIBLE
                    chipsScroll.visibility = if (running) View.VISIBLE else View.GONE
                    status.visibility = View.VISIBLE
                    render(final = finished)
                } else showHome()
            }
            screen == "results" -> goHome()
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    /** Sonuçları kapatıp ana sayfaya dön */
    private fun goHome() {
        filterBar.visibility = View.GONE
        chipsScroll.visibility = View.GONE
        status.visibility = View.GONE
        offers.clear()
        showHome()
        resultsScroll.post { resultsScroll.scrollTo(0, 0) }
    }

    override fun onResume() {
        super.onResume()
        maybeCheckUpdate()
        // İzin ekranından dönünce durumları yenile
        if (screen == "settings") showSettings()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
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


    private val siteColors = mapOf(
        "Trendyol" to Color.parseColor("#F27A1A"), "Hepsiburada" to Color.parseColor("#FF6000"),
        "Amazon" to Color.parseColor("#232F3E"), "MediaMarkt" to Color.parseColor("#D7000F"),
        "Teknosa" to Color.parseColor("#1E3A8A"), "N11" to Color.parseColor("#6D28D9"),
        "Pazarama" to Color.parseColor("#5B21B6"), "Migros" to Color.parseColor("#E8750A"),
        "CarrefourSA" to Color.parseColor("#0055A5"), "A101" to Color.parseColor("#0093D0"),
        "Şok" to Color.parseColor("#C99400"), "Vatan" to Color.parseColor("#C8102E"),
        "Boyner" to Color.parseColor("#1F1F1F"), "Flo" to Color.parseColor("#D3121B"),
        "Decathlon" to Color.parseColor("#0082C3"),
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

    /** Yuvarlak ikon düğmesi (44 dp dokunma alanı) */
    private fun iconButton(res: Int, desc: String, onClick: () -> Unit) = ImageView(this).apply {
        setImageResource(res)
        imageTintList = android.content.res.ColorStateList.valueOf(ink)
        contentDescription = desc
        setPadding(dp(11), dp(11), dp(11), dp(11))
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        background = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(greyBg), null, rounded(Color.WHITE, 22))
        setOnClickListener { onClick() }
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

    private fun loadImage(url: String, iv: ImageView, referer: String = "") {
        iv.tag = url
        imgCache.get(url)?.let { iv.setImageBitmap(it); return }
        imgPool.execute {
            try {
                val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 5000
                c.readTimeout = 7000
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36")
                c.setRequestProperty("Accept", "image/webp,image/avif,image/*,*/*")
                // Bazı siteler resmi sadece kendi sayfasından gelen isteğe verir
                if (referer.isNotEmpty()) c.setRequestProperty("Referer", referer)
                c.instanceFollowRedirects = true
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

        // Başlık: logo, ad, alarm ve ayar düğmeleri
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(10), dp(10))
        }
        header.addView(label("FA", 15f, brand, true).apply {
            gravity = Gravity.CENTER
            background = rounded(if (dark) c("#2A2A2A") else c("#111111"), 10)
        }, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(10) })
        header.addView(label("Fiyat Avcısı", 20f, ink, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(iconButton(R.drawable.ic_bell, "Alarmlar") { showAlarms() })
        // Ayarlar simgesi + yeni sürüm noktası
        val gearBox = FrameLayout(this)
        gearBox.addView(iconButton(R.drawable.ic_gear, "Ayarlar") { showSettings() })
        gearDot = View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c("#E5484D")); setStroke(dp(2), paper) }
            visibility = View.GONE
        }
        gearBox.addView(gearDot, FrameLayout.LayoutParams(dp(12), dp(12)).apply {
            gravity = Gravity.TOP or Gravity.END
            setMargins(0, dp(7), dp(7), 0)
        })
        header.addView(gearBox)
        val menuBtn = iconButton(R.drawable.ic_more, "Menü") {}
        menuBtn.setOnClickListener { showMenu(menuBtn) }
        header.addView(menuBtn)
        root.addView(header)

        // Arama kutusu: hap biçimi, içinde barkod ve Ara düğmeleri
        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(greyBg, 28)
            setPadding(dp(16), dp(4), dp(5), dp(4))
        }
        searchRow.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_search)
            imageTintList = android.content.res.ColorStateList.valueOf(muted)
        }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(8) })
        input = EditText(this).apply {
            hint = "Ürün adı, model kodu ya da barkod"
            setSingleLine()
            textSize = 16f
            setTextColor(ink)
            setHintTextColor(hintColor)
            background = null
            setPadding(0, dp(10), 0, dp(10))
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, id, _ ->
                if (id == EditorInfo.IME_ACTION_SEARCH) { startTest(); true } else false
            }
        }
        searchRow.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        searchRow.addView(iconButton(R.drawable.ic_barcode, "Barkod okut") { scanBarcode() })
        val go = label("Ara", 15f, accentOn, true).apply {
            gravity = Gravity.CENTER
            background = rounded(gold, 22)
            setPadding(dp(18), dp(11), dp(18), dp(11))
            setOnClickListener { startTest() }
        }
        searchRow.addView(go, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(2) })
        root.addView(searchRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(dp(14), dp(2), dp(14), dp(8)) })

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
            setHintTextColor(hintColor)
            background = rounded(surface, 12, line)
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
            background = rounded(surface, 12, line)
            setPadding(dp(12), dp(9), dp(12), dp(9))
        }
        sortBtn.setOnClickListener {
            PopupMenu(this, sortBtn).apply {
                sortNames.forEachIndexed { i, n -> menu.add(0, i, i, (if (i == sortMode) "✓ " else "") + n) }
                setOnMenuItemClickListener {
                    sortMode = it.itemId
                    sortBtn.text = if (sortMode == 0) "Sırala ▾" else "Sıralı ▾"
                    render(final = finished)
                    true
                }
                show()
            }
        }
        // Siteler: gösterilecek siteleri seçme listesi (ayrı çip satırı yerine tek düğme)
        sitesBtn = label("Siteler ▾", 14f, ink, true).apply {
            background = rounded(surface, 12, line)
            setPadding(dp(12), dp(9), dp(12), dp(9))
            setOnClickListener { chooseSites() }
        }
        fRow.addView(sitesBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(8) })
        fRow.addView(sortBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(8) })
        filterBar.addView(fRow)
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
            menu.add(0, 6, 0, "Barkod okut")
            menu.add(0, 4, 0, "Alarmlarım")
            menu.add(0, 5, 0, "Ayarlar")
            // Teşhis araçları sadece geliştirici modunda
            if (flag("devMode")) {
                menu.add(0, 1, 1, if (resultsScroll.visibility == View.VISIBLE) "Tarayıcıyı göster" else "Sonuçları göster")
                menu.add(0, 2, 2, "Raporu kopyala")
                menu.add(0, 3, 3, "Raporu paylaş")
            }
            setOnMenuItemClickListener {
                when (it.itemId) {
                    4 -> showAlarms()
                    6 -> scanBarcode()
                    5 -> showSettings()
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
        screen = "home"
        results.removeAllViews()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(surface, 16, line)
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

        // Yeni sürüm bildirimi
        Update.pending(this)?.let { newName ->
            val info = label("Yeni sürüm: $newName · şu anki ${Update.currentName(this)}", 12f, ink)
            results.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = rounded(amberBg, 18)
                setPadding(dp(16), dp(12), dp(12), dp(12))
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(label("Güncelleme var", 15f, ink, true))
                    addView(info)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(label("Güncelle", 14f, accentOn, true).apply {
                    background = rounded(gold, 18)
                    setPadding(dp(16), dp(9), dp(16), dp(9))
                    setOnClickListener { startUpdate(info) }
                })
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) })
        }

        // Barkod okut
        results.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(gold, 18)
            setPadding(dp(18), dp(14), dp(18), dp(14))
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_barcode)
                imageTintList = android.content.res.ColorStateList.valueOf(accentOn)
            }, LinearLayout.LayoutParams(dp(26), dp(26)))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), 0, 0, 0)
                addView(label("Barkod okut", 16f, accentOn, true))
                addView(label("Raftaki ürünün barkodunu ya da QR kodunu tara", 12f, accentOn).apply { alpha = 0.85f })
            })
            setOnClickListener { scanBarcode() }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })

        // Alarmlar kartı
        val alarmCount = Alarms.load(this).size
        results.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(amberBg, 16)
            setPadding(dp(18), dp(14), dp(18), dp(14))
            addView(label("🔔", 20f, ink))
            addView(label(if (alarmCount > 0) "Alarmlarım · $alarmCount ürün takipte" else
                "Fiyat alarmı: bir ilandaki 🔔 düğmesine dokun", 14f, ink, true).apply {
                setPadding(dp(12), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (alarmCount > 0) addView(label("▸", 16f, muted, true))
            setOnClickListener { showAlarms() }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })

        val recent = recentSearches()
        results.addView(label(if (recent.isNotEmpty()) "Son aramalar" else "Örnek aramalar", 14f, ink, true)
            .apply { setPadding(dp(4), dp(18), dp(4), dp(8)) })
        val flow = FlowLayout(this, dp(8))
        (recent.ifEmpty { listOf("iphone 16 128gb", "nescafe gold 200 gr", "dior sauvage edt 100 ml", "prima 4 numara") })
            .forEach { q ->
                flow.addView(pill(q, ink, surface, size = 14f).apply {
                    background = rounded(surface, 20, line)
                    setPadding(dp(14), dp(8), dp(14), dp(8))
                    // Dokununca sadece arama kutusuna yazar: düzenleyip Ara'ya basılır
                    setOnClickListener {
                        input.setText(q)
                        input.setSelection(q.length)
                        input.requestFocus()
                        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                            .showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
                    }
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
        for (s in sites.filter { siteOn(it.name) && (scanSites.isEmpty() || it.name in scanSites) }) {
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
    private val poolSize = 8

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
    private fun runSites(all: List<Site>, q: String, label: String, onEach: () -> Unit, onAll: () -> Unit) {
        // Ayarlardan kapatılan siteler ve aramanın kategorisiyle ilgisi olmayan siteler taranmaz
        val cat = detectCategory()
        val list = all.filter { siteOn(it.name) && (it.cats.isEmpty() || cat == "Diğer" || cat in it.cats) }
        scanSites = list.map { it.name }.toSet()
        if (list.isEmpty()) {
            siteSummary.add("Hiçbir site açık değil (Ayarlar → Siteler)")
            onAll()
            return
        }
        val gen = ++phaseGen
        var left = list.size
        var next = 0
        val t0 = android.os.SystemClock.elapsedRealtime()
        status.text = "$label: ${list.size} site taranıyor…"
        val done = BooleanArray(list.size)
        val viewOf = HashMap<Int, WebView>()
        var phaseDone = false
        val finish = { if (!phaseDone) { phaseDone = true; onAll() } }
        // Sırayla: boşalan tarayıcı sıradaki siteyi alır (aynı anda en çok ${poolSize} site)
        lateinit var launch: (WebView) -> Unit
        launch = launch@{ v ->
            if (next >= list.size || gen != phaseGen || phaseDone) return@launch
            val i = next++
            val site = list[i]
            viewOf[i] = v
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
                    if (left == 0) finish() else launch(v)
                }
            }
        }
        pool.forEach { launch(it) }
        // Mağazada hız: 14 saniyede bitmeyen siteler beklenmez (en az 4 site bittiyse)
        handler.postDelayed({
            if (gen == phaseGen && !phaseDone && list.size - left >= 4) {
                list.forEachIndexed { i, s ->
                    if (!done[i]) {
                        viewOf[i]?.let { v -> slots.getValue(v).gen++; v.stopLoading() }
                        siteSummary.add("${s.name}: yavaş — beklenmedi (14 sn)")
                    }
                }
                finish()
            }
        }, 14000)
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
        // "Seçimlerimi hatırla" kapalıysa her arama sıfırdan
        val mem = { k: String -> if (!flag("remember")) null else prefs.getString(k + norm(userInput), null) }
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
                memberPrice = c.optDouble("mp", 0.0).takeIf { !it.isNaN() } ?: 0.0,
                memberName = c.optString("mpn"),
                shipFee = c.optDouble("ship", -1.0).takeIf { !it.isNaN() } ?: -1.0,
                freeOver = c.optDouble("fover", 0.0).takeIf { !it.isNaN() } ?: 0.0,
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

    // Sayı ile TL arasında bölünmez boşluk: fiyat iki satıra kırılmasın
    private fun money(v: Double) =
        "${if (v == Math.floor(v)) tlWhole.format(v) else tl.format(v)}\u00A0TL"

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
        finishedAt = android.os.SystemClock.elapsedRealtime()
        running = false
        stopAll()
        progress.visibility = View.GONE
        // Site durum çipleri sadece arama sürerken görünür; sonuç gelince filtre satırı kalır
        chipsScroll.visibility = View.GONE
        render(final = true)
        // Ürün alarmı aynı aramayla yeniden yapıldıysa: yeni ve daha uygun ilanları gruba kat
        try {
            val all = Alarms.load(this)
            for ((gid, items) in all.filter { it.groupId != 0L && norm(it.groupQuery) == norm(userInput) }.groupBy { it.groupId }) {
                val ref = items.first()
                val byUnit = ref.unitTarget > 0 || ref.unitAmount > 0
                val seed = offers.filter { it.kind == Kind.MATCH }.minByOrNull { it.price } ?: continue
                val fresh = groupCandidates(seed)
                val have = items.map { it.link.substringBefore('?') }.toSet()
                val added = fresh.filter { it.link.substringBefore('?') !in have }.mapIndexed { i, c ->
                    val cu = offerUnit(c)
                    ref.copy(id = System.currentTimeMillis() + 100 + i, title = c.title, site = c.site, link = c.link, image = c.image,
                        startPrice = c.price, lastPrice = c.price, lowest = c.price, lastCheck = 0L, status = "aramadan eklendi",
                        unitAmount = if (byUnit) cu?.first ?: 0.0 else 0.0, unitName = if (byUnit) cu?.second.orEmpty() else "")
                }
                if (added.isNotEmpty()) Alarms.save(this, Alarms.load(this) + added)
            }
        } catch (e: Exception) {}
        // Fiyat geçmişi: bu ürünün bugünkü en ucuzu ve her eşleşen ilanın fiyatı
        try {
            val m = offers.filter { it.kind == Kind.MATCH }
            m.minOfOrNull { it.price }?.let { History.recordProduct(this, productKey(), it) }
            m.forEach { History.recordOffer(this, it.link, it.price) }
        } catch (e: Exception) {}
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
        screen = "results"
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
        currentCategory = detectCategory()
        bar.addView(label(if (id != null) "Ürün: ${id.label}" else "Aranan: $query", 13f, muted).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        // Kategori: dokununca o kategorinin kurallarını açar
        bar.addView(pill(currentCategory, ink, greyBg).apply {
            setPadding(dp(10), dp(4), dp(10), dp(4))
            setOnClickListener { if (!running) { editCat = currentCategory; showSettings() } }
        })
        if (!running) bar.addView(label("Yeniden tanı", 13f, gold, true).apply {
            setPadding(dp(10), dp(4), 0, dp(4))
            setOnClickListener { forgetIdentity() }
        })
        results.addView(bar)

        val matches = offers.filter { it.kind == Kind.MATCH }.sortedBy { it.price }
        val others = offers.filter { it.kind != Kind.MATCH }.sortedBy { it.price }
        markUnitPrices(matches)

        val took = elapsedText((if (finished && finishedAt > 0) finishedAt else android.os.SystemClock.elapsedRealtime()) - startedAt)
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
            results.addView(label("Filtre ve kurallar: ${fm.size + fo.size}/${matches.size + others.size} ilan gösteriliyor", 12f, gold, true)
                .apply { setPadding(dp(6), dp(2), dp(6), dp(4)) })
        }

        // Adetli / gramlı ürünlerde "en uygun" = birim fiyatı en düşük ilan
        val unitBased = fm.count { unitPrice(it) != null } * 2 >= fm.size && fm.size >= 2
        // En ucuz teklif kutusu
        if (fm.isNotEmpty()) heroCard(fm, unitBased)
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
        val matchSortName = when {
            sortMode != 0 -> sortNames[sortMode].lowercase(Locale("tr", "TR"))
            unitBased -> "tane / kg başı fiyata göre"
            else -> "ucuzdan pahalıya"
        }
        if (fm.isNotEmpty()) section("match", "Aynı ürün · $matchSortName",
            fm.size, green, true) {
            (if (sortMode == 0 && unitBased) sortedByUnit(fm) else applySort(fm)).forEach { o -> offerRow(o, o === cheapest) }
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
        if (siteSummary.isNotEmpty() && flag("devMode")) section("sites", "Site ayrıntıları", siteSummary.size, muted, false) {
            siteSummary.forEach { results.addView(label("• $it", 12f, muted).apply { setPadding(dp(6), dp(3), dp(6), dp(3)) }) }
        }

        buildReport(matches, others)
        resultsScroll.post { resultsScroll.scrollTo(0, y) }
    }

    /** Filtreye uyuyor mu: site gizli değil ve yazılan kelimelerin hepsi adda/satıcıda geçiyor */
    private fun shown(o: Offer): Boolean {
        if (o.site in hiddenSites) return false
        // Ayarlardaki kurallar
        if (ruleOn("hideAbroad") && o.origin.isNotEmpty() && !o.origin.startsWith("Türkiye")) return false
        if (ruleOn("hideUsed") && o.kind == Kind.USED) return false
        if (ruleOn("hideSuspicious") && o.kind == Kind.SUSPICIOUS) return false
        if (ruleOn("hideImporter") && (o.warranty + " " + o.warrantyType).lowercase(Locale("tr", "TR")).contains("ithalatçı")) return false
        val minR = ruleMinRating()
        if (minR > 0 && ratingNum(o) > 0 && ratingNum(o) < minR) return false
        val minC = ruleMinReviews()
        if (minC > 0 && countNum(o.ratingCount) < minC) return false
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
        // Kargosu bilinmeyen ilan kendi fiyatıyla sıralanır
        6 -> list.sortedBy { totalPrice(it) ?: it.price }
        else -> list
    }

    /** Siteler düğmesinin yazısı: kaç site gösteriliyor */
    private fun updateSiteToggles() {
        val withOffers = sites.filter { s -> offers.any { it.site == s.name } }
        val shownN = withOffers.count { it.name !in hiddenSites }
        sitesBtn.text = if (shownN == withOffers.size) "Siteler ▾" else "Siteler $shownN/${withOffers.size} ▾"
        sitesBtn.setTextColor(if (shownN == withOffers.size) ink else gold)
    }

    /** Gösterilecek siteleri seç: işaretli olanlar listede kalır */
    private fun chooseSites() {
        val withOffers = sites.filter { s -> offers.any { it.site == s.name } }
        if (withOffers.isEmpty()) return
        val names = withOffers.map { s -> "${s.name} (${offers.count { it.site == s.name }} ilan)" }.toTypedArray<CharSequence>()
        val checked = withOffers.map { it.name !in hiddenSites }.toBooleanArray()
        AlertDialog.Builder(this)
            .setTitle("Gösterilecek siteler")
            .setMultiChoiceItems(names, checked) { _, which, on -> checked[which] = on }
            .setPositiveButton("Uygula") { _, _ ->
                hiddenSites.clear()
                withOffers.forEachIndexed { i, s -> if (!checked[i]) hiddenSites.add(s.name) }
                render(final = finished)
            }
            .setNeutralButton("Hepsi") { _, _ -> hiddenSites.clear(); render(final = finished) }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    /** En üstte büyük kutu: en ucuz aynı ürün */
    private fun heroCard(matches: List<Offer>, unitBased: Boolean = false) {
        val o = if (unitBased) matches.filter { unitPrice(it) != null }.minByOrNull { unitPrice(it)!!.first } ?: matches.first()
            else matches.first()
        val up = if (unitBased) unitPrice(o) else null
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(greenBg, 24)
            setPadding(dp(16), dp(16), dp(16), dp(14))
            setOnClickListener { openLink(o) }
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        top.addView(photo(o, 92), LinearLayout.LayoutParams(dp(92), dp(92)).apply { marginEnd = dp(14) })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(label((if (up != null) "En uygun (${up.second} başı) · " else "En ucuz · ") + o.site, 12f, green, true),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_share)
                imageTintList = android.content.res.ColorStateList.valueOf(green)
                contentDescription = "Sonucu paylaş"
                setPadding(dp(8), dp(8), dp(8), dp(8))
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
                setOnClickListener { shareResult(matches) }
            })
            addView(bell(o))
        })
        if (up != null) {
            col.addView(label(unitText(o).removePrefix("≈ "), 30f, green, true).apply { setSingleLine(true) })
            val amt = offerUnit(o)
            col.addView(label(money(o.price) + (amt?.let { " · ${if (it.second == "adet") tlWhole.format(it.first) + " adet" else tl.format(it.first) + " " + it.second}" } ?: ""),
                14f, ink, true))
        } else {
            col.addView(label(money(o.price), 30f, green, true).apply { setSingleLine(true) })
        }
        col.addView(label(o.title, 13f, ink).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
        val extra = mutableListOf<String>()
        if (o.seller.isNotEmpty()) extra.add("Satıcı ${o.seller}")
        shipText(o)?.let { extra.add(it) }
        if (extra.isNotEmpty()) col.addView(label(extra.joinToString(" · "), 12f, muted).apply { setPadding(0, dp(3), 0, 0) })
        top.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(top)

        // Fiyat geçmişi: bu ürün için günlerin en ucuzu
        val hist = History.product(this, productKey())
        if (hist.size >= 2) {
            val lo = hist.minOf { it.second }
            val hi = hist.maxOf { it.second }
            val days = (History.today() - hist.first().first + 1).toInt()
            card.addView(Sparkline(this, hist, green, line), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(10) })
            val verdict = when {
                o.price <= lo -> "Son $days günün en düşük fiyatı"
                o.price <= lo * 1.03 -> "En düşüğe çok yakın (en düşük ${money(lo)})"
                else -> "Daha önce ${money(lo)} görüldü"
            }
            card.addView(label("$verdict · aralık ${money(lo)} – ${money(hi)}", 12f, ink, true).apply { setPadding(0, dp(4), 0, 0) })
        } else {
            card.addView(label("Fiyat geçmişi bu aramadan itibaren tutuluyor", 11f, muted).apply { setPadding(0, dp(8), 0, 0) })
        }
        val top2 = matches.last().price
        if (matches.size > 1) card.addView(label(
            "${matches.size} teklif · en pahalısı ${money(top2)}" + if (top2 > o.price) " (+${money(top2 - o.price)})" else "",
            12f, muted
        ).apply { setPadding(0, dp(4), 0, 0) })
        results.addView(card, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(dp(2), dp(4), dp(2), dp(6)) })
    }

    /** En ucuz sonucu ve diğer sitelerdeki fiyatları WhatsApp vb. ile paylaş */
    private fun shareResult(matches: List<Offer>) {
        val o = matches.first()
        val sb = StringBuilder()
        sb.append(o.title).append("\n\n")
        sb.append("En ucuz: ").append(money(o.price)).append(" · ").append(o.site)
        shipText(o)?.let { sb.append(" (").append(it.lowercase(Locale("tr", "TR"))).append(")") }
        sb.append("\n").append(o.link).append("\n")
        // Her siteden en ucuz ilan
        val perSite = matches.drop(1).groupBy { it.site }.values.map { g -> g.minByOrNull { it.price }!! }
            .filter { it.site != o.site }.sortedBy { it.price }.take(5)
        if (perSite.isNotEmpty()) {
            sb.append("\nDiğer siteler:\n")
            perSite.forEach { sb.append("• ").append(it.site).append(": ").append(money(it.price)).append("\n") }
        }
        val hist = History.product(this, productKey())
        if (hist.size >= 2) sb.append("\nSon ${History.today() - hist.first().first + 1} günün en düşüğü: ")
            .append(money(hist.minOf { it.second })).append("\n")
        sb.append("\nFiyat Avcısı ile bulundu")
        shareText(sb.toString())
    }

    private fun shareText(t: String) {
        try {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, t)
            }, "Paylaş"))
        } catch (e: Exception) {}
    }

    /** Ürünün geçmiş anahtarı: seçilen kimlik ya da aramanın sade hali */
    private fun productKey() = norm(identity?.label ?: query)

    /** Kargo dahil fiyat; kargo bilinmiyorsa null */
    private fun totalPrice(o: Offer): Double? = when {
        o.shipFee == 0.0 -> o.price
        o.freeOver > 0 && o.price >= o.freeOver -> o.price
        o.shipFee > 0 -> o.price + o.shipFee
        else -> null
    }

    private fun shipText(o: Offer): String? = when {
        o.shipFee == 0.0 || (o.freeOver > 0 && o.price >= o.freeOver) -> "Kargo bedava"
        o.shipFee > 0 -> "+${money(o.shipFee)} kargo"
        else -> null
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
        background = rounded(if (dark) greyBg else surface, 14, line)
        setPadding(dp(4), dp(4), dp(4), dp(4))
        scaleType = ImageView.ScaleType.FIT_CENTER
        if (o.image.isNotEmpty()) loadImage(o.image, this,
            Uri.parse(o.link).let { u -> "${u.scheme}://${u.host}/" })
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
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(12), dp(4), dp(12))
            setOnClickListener { openLink(o) }
            // Uzun basınca bu ilanı paylaş
            setOnLongClickListener { shareText("${o.title}\n${money(o.price)} · ${o.site}\n${o.link}"); true }
        }
        row.addView(photo(o, 60), LinearLayout.LayoutParams(dp(60), dp(60)).apply { marginEnd = dp(12) })

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(label(o.title.ifEmpty { "(ad okunamadı)" }, 14f, ink).apply {
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        })
        // Site (renkli nokta) · puan · başka satıcı
        val meta = android.text.SpannableStringBuilder()
        meta.append("● ")
        meta.setSpan(android.text.style.ForegroundColorSpan(siteColors[o.site] ?: muted), 0, 1, 0)
        meta.append(o.site)
        if (o.rating.isNotEmpty()) meta.append(" · ★ ${o.rating}" + if (o.ratingCount.isNotEmpty()) " (${countText(o.ratingCount)})" else "")
        if (o.dupCount > 0) meta.append(" · +${o.dupCount} satıcı")
        col.addView(label("", 12f, muted).apply { text = meta; setPadding(0, dp(3), 0, 0) })

        // Etiketler
        val chips = FlowLayout(this, dp(6)).apply { setPadding(0, dp(6), 0, 0) }

        when {
            o.kind == Kind.MATCH && o.variant.isNotEmpty() -> chips.addView(pill(o.variant, ink, greyBg))
            o.kind != Kind.MATCH && o.kind != Kind.MUADIL -> chips.addView(pill(shortKind(o), orange, orangeBg))
        }
        if (o.note.isNotEmpty()) chips.addView(pill(o.note, if (o.note.startsWith("★")) green else red,
            if (o.note.startsWith("★")) greenBg else redBg, bold = true))
        shipText(o)?.let { chips.addView(pill(it, if (it.startsWith("Kargo bedava")) green else orange,
            if (it.startsWith("Kargo bedava")) greenBg else orangeBg)) }
        if (o.memberPrice > 0 && o.memberPrice < o.price) chips.addView(pill("${o.memberName} ile ${money(o.memberPrice)}", ink, amberBg))
        if (o.exact) chips.addView(pill("Barkodla birebir", green, greenBg, bold = true))
        if (o.badge.isNotEmpty()) chips.addView(pill("✓ ${o.badge}", green, greenBg))
        if (o.origin.isNotEmpty()) chips.addView(pill(o.origin.substringBefore(" —"),
            if (o.origin.startsWith("Türkiye")) green else red, if (o.origin.startsWith("Türkiye")) greenBg else redBg))
        warrantyBadge(o)?.let { (txt, col2) -> chips.addView(pill(txt.substringBefore(" —"), col2,
            if (col2 == green) greenBg else if (col2 == red) redBg else greyBg)) }
        // Geçmiş: bu ilanın önceki fiyatı
        val oh = History.offer(this, o.link).filter { it.first < History.today() }
        oh.lastOrNull()?.second?.let { prev ->
            if (Math.abs(prev - o.price) >= 1) chips.addView(pill(
                (if (o.price < prev) "↓ önce " else "↑ önce ") + money(prev),
                if (o.price < prev) green else red, if (o.price < prev) greenBg else redBg))
        }
        if (chips.childCount > 0) col.addView(chips)
        if (o.seller.isNotEmpty()) col.addView(label(
            "Satıcı: ${o.seller}" + if (o.sellerScore.isNotEmpty()) " · ${o.sellerScore}" else "", 12f, muted
        ).apply { setPadding(0, dp(5), 0, 0) })
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        // Sağda fiyat
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            setPadding(dp(10), 0, 0, 0)
        }
        right.addView(label(money(o.price), 17f, if (best) green else ink, true).apply {
            gravity = Gravity.END
            maxLines = 1
            setSingleLine(true)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.END })
        if (o.listPrice > o.price) right.addView(label(money(o.listPrice), 12f, muted).apply {
            paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG; gravity = Gravity.END; setSingleLine(true)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.END })
        if (o.basket) right.addView(label("sepette", 11f, muted).apply { gravity = Gravity.END })
        // Tane / kg / L başı fiyat, fiyatın hemen altında
        unitText(o).takeIf { it.isNotEmpty() }?.let {
            right.addView(label(it.removePrefix("≈ "), 12f, if (o.note.startsWith("★")) green else ink, true).apply {
                gravity = Gravity.END; setSingleLine(true)
                background = rounded(if (o.note.startsWith("★")) greenBg else greyBg, 10)
                setPadding(dp(6), dp(2), dp(6), dp(2))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.END; topMargin = dp(3)
            })
        }
        if (best) right.addView(label("en ucuz", 11f, green, true).apply { gravity = Gravity.END })
        right.addView(bell(o), LinearLayout.LayoutParams(dp(36), dp(36)).apply { gravity = Gravity.END })
        // Fiyat sütunu en az kendi genişliği kadar yer alır, ad sütunu kalanı paylaşır
        right.minimumWidth = dp(84)
        row.addView(right, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        results.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        results.addView(View(this).apply { setBackgroundColor(line) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { setMargins(dp(76), 0, 0, 0) })
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

    // ---------- Ayarlar ----------

    private fun settingsCard(title: String): LinearLayout {
        results.addView(label(title, 15f, ink, true).apply { setPadding(dp(6), dp(18), dp(6), dp(6)) })
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(surface, 16, line)
            setPadding(dp(16), dp(6), dp(16), dp(6))
        }
        results.addView(card, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(dp(2), 0, dp(2), 0) })
        return card
    }

    private fun rowBase(card: LinearLayout, title: String, sub: String, subColor: Int = muted): LinearLayout {
        if (card.childCount > 0) card.addView(View(this).apply { setBackgroundColor(line) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(label(title, 15f, ink))
        if (sub.isNotEmpty()) col.addView(label(sub, 12f, subColor).apply { setPadding(0, dp(2), dp(8), 0) })
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(row)
        return row
    }

    /** Durum + düğme satırı (izinler) */
    private fun actionRow(card: LinearLayout, title: String, ok: Boolean?, sub: String, button: String, action: () -> Unit) {
        val stateText = when (ok) { true -> "✓ Açık"; false -> "✗ Kapalı"; null -> "" }
        val row = rowBase(card, title, listOf(stateText, sub).filter { it.isNotEmpty() }.joinToString(" · "),
            when (ok) { true -> green; false -> red; null -> muted })
        row.addView(label(button, 13f, if (ok == true) ink else accentOn, true).apply {
            background = rounded(if (ok == true) greyBg else gold, 12)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { action() }
        })
    }

    private fun switchRow(card: LinearLayout, title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        val row = rowBase(card, title, sub)
        row.addView(android.widget.Switch(this).apply {
            isChecked = checked
            thumbTintList = android.content.res.ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(gold, if (dark) c("#5A5A5A") else c("#D6D3D1")))
            trackTintList = android.content.res.ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(if (dark) c("#1F5A3C") else c("#A7D7B8"), if (dark) c("#333333") else c("#E7E5E4")))
            setOnCheckedChangeListener { _, v -> onChange(v) }
        })
    }

    /** Seçenekli satır: dokununca liste açılır */
    private fun choiceRow(card: LinearLayout, title: String, sub: String, options: List<String>, current: Int, onPick: (Int) -> Unit) {
        val row = rowBase(card, title, sub)
        row.addView(label("${options.getOrElse(current) { options[0] }} ▾", 14f, gold, true).apply {
            setPadding(dp(10), dp(6), 0, dp(6))
        })
        row.setOnClickListener {
            AlertDialog.Builder(this).setTitle(title)
                .setSingleChoiceItems(options.toTypedArray<CharSequence>(), current) { d, which ->
                    onPick(which); d.dismiss(); showSettings()
                }.show()
        }
    }

    private fun openIntent(vararg intents: Intent) {
        for (i in intents) {
            try { startActivity(i); return } catch (e: Exception) {}
        }
        Toast.makeText(this, "Bu ayar telefonda açılamadı", Toast.LENGTH_SHORT).show()
    }

    private fun appDetails() = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))

    private fun showSettings() {
        if (running) return
        screen = "settings"
        filterBar.visibility = View.GONE
        chipsScroll.visibility = View.GONE
        progress.visibility = View.GONE
        status.visibility = View.GONE
        results.removeAllViews()
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(8), dp(4), 0)
        }
        head.addView(label("Ayarlar", 20f, ink, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(label("Kapat", 14f, gold, true).apply {
            setPadding(dp(10), dp(6), dp(4), dp(6))
            setOnClickListener { if (offers.isNotEmpty()) { filterBar.visibility = View.VISIBLE; status.visibility = View.VISIBLE; chipsScroll.visibility = if (running) View.VISIBLE else View.GONE; render(final = finished) } else showHome() }
        })
        results.addView(head)

        // Güncelleme
        val up = settingsCard(if (Update.pending(this) != null) "Güncelleme · yeni sürüm var" else "Güncelleme")
        val pend = Update.pending(this)
        actionRow(up, "Sürüm ${Update.currentName(this)}", null,
            if (pend != null) "Yeni sürüm var: $pend" else "Son denetim: " +
                (if (Update.lastCheck(this) > 0) java.text.SimpleDateFormat("d MMM HH:mm", Locale("tr", "TR"))
                    .format(java.util.Date(Update.lastCheck(this))) else "hiç"),
            if (pend != null) "Güncelle" else "Denetle") {
            if (pend != null) {
                startUpdate(null)
                Toast.makeText(this, "Güncelleme indiriliyor, bitince telefon onay isteyecek", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "Denetleniyor…", Toast.LENGTH_SHORT).show()
                Update.check(this) { name, code, err ->
                    Toast.makeText(this, when {
                        err != null -> "Denetlenemedi: $err"
                        code > Update.current(this) -> "Yeni sürüm var: $name"
                        else -> "Uygulama güncel"
                    }, Toast.LENGTH_LONG).show()
                    if (screen == "settings") showSettings()
                }
            }
        }
        switchRow(up, "Açılışta otomatik denetle", "Yeni sürüm varsa ana sayfada haber verir", flag("autoUpdate", true)) { v ->
            settings.edit().putBoolean("autoUpdate", v).apply()
        }

        // İzinler
        val perm = settingsCard("İzinler")
        val nm = getSystemService(android.app.NotificationManager::class.java)
        val notifOk = nm?.areNotificationsEnabled() == true
        actionRow(perm, "Bildirimler", notifOk, "Fiyat düşünce haber vermek için", if (notifOk) "Ayarlar" else "İzin ver") {
            if (!notifOk && android.os.Build.VERSION.SDK_INT >= 33 && !settings.getBoolean("notifAsked", false)) {
                settings.edit().putBoolean("notifAsked", true).apply()
                askNotificationPermission()
            } else {
                openIntent(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName), appDetails())
            }
        }
        val pm = getSystemService(android.os.PowerManager::class.java)
        val batteryOk = pm?.isIgnoringBatteryOptimizations(packageName) == true
        actionRow(perm, "Pil kısıtlaması yok", batteryOk, "Alarmların arka planda çalışması için", if (batteryOk) "Ayarlar" else "Kaldır") {
            if (!batteryOk) openIntent(
                Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
                Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS), appDetails())
            else openIntent(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS), appDetails())
        }
        actionRow(perm, "Otomatik başlatma (Xiaomi)", null, "Telefon açılınca alarmlar sürsün diye açık olmalı; durumu okunamıyor", "Aç") {
            openIntent(
                Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
                Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT),
                appDetails())
        }
        actionRow(perm, "Uygulama ayarları", null, "Android'in bu uygulama için bütün ayarları", "Aç") { openIntent(appDetails()) }

        // Siteler
        val src = settingsCard("Taranan siteler")
        sites.forEach { site ->
            switchRow(src, site.name, "", siteOn(site.name)) { v -> settings.edit().putBoolean("site_${site.name}", v).apply() }
        }

        // Görünüm
        val look = settingsCard("Görünüm")
        choiceRow(look, "Tema", "Koyu tema gece göz yormaz", listOf("Telefona göre", "Açık", "Koyu"),
            settings.getInt("theme", 0)) { i ->
            settings.edit().putInt("theme", i).apply()
            handler.post { recreate() }
        }

        // Kurallar: genel ya da kategoriye özel
        val rules = settingsCard("Kurallar (sonuçlarda gizlenenler)")
        val cats = listOf("Genel") + categories
        choiceRow(rules, "Düzenlenen", "Kategori seçilirse o kategorinin aramalarında genel kuralın yerine geçer",
            cats, cats.indexOf(editCat).coerceAtLeast(0)) { i -> editCat = cats[i] }
        val ruleDefs = listOf(
            Triple("hideAbroad", "Yurtdışı / ithal ilanları gizle", "International Version, menşei yurtdışı, TU/A olmayan cihazlar"),
            Triple("hideImporter", "İthalatçı garantili ilanları gizle", "Ürün sayfası açılan pahalı ilanlarda geçerli"),
            Triple("hideUsed", "Yenilenmiş / ikinci el / teşhir gizle", ""),
            Triple("hideSuspicious", "Şüpheli ucuz ilanları gizle", "Gerçek fiyatın çok altındaki taklit ya da yanlış ilanlar"),
        )
        val ratings = listOf(0f, 3.5f, 4f, 4.5f)
        val ratingNames = listOf("Yok", "3,5", "4,0", "4,5")
        val counts = listOf(0, 10, 50, 100)
        val countNames = listOf("Yok", "10", "50", "100")
        if (editCat == "Genel") {
            ruleDefs.forEach { (k, t, sub) -> switchRow(rules, t, sub, flag(k)) { v -> settings.edit().putBoolean(k, v).apply() } }
            choiceRow(rules, "En az ürün puanı", "Puanı bunun altındaki ilanlar gizlenir (puansızlar kalır)",
                ratingNames, ratings.indexOf(settings.getFloat("minRating", 0f)).coerceAtLeast(0)) { i ->
                settings.edit().putFloat("minRating", ratings[i]).apply()
            }
            choiceRow(rules, "En az değerlendirme sayısı", "Az değerlendirilen ya da hiç değerlendirilmemiş ilanlar gizlenir",
                countNames, counts.indexOf(settings.getInt("minReviews", 0)).coerceAtLeast(0)) { i ->
                settings.edit().putInt("minReviews", counts[i]).apply()
            }
        } else {
            val cat = editCat
            val tri = listOf("Genel ayar", "Açık", "Kapalı")
            ruleDefs.forEach { (k, t, sub) ->
                choiceRow(rules, t, (if (sub.isNotEmpty()) "$sub · " else "") + "genel: " + (if (flag(k)) "açık" else "kapalı"),
                    tri, settings.getInt("cat_${cat}_$k", 0)) { i -> settings.edit().putInt("cat_${cat}_$k", i).apply() }
            }
            val rCur = settings.getFloat("cat_${cat}_minRating", -1f)
            choiceRow(rules, "En az ürün puanı", "Puanı bunun altındaki ilanlar gizlenir",
                listOf("Genel ayar") + ratingNames, if (rCur < 0) 0 else ratings.indexOf(rCur).coerceAtLeast(0) + 1) { i ->
                settings.edit().putFloat("cat_${cat}_minRating", if (i == 0) -1f else ratings[i - 1]).apply()
            }
            val cCur = settings.getInt("cat_${cat}_minReviews", -1)
            choiceRow(rules, "En az değerlendirme sayısı", "Az değerlendirilen ilanlar gizlenir",
                listOf("Genel ayar") + countNames, if (cCur < 0) 0 else counts.indexOf(cCur).coerceAtLeast(0) + 1) { i ->
                settings.edit().putInt("cat_${cat}_minReviews", if (i == 0) -1 else counts[i - 1]).apply()
            }
        }

        // Alarmlar
        val al = settingsCard("Fiyat alarmları")
        val hours = listOf(1, 2, 4, 6, 12)
        choiceRow(al, "Kontrol sıklığı", "Sık kontrol biraz daha pil harcar",
            hours.map { "$it saatte bir" }, hours.indexOf(Alarms.hours(this)).coerceAtLeast(0)) { i -> Alarms.setHours(this, hours[i]) }
        actionRow(al, "Alarmlarım", null, "${Alarms.load(this).size} ürün takipte", "Aç") { showAlarms() }

        // Hafıza
        val mem = settingsCard("Hafıza")
        switchRow(mem, "Seçimlerimi hatırla", "\"Hangisini kastettin?\" sorusundaki seçimin bir dahaki aramada sorulmaz",
            flag("remember")) { v -> settings.edit().putBoolean("remember", v).apply() }
        actionRow(mem, "Son aramaları temizle", null, "${recentSearches().size} arama kayıtlı", "Temizle") {
            prefs.edit().remove("recent").apply()
            Toast.makeText(this, "Son aramalar silindi", Toast.LENGTH_SHORT).show()
            showSettings()
        }

        // Geliştirici modu gizli: sürüm yazısına 7 kez dokununca açılır/kapanır
        results.addView(label("Fiyat Avcısı · ${Update.currentName(this)}" + if (flag("devMode")) " · geliştirici" else "", 12f, muted).apply {
            setOnClickListener {
                devTaps++
                if (devTaps >= 7) {
                    devTaps = 0
                    val on = !flag("devMode")
                    settings.edit().putBoolean("devMode", on).apply()
                    Toast.makeText(this@MainActivity, if (on) "Geliştirici modu açıldı" else "Geliştirici modu kapandı", Toast.LENGTH_SHORT).show()
                    showSettings()
                }
            }
            gravity = Gravity.CENTER
            setPadding(0, dp(20), 0, dp(10))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        resultsScroll.post { resultsScroll.scrollTo(0, 0) }
    }

    // ---------- Fiyat alarmı ----------

    /** İlan kartındaki zil: dokununca alarm kurma penceresi */
    /** Bu aramaya kurulu ürün alarmı (varsa grup numarası) */
    private fun productAlarmGroup(): Long? =
        Alarms.load(this).firstOrNull { it.groupId != 0L && norm(it.groupQuery) == norm(userInput) }?.groupId

    private fun bell(o: Offer): ImageView {
        val all = Alarms.load(this)
        val own = all.any { it.link.substringBefore('?') == o.link.substringBefore('?') }
        // Bu ürüne (aramaya) ürün alarmı kuruluysa aynı ürünün bütün ilanları "alarm var" görünür
        val gid = if (o.kind == Kind.MATCH || o.kind == Kind.OTHER_PACKAGE) productAlarmGroup() else null
        val on = own || gid != null
        return ImageView(this).apply {
            setImageResource(if (on) R.drawable.ic_bell else R.drawable.ic_bell_off)
            imageTintList = android.content.res.ColorStateList.valueOf(if (on) gold else muted)
            contentDescription = if (on) "Alarm kurulu" else "Fiyat alarmı kur"
            setPadding(dp(8), dp(8), dp(4), dp(8))
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
            setOnClickListener {
                val g = gid ?: all.firstOrNull { it.link.substringBefore('?') == o.link.substringBefore('?') && it.groupId != 0L }?.groupId
                if (g != null) productAlarmDialog(g, o)
                else alarmDialog(o.title, o.link, o.site, o.image, o.price, available = true, offer = o)
            }
        }
    }

    /** Ürün alarmı zaten kurulu: bilgi + bu ilanı ekle / hedefi değiştir / sil */
    private fun productAlarmDialog(gid: Long, o: Offer) {
        val items = Alarms.load(this).filter { it.groupId == gid }
        if (items.isEmpty()) { alarmDialog(o.title, o.link, o.site, o.image, o.price, available = true, offer = o); return }
        val first = items.first()
        val unit = first.unitTarget > 0 || first.unitAmount > 0
        val fmt = { v: Double -> if (unit) Alarms.unitText(v, items.firstOrNull { it.unitName.isNotEmpty() }?.unitName ?: "adet") else money(v) }
        val best = items.filter { Alarms.metric(it) != null }.minByOrNull { Alarms.metric(it)!! }
        val tracked = items.any { it.link.substringBefore('?') == o.link.substringBefore('?') }
        val target = if (first.unitTarget > 0) first.unitTarget else first.target
        val msg = StringBuilder()
        msg.append("${first.groupName.ifEmpty { first.title }}\n\n")
        msg.append("${items.size} ilan takipte: ${items.map { it.site }.distinct().joinToString(", ")}\n")
        best?.let { msg.append("Şu an en uygun: ${it.site} · ${fmt(Alarms.metric(it)!!)}\n") }
        msg.append(if (target > 0) "Hedef: ${fmt(target)}" else "Hedef: her ucuzlamada haber ver")
        msg.append("\n\n").append(if (tracked) "Dokunduğun ilan zaten takipte." else "Dokunduğun ilan (${o.site}, ${money(o.price)}) takipte değil.")
        AlertDialog.Builder(this)
            .setTitle("Bu ürün için alarm kurulu")
            .setMessage(msg.toString())
            .apply {
                if (!tracked) setPositiveButton("Bu ilanı da ekle") { _, _ ->
                    val cu = offerUnit(o)
                    Alarms.save(this@MainActivity, Alarms.load(this@MainActivity) + first.copy(
                        id = System.currentTimeMillis(), title = o.title, site = o.site, link = o.link, image = o.image,
                        startPrice = o.price, lastPrice = o.price, lowest = o.price, lastCheck = 0L, status = "elle eklendi",
                        unitAmount = if (unit) cu?.first ?: 0.0 else 0.0, unitName = if (unit) cu?.second.orEmpty() else ""))
                    Toast.makeText(this@MainActivity, "İlan ürün alarmına eklendi", Toast.LENGTH_SHORT).show()
                    if (offers.isNotEmpty() && !running) render(final = finished)
                }
                else setPositiveButton("Kapat", null)
            }
            .setNeutralButton("Alarmı sil") { _, _ ->
                Alarms.removeGroup(this@MainActivity, gid)
                Toast.makeText(this@MainActivity, "Ürün alarmı silindi", Toast.LENGTH_SHORT).show()
                if (offers.isNotEmpty() && !running) render(final = finished)
            }
            .setNegativeButton("Hedefi değiştir") { _, _ ->
                // Yeni hedef kurulunca eski grup silinir (vazgeçilirse eskisi kalır)
                alarmDialog(o.title, o.link, o.site, o.image, o.price, available = true, offer = o, replaceGroup = gid)
            }
            .show()
    }

    /** İlanın birim bölümü: birim fiyat = fiyat / bölüm ("216 adet" -> 216, "200 g" -> 0,2 kg) */
    private fun offerUnit(o: Offer): Pair<Double, String>? {
        if (isSet(o)) return null
        val a = amountOf(o.title, queryAmount?.unit) ?: return null
        val total = a.total
        if (total <= 0) return null
        return when (a.unit) {
            "g" -> total / 1000 to "kg"
            "ml" -> total / 1000 to "L"
            else -> total to "adet"
        }
    }

    /** Ürün alarmı için her sitenin en uygun ilanı (aynı ürün; adetliyse aynı seri/numaranın başka paketleri de) */
    private fun groupCandidates(o: Offer): List<Offer> {
        val unit = offerUnit(o)?.second
        val cand = offers.filter {
            (it.kind == Kind.MATCH || (unit != null && it.kind == Kind.OTHER_PACKAGE && !isSet(it))) &&
                (unit == null || offerUnit(it)?.second == unit)
        } + o
        val metric = { x: Offer -> offerUnit(x)?.let { u -> if (unit != null) x.price / u.first else null } ?: x.price }
        return cand.groupBy { it.site }.values.map { g -> g.minByOrNull(metric)!! }
            .sortedBy(metric).take(10)
    }

    private fun alarmDialog(title: String, link: String, site: String, image: String, price: Double?, available: Boolean?,
                            offer: Offer? = null, replaceGroup: Long = 0L) {
        val unit = offer?.let { offerUnit(it) }
        val group = offer?.let { groupCandidates(it) }.orEmpty()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), dp(0))
        }
        box.addView(label(title.take(120), 14f, ink))
        // Ürün alarmında asıl ölçü: sitelerdeki en uygun ilan
        group.firstOrNull()?.takeIf { group.size >= 2 }?.let { b ->
            val bu = offerUnit(b)
            box.addView(label("Şu an en uygun: ${b.site} · " +
                (if (unit != null && bu != null) Alarms.unitText(b.price / bu.first, bu.second) + " (${money(b.price)})" else money(b.price)),
                13f, green, true).apply { setPadding(0, dp(6), 0, 0) })
        }
        box.addView(label(
            (if (price != null) "Dokunduğun ilan: ${money(price)} · $site" else "Şu an fiyat okunamadı · $site") +
                (if (unit != null && price != null) " · ${Alarms.unitText(price / unit.first, unit.second)}" else "") +
                if (available == false) " · stokta yok" else "",
            13f, muted, true
        ).apply { setPadding(0, dp(6), 0, dp(10)) })

        // Ne takip edilsin: bu ilan mı, ürün bütün sitelerde mi
        val scopeGroup = android.widget.RadioGroup(this)
        val rOne = android.widget.RadioButton(this).apply { id = View.generateViewId(); text = "Sadece bu ilan ($site)" }
        val rAll = android.widget.RadioButton(this).apply {
            id = View.generateViewId()
            text = "Bu ürünü bütün sitelerde takip et (${group.size} site: ${group.joinToString(", ") { it.site }})"
        }
        if (group.size >= 2) {
            box.addView(label("Ne takip edilsin?", 13f, ink, true))
            scopeGroup.addView(rOne); scopeGroup.addView(rAll)
            scopeGroup.check(rAll.id)
            box.addView(scopeGroup)
        }

        // Hedef: toplam fiyat mı, adet başı mı
        val modeGroup = android.widget.RadioGroup(this).apply { orientation = android.widget.RadioGroup.HORIZONTAL }
        val mTotal = android.widget.RadioButton(this).apply { id = View.generateViewId(); text = "Toplam fiyat" }
        val mUnit = android.widget.RadioButton(this).apply { id = View.generateViewId(); text = "${unit?.second?.replaceFirstChar { it.uppercase() } ?: "Adet"} başı" }
        val target = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine()
        }
        val setHint = {
            target.hint = if (unit != null && modeGroup.checkedRadioButtonId == mUnit.id)
                "Hedef TL/${unit.second} (boş: her düşüşte)" else "Hedef fiyat TL (boş: her düşüşte)"
        }
        if (unit != null) {
            box.addView(label("Hedef nasıl olsun?", 13f, ink, true).apply { setPadding(0, dp(8), 0, 0) })
            modeGroup.addView(mTotal); modeGroup.addView(mUnit)
            modeGroup.check(mUnit.id)
            modeGroup.setOnCheckedChangeListener { _, _ -> setHint() }
            box.addView(modeGroup)
        }
        setHint()
        box.addView(target)
        val stock = android.widget.CheckBox(this).apply {
            text = "Stoğa girince de haber ver"
            isChecked = available == false
        }
        box.addView(stock)
        box.addView(label("Fiyatlar ${Alarms.hours(this)} saatte bir kontrol edilir.", 12f, muted)
            .apply { setPadding(0, dp(6), 0, 0) })
        val existing = Alarms.load(this).firstOrNull { it.link.substringBefore('?') == link.substringBefore('?') }
        AlertDialog.Builder(this)
            .setTitle(if (existing != null) "Alarm kurulu — güncelle" else "Fiyat alarmı kur")
            .setView(box)
            .setPositiveButton("Kur") { _, _ -> try {
                if (replaceGroup != 0L) Alarms.removeGroup(this, replaceGroup)
                val t = target.text.toString().replace(",", ".").toDoubleOrNull() ?: 0.0
                val byUnit = unit != null && modeGroup.checkedRadioButtonId == mUnit.id
                val wantGroup = group.size >= 2 && scopeGroup.checkedRadioButtonId == rAll.id
                if (wantGroup) {
                    val gid = System.currentTimeMillis()
                    val name = identity?.label ?: query
                    val items = group.mapIndexed { i, c ->
                        val cu = offerUnit(c)
                        Alarm(
                            id = gid + i, title = c.title, site = c.site, link = c.link, image = c.image,
                            startPrice = c.price, lastPrice = c.price, lowest = c.price,
                            target = if (byUnit) 0.0 else t, wantStock = stock.isChecked, inStock = true,
                            lastCheck = 0L, status = "henüz kontrol edilmedi",
                            unitAmount = if (byUnit) cu?.first ?: 0.0 else 0.0, unitName = if (byUnit) cu?.second.orEmpty() else "",
                            unitTarget = if (byUnit) t else 0.0, groupId = gid, groupName = name, groupQuery = userInput,
                        )
                    }
                    Alarms.addGroup(this, items)
                    Toast.makeText(this, "Ürün alarmı kuruldu: ${items.size} sitede takip", Toast.LENGTH_SHORT).show()
                } else {
                    val p = price ?: 0.0
                    Alarms.add(this, Alarm(
                        id = existing?.id ?: System.currentTimeMillis(), title = title, site = site, link = link, image = image,
                        startPrice = existing?.startPrice ?: p, lastPrice = p, lowest = if (p > 0) p else 0.0,
                        target = if (byUnit) 0.0 else t, wantStock = stock.isChecked, inStock = available,
                        lastCheck = 0L, status = "henüz kontrol edilmedi",
                        unitAmount = if (unit != null) unit.first else 0.0, unitName = unit?.second.orEmpty(),
                        unitTarget = if (byUnit) t else 0.0,
                    ))
                    Toast.makeText(this, "Alarm kuruldu", Toast.LENGTH_SHORT).show()
                }
                askNotificationPermission()
                if (offers.isNotEmpty() && !running) render(final = finished) else if (!running) showHome()
            } catch (e: Exception) {
                AlertDialog.Builder(this).setTitle("Alarm kurulamadı")
                    .setMessage(e.javaClass.simpleName + ": " + (e.message ?: ""))
                    .setNegativeButton("Kapat", null).show()
            } }
            .apply {
                if (existing != null) setNeutralButton("Alarmı sil") { _, _ ->
                    if (existing.groupId != 0L) Alarms.removeGroup(this@MainActivity, existing.groupId)
                    else Alarms.remove(this@MainActivity, existing.id)
                    if (offers.isNotEmpty() && !running) render(final = finished)
                }
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    private fun askNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7)
        }
    }

    /** Alarmlarım ekranı */
    private fun showAlarms() {
        if (running) return
        screen = "alarms"
        filterBar.visibility = View.GONE
        chipsScroll.visibility = View.GONE
        progress.visibility = View.GONE
        status.visibility = View.VISIBLE
        status.text = "Alarmlar ${Alarms.hours(this)} saatte bir arka planda kontrol edilir"
        results.removeAllViews()
        val list = Alarms.load(this)
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }
        head.addView(label("Alarmlarım (${list.size})", 18f, ink, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(label("Ana sayfa", 13f, muted, true).apply {
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener { status.visibility = View.GONE; showHome() }
        })
        if (list.isNotEmpty()) head.addView(label("Şimdi kontrol et", 13f, accentOn, true).apply {
            background = rounded(gold, 12)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener {
                status.text = "Kontrol ediliyor…"
                progress.visibility = View.VISIBLE
                Thread {
                    val n = try { Alarms.checkAll(applicationContext) } catch (e: Exception) { 0 }
                    handler.post {
                        progress.visibility = View.GONE
                        Toast.makeText(this@MainActivity, if (n > 0) "$n bildirim gönderildi" else "Değişiklik yok", Toast.LENGTH_SHORT).show()
                        showAlarms()
                    }
                }.start()
            }
        })
        results.addView(head)
        if (list.isEmpty()) {
            noticeCard("Henüz alarm yok. Arama sonuçlarında bir ilanın 🔕 düğmesine dokun ya da Trendyol, Hepsiburada gibi " +
                "uygulamalarda bir ürünü Paylaş → Fiyat Avcısı ile gönder.", ink, amberBg)
            return
        }
        val df = java.text.SimpleDateFormat("d MMM HH:mm", Locale("tr", "TR"))
        // Ürün alarmları: bir kart, içinde siteler
        for ((gid, items) in list.filter { it.groupId != 0L }.groupBy { it.groupId }) {
            val best = items.filter { Alarms.metric(it) != null }.minByOrNull { Alarms.metric(it)!! } ?: items.first()
            val unit = best.unitAmount > 0
            val fmt = { v: Double -> if (unit) Alarms.unitText(v, best.unitName) else money(v) }
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(surface, 14, line)
                setPadding(dp(14), dp(12), dp(14), dp(12))
            }
            card.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(label("Ürün alarmı · ${items.size} site", 12f, gold, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(label("Ara ve güncelle", 13f, gold, true).apply {
                    setPadding(dp(8), dp(2), dp(8), dp(2))
                    setOnClickListener { input.setText(best.groupQuery); startTest() }
                })
                addView(label("Sil", 13f, red, true).apply {
                    setPadding(dp(8), dp(2), dp(2), dp(2))
                    setOnClickListener { Alarms.removeGroup(this@MainActivity, gid); showAlarms() }
                })
            })
            card.addView(label(best.groupName.ifEmpty { best.title }, 16f, ink, true).apply { setPadding(0, dp(4), 0, dp(2)) })
            Alarms.metric(best)?.let { m ->
                card.addView(label("En uygun: ${best.site} · ${fmt(m)}" + if (unit) " · ${money(best.lastPrice)}" else "", 14f, green, true))
            }
            val t = if (best.unitTarget > 0) best.unitTarget else best.target
            card.addView(label(if (t > 0) "Hedef: ${fmt(t)}" else "Hedef: her ucuzlamada haber ver", 12f, muted).apply { setPadding(0, dp(2), 0, dp(6)) })
            items.sortedBy { Alarms.metric(it) ?: Double.MAX_VALUE }.forEach { a ->
                card.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dp(4), 0, dp(4))
                    setOnClickListener { try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(a.link))) } catch (e: Exception) {} }
                    addView(label("● ", 12f, siteColors[a.site] ?: muted))
                    addView(label(a.site, 13f, ink), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(label((Alarms.metric(a)?.let { fmt(it) } ?: "?") + if (a.status.startsWith("fiyat okunamadı")) " · okunamadı" else "", 13f, muted))
                })
            }
            if (items.any { it.lastCheck > 0 }) card.addView(label("Son kontrol ${df.format(java.util.Date(items.maxOf { it.lastCheck }))}", 11f, muted)
                .apply { setPadding(0, dp(4), 0, 0) })
            results.addView(card, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(2), dp(4), dp(2), dp(4)) })
        }
        for (a in list.filter { it.groupId == 0L }) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                background = rounded(surface, 14, line)
                setPadding(dp(12), dp(12), dp(12), dp(12))
                setOnClickListener { try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(a.link))) } catch (e: Exception) {} }
            }
            val iv = ImageView(this).apply {
                background = rounded(surface, 12, line)
                setPadding(dp(4), dp(4), dp(4), dp(4))
                scaleType = ImageView.ScaleType.FIT_CENTER
                if (a.image.isNotEmpty()) loadImage(a.image, this, Uri.parse(a.link).let { u -> "${u.scheme}://${u.host}/" })
            }
            card.addView(iv, LinearLayout.LayoutParams(dp(64), dp(64)).apply { marginEnd = dp(12) })
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(pill(a.site, Color.WHITE, siteColors[a.site] ?: ink, bold = true))
                addView(View(this@MainActivity), LinearLayout.LayoutParams(0, 1, 1f))
                addView(label("Sil", 13f, red, true).apply {
                    setPadding(dp(10), dp(2), dp(2), dp(2))
                    setOnClickListener { Alarms.remove(this@MainActivity, a.id); showAlarms() }
                })
            })
            col.addView(label(a.title, 14f, ink).apply {
                maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(6), 0, dp(4))
            })
            val now = if (a.lastPrice > 0) money(a.lastPrice) else "?"
            col.addView(label(now, 18f, if (a.lastPrice > 0 && a.lastPrice < a.startPrice) green else ink, true))
            val chips = FlowLayout(this, dp(6)).apply { setPadding(0, dp(6), 0, 0) }
            if (a.startPrice > 0) chips.addView(pill("başta ${money(a.startPrice)}", ink, greyBg))
            if (a.lowest > 0 && a.lowest < a.startPrice) chips.addView(pill("en düşük ${money(a.lowest)}", green, greenBg))
            chips.addView(pill(when {
                a.unitTarget > 0 -> "hedef ${Alarms.unitText(a.unitTarget, a.unitName)}"
                a.target > 0 -> "hedef ${money(a.target)}"
                else -> "her düşüşte"
            }, ink, amberBg))
            a.unitPrice()?.let { chips.addView(pill(Alarms.unitText(it, a.unitName), ink, greyBg)) }
            if (a.wantStock) chips.addView(pill(when (a.inStock) { false -> "stok bekleniyor"; true -> "stokta"; else -> "stok takibi" },
                if (a.inStock == false) orange else green, if (a.inStock == false) orangeBg else greenBg))
            col.addView(chips)
            col.addView(label(
                (if (a.lastCheck > 0) "Son kontrol ${df.format(java.util.Date(a.lastCheck))} · " else "") + a.status,
                11f, muted
            ).apply { setPadding(0, dp(6), 0, 0) })
            card.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            results.addView(card, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(2), dp(4), dp(2), dp(4)) })
        }
        noticeCard("Xiaomi telefonlarda arka plan kontrolünün çalışması için: Ayarlar → Uygulamalar → Fiyat Avcısı → " +
            "Pil tasarrufu → Kısıtlama yok.", muted, greyBg)
    }

    /** Başka uygulamadan "Paylaş" ile gelen ürün linki: karşılaştır ya da alarm kur */
    private fun handleShare(i: Intent?) {
        if (i?.action != Intent.ACTION_SEND) return
        val text = i.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        i.action = null
        handleSharedText(text)
    }

    /** Kameradan barkod okutma (Google'ın tarayıcısı; ilk kullanımda kendini indirebilir) */
    private fun scanBarcode() {
        if (running) return
        try {
            val opts = com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(
                    com.google.mlkit.vision.barcode.common.Barcode.FORMAT_EAN_13,
                    com.google.mlkit.vision.barcode.common.Barcode.FORMAT_EAN_8,
                    com.google.mlkit.vision.barcode.common.Barcode.FORMAT_UPC_A,
                    com.google.mlkit.vision.barcode.common.Barcode.FORMAT_UPC_E,
                    com.google.mlkit.vision.barcode.common.Barcode.FORMAT_CODE_128,
                    com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE)
                .enableAutoZoom()
                .build()
            com.google.mlkit.vision.codescanner.GmsBarcodeScanning.getClient(this, opts).startScan()
                .addOnSuccessListener { b ->
                    val raw = (b.rawValue ?: "").trim()
                    when {
                        raw.startsWith("http") -> handleSharedText(raw)
                        raw.isNotEmpty() -> { input.setText(raw); startTest() }
                    }
                }
                .addOnFailureListener { e ->
                    Toast.makeText(this, "Barkod okuyucu açılamadı. İlk kullanımda Google birkaç saniye indirme yapıyor olabilir, " +
                        "biraz sonra tekrar dene. (${e.message ?: e.javaClass.simpleName})", Toast.LENGTH_LONG).show()
                }
        } catch (e: Throwable) {
            Toast.makeText(this, "Barkod okuyucu bu telefonda açılamadı: ${e.message ?: e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
        }
    }

    /** Paylaşılan ya da QR'dan okunan yazı: linkse ürünü oku, değilse ara */
    private fun handleSharedText(text: String) {
        val url = Regex("https?://\\S+").find(text)?.value?.trimEnd('.', ',', ')') ?: run {
            // Link yoksa paylaşılan yazıyı arama olarak kullan
            if (text.isNotBlank()) { input.setText(text.take(80)); startTest() }
            return
        }
        status.visibility = View.VISIBLE
        status.text = "Paylaşılan ürün okunuyor…"
        progress.visibility = View.VISIBLE
        Thread {
            val info = try { Alarms.fetch(url) } catch (e: Exception) { null }
            handler.post {
                progress.visibility = View.GONE
                status.text = ""
                val finalUrl = info?.finalUrl ?: url
                val host = Uri.parse(finalUrl).host.orEmpty()
                val site = sites.firstOrNull { host.contains(it.name.lowercase(Locale.ROOT)) }?.name
                    ?: host.removePrefix("www.").substringBefore('.').replaceFirstChar { it.uppercase() }
                // Ürün adı: sayfa başlığı (site adı ve "Fiyatı" gibi ekler atılır); yoksa paylaşılan yazı
                val rawTitle = info?.title?.takeIf { it.isNotBlank() } ?: text.replace(url, "").trim()
                val name = rawTitle.split(Regex("\\s[-|–]\\s")).first()
                    .replace(Regex("(?i)\\b(fiyatı|fiyatları|yorumları|özellikleri|satın al|ve)\\b"), " ")
                    .replace(Regex("\\s+"), " ").trim()
                val searchName = name.split(" ").take(8).joinToString(" ")
                AlertDialog.Builder(this)
                    .setTitle(name.take(90).ifEmpty { "Paylaşılan ürün" })
                    .setMessage((info?.price?.let { "Şu an ${money(it)} · $site" } ?: "Fiyat okunamadı · $site") +
                        (if (info?.inStock == false) " · stokta yok" else ""))
                    .setPositiveButton("Fiyatları karşılaştır") { _, _ ->
                        if (searchName.isNotBlank()) { input.setText(searchName); startTest() }
                    }
                    .setNeutralButton("Alarm kur") { _, _ ->
                        alarmDialog(name.ifEmpty { finalUrl }, finalUrl, site, info?.image.orEmpty(), info?.price, info?.inStock)
                    }
                    .setNegativeButton("Vazgeç", null)
                    .show()
            }
        }.start()
    }

    // ---------- Rapor ----------

    private fun buildReport(matches: List<Offer>, others: List<Offer>) {
        report.setLength(0)
        val total = android.os.SystemClock.elapsedRealtime() - startedAt
        report.append("FİYAT AVCISI · DENEME 53\nGiriş: ").append(userInput)
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
