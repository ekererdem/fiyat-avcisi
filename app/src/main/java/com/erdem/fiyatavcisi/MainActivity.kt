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
class MainActivity : Activity(), MatchLogic {

    /** linkPattern: o sitede bir ürün sayfası linkini tanıyan kalıp */
    /** cats: sitenin tarandığı kategoriler (boş = hepsi). Market zinciri ayakkabı aramasında taranmaz. */
    private data class Site(val name: String, val linkPattern: String, val cats: Set<String> = emptySet(), val url: (String) -> String)




    private val sites = listOf(
        Site("Trendyol", "-p-[0-9]") { "https://www.trendyol.com/sr?q=$it" },
        Site("Hepsiburada", "-pm?-[A-Za-z0-9]") { "https://www.hepsiburada.com/ara?q=$it" },
        Site("Amazon", "/dp/") { "https://www.amazon.com.tr/s?k=$it" },
        Site("MediaMarkt", "/product/", setOf("Elektronik", "Diğer")) { "https://www.mediamarkt.com.tr/tr/search.html?query=$it" },
        Site("Teknosa", "-p-[0-9]", setOf("Elektronik", "Diğer")) { "https://www.teknosa.com/arama/?s=$it" },
        Site("N11", "urun[.]n11[.]com|n11[.]com/urun/") { "https://www.n11.com/arama?q=$it" },
        Site("Pazarama", "pazarama[.]com/[^/?#]+-p-[A-Za-z0-9]+") { "https://www.pazarama.com/arama?q=$it" },
        // Market zincirleri
        Site("Migros", "migros[.]com[.]tr/[^/?#]+-p-[0-9a-f]+", setOf("Market", "Bebek", "Kozmetik")) { "https://www.migros.com.tr/arama?q=$it" },
        Site("CarrefourSA", "carrefoursa[.]com/[^/?#]+-p-[0-9]{5,}", setOf("Market", "Bebek", "Kozmetik")) { "https://www.carrefoursa.com/search/?text=$it" },
        Site("A101", "a101[.]com[.]tr/.+_p-[0-9]+", setOf("Market", "Bebek", "Kozmetik")) { "https://www.a101.com.tr/arama?k=$it" },
        Site("Şok", "sokmarket[.]com[.]tr/[^/?#]+-p-[0-9]+", setOf("Market", "Bebek", "Kozmetik")) { "https://www.sokmarket.com.tr/arama?q=$it" },
        // Elektronik
        Site("Vatan", "vatanbilgisayar[.]com/[a-z0-9-]+[.]html", setOf("Elektronik")) { "https://www.vatanbilgisayar.com/arama/$it/" },
        // Giyim, ayakkabı, spor
        Site("Boyner", "boyner[.]com[.]tr/[^/?#]+-p-[0-9]+", setOf("Giyim", "Kozmetik")) { "https://www.boyner.com.tr/search?q=$it" },
        Site("Flo", "flo[.]com[.]tr/(?:urun/)?[^/?#]+-[0-9]{6,}", setOf("Giyim")) { "https://www.flo.com.tr/arama?q=$it" },
        Site("Decathlon", "decathlon[.]com[.]tr/p/.+R-p-[0-9]+", setOf("Giyim")) { "https://www.decathlon.com.tr/search?Ntt=$it" },
    )

    // Kimlik bulmak için hızlı taranan siteler (barkod ve kodla aramada en iyi sonuç verenler)
    private val resolverSites by lazy { listOf(sites[0], sites[2], sites[1]) }


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
            // Loş lacivert (eski Twitter "Dim" teması gibi)
            paper = c("#15202B"); surface = c("#1C2A38"); greyBg = c("#253341"); ink = c("#F2F5F7"); muted = c("#8B98A5")
            hintColor = c("#66768A"); line = c("#2F3E4E"); gold = c("#2DD4BF"); accentOn = c("#0F1F24"); brand = c("#F2C14E")
            // Vurgu rengi: İzci logosunun sarısı (önceden yeşildi)
            green = c("#2DD4BF"); red = c("#F4908A"); orange = c("#F5A962")
            greenBg = c("#123A3A"); redBg = c("#3A2330"); orangeBg = c("#3A2E26"); amberBg = c("#15343A")
        } else {
            paper = c("#FFFFFF"); surface = c("#FFFFFF"); greyBg = c("#F2F2F2"); ink = c("#111111"); muted = c("#6B6B6B")
            hintColor = c("#9A9A9A"); line = c("#E8E8E8"); gold = c("#0D9488"); accentOn = c("#FFFFFF"); brand = c("#F2C14E")
            green = c("#0D9488"); red = c("#B42318"); orange = c("#B54708")
            greenBg = c("#DDF7F3"); redBg = c("#FDECEA"); orangeBg = c("#FDF0E3"); amberBg = c("#E6FAF7")
        }
    }

    override var identity: Identity? = null
    private var userInput = ""
    private var resolveType = ""     // barkod | kod | ad
    private var resolveQuery = ""    // kimlik aranırken kullanılan yazı (barkoddan bulunan ad olabilir)
    private var googleTried = false
    private val barcodeHits = mutableListOf<Offer>()   // barkodla sitelerde birebir bulunan ilanlar
    override var bookMode = false      // ISBN (978/979) = kitap: muadil yok, garanti yok
    private val resolveLog = StringBuilder()
    private val prefs by lazy { getSharedPreferences("kimlik", Context.MODE_PRIVATE) }
    private val detailQueue = mutableListOf<Offer>()
    override var queryAmount: Amount? = null   // aramadaki miktar: 200 g, 80 adet, 100 ml
    override var acceptedMods: Set<String> = emptySet()
    override var requiredSeries: Set<String> = emptySet()
    private val settings by lazy { getSharedPreferences("ayarlar", Context.MODE_PRIVATE) }
    private fun flag(k: String, def: Boolean = false) = if (k == "devMode") false else settings.getBoolean(k, def)
    private fun siteOn(name: String) = settings.getBoolean("site_$name", true)
    private var screen = "home"           // home / results / alarms / settings
        set(v) {
            field = v
            updateNav()
            // Ana sayfada üstteki arama kutusu gizli: ortadaki barkod kartı ve arama kutusu kullanılır
            if (::searchBar.isInitialized) searchBar.visibility = if (v == "results" || v == "product") View.VISIBLE else View.GONE
        }
    private lateinit var searchBar: LinearLayout
    private var homeInput: EditText? = null

    // Alt menü çubuğu: Ana sayfa, Barkod, Alarmlar, Ayarlar
    private lateinit var navBar: LinearLayout
    private val navItems = mutableListOf<Triple<String, ImageView, TextView>>()

    private fun updateNav() {
        if (!::navBar.isInitialized) return
        val cur = when (screen) {
            "item", "pick", "barcode" -> homeFilter
            "settings" -> "profile"
            "results", "product" -> "search"
            else -> screen
        }
        for ((key, icon, text) in navItems) {
            val on = key == cur
            val col = if (on) gold else muted
            icon.imageTintList = android.content.res.ColorStateList.valueOf(col)
            text.setTextColor(col)
        }
    }

    private fun buildNav(): LinearLayout {
        navBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(surface)
            setPadding(0, dp(6), 0, dp(6))
        }
        fun item(key: String, icon: Int, text: String, action: () -> Unit) {
            val iv = ImageView(this).apply { setImageResource(icon) }
            // Ayarlar simgesinde yeni sürüm noktası
            val iconView: View = if (key != "profile") iv else FrameLayout(this).apply {
                addView(iv, FrameLayout.LayoutParams(dp(24), dp(24)))
                gearDot = View(this@MainActivity).apply {
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c("#E5484D")); setStroke(dp(2), surface) }
                    visibility = View.GONE
                }
                addView(gearDot, FrameLayout.LayoutParams(dp(11), dp(11)).apply { gravity = Gravity.TOP or Gravity.END })
            }
            val tv = label(text, 11f, muted, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(3), 0, 0) }
            navBar.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                contentDescription = text
                minimumHeight = dp(52)
                background = android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(greyBg), null, rounded(Color.WHITE, 12))
                setOnClickListener { action() }
                addView(iconView, LinearLayout.LayoutParams(dp(24), dp(24)))
                addView(tv)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            navItems.add(Triple(key, iv, tv))
        }
        item("home", R.drawable.ic_home, "Ana sayfa") { if (running) { stopAll(); running = false; progress.visibility = View.GONE }; homeFilter = "home"; goHome() }
        item("alarms", R.drawable.ic_bell, "Alarmlar") { showAlarms("alarms") }
        item("watch", R.drawable.ic_watch, "Takip") { showAlarms("watch") }
        item("profile", R.drawable.ic_person, "Profil") { showSettings() }
        updateNav()
        updateGearDot()
        return navBar
    }
    private val categories = listOf("Elektronik", "Market", "Kozmetik", "Giyim", "Bebek", "Diğer")
    private var currentCategory = "Diğer"
    private var editCat = "Genel"


    // Filtre kuralları her kategoride aynı uygulanır (kategoriye özel ayar kaldırıldı)
    private fun ruleOn(k: String): Boolean = flag(k)

    private fun ruleMinRating(): Float = settings.getFloat("minRating", 0f)

    private fun ruleMinReviews(): Int = settings.getInt("minReviews", 0)
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
    private var avatarView: TextView? = null

    // ---------- Profil (telefonda saklanır) ----------
    private fun profileName() = settings.getString("profileName", "").orEmpty()
    private fun profileMail() = settings.getString("profileMail", "").orEmpty()

    private fun initials(): String {
        val n = profileName().trim().ifEmpty { profileMail().substringBefore('@') }
        if (n.isEmpty()) return "İ"
        val parts = n.split(Regex("[\\s._-]+")).filter { it.isNotEmpty() }
        return (parts.take(2).joinToString("") { it.take(1) }).uppercase(Locale("tr", "TR"))
    }

    /** Soldan açılan menü: profil, sayfalar, ayarlar */
    private fun showDrawer() {
        val d = android.app.Dialog(this, android.R.style.Theme_Material_NoActionBar)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(surface)
            setPadding(dp(18), dp(36), dp(18), dp(18))
        }
        // Profil başlığı
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(8), dp(4), dp(16))
            setOnClickListener { d.dismiss(); profileDialog() }
        }
        head.addView(label(initials(), 20f, brand, true).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (dark) c("#253341") else c("#111111")) }
        }, LinearLayout.LayoutParams(dp(56), dp(56)).apply { marginEnd = dp(14) })
        val who = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (profileMail().isEmpty() && profileName().isEmpty()) {
            who.addView(label("Giriş yap", 17f, ink, true))
            who.addView(label("E-posta adresinle profil oluştur", 13f, muted))
        } else {
            who.addView(label(profileName().ifEmpty { profileMail().substringBefore('@') }, 17f, ink, true))
            if (profileMail().isNotEmpty()) who.addView(label(profileMail(), 13f, muted))
            who.addView(label("Profili düzenle", 12f, gold, true).apply { setPadding(0, dp(3), 0, 0) })
        }
        head.addView(who, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        panel.addView(head)
        panel.addView(View(this).apply { setBackgroundColor(line) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))

        fun item(icon: Int, text: String, badge: String? = null, badgeColor: Int = gold, action: () -> Unit) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(6), dp(14), dp(6), dp(14))
                background = android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(greyBg), null, rounded(Color.WHITE, 12))
                setOnClickListener { d.dismiss(); action() }
            }
            row.addView(ImageView(this).apply {
                setImageResource(icon); imageTintList = android.content.res.ColorStateList.valueOf(ink)
            }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(16) })
            row.addView(label(text, 16f, ink), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            badge?.let { row.addView(pill(it, Color.WHITE, badgeColor, bold = true)) }
            panel.addView(row)
        }
        item(R.drawable.ic_search, "Ana sayfa") { if (running) { stopAll(); running = false; progress.visibility = View.GONE }; goHome() }
        item(R.drawable.ic_barcode, "Barkod okut") { scanBarcode() }
        val ac = try { Alarms.load(this).size } catch (e: Exception) { 0 }
        item(R.drawable.ic_bell, "Alarmlarım", if (ac > 0) "$ac" else null) { showAlarms() }
        item(R.drawable.ic_gear, "Ayarlar", if (Update.pending(this) != null) "Güncelleme var" else null, c("#E5484D")) { showSettings() }
        if (flag("devMode")) {
            panel.addView(label("Geliştirici", 12f, muted, true).apply { setPadding(dp(6), dp(14), 0, dp(2)) })
            item(R.drawable.ic_more, if (resultsScroll.visibility == View.VISIBLE) "Tarayıcıyı göster" else "Sonuçları göster") { toggleBrowser() }
            item(R.drawable.ic_more, "Raporu kopyala") { copyReport() }
            item(R.drawable.ic_share, "Raporu paylaş") { shareReport() }
        }
        panel.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        panel.addView(label("İzci ${Update.currentName(this)}", 12f, muted).apply { setPadding(dp(6), 0, 0, 0) })

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(110, 0, 0, 0))
            setOnClickListener { d.dismiss() }
        }
        panel.isClickable = true
        root.addView(panel, FrameLayout.LayoutParams((resources.displayMetrics.widthPixels * 0.8).toInt(), ViewGroup.LayoutParams.MATCH_PARENT))
        d.setContentView(root)
        d.window?.let { w ->
            w.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            w.setWindowAnimations(android.R.style.Animation_Translucent)
        }
        panel.translationX = -resources.displayMetrics.widthPixels * 0.8f
        d.setOnShowListener { panel.animate().translationX(0f).setDuration(180).start() }
        d.show()
    }

    /** Profil: ad ve e-posta (telefonda saklanır) */
    private fun profileDialog() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(8), dp(22), 0) }
        val name = EditText(this).apply { hint = "Adın"; setText(profileName()); setSingleLine() }
        val mail = EditText(this).apply {
            hint = "E-posta"; setText(profileMail()); setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        box.addView(name); box.addView(mail)
        box.addView(label("Bilgiler sadece bu telefonda saklanır.", 12f, muted).apply { setPadding(0, dp(8), 0, 0) })
        val b = android.app.AlertDialog.Builder(this)
            .setTitle(if (profileMail().isEmpty()) "Giriş yap" else "Profil")
            .setView(box)
            .setPositiveButton("Kaydet") { _, _ ->
                val m = mail.text.toString().trim()
                if (m.isNotEmpty() && !android.util.Patterns.EMAIL_ADDRESS.matcher(m).matches()) {
                    Toast.makeText(this, "E-posta adresi geçersiz", Toast.LENGTH_LONG).show(); return@setPositiveButton
                }
                settings.edit().putString("profileName", name.text.toString().trim()).putString("profileMail", m).apply()
                avatarView?.text = initials()
                if (screen == "profile") showSettings()
            }
            .setNegativeButton("Vazgeç", null)
        if (profileMail().isNotEmpty() || profileName().isNotEmpty()) b.setNeutralButton("Çıkış yap") { _, _ ->
            settings.edit().remove("profileName").remove("profileMail").apply()
            avatarView?.text = initials()
        }
        b.show()
    }
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
    // Aramaya özel filtreler (hafıza, renk, beden...): ad -> seçili değerler
    private val facetSel = LinkedHashMap<String, MutableSet<String>>()
    private lateinit var facetRow: LinearLayout
    private lateinit var facetScroll: HorizontalScrollView
    private val sortNames = listOf("Önerilen", "En ucuz", "En pahalı", "Tane / kg / L başı fiyat", "Puan", "Değerlendirme sayısı", "Kargo dahil en ucuz")
    private val refilter = Runnable { if (offers.isNotEmpty()) render(final = finished) }

    private val handler = Handler(Looper.getMainLooper())
    private val report = StringBuilder()
    override val offers = mutableListOf<Offer>()
    private val siteSummary = mutableListOf<String>()

    private var running = false
    override var query = ""
    override var traitCat = "Diğer"

    /**
     * Ürün kartı okuyucu. Her ürün linkinden yukarı doğru çıkıp yalnızca o ürünü içeren
     * en büyük kutuyu "kart" kabul eder; kartın içinden ad, fiyat ve puanı çeker.
     * Taksit, aylık ödeme, kupon ve kargo tutarlarını fiyat saymadan önce siler.
     */
    private fun extractJs(linkPattern: String, q: String = "") = """
        (function(){
          var pat = new RegExp('__PAT__');
          // Migros: sayfa teslimat seçimi isteyip ürünleri çizmeyebiliyor; sitenin kendi arama servisinden oku
          if (location.host.indexOf('migros.com.tr') >= 0) {
            try {
              if (!window.__faMig) {
                var mx = new XMLHttpRequest();
                mx.open('GET', '/rest/search/screens/products?q=' + encodeURIComponent(__RQ__), false);
                mx.setRequestHeader('Accept', 'application/json');
                mx.send();
                window.__faMig = mx.status == 200 ? mx.responseText : ('ERR' + mx.status);
              }
              var mfound = [];
              if (window.__faMig.charAt(0) == '{') {
                (function walk(o, d){
                  if (!o || d > 9) return;
                  if (Array.isArray(o)) { o.forEach(function(e){ walk(e, d + 1); }); return; }
                  if (typeof o == 'object') {
                    if (o.prettyName && o.name && (o.shownPrice != null || o.regularPrice != null)) mfound.push(o);
                    else for (var k in o) walk(o[k], d + 1);
                  }
                })(JSON.parse(window.__faMig), 0);
              }
              var mc = mfound.map(function(o){
                var sp = o.shownPrice != null ? o.shownPrice : o.regularPrice, rp = o.regularPrice;
                var ln = String(o.prettyName).replace(/^\//, '');
                if (!/-p-[0-9a-f]+$/.test(ln) && o.id != null && !isNaN(o.id)) ln = ln + '-p-' + Number(o.id).toString(16);
                var img = '';
                try { var u = (o.images && o.images[0] && o.images[0].urls) || {}; img = u.PRODUCT_LIST || u.PRODUCT_DETAIL || u[Object.keys(u)[0]] || ''; } catch (e) {}
                var nm = (o.brand && o.brand.name && String(o.name).toLowerCase().indexOf(String(o.brand.name).toLowerCase()) < 0 ? o.brand.name + ' ' : '') + o.name;
                return { t: nm, p: sp / 100, lp: (rp && rp > sp) ? rp / 100 : 0, link: 'https://www.migros.com.tr/' + ln, img: img, raw: nm, r: '', rc: '' };
              }).filter(function(c){ return c.p > 0; });
              if (mc.length) return JSON.stringify({ title: document.title, url: location.href, block: '', anchors: mc.length, links: mc.length, cards: mc, api: true });
              if (window.__faMig.charAt(0) == '{' && !mfound.length && document.readyState == 'complete')
                return JSON.stringify({ title: document.title, url: location.href, block: '', noResults: true, anchors: 0, links: 0, cards: [] });
            } catch (e) {}
          }
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

          var low = (document.body ? document.body.innerText : '').toLocaleLowerCase('tr-TR');
          var words = ['captcha','robot olmad','robot değil','access denied','erişim engellendi','are you a human',
            'verify you are human','güvenlik doğrulaması','pardon our interruption','request blocked','just a moment',
            'karakterleri girin','characters you see'];
          var hit = '';
          for (var w = 0; w < words.length; w++) { if (low.indexOf(words[w]) >= 0) { hit = words[w]; break; } }
          // Site "bulunamadı" diyorsa altındaki öneri/popüler ürünleri sonuç sanma
          var head = low.slice(0, 5000);
          // (Sepet/favori kutusundaki "ürün bulunamadı" yazıları sayılmaz)
          var noresRe = /sonuç bulunamadı|ürün bulunamadı|bulamadık|sonuç bulunmamaktadır|ürün bulunmamaktadır|sonuç bulunmadı|eşleşen (bir )?(ürün|sonuç) (bulunamadı|yok)|için sonuç yok|uygun ürün bulunamadı|(^|[^0-9])0 (adet )?(ürün|sonuç)/g;
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
        .replace("__RQ__", JSONObject.quote(siteQuery(q)))
        .replace("__Q__", q.lowercase(Locale("tr", "TR")).replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim())

    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme()
        // Pencereler (diyalog, menü) de temaya uysun
        setTheme(if (dark) android.R.style.Theme_Material_NoActionBar else android.R.style.Theme_Material_Light_NoActionBar)
        super.onCreate(savedInstanceState)
        // Kontrol sıklığı artık sabit (3 saat): eski ayarla kurulmuş işi bir kez yeniden kur
        if (!settings.getBoolean("sched_fixed3", false)) {
            try { Alarms.schedule(this, Alarms.load(this).isNotEmpty(), force = true) } catch (e: Exception) {}
            settings.edit().putBoolean("sched_fixed3", true).apply()
        }
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
            if (onList() && !running) showHome()
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
                    .setPrimaryClip(ClipData.newPlainText("İzci hata", "İZCİ HATA\n" + trace))
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
            running -> { stopAll(); running = false; progress.visibility = View.GONE; goHome() }
            screen == "product" -> backToResults()
            screen == "item" -> itemGoBack()
            screen == "profile" || screen == "alarms" || screen == "watch" -> { homeFilter = "home"; goHome() }
            screen == "settings" -> {
                if (offers.isNotEmpty()) {
                    filterBar.visibility = View.VISIBLE
                    chipsScroll.visibility = if (running) View.VISIBLE else View.GONE
                    status.visibility = View.VISIBLE
                    render(final = finished)
                } else showHome()
            }
            screen == "results" -> goHome(search = true)
            screen == "search" -> goHome()
            screen == "pick" -> { pickFor = null; goHome() }
            screen == "barcode" -> goHome()
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    /** Sonuçları kapatıp listeye (ya da arama ekranına) dön */
    private fun goHome(search: Boolean = false) {
        // Arama sürüyorsa durdur: ana sayfada site çipleri ve "taranıyor" yazısı kalmasın
        if (running) { stopAll(); running = false }
        pickFor = null
        barcodeOnly = false
        progress.visibility = View.GONE
        filterBar.visibility = View.GONE
        chipsScroll.visibility = View.GONE
        status.visibility = View.GONE
        offers.clear()
        if (search) showSearch() else showHome()
        resultsScroll.post { resultsScroll.scrollTo(0, 0) }
    }

    override fun onResume() {
        super.onResume()
        maybeCheckUpdate()
        // İzin ekranından dönünce durumları yenile
        if (screen == "profile") showSettings()
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

        // Başlık: solda profil, ortada marka (oyma logo + İzci)
        val header = FrameLayout(this).apply {
            setPadding(dp(16), dp(14), dp(16), dp(10))
        }
        // Ortada marka: yuvarlak içinde oyma İzci logosu + yazı. Dokununca ana sayfa.
        header.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            contentDescription = "İzci, ana sayfa"
            setOnClickListener { if (!running) goHome() }
            addView(FrameLayout(this@MainActivity).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (dark) c("#253341") else c("#15202B")) }
                addView(ImageView(this@MainActivity).apply {
                    setImageResource(R.drawable.ic_logo)
                    imageTintList = android.content.res.ColorStateList.valueOf(brand)
                }, FrameLayout.LayoutParams(dp(22), dp(22)).apply { gravity = Gravity.CENTER })
            }, LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(10) })
            // Marka yazısı: Outfit Black, sonunda altın nokta ("İzci.")
            addView(TextView(this@MainActivity).apply {
                text = android.text.SpannableString("İzci.").apply {
                    setSpan(android.text.style.ForegroundColorSpan(brand), 4, 5, 0)
                }
                textSize = 30f
                setTextColor(ink)
                typeface = try { resources.getFont(R.font.outfit_black) } catch (e: Exception) {
                    android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
                }
                letterSpacing = -0.03f
                includeFontPadding = false
            })
            // Logo kadar boşluk: yazı ekranın tam ortasında kalsın, logo başında dursun
            addView(View(this@MainActivity), LinearLayout.LayoutParams(dp(48), 1))
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)).apply { gravity = Gravity.CENTER })
        root.addView(header)

        // Arama kutusu: hap biçimi, içinde barkod ve Ara düğmeleri
        searchBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(greyBg, 28)
            setPadding(dp(4), dp(4), dp(5), dp(4))
        }
        // Solda barkod düğmesi
        searchBar.addView(iconButton(R.drawable.ic_barcode, "Barkod okut") { scanBarcode() }.apply {
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(2) }
        })
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
        searchBar.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        // Sağda yeşil yuvarlak büyüteç: aramayı başlatır (klavyedeki ara tuşu da çalışır)
        searchBar.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_search)
            imageTintList = android.content.res.ColorStateList.valueOf(accentOn)
            contentDescription = "Ara"
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(gold) }
            setOnClickListener { startTest() }
        }, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginStart = dp(6) })
        root.addView(searchBar, LinearLayout.LayoutParams(
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
        facetRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        facetScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
            addView(facetRow)
        }
        filterBar.addView(facetScroll)
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
        root.addView(View(this).apply { setBackgroundColor(line) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
        root.addView(buildNav())
        // Klavye açıkken alt menü gizlenir (klavyenin üstüne binmesin)
        root.viewTreeObserver.addOnGlobalLayoutListener {
            val r = android.graphics.Rect()
            root.getWindowVisibleDisplayFrame(r)
            val full = root.rootView.height
            val kb = full > 0 && (full - r.bottom) > full * 0.2
            val want = if (kb) View.GONE else View.VISIBLE
            if (navBar.visibility != want) navBar.visibility = want
        }
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

    /** Ara ekranı: barkod kartı, arama kutusu, son aramalar */
    private fun showSearch() {
        if (running) return
        screen = "search"
        filterBar.visibility = View.GONE
        chipsScroll.visibility = View.GONE
        status.visibility = View.GONE
        progress.visibility = View.GONE
        results.removeAllViews()
        homeHero()

        val recent = recentSearches()
        results.addView(label(if (recent.isNotEmpty()) "Son aramalar" else "Örnek aramalar", 14f, muted, true)
            .apply { setPadding(dp(4), dp(28), dp(4), dp(10)) })
        val flow = FlowLayout(this, dp(8))
        (recent.ifEmpty { listOf("iphone 16 128gb", "nescafe gold 200 gr", "dior sauvage edt 100 ml", "prima 4 numara") })
            .forEach { q ->
                flow.addView(pill(q, ink, paper, size = 14f).apply {
                    background = rounded(paper, 20, line)
                    setPadding(dp(14), dp(8), dp(14), dp(8))
                    // Dokununca sadece arama kutusuna yazar: düzenleyip aramaya basılır
                    setOnClickListener {
                        val box = homeInput ?: input
                        box.setText(q)
                        box.setSelection(q.length)
                        box.requestFocus()
                        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                            .showSoftInput(box, InputMethodManager.SHOW_IMPLICIT)
                    }
                })
            }
        results.addView(flow)
        if (recent.isNotEmpty()) results.addView(label("Arama geçmişini temizle", 13f, muted, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(28), dp(12), dp(12))
            setOnClickListener {
                prefs.edit().remove("recent").apply()
                Toast.makeText(this@MainActivity, "Arama geçmişi temizlendi", Toast.LENGTH_SHORT).show()
                showSearch()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /** Ana sayfa: ortada büyük barkod kartı, altında arama kutusu (mağaza modu + sade) */
    private fun homeHero() {
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), dp(28), dp(4), 0) }
        // Barkod kartı
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = rounded(surface, 28, line)
            setPadding(dp(20), dp(30), dp(20), dp(26))
            setOnClickListener { scanBarcode() }
        }
        card.addView(FrameLayout(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(gold); setStroke(dp(10), greenBg) }
            contentDescription = "Barkod okut"
            setOnClickListener { scanBarcode() }
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_barcode)
                imageTintList = android.content.res.ColorStateList.valueOf(accentOn)
            }, FrameLayout.LayoutParams(dp(62), dp(62)).apply { gravity = Gravity.CENTER })
        }, LinearLayout.LayoutParams(dp(150), dp(150)))
        card.addView(TextView(this).apply {
            text = "Barkodu okut"
            textSize = 26f
            setTextColor(ink)
            gravity = Gravity.CENTER
            typeface = try { resources.getFont(R.font.outfit_black) } catch (e: Exception) { android.graphics.Typeface.DEFAULT_BOLD }
            letterSpacing = -0.02f
            setPadding(0, dp(16), 0, 0)
        })
        card.addView(label("Raftaki fiyat iyi mi, hemen gör", 14f, muted).apply { gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0) })
        wrap.addView(card)
        // Arama kutusu
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(greyBg, 28)
            setPadding(dp(18), dp(6), dp(6), dp(6))
        }
        bar.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_search)
            imageTintList = android.content.res.ColorStateList.valueOf(muted)
        }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(10) })
        val hi = EditText(this).apply {
            hint = "ya da ürün adını yaz"
            setSingleLine()
            textSize = 16f
            setTextColor(ink)
            setHintTextColor(hintColor)
            background = null
            setPadding(0, dp(8), 0, dp(8))
            imeOptions = EditorInfo.IME_ACTION_SEARCH
        }
        val go = {
            val q = hi.text.toString().trim()
            if (q.isNotEmpty()) { input.setText(q); startTest() }
        }
        hi.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEARCH) { go(); true } else false }
        homeInput = hi
        bar.addView(hi, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_arrow)
            imageTintList = android.content.res.ColorStateList.valueOf(accentOn)
            contentDescription = "Ara"
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(gold) }
            setOnClickListener { go() }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        wrap.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(24) })
        results.addView(wrap)
    }

    // ---------- Listem (ana sayfa) ----------

    /** Listedeki bir ürün: tek ilan ya da birçok sitede aynı ürün (grup) */
    private data class WishEntry(
        val gid: Long, val focus: Alarm, val items: List<Alarm>, val title: String,
        val price: Double?, val priceText: String, val target: Double, val targetText: String?,
        val start: Double, val lowest: Double, val watch: Boolean, val sub: String, val history: List<Pair<Long, Double>>,
    ) {
        val hit get() = target > 0 && price != null && price <= target
        val drop get() = if (price != null && start > 0 && price < start) (start - price) / start else 0.0
    }

    private fun wishEntries(all: List<Alarm>): List<WishEntry> {
        val out = mutableListOf<WishEntry>()
        for ((gid, items) in all.filter { it.groupId != 0L }.groupBy { it.groupId }) {
            val best = Alarms.groupBest(items) ?: items.first()
            val unit = best.unitTarget > 0 || best.unitAmount > 0
            val p = Alarms.metric(best)
            val fmt = { v: Double -> if (unit) Alarms.unitText(v, best.unitName) else money(v) }
            val t = if (best.unitTarget > 0) best.unitTarget else best.target
            val hist = History.get(this, "g:$gid")
            out.add(WishEntry(gid, best, items, best.groupName.ifEmpty { best.title }, p, p?.let(fmt) ?: "?",
                t, if (t > 0) fmt(t) else null, hist.firstOrNull()?.second ?: 0.0, hist.minOfOrNull { it.second } ?: 0.0,
                best.watch, "${items.size} link · en ucuz ${best.site}" + (if (best.seller.isNotEmpty()) " · ${best.seller}" else ""), hist))
        }
        for (a in all.filter { it.groupId == 0L }) {
            val byUnit = a.unitTarget > 0
            val p = if (byUnit) a.unitPrice() else a.lastPrice.takeIf { it > 0 }
            val fmt = { v: Double -> if (byUnit) Alarms.unitText(v, a.unitName) else money(v) }
            val t = if (byUnit) a.unitTarget else a.target
            out.add(WishEntry(0L, a, listOf(a), a.title, p, p?.let(fmt) ?: "?", t, if (t > 0) fmt(t) else null,
                if (byUnit) 0.0 else a.startPrice, if (byUnit) 0.0 else a.lowest, a.watch,
                a.site + (if (a.seller.isNotEmpty()) " · ${a.seller}" + (if (a.sellers > 1) " (${a.sellers} satıcıda en ucuz)" else "") else "") +
                    (if (a.inStock == false) " · stokta yok" else ""), History.offer(this, a.link)))
        }
        // Hedefe inenler en üstte, sonra en çok ucuzlayanlar, sonra en yeniler
        return out.sortedWith(compareByDescending<WishEntry> { it.hit }.thenByDescending { it.drop }.thenByDescending { it.focus.id })
    }

    @Volatile private var homeChecking = false

    /** Listem: takip edilen bütün ürünler; hedefe inenler üstte */
    /** Ana sayfa / Alarmlar / Takip: aynı kart listesi, farklı süzgeç */
    private var homeFilter = "home"
    private fun onList() = screen == "home" || screen == "alarms" || screen == "watch"

    private fun showHome() {
        if (running) return
        screen = homeFilter
        filterBar.visibility = View.GONE
        chipsScroll.visibility = View.GONE
        status.visibility = View.GONE
        progress.visibility = if (homeChecking) View.VISIBLE else View.GONE
        results.removeAllViews()
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
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        }

        val all = try { Alarms.load(this) } catch (e: Exception) { emptyList() }
        val list = wishEntries(all).filter { when (homeFilter) { "alarms" -> !it.watch; "watch" -> it.watch; else -> true } }
        val hits = list.count { it.hit }
        val newest = all.maxOfOrNull { it.lastCheck } ?: 0L

        // Başlık
        results.addView(label(when (homeFilter) { "alarms" -> "Alarmlar"; "watch" -> "Takip"; else -> "Listem" }, 26f, ink, true).apply {
            typeface = try { resources.getFont(R.font.outfit_black) } catch (e: Exception) { Typeface.DEFAULT_BOLD }
            setPadding(dp(4), dp(16), dp(4), 0)
        })
        val df = java.text.SimpleDateFormat("d MMM HH:mm", Locale("tr", "TR"))
        results.addView(label(when {
            list.isEmpty() && homeFilter == "alarms" -> "Alarm kurulu ürün yok. Bir kartın zilini açınca burada görünür."
            list.isEmpty() && homeFilter == "watch" -> "Sadece takip ettiğin ürün yok. Bir kartın zilini kapatınca burada görünür."
            list.isEmpty() -> "Almak istediğin ürünleri ekle, fiyatı düşünce haber vereyim."
            else -> "${list.size} ürün" + (if (hits > 0) " · $hits tanesi hedefte 🎯" else "") +
                (if (homeChecking) " · fiyatlar kontrol ediliyor…" else if (newest > 0) " · son kontrol ${df.format(java.util.Date(newest))}" else "")
        }, 13f, if (hits > 0) green else muted, hits > 0).apply { setPadding(dp(4), dp(4), dp(4), dp(12)) })

        // Düğmeler: link yapıştır, barkod okut, yenile
        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(2), 0, dp(2), dp(10)) }
        btns.addView(label("+  Link yapıştır", 14f, accentOn, true).apply {
            gravity = Gravity.CENTER
            background = rounded(gold, 22)
            setPadding(dp(10), dp(11), dp(10), dp(11))
            setOnClickListener { pasteLink() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
        btns.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = rounded(surface, 22, line)
            setPadding(dp(10), dp(9), dp(10), dp(9))
            setOnClickListener { scanBarcode() }
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_barcode)
                imageTintList = android.content.res.ColorStateList.valueOf(ink)
            }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(8) })
            addView(label("Barkod okut", 14f, ink, true))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (list.isNotEmpty()) btns.addView(label(if (homeChecking) "…" else "↻", 20f, ink, true).apply {
            gravity = Gravity.CENTER
            contentDescription = "Fiyatları yenile"
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(surface); setStroke(dp(1), line) }
            setOnClickListener { refreshList() }
        }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(8) })

        results.addView(btns)

        if (list.isEmpty() && homeFilter != "home") return
        if (list.isEmpty()) {
            results.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(surface, 20, line)
                setPadding(dp(18), dp(20), dp(18), dp(20))
                addView(label("Nasıl eklenir?", 16f, ink, true).apply { setPadding(0, 0, 0, dp(10)) })
                listOf(
                    "Trendyol, Hepsiburada, Amazon gibi bir uygulamada ürünü aç",
                    "Paylaş'a dokun, listeden İzci'yi seç",
                    "İstersen hedef fiyat yaz, Ekle'ye bas. Fiyat düşünce bildirim gelir",
                ).forEachIndexed { i, t ->
                    addView(LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(0, dp(6), 0, dp(6))
                        addView(label("${i + 1}", 13f, accentOn, true).apply {
                            gravity = Gravity.CENTER
                            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(gold) }
                        }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(12) })
                        addView(label(t, 14f, ink), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    })
                }
                addView(label("Ya da linki kopyala, yukarıdaki “Link yapıştır”a dokun. Aynı ürünün başka sitedeki linkini eklerken “Listemdeki ürüne ekle”yi seç; " +
                    "hepsi tek kartta izlenir, en ucuz site ve satıcı öne çıkar.", 12f, muted).apply { setPadding(0, dp(10), 0, 0) })
            })
            return
        }

        list.forEach { wishCard(it) }

        // Fiyatlar 2 saatten eskiyse sayfa açılınca sessizce yenilenir
        if (!homeChecking && System.currentTimeMillis() - newest > 2 * 3_600_000L) refreshList()
    }

    /** Bir ürün kartı: fotoğraf, ad, güncel fiyat, düşüş, hedef durumu, küçük grafik */
    private fun wishCard(e: WishEntry) {
        val a = e.focus
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(if (e.hit) greenBg else surface, 18, if (e.hit) gold else line, if (e.hit) 2 else 1)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener { itemBack = homeFilter; alarmPage(e.gid, a.id) }
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(ImageView(this).apply {
            background = rounded(Color.WHITE, 12)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            scaleType = ImageView.ScaleType.FIT_CENTER
            if (a.image.isNotEmpty()) loadImage(a.image, this, Uri.parse(a.link).let { u -> "${u.scheme}://${u.host}/" })
        }, LinearLayout.LayoutParams(dp(76), dp(76)).apply { marginEnd = dp(12) })
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        info.addView(label(e.title, 14f, ink, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
        info.addView(label(e.sub, 12f, if (a.inStock == false && e.gid == 0L) red else muted).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(2), 0, 0)
        })
        val priceRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, 0) }
        priceRow.addView(label(e.priceText, 19f, if (e.hit || e.drop > 0) green else ink, true))
        if (e.drop >= 0.01) priceRow.addView(pill("↓ %${Math.round(e.drop * 100)}", green, if (e.hit) surface else greenBg, bold = true).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) }
        })
        info.addView(priceRow)
        row.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        // Sağ üst: takip (dokununca takipten çıkarma sorusu) ve alarm açık/kapalı
        row.addView(FrameLayout(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(greenBg) }
            contentDescription = "Takipten çıkar"
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Takipten çıkılsın mı?")
                    .setMessage("“${e.title.take(60)}” listenden silinecek; fiyat geçmişi de izlenmeyecek. Emin misin?")
                    .setPositiveButton("Evet, çıkar") { _, _ ->
                        if (e.gid != 0L) Alarms.removeGroup(this@MainActivity, e.gid) else Alarms.remove(this@MainActivity, a.id)
                        Toast.makeText(this@MainActivity, "Takipten çıkarıldı", Toast.LENGTH_SHORT).show()
                        showHome()
                    }
                    .setNegativeButton("Vazgeç", null).show()
            }
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_watch)
                imageTintList = android.content.res.ColorStateList.valueOf(gold)
            }, FrameLayout.LayoutParams(dp(20), dp(20)).apply { gravity = Gravity.CENTER })
        }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(8) })
        row.addView(FrameLayout(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (e.watch) greyBg else gold) }
            contentDescription = if (e.watch) "Alarmı aç" else "Alarmı kapat"
            setOnClickListener { toggleAlarm(e) }
            addView(ImageView(this@MainActivity).apply {
                setImageResource(if (e.watch) R.drawable.ic_bell_off else R.drawable.ic_bell)
                imageTintList = android.content.res.ColorStateList.valueOf(if (e.watch) muted else accentOn)
            }, FrameLayout.LayoutParams(dp(20), dp(20)).apply { gravity = Gravity.CENTER })
        }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(8) })
        card.addView(row)

        // Hedef durumu
        val p = e.price
        val statusText: String
        val statusColor: Int
        when {
            e.watch -> { statusText = "Sadece izleniyor · bildirim yok"; statusColor = muted }
            e.hit -> { statusText = "Hedefin ${e.targetText} · fiyat hedefte, alma zamanı! 🎯"; statusColor = green }
            e.target > 0 && p != null -> {
                val left = p - e.target
                val fmtLeft = if (a.unitTarget > 0) Alarms.unitText(left, a.unitName) else money(left)
                statusText = "Hedef ${e.targetText} · $fmtLeft kaldı"; statusColor = ink
            }
            else -> { statusText = "Her ucuzlamada haber verilecek"; statusColor = muted }
        }
        card.addView(label(statusText, 13f, statusColor, e.hit).apply { setPadding(dp(2), dp(10), dp(2), 0) })
        // Hedefe ilerleme çubuğu: eklendiği fiyattan hedefe ne kadar yol alındı
        if (!e.watch && !e.hit && e.target > 0 && p != null && e.start > e.target) {
            val f = ((e.start - p) / (e.start - e.target)).coerceIn(0.0, 1.0).toFloat()
            card.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                background = rounded(greyBg, 4)
                addView(View(this@MainActivity).apply { background = rounded(gold, 4) },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, f.coerceAtLeast(0.02f)))
                addView(View(this@MainActivity), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f - f.coerceAtLeast(0.02f)))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)).apply { setMargins(dp(2), dp(8), dp(2), 0) })
        }
        // Küçük fiyat grafiği
        if (e.history.size >= 2) {
            card.addView(Sparkline(this, e.history, green, line), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(36)).apply { topMargin = dp(8) })
            if (e.lowest > 0) card.addView(label("En düşük ${if (a.unitTarget > 0) Alarms.unitText(e.lowest, a.unitName) else money(e.lowest)} · ${e.history.size} gündür izleniyor", 11f, muted)
                .apply { setPadding(dp(2), dp(2), dp(2), 0) })
        }
        // Alt düğmeler: başka site linki ekle, siteye git
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
            addView(label("+  Başka site linki", 13f, ink, true).apply {
                gravity = Gravity.CENTER
                background = rounded(greyBg, 16)
                setPadding(dp(10), dp(9), dp(10), dp(9))
                setOnClickListener { attachFromClipboard(a) }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f).apply { marginEnd = dp(8) })
            addView(label("Siteye git ›", 13f, ink, true).apply {
                gravity = Gravity.CENTER
                background = rounded(greyBg, 16)
                setPadding(dp(10), dp(9), dp(10), dp(9))
                setOnClickListener { try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(a.link))) } catch (x: Exception) {} }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        })
        results.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(dp(2), dp(5), dp(2), dp(5)) })
    }

    /** Paylaşılan/yapıştırılan linkten yeni ilan kaydı (gruba eklenmeden önce) */
    private fun newListing(title: String, link: String, site: String, image: String, price: Double?, inStock: Boolean?, info: Alarms.PageInfo?) =
        Alarm(id = System.currentTimeMillis(), title = title, site = site, link = Links.clean(link), image = image,
            startPrice = price ?: 0.0, lastPrice = price ?: 0.0, lowest = price ?: 0.0, target = 0.0, wantStock = false, inStock = inStock,
            lastCheck = 0L, status = if (price != null) "fiyat okundu" else "henüz kontrol edilmedi",
            seller = info?.seller.orEmpty(), sellers = info?.sellers ?: 0)

    /** İlanı listedeki karta katar: kart tek linkliyse gruba çevrilir; hedef ve bildirim ayarı kartınkiyle aynı olur */
    private fun joinGroup(target: Alarm, add: Alarm, quiet: Boolean = false) {
        val all = Alarms.load(this)
        val cur = all.firstOrNull { it.id == target.id } ?: target
        val gid = if (cur.groupId != 0L) cur.groupId else System.currentTimeMillis()
        val members = all.filter { if (cur.groupId != 0L) it.groupId == cur.groupId else it.id == cur.id }
        val name = members.firstOrNull()?.groupName?.ifEmpty { null } ?: cleanTitle(cur.title)
        if (members.any { Links.clean(it.link).substringBefore('?') == Links.clean(add.link).substringBefore('?') }) {
            if (!quiet) Toast.makeText(this, "Bu link zaten bu kartta", Toast.LENGTH_SHORT).show()
            return
        }
        val head = members.first()
        val items = members.map { it.copy(groupId = gid, groupName = name) } +
            add.copy(id = maxOf(System.currentTimeMillis(), (all.maxOfOrNull { it.id } ?: 0L) + 1), groupId = gid, groupName = name,
                target = head.target, unitTarget = 0.0, unitAmount = 0.0, watch = head.watch, wantStock = head.wantStock)
        Alarms.addGroup(this, items)
        if (!quiet) Toast.makeText(this, "${add.site} linki karta eklendi · ${items.size} link", Toast.LENGTH_SHORT).show()
    }

    /** Listedeki kartlardan birini seç (yeni linkin adına en çok benzeyen üstte) */
    private fun chooseCard(forTitle: String, pick: (Alarm) -> Unit) {
        val base = words(forTitle)
        val list = wishEntries(Alarms.load(this)).sortedByDescending { (words(it.title) intersect base).size }
        if (list.isEmpty()) return
        AlertDialog.Builder(this).setTitle("Hangi ürüne eklensin?")
            .setItems(list.map { e -> "${e.title.take(60)}\n${e.items.map { it.site }.distinct().joinToString(", ")} · ${e.priceText}" }.toTypedArray()) { _, i ->
                pick(list[i].focus)
            }
            .setNegativeButton("Vazgeç", null).show()
    }

    /** Panodaki başka site linkini bu karta ekler */
    private fun attachFromClipboard(a: Alarm) {
        val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = try { cb.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty() } catch (e: Exception) { "" }
        val url = Regex("https?://\\S+").find(text)?.value
        val mine = Alarms.load(this).filter { if (a.groupId != 0L) it.groupId == a.groupId else it.id == a.id }.map { it.link.substringBefore('?') }
        if (url == null || url.substringBefore('?') in mine) {
            AlertDialog.Builder(this).setTitle("Başka site linki ekle")
                .setMessage("Aynı ürünü diğer sitede (Hepsiburada, Amazon…) aç, linkini kopyala, sonra tekrar bu düğmeye dokun.\n\n" +
                    "Ya da o uygulamada Paylaş → İzci yap ve “Listemdeki ürüne ekle”yi seç.")
                .setPositiveButton("Tamam", null).show()
            return
        }
        handleSharedText(text, attachTo = a)
    }

    /** Aynı ürünü öbür sitelerde arar; sonuçlarda zile dokununca hepsi tek kartta izlenir */
    private fun searchOtherSites(a: Alarm) {
        if (Links.looksLikeUrl(a.title) || a.title.endsWith(" ürünü")) {
            Toast.makeText(this, "Ürünün adı henüz okunmadı. “Fiyatları yenile”ye dokunup tekrar dene.", Toast.LENGTH_LONG).show()
            return
        }
        val q = a.title.split(Regex("\\s[-|–]\\s")).first().split(" ").filter { it.isNotBlank() }.take(8).joinToString(" ")
        pendingPick = a
        picked.clear()
        input.setText(q); startTest()
    }

    // ---------- Diğer sitelerde aynı ürün: sade seçim ekranı ----------

    /** "Diğer sitelerde ara" ile başlatılan aramanın listedeki ürünü (null = normal arama) */
    private var pickFor: Alarm? = null
    private var pendingPick: Alarm? = null
    private val picked = mutableSetOf<String>()

    /** Kelime kökleri: küçük harf, Türkçe karakter sade, 2+ harf */
    private fun words(t: String) = t.lowercase(Locale("tr", "TR"))
        .replace('ı', 'i').replace('ş', 's').replace('ğ', 'g').replace('ü', 'u').replace('ö', 'o').replace('ç', 'c')
        .split(Regex("[^a-z0-9]+")).filter { it.length >= 2 }.toSet()

    /** Aday: başka sitede, adı listedeki ürüne benzeyen ilan. Aksesuar, ikinci el, başka model atılır. */
    private fun pickCandidates(a: Alarm): List<Offer> {
        val base = words(a.title)
        val brand = brandOf(a.title).lowercase(Locale("tr", "TR"))
        val ok = setOf(Kind.MATCH, Kind.POSSIBLE, Kind.OTHER_PACKAGE, Kind.OTHER, Kind.SUSPICIOUS)
        return offers.asSequence()
            .filter { it.kind in ok && it.site != a.site && it.price > 0 }
            .filter { brand.isEmpty() || it.title.lowercase(Locale("tr", "TR")).contains(brand) }
            .map { o -> o to (words(o.title) intersect base).size.toDouble() / base.size.coerceAtLeast(1) }
            .filter { (o, sim) -> o.kind == Kind.MATCH || sim >= 0.45 }
            .sortedWith(compareBy<Pair<Offer, Double>> { if (it.first.kind == Kind.MATCH) 0 else 1 }.thenByDescending { it.second }.thenBy { it.first.price })
            .map { it.first }
            .distinctBy { offerKey(it) }
            .take(10).toList()
    }

    private fun renderPicker(final: Boolean) {
        val a = pickFor ?: return
        screen = "pick"
        filterBar.visibility = View.GONE
        status.visibility = View.GONE
        searchBar.visibility = View.GONE
        updateSiteChips()
        results.removeAllViews()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), dp(4), dp(4), dp(16)) }
        results.addView(col)
        col.addView(label("‹  Listem", 14f, gold, true).apply {
            setPadding(0, dp(6), 0, dp(10))
            setOnClickListener { pickFor = null; goHome() }
        })
        col.addView(label("Diğer sitelerde aynı ürün", 22f, ink, true))
        // Listedeki ürün
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(surface, 14, line)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            addView(ImageView(this@MainActivity).apply {
                background = rounded(Color.WHITE, 8)
                scaleType = ImageView.ScaleType.FIT_CENTER
                if (a.image.isNotEmpty()) loadImage(a.image, this, Uri.parse(a.link).let { u -> "${u.scheme}://${u.host}/" })
            }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(10) })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(a.title, 13f, ink, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
                addView(label("${a.site} · ${if (a.lastPrice > 0) money(a.lastPrice) else "?"}", 12f, muted))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })

        val cands = pickCandidates(a)
        if (!final) {
            col.addView(label("Siteler taranıyor… " + (if (cands.isNotEmpty()) "${cands.size} aday bulundu" else ""), 14f, muted).apply { setPadding(0, dp(16), 0, dp(6)) })
        }
        if (final && cands.isEmpty()) {
            col.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                background = rounded(surface, 18, line)
                setPadding(dp(18), dp(22), dp(18), dp(20))
                addView(label("Başka sitede bulunamadı", 17f, ink, true).apply { gravity = Gravity.CENTER })
                addView(label("Bu ürün büyük ihtimalle sadece ${a.site}'da satılıyor. ${a.site} fiyatını izlemeye devam ediyorum.", 13f, muted)
                    .apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(14)) })
                addView(label("Listeme dön", 14f, accentOn, true).apply {
                    background = rounded(gold, 22)
                    setPadding(dp(22), dp(11), dp(22), dp(11))
                    setOnClickListener { pickFor = null; goHome() }
                })
                addView(label("Benzer ürünleri yine de göster", 13f, muted, true).apply {
                    setPadding(dp(8), dp(14), dp(8), 0)
                    setOnClickListener { pickFor = null; render(final = true) }
                })
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })
            return
        }
        if (cands.isEmpty()) return
        if (final) col.addView(label("Aynı ürün olanları işaretle. Hepsi tek kartta izlenir, en ucuzu öne çıkar.", 13f, muted)
            .apply { setPadding(0, dp(16), 0, dp(6)) })
        cands.forEach { o ->
            val key = offerKey(o)
            val on = key in picked
            col.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = rounded(if (on) greenBg else surface, 14, if (on) gold else line, if (on) 2 else 1)
                setPadding(dp(10), dp(10), dp(10), dp(10))
                setOnClickListener { if (on) picked.remove(key) else picked.add(key); renderPicker(final) }
                addView(ImageView(this@MainActivity).apply {
                    background = rounded(Color.WHITE, 8)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    if (o.image.isNotEmpty()) loadImage(o.image, this, Uri.parse(o.link).let { u -> "${u.scheme}://${u.host}/" })
                }, LinearLayout.LayoutParams(dp(56), dp(56)).apply { marginEnd = dp(10) })
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(label(o.title, 13f, ink, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
                    addView(LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, dp(4), 0, 0)
                        addView(sitePill(o))
                        addView(label("  " + money(o.price), 14f, if (a.lastPrice > 0 && o.price < a.lastPrice) green else ink, true))
                    })
                    if (o.kind == Kind.MATCH) addView(label("büyük ihtimalle aynı ürün", 11f, green))
                    addView(label("Sayfayı aç ›", 11f, muted).apply {
                        setPadding(0, dp(4), 0, 0)
                        setOnClickListener { openLink(o) }
                    })
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(label(if (on) "✓" else "", 18f, accentOn, true).apply {
                    gravity = Gravity.CENTER
                    background = if (on) GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(gold) }
                        else GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(dp(2), muted) }
                }, LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginStart = dp(8) })
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        }
        if (final) col.addView(label(if (picked.isEmpty()) "Hiçbiri aynı değil" else "Listeme ekle (${picked.size} site daha)", 15f,
            if (picked.isEmpty()) ink else accentOn, true).apply {
            gravity = Gravity.CENTER
            background = if (picked.isEmpty()) rounded(surface, 24, line) else rounded(gold, 24)
            setPadding(dp(16), dp(13), dp(16), dp(13))
            setOnClickListener {
                if (picked.isNotEmpty()) addPicked(a, cands.filter { offerKey(it) in picked })
                pickFor = null; goHome()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })
    }

    /** Sade arama sonucu: sadece aranan markayı ve kelimelerin çoğunu taşıyan ilanlar (muadil, aksesuar yok) */
    private var showAllResults = false
    private val stopWords = setOf("in", "ve", "ile", "icin", "li", "lu", "lı", "lü", "adet", "arada", "the", "for", "set")

    private fun simpleResults(): List<Offer> {
        val q = (identity?.label ?: query)
        val qw = words(q).filter { it !in stopWords }
        if (qw.isEmpty()) return emptyList()
        val brand = qw.first()
        val bad = setOf(Kind.ACCESSORY, Kind.USED, Kind.MUADIL, Kind.DIFFERENT_MODEL)
        return offers.asSequence()
            .filter { it.kind !in bad && it.price > 0 }
            .map { it to words(it.title) }
            .filter { (o, w) -> o.kind == Kind.MATCH || (brand in w && qw.count { it in w } * 10 >= qw.size * 6) }
            .map { it.first }
            .distinctBy { offerKey(it) }
            .sortedWith(compareBy<Offer> { if (it.kind == Kind.MATCH) 0 else 1 }.thenBy { it.price })
            .toList()
    }

    private fun renderSimple(final: Boolean, matches: List<Offer>) {
        filterBar.visibility = View.GONE
        val list = simpleResults()
        val took = elapsedText((if (finished && finishedAt > 0) finishedAt else android.os.SystemClock.elapsedRealtime()) - startedAt)
        status.text = when {
            !final -> "Siteler taranıyor… " + (if (list.isNotEmpty()) "${list.size} ilan, en ucuz ${money(list.minOf { it.price })}" else "") + " · $took"
            list.isEmpty() -> "Bulunamadı · $took"
            else -> "${list.size} ilan · ${list.map { it.site }.distinct().size} site · $took"
        }
        if (final && list.isEmpty()) {
            val brand = words(identity?.label ?: query).firstOrNull { it !in stopWords }.orEmpty()
            results.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                background = rounded(surface, 18, line)
                setPadding(dp(18), dp(22), dp(18), dp(20))
                addView(label("Bu ürün sitelerde bulunamadı", 17f, ink, true).apply { gravity = Gravity.CENTER })
                addView(label("Taranan sitelerde “${brand.uppercase(Locale("tr", "TR"))}” markalı ve adı uyan bir ilan yok. " +
                    "Ürün tek bir sitede satılıyor olabilir ya da adı farklı yazılmıştır; daha kısa yazıp tekrar dene.", 13f, muted)
                    .apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(12)) })
                addView(label("Benzer ürünleri yine de göster", 13f, gold, true).apply {
                    setPadding(dp(8), dp(6), dp(8), dp(4))
                    setOnClickListener { showAllResults = true; render(final = finished) }
                })
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
            return
        }
        list.forEachIndexed { i, o -> offerRow(o, i == 0 && final) }
        if (final) results.addView(label("Benzer ve muadil ürünleri de göster", 13f, muted, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(20), dp(12), dp(8))
            setOnClickListener { showAllResults = true; render(final = finished) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun restoreScroll(y: Int) { resultsScroll.post { resultsScroll.scrollTo(0, y) } }


    // ---------- Barkod okut: sadece barkodu tutan ilanlar ----------

    /** Barkod aramasındayken: tahmin yok, isimle aramaya geçilmez */
    private var barcodeOnly = false
    private var barcodeCode = ""
    private val barcodePicked = mutableSetOf<String>()

    private fun barcodeSearch(code: String) {
        if (running) return
        stopAll()
        barcodeOnly = true
        barcodeCode = code
        pickFor = null
        barcodePicked.clear()
        running = true
        finished = false
        startedAt = android.os.SystemClock.elapsedRealtime()
        offers.clear(); siteSummary.clear(); barcodeHits.clear(); weakSites.clear()
        identity = null
        query = code
        userInput = code
        bookMode = code.length == 13 && (code.startsWith("978") || code.startsWith("979"))
        if (resultsScroll.visibility != View.VISIBLE) toggleBrowser()
        progress.visibility = View.VISIBLE
        chipsScroll.visibility = View.VISIBLE
        status.visibility = View.GONE
        renderBarcode(false)
        val myStart = startedAt
        handler.postDelayed({ if (startedAt == myStart && running && barcodeOnly) finishBarcode() }, 40000)
        runSites(sites, code, "Barkod", onEach = { if (barcodeOnly) renderBarcode(false) }, noSimple = true, hardMs = 30000) {
            if (barcodeOnly) finishBarcode()
        }
    }

    private fun finishBarcode() {
        if (finished) return
        finished = true
        running = false
        stopAll()
        progress.visibility = View.GONE
        chipsScroll.visibility = View.GONE
        val hits = barcodeResults()
        barcodePicked.clear()
        barcodePicked.addAll(hits.map { offerKey(it) })
        renderBarcode(true)
    }

    /**
     * Barkodu tutan ilanlar. Kurallar:
     *  - Barkodla az ilan (1-4) döndüren site: o ilanlar ürünün kendisidir
     *  - Çok ilan döndüren site öneri listesi göstermiş olabilir: sadece ortak adı taşıyanlar kalır
     *  - Aksesuar atılır; birden çok site varsa çoğunluğun adıyla hiç ortak kelimesi olmayan ilan atılır
     */
    private fun barcodeResults(): List<Offer> {
        val bySite = offers.filter { it.price > 0 && !isAccessory(it.title.lowercase(Locale("tr", "TR"))) }.groupBy { it.site }
        val kept = mutableListOf<Offer>()
        for ((_, list) in bySite) {
            if (list.size <= 4) { kept.addAll(list); continue }
            val common = commonName(list.map { it.title })
            if (common.isEmpty()) continue
            val cw = words(common)
            kept.addAll(list.filter { (words(it.title) intersect cw).size * 2 >= cw.size }.take(4))
        }
        if (kept.map { it.site }.distinct().size >= 2) {
            // Çoğunluğun kelimeleri: en az iki sitede geçen kelimeler
            val freq = HashMap<String, Int>()
            kept.groupBy { it.site }.values.forEach { l -> l.flatMap { words(it.title) }.toSet().forEach { w -> freq[w] = (freq[w] ?: 0) + 1 } }
            val core = freq.filter { it.value >= 2 && it.key.length >= 3 }.keys
            if (core.size >= 2) kept.retainAll { (words(it.title) intersect core).size >= 2 }
        }
        return kept.distinctBy { offerKey(it) }.sortedBy { it.price }
    }

    private fun renderBarcode(final: Boolean) {
        screen = "barcode"
        filterBar.visibility = View.GONE
        searchBar.visibility = View.GONE
        updateSiteChips()
        results.removeAllViews()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), dp(4), dp(4), dp(16)) }
        results.addView(col)
        col.addView(label("‹  Listem", 14f, gold, true).apply {
            setPadding(0, dp(6), 0, dp(10))
            setOnClickListener { if (running) { stopAll(); running = false }; barcodeOnly = false; goHome() }
        })
        col.addView(label("Barkod $barcodeCode", 22f, ink, true))
        val hits = if (final) barcodeResults() else barcodeResults()
        if (!final) {
            col.addView(label("Siteler taranıyor… " + (if (hits.isNotEmpty()) "${hits.size} ilan bulundu" else ""), 14f, muted)
                .apply { setPadding(0, dp(10), 0, dp(6)) })
            hits.forEach { barcodeRow(col, it, false) }
            return
        }
        if (hits.isEmpty()) {
            col.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                background = rounded(surface, 18, line)
                setPadding(dp(18), dp(22), dp(18), dp(20))
                addView(label("Bu barkod sitelerde bulunamadı", 17f, ink, true).apply { gravity = Gravity.CENTER })
                addView(label("Hiçbir site bu barkodla ilan döndürmedi. Tahmin yapmıyorum; yanlış ürün göstermektense boş bırakıyorum. " +
                    "Ürünü bir sitede bulursan linkini paylaşarak ekleyebilirsin.", 13f, muted)
                    .apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(14)) })
                addView(label("Başka barkod okut", 14f, accentOn, true).apply {
                    background = rounded(gold, 22)
                    setPadding(dp(22), dp(11), dp(22), dp(11))
                    setOnClickListener { barcodeOnly = false; scanBarcode() }
                })
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })
            return
        }
        val siteCount = hits.map { it.site }.distinct().size
        col.addView(label("${hits.size} ilan · $siteCount site · en ucuz ${money(hits.first().price)} (${hits.first().site})", 14f, green, true)
            .apply { setPadding(0, dp(8), 0, 0) })
        col.addView(label("Barkodu tutan ilanlar. Listene eklemek istemediklerinin işaretini kaldır.", 12f, muted)
            .apply { setPadding(0, dp(4), 0, dp(4)) })
        hits.forEach { barcodeRow(col, it, true) }
        val chosen = hits.filter { offerKey(it) in barcodePicked }
        col.addView(label(if (chosen.isEmpty()) "Bir ilan seç" else "Listeme ekle (${chosen.size} ilan)", 15f,
            if (chosen.isEmpty()) muted else accentOn, true).apply {
            gravity = Gravity.CENTER
            background = if (chosen.isEmpty()) rounded(surface, 24, line) else rounded(gold, 24)
            setPadding(dp(16), dp(13), dp(16), dp(13))
            setOnClickListener {
                if (chosen.isEmpty()) return@setOnClickListener
                val main = chosen.first()
                alarmDialog(cleanTitle(main.title), main.link, main.site, main.image, main.price, true,
                    compareName = "", extras = chosen.drop(1))
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })
    }

    private fun barcodeRow(col: LinearLayout, o: Offer, selectable: Boolean) {
        val key = offerKey(o)
        val on = selectable && key in barcodePicked
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(if (on) greenBg else surface, 14, if (on) gold else line, if (on) 2 else 1)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            if (selectable) setOnClickListener { if (on) barcodePicked.remove(key) else barcodePicked.add(key); renderBarcode(true) }
            addView(ImageView(this@MainActivity).apply {
                background = rounded(Color.WHITE, 8)
                scaleType = ImageView.ScaleType.FIT_CENTER
                if (o.image.isNotEmpty()) loadImage(o.image, this, Uri.parse(o.link).let { u -> "${u.scheme}://${u.host}/" })
            }, LinearLayout.LayoutParams(dp(56), dp(56)).apply { marginEnd = dp(10) })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(o.title, 13f, ink, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(4), 0, 0)
                    addView(sitePill(o))
                    addView(label("  " + money(o.price), 15f, ink, true))
                })
                addView(label("Sayfayı aç ›", 11f, muted).apply { setPadding(0, dp(4), 0, 0); setOnClickListener { openLink(o) } })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (selectable) addView(label(if (on) "✓" else "", 18f, accentOn, true).apply {
                gravity = Gravity.CENTER
                background = if (on) GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(gold) }
                    else GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(dp(2), muted) }
            }, LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginStart = dp(8) })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
    }

    /** Listedeki ürünü seçilen ilanlarla tek grup yapar (hedef ve bildirim ayarı korunur) */
    private fun addPicked(a: Alarm, chosen: List<Offer>) {
        val gid = if (a.groupId != 0L) a.groupId else System.currentTimeMillis()
        val name = cleanTitle(a.title)
        val all = Alarms.load(this)
        val existing = all.filter { if (a.groupId != 0L) it.groupId == a.groupId else it.id == a.id }
            .map { it.copy(groupId = gid, groupName = it.groupName.ifEmpty { name }) }
        val t = existing.firstOrNull()?.target ?: a.target
        val items = existing + chosen.mapIndexed { i, c ->
            Alarm(id = gid + 1 + i, title = c.title, site = c.site, link = c.link, image = c.image,
                startPrice = c.price, lastPrice = c.price, lowest = c.price, target = t, wantStock = a.wantStock, inStock = true,
                lastCheck = 0L, status = "henüz kontrol edilmedi", groupId = gid, groupName = name, watch = a.watch)
        }
        Alarms.addGroup(this, items)
        Toast.makeText(this, "Artık ${items.map { it.site }.distinct().size} sitede izleniyor", Toast.LENGTH_SHORT).show()
    }

    /** Kartın alarmını aç/kapat: kapalıyken sadece takip edilir (fiyat ve geçmiş tutulur, bildirim gelmez) */
    private fun toggleAlarm(e: WishEntry) {
        val ids = e.items.map { it.id }.toSet()
        val toWatch = !e.watch
        Alarms.save(this, Alarms.load(this).map { if (it.id in ids) it.copy(watch = toWatch) else it })
        if (!toWatch) askNotificationPermission()
        Toast.makeText(this, if (toWatch) "Alarm kapatıldı · sadece takip" else
            "Alarm açıldı · " + (e.targetText?.let { "hedef $it" } ?: "her ucuzlamada haber verilecek"), Toast.LENGTH_SHORT).show()
        showHome()
    }

    /** Listedeki bütün fiyatları şimdi kontrol et */
    private fun refreshList() {
        if (homeChecking) return
        homeChecking = true
        if (onList()) showHome()
        Thread {
            try { Alarms.checkAll(applicationContext) } catch (e: Exception) {}
            handler.post {
                if (onList() && !running) showHome()
                repairEntries {
                    homeChecking = false
                    if (onList() && !running) showHome()
                }
            }
        }.start()
    }

    /** Panodaki linki listeye ekler */
    private fun pasteLink() {
        val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = try { cb.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty() } catch (e: Exception) { "" }
        if (Regex("https?://\\S+").find(text) == null) {
            Toast.makeText(this, "Panoda ürün linki yok. Önce sitede ürün linkini kopyala.", Toast.LENGTH_LONG).show()
            return
        }
        handleSharedText(text)
    }

    /** Ürün sayısı: bir ürün alarmının bütün siteleri tek ürün sayılır */
    private fun productCount(list: List<Alarm>) =
        list.filter { it.groupId != 0L }.map { it.groupId }.toSet().size + list.count { it.groupId == 0L }

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
        val url = site.url(URLEncoder.encode(siteQuery(q), "UTF-8").replace("+", "%20"))
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
                !simpleUsed && !isBarcodeQ && (d?.optBoolean("noResults") == true || (d != null && cards == 0) || isIrrelevant(d, q)) &&
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
    private fun runSites(all: List<Site>, q: String, label: String, onEach: () -> Unit, noSimple: Boolean = false,
                         hardMs: Long = 25000, onAll: () -> Unit) {
        // Ayarlardan kapatılan siteler ve aramanın kategorisiyle ilgisi olmayan siteler taranmaz
        val cat = detectCategory()
        val list = all.filter { siteOn(it.name) && (barcodeOnly || it.cats.isEmpty() || cat in it.cats) }
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
            fun siteDone(d: JSONObject?, simpleUsed: Boolean) {
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
            runSite(v, site, q, simpleUsed = noSimple, reloaded = false) { d0, simpleUsed ->
                // Trendyol sayfası sadece ilk ürünleri çiziyor: sitenin arama servisindeki sonuçlar da eklenir
                if (site.name == "Trendyol" && gen == phaseGen && !phaseDone) trendyolApi(v, q, d0) { d -> siteDone(d, simpleUsed) }
                else siteDone(d0, simpleUsed)
            }
        }
        pool.forEach { launch(it) }
        // Mağazada hız: 14 saniyede bitmeyen siteler beklenmez (en az 4 site bittiyse).
        // 4 site bitmediyse 2 sn'de bir yeniden bakılır; 25 saniyede ne olursa olsun bitirilir.
        lateinit var cut: () -> Unit
        cut = {
            if (gen == phaseGen && !phaseDone) {
                val el = android.os.SystemClock.elapsedRealtime() - t0
                // Hedefli (ikinci) aramada siteler sonuna kadar beklenir; normal aramada 4 site bitince yavaşlar beklenmez
                if (el >= hardMs || (hardMs <= 25000 && list.size - left >= 4)) {
                    list.forEachIndexed { i, s ->
                        if (!done[i]) {
                            viewOf[i]?.let { v -> slots.getValue(v).gen++; v.stopLoading() }
                            siteSummary.add("${s.name}: yavaş — beklenmedi (${el / 1000} sn)")
                        }
                    }
                    finish()
                } else handler.postDelayed({ cut() }, 2000)
            }
        }
        handler.postDelayed({ cut() }, 14000)
    }

    /** Trendyol arama servisi (JSON) sayfa olarak açılır, ürünler okunur ve sayfadan okunanlarla birleştirilir */
    private fun trendyolApi(v: WebView, q: String, d: JSONObject?, done: (JSONObject?) -> Unit) {
        val url = "https://public.trendyol.com/discovery-web-searchgw-service/v2/api/infinite-scroll/sr?q=" +
            URLEncoder.encode(siteQuery(q), "UTF-8").replace("+", "%20") + "&pi=1&culture=tr-TR&storefrontId=1"
        loadAndPoll(v, url, trendyolApiJs, maxMs = 7000, scroll = false, ready = { r, n ->
            (r?.optJSONArray("cards")?.length() ?: 0) > 0 || r?.optBoolean("empty") == true || n >= 6
        }) { r ->
            val extra = r?.optJSONArray("cards")
            val base = d ?: JSONObject().put("cards", org.json.JSONArray())
            val cards = base.optJSONArray("cards") ?: org.json.JSONArray().also { base.put("cards", it) }
            val have = HashMap<String, JSONObject>()
            for (i in 0 until cards.length()) {
                val c = cards.getJSONObject(i)
                Regex("-p-([0-9]+)").find(c.optString("link"))?.let { have[it.groupValues[1]] = c }
            }
            var added = 0
            if (extra != null) for (i in 0 until extra.length()) {
                val c = extra.getJSONObject(i)
                val id = Regex("-p-([0-9]+)").find(c.optString("link"))?.groupValues?.get(1)
                val old = id?.let { have[it] }
                if (old != null) { if (old.optString("img").isEmpty()) old.put("img", c.optString("img")); continue }
                cards.put(c); added++
                if (id != null) have[id] = c
            }
            if (added > 0) base.put("extra", base.optInt("extra") + added)
            done(base)
        }
    }

    private val trendyolApiJs = """
        (function(){
          var txt = document.body ? document.body.innerText : '';
          if (!txt || txt.charAt(0) != '{') return JSON.stringify({ cards: [], links: 0 });
          var data; try { data = JSON.parse(txt); } catch (e) { return JSON.stringify({ cards: [], links: 0 }); }
          var got = [];
          (function walk(o, d){
            if (!o || d > 8) return;
            if (Array.isArray(o)) { o.forEach(function(e){ walk(e, d + 1); }); return; }
            if (typeof o == 'object') {
              if (typeof o.url == 'string' && /-p-[0-9]+/.test(o.url) && o.name && o.price) got.push(o);
              else for (var k in o) walk(o[k], d + 1);
            }
          })(data, 0);
          function num(v){ if (v == null) return 0; if (typeof v == 'object') v = v.value != null ? v.value : v.text;
            if (typeof v == 'number') return v;
            var n = parseFloat(String(v).replace(/[^0-9.,]/g,'').replace(/\.(?=\d{3}(\D|$))/g,'').replace(',', '.')); return isNaN(n) ? 0 : n; }
          var cards = [];
          got.forEach(function(o){
            var pr = o.price || {};
            var c = [num(pr.discountedPrice), num(pr.sellingPrice), num(pr.buyingPrice)].filter(function(x){ return x > 0; });
            if (!c.length) return;
            var p = Math.min.apply(null, c), lp = num(pr.originalPrice);
            var brand = o.brand && o.brand.name ? String(o.brand.name) : '';
            var nm = (brand && String(o.name).toLowerCase().indexOf(brand.toLowerCase()) < 0 ? brand + ' ' : '') + o.name;
            var img = (o.images && o.images[0]) || o.image || '';
            if (img && img.charAt(0) == '/') img = 'https://cdn.dsmcdn.com' + img;
            var link = /^https?:/.test(o.url) ? o.url : 'https://www.trendyol.com' + o.url;
            var rs = o.ratingScore || {};
            cards.push({ t: nm, p: p, lp: lp > p ? lp : 0, link: link, img: img, raw: nm,
              r: rs.averageRating ? String(Math.round(rs.averageRating * 10) / 10) : '', rc: rs.totalCount ? String(rs.totalCount) : '' });
          });
          return JSON.stringify({ cards: cards, links: cards.length, empty: cards.length == 0 && got.length == 0 && txt.length > 50 });
        })()
    """.trimIndent()

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
        pickFor = pendingPick
        pendingPick = null
        showAllResults = false
        barcodeOnly = false
        userInput = input.text.toString().trim()
        if (userInput.isEmpty()) return
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(input.windowToken, 0)
        searchBar.visibility = View.VISIBLE
        if (resultsScroll.visibility != View.VISIBLE) toggleBrowser()
        stopAll()
        running = true
        finished = false
        startedAt = android.os.SystemClock.elapsedRealtime()
        // Emniyet: hangi adımda takılırsa takılsın arama 70 saniyede biter, o ana kadar bulunanlar gösterilir
        val myStart = startedAt
        handler.postDelayed({
            if (startedAt == myStart && running && !finished) {
                siteSummary.add("Arama 70 saniyede bitirildi (takılan adım: ${status.text})")
                try { prepareResults() } catch (e: Exception) {}
                finishSearch()
            }
        }, 70000)
        resolvedAt = 0L
        results.removeAllViews()
        expanded.clear()
        hiddenSites.clear()
        facetSel.clear()
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
        rescueTried = false
        weakSites.clear()
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

    /** Bu aramada ilgisiz ya da hiç sonuç döndüren siteler (ürün adıyla bir kez daha sorulur) */
    private val weakSites = mutableSetOf<String>()
    private var rescueTried = false

    private fun collect(name: String, d: JSONObject?, q: String, simpleUsed: Boolean, slot: Slot) {
        val cards = d?.optJSONArray("cards")
        val count = cards?.length() ?: 0
        if (count == 0) weakSites.add(name)
        val isBarcode = q.replace(" ", "").let { s -> s.length in 8..14 && s.all { it.isDigit() } }
        if (count > 0 && !isBarcode && isIrrelevant(d, q)) {
            val sample = (0 until minOf(2, count)).joinToString(" | ") { cards!!.getJSONObject(it).optString("t").take(60) }
            siteSummary.add("$name: sonuçlar aramayla ilgisiz (site muhtemelen öneri gösteriyor) — ör: $sample")
            weakSites.add(name)
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



























    /** Sitelere gönderilen metin: klavyenin düzelttiği "4 ın 1" gibi yazımlar düzeltilir (site "ın" ile "In"i eşlemiyor) */
    private fun siteQuery(q: String): String =
        q.replace(Regex("(?<![\\p{L}])[ıIİ][nN](?![\\p{L}])"), "in")

    /** Sade arama: miktar ve renk atılır ("prima 4 numara 80 adet" -> "prima 4 numara"); olmazsa ürün türü + ölçü */
    private fun simplifiedQuery(q: String): String {
        val toks = q.split(Regex("\\s+")).filter { it.isNotBlank() }
        val qty = Regex("^[0-9]+(li|lu|adet|kg|gr|g|ml|lt|l|cl|gram|parca)$")
        val keep = toks.filterIndexed { i, w ->
            val n = norm(w)
            val next = toks.getOrNull(i + 1)?.let { norm(it) }
            n.isNotEmpty() && n !in unitNorm && !qty.matches(n) && n !in colorWords &&
                !(n.all { it.isDigit() } && next != null && next in unitNorm) &&
                // Birimsiz büyük sayı ("16.000", "20000"): sitelerin aramasını daraltır, sade aramada atılır
                !w.matches(Regex("[0-9]{1,3}([.,][0-9]{3})+|[0-9]{4,}"))
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
        // Bazı siteler aramayı başka markaya düzeltiyor ("metontech" -> "vontech") ya da hiç sonuç vermiyor.
        // Başka sitede aynı ürün bulunduysa, o sitelere bir kez de ürünün adıyla (markasız) sorulur.
        if (!rescueTried && identity == null) {
            rescueTried = true
            val best = offers.filter { it.kind == Kind.MATCH }.maxByOrNull { countNum(it.ratingCount) }
            val again = sites.filter { it.name in weakSites && it.name in scanSites }
            if (best != null && again.isNotEmpty()) {
                val brand = norm(query.split(" ").firstOrNull().orEmpty())
                val words = best.title.substringBefore('|').substringBefore(',').substringBefore(" - ")
                    .split(Regex("\\s+")).filter { it.isNotBlank() }
                    .let { w -> if (w.isNotEmpty() && norm(w[0]) == brand) w.drop(1) else w }.take(7)
                val rq = words.joinToString(" ")
                if (words.size >= 3 && norm(rq) != norm(query)) {
                    resolveLog.append("Sonuç vermeyen sitelere ürün adıyla soruluyor: ").append(rq)
                        .append(" (").append(again.joinToString { it.name }).append(")\n")
                    weakSites.clear()
                    val prevScan = scanSites
                    runSites(again, rq, "Ürün adıyla tekrar aranıyor", onEach = { prepareResults(); render(final = false) },
                        noSimple = true, hardMs = 35000) {
                        scanSites = prevScan; startDetails()
                    }
                    return
                }
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
        // Ürün sayfaları en çok 22 saniye beklenir
        handler.postDelayed({ if (gen == phaseGen && !finished) finishSearch() }, 22000)
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

    /** "Ara ve güncelle" ile başlatılan aramanın ürün alarmı (arama bitince fiyatları güncellenir) */
    private var alarmRefresh = 0L

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
                // Gruptaki ilanların fiyatlarını bu aramada görülen fiyatlarla güncelle
                val seen = offers.associateBy { History.key(it.link) }
                val now = System.currentTimeMillis()
                var updated = 0
                val cur = Alarms.load(this)
                cur.filter { it.groupId == gid }.forEach { a ->
                    val o = seen[History.key(a.link)] ?: return@forEach
                    if (a.startPrice > 0 && o.price < a.startPrice * 0.4) return@forEach
                    a.lastPrice = o.price
                    if (a.lowest <= 0 || o.price < a.lowest) a.lowest = o.price
                    a.inStock = true
                    a.lastCheck = now
                    a.status = "aramadan güncellendi"
                    updated++
                }
                val merged = cur + added
                Alarms.save(this, merged)
                val grp = merged.filter { it.groupId == gid }
                Alarms.groupBest(grp)?.let { b -> Alarms.metric(b)?.let { History.record(this, "g:$gid", it) } }
                if (alarmRefresh == gid) {
                    alarmRefresh = 0L
                    val b = Alarms.groupBest(grp)
                    Toast.makeText(this, "Alarm güncellendi: $updated ilan" +
                        (if (added.isNotEmpty()) ", ${added.size} yeni" else "") +
                        (b?.let { " · en uygun ${it.site}" } ?: ""), Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) {}
        if (alarmRefresh != 0L) { alarmRefresh = 0L; Toast.makeText(this, "Bu aramada alarmdaki ürün bulunamadı", Toast.LENGTH_LONG).show() }
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
        if (barcodeOnly) { renderBarcode(final); return }
        if (pickFor != null) { renderPicker(final); return }
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
            setOnClickListener { if (!running) showSettings() }
        })
        if (!running) bar.addView(label("Yeniden tanı", 13f, gold, true).apply {
            setPadding(dp(10), dp(4), 0, dp(4))
            setOnClickListener { forgetIdentity() }
        })
        results.addView(bar)

        val matches = offers.filter { it.kind == Kind.MATCH }.sortedBy { it.price }
        val others = offers.filter { it.kind != Kind.MATCH }.sortedBy { it.price }
        markUnitPrices(matches)

        if (!showAllResults) { renderSimple(final, matches); restoreScroll(y); return }

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
        updateFacets()
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

    /** İlanın özellikleri: başlıktan okunur (hafıza, RAM, renk, beden, miktar, seri) */
    private fun facetsOf(o: Offer): Map<String, String> {
        val t = o.title.lowercase(Locale("tr", "TR"))
        val out = LinkedHashMap<String, String>()
        // RAM: "8 GB RAM", "RAM 8 GB"
        val ram = Regex("(\\d{1,2})\\s*gb\\s*(?:lpddr\\w*\\s*)?ram|ram\\s*:?\\s*(\\d{1,2})\\s*gb").find(t)
        ram?.let { out["RAM"] = (it.groupValues[1].ifEmpty { it.groupValues[2] }) + " GB" }
        // Hafıza: RAM olmayan en büyük GB/TB değeri
        val store = Regex("(\\d{1,4})\\s*(gb|tb)\\b").findAll(t).filter { m ->
            val after = t.substring(m.range.last + 1, minOf(t.length, m.range.last + 12))
            !after.trimStart().startsWith("ram") && !Regex("^\\s*(lpddr|ddr)").containsMatchIn(after) &&
                (ram == null || m.range.first != ram.range.first)
        }.map { m -> val n = m.groupValues[1].toInt(); if (m.groupValues[2] == "tb") n * 1024 else n }
            .filter { it >= 16 }.maxOrNull()
        store?.let { out["Hafıza"] = if (it >= 1024 && it % 1024 == 0) "${it / 1024} TB" else "$it GB" }
        colorsIn(o.title).singleOrNull()?.let { out["Renk"] = it.replaceFirstChar { c -> c.titlecase(Locale("tr", "TR")) } }
        sizeNos(o.title).singleOrNull()?.let { out["Numara/Beden"] = it }
        if (store == null) amountOf(o.title, queryAmount?.unit)?.let { if (!isSet(o)) out["Miktar"] = amountLabel(it) }
        val tr = traitsOf(o.title)
        out["Seri"] = if (tr.isEmpty()) "Standart" else tr.sorted().joinToString(" ")
        return out
    }

    /** Sonuçlardaki ortak özelliklerden aramaya özel filtre çipleri: "Hafıza 256 GB", "Renk Siyah"… */
    private fun updateFacets() {
        if (!::facetRow.isInitialized) return
        val pool = offers.filter { it.kind != Kind.OTHER && it.kind != Kind.ACCESSORY }
        val counts = LinkedHashMap<String, LinkedHashMap<String, Int>>()
        pool.forEach { o -> facetsOf(o).forEach { (k, v) -> counts.getOrPut(k) { LinkedHashMap() }.merge(v, 1, Int::plus) } }
        fun numKey(v: String) = Regex("[0-9]+(?:[.,][0-9]+)?").find(v)?.value?.replace(",", ".")?.toDoubleOrNull()?.let {
            if (v.contains("TB")) it * 1024 else it } ?: Double.MAX_VALUE
        val order = listOf("Seri", "Hafıza", "RAM", "Renk", "Numara/Beden", "Miktar")
        val chips = mutableListOf<Pair<String, String>>()
        for (k in order) {
            val m = counts[k] ?: continue
            val covered = m.values.sum()
            // Seri sadece en az iki farklı seri ve "Standart" dışında bir seri varsa anlamlı
            if (m.size < 2 || covered < 3) continue
            if (k == "Seri" && m.keys.all { it == "Standart" }) continue
            m.keys.sortedWith(compareBy({ numKey(it) }, { it })).take(8).forEach { chips.add(k to it) }
        }
        // Seçili ama artık listede olmayan değerler de görünsün (kaldırabilmek için)
        facetSel.forEach { (k, set) -> set.forEach { v -> if (chips.none { it.first == k && it.second == v }) chips.add(0, k to v) } }
        facetRow.removeAllViews()
        facetScroll.visibility = if (chips.isEmpty()) View.GONE else View.VISIBLE
        for ((k, v) in chips) {
            val on = facetSel[k]?.contains(v) == true
            facetRow.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = if (on) rounded(gold, 18) else rounded(surface, 18, line)
                setPadding(dp(14), dp(6), dp(14), dp(7))
                addView(label(k, 11f, if (on) accentOn else muted))
                addView(label(v, 14f, if (on) accentOn else ink, true))
                setOnClickListener {
                    val set = facetSel.getOrPut(k) { mutableSetOf() }
                    if (!set.add(v)) set.remove(v)
                    if (set.isEmpty()) facetSel.remove(k)
                    render(final = finished)
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
        }
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
        for ((name, sel) in facetSel) {
            if (sel.isEmpty()) continue
            val v = facetsOf(o)[name] ?: return false
            if (v !in sel) return false
        }
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
            setOnClickListener { productSheet(o) }
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
        sb.append("\nİzci ile bulundu")
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

    /** Ürün sayfası: Fotoğraf, fiyat, geçmiş, diğer siteler; takip et / alarm kur / siteye git */
    private fun productSheet(o: Offer) {
        if (running) return
        if (screen == "results") resultsScrollY = resultsScroll.scrollY
        screen = "product"
        filterBar.visibility = View.GONE
        chipsScroll.visibility = View.GONE
        status.visibility = View.GONE
        results.removeAllViews()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(6), dp(4), dp(6), dp(16)) }
        results.addView(col)
        // Geri
        col.addView(label("‹  Sonuçlara dön", 14f, gold, true).apply {
            setPadding(0, dp(6), 0, dp(10))
            setOnClickListener { backToResults() }
        })
        // Fotoğraf
        col.addView(ImageView(this).apply {
            background = rounded(Color.WHITE, 16)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            scaleType = ImageView.ScaleType.FIT_CENTER
            if (o.image.isNotEmpty()) loadImage(o.image, this, Uri.parse(o.link).let { u -> "${u.scheme}://${u.host}/" })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220)))
        brandOf(o.brandHint.ifEmpty { o.title }).takeIf { it.isNotEmpty() }?.let { col.addView(label(it, 13f, muted, true).apply { letterSpacing = 0.08f; setPadding(0, dp(16), 0, 0) }) }
        col.addView(label(o.title.ifEmpty { "(ad okunamadı)" }, 17f, ink, true).apply { setPadding(0, dp(4), 0, dp(10)) })
        // Site · puan
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(6))
            addView(pill(o.site, Color.WHITE, siteColors[o.site] ?: ink, bold = true, size = 12f))
            if (o.rating.isNotEmpty()) addView(label("★ ${o.rating}" + if (o.ratingCount.isNotEmpty()) " (${countText(o.ratingCount)})" else "", 13f, muted)
                .apply { setPadding(dp(10), 0, 0, 0) })
        })
        // Fiyat
        val priceRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        priceRow.addView(label(money(o.price), 30f, if (o.kind == Kind.MATCH) green else ink, true))
        if (o.listPrice > o.price + 0.5) priceRow.addView(label(money(o.listPrice), 15f, muted).apply {
            paintFlags = paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
            setPadding(dp(10), 0, 0, dp(5))
        })
        col.addView(priceRow)
        val info = mutableListOf<String>()
        unitText(o).takeIf { it.isNotEmpty() }?.let { info.add(it) }
        if (o.basket) info.add("sepette")
        when { o.shipFee == 0.0 -> info.add("kargo bedava"); o.shipFee > 0 -> info.add("kargo ${money(o.shipFee)}") }
        if (o.memberPrice > 0) info.add("${o.memberName.ifEmpty { "üyelere" }} ${money(o.memberPrice)}")
        if (info.isNotEmpty()) col.addView(label(info.joinToString(" · "), 13f, muted).apply { setPadding(0, dp(4), 0, 0) })
        val chips = FlowLayout(this, dp(6)).apply { setPadding(0, dp(10), 0, 0) }
        warrantyBadge(o)?.let { (txt, c2) -> chips.addView(pill(txt.substringBefore(" —"), c2, if (c2 == green) greenBg else if (c2 == red) redBg else greyBg, size = 12f)) }
        if (o.origin.isNotEmpty()) chips.addView(pill(o.origin, if (o.origin.startsWith("Türkiye")) green else red,
            if (o.origin.startsWith("Türkiye")) greenBg else redBg, size = 12f))
        if (o.seller.isNotEmpty()) chips.addView(pill("Satıcı: ${o.seller}" + if (o.sellerScore.isNotEmpty()) " · ${o.sellerScore}" else "", ink, greyBg, size = 12f))
        if (o.kind != Kind.MATCH) chips.addView(pill(kindLabel(o), orange, orangeBg, size = 12f))
        if (chips.childCount > 0) col.addView(chips)

        // Takip / alarm durumu
        val all = Alarms.load(this)
        val own = all.firstOrNull { it.link.substringBefore('?') == o.link.substringBefore('?') }
        val gid = own?.groupId?.takeIf { it != 0L } ?: if (o.kind == Kind.MATCH || o.kind == Kind.OTHER_PACKAGE) productAlarmGroup() else null
        val tracked = own ?: gid?.let { g -> all.firstOrNull { it.groupId == g } }
        if (tracked != null) col.addView(label(if (tracked.watch) "Bu ürün takip listende" else "Bu ürün için alarm kurulu", 13f, gold, true)
            .apply { setPadding(0, dp(12), 0, 0) })

        // İşlemler: takip et · alarm kur · paylaş · siteye git (simgeli düğmeler)
        val acts = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(16), 0, 0) }
        fun act(icon: Int, text: String, on: Boolean, action: () -> Unit) {
            acts.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = rounded(if (on) greenBg else surface, 14, line)
                setPadding(dp(4), dp(12), dp(4), dp(10))
                contentDescription = text
                setOnClickListener { action() }
                addView(ImageView(this@MainActivity).apply {
                    setImageResource(icon)
                    imageTintList = android.content.res.ColorStateList.valueOf(if (on) green else ink)
                }, LinearLayout.LayoutParams(dp(24), dp(24)))
                addView(label(text, 12f, if (on) green else ink, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0) })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
        }
        val watching = tracked?.watch == true
        val alarmOn = tracked != null && !tracked.watch
        act(R.drawable.ic_watch, if (watching) "Takipte" else "Takip et", watching) {
            if (tracked == null) { quickWatch(o); productSheet(o) }
            else if (tracked.groupId != 0L) productAlarmDialog(tracked.groupId, o)
            else alarmDialog(o.title, o.link, o.site, o.image, o.price, available = true, offer = o)
        }
        act(R.drawable.ic_bell, if (alarmOn) "Alarm var" else "Alarm kur", alarmOn) {
            if (tracked != null && tracked.groupId != 0L) productAlarmDialog(tracked.groupId, o)
            else alarmDialog(o.title, o.link, o.site, o.image, o.price, available = true, offer = o)
        }
        act(R.drawable.ic_share, "Paylaş", false) { shareText("${o.title}\n${money(o.price)} · ${o.site}\n${o.link}") }
        act(R.drawable.ic_open, "Siteye git", false) { openLink(o) }
        col.addView(acts)

        // Fiyat geçmişi: bu ilan
        fun historyBlock(title: String, pts: List<Pair<Long, Double>>) {
            col.addView(label(title, 15f, ink, true).apply { setPadding(0, dp(20), 0, dp(6)) })
            if (pts.size >= 2) {
                col.addView(Sparkline(this, pts, green, line), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)))
                val days = (History.today() - pts.first().first + 1).toInt()
                col.addView(label("Son $days gün · en düşük ${money(pts.minOf { it.second })} · en yüksek ${money(pts.maxOf { it.second })}", 12f, muted)
                    .apply { setPadding(0, dp(4), 0, 0) })
            } else col.addView(label("Fiyat geçmişi bugünden itibaren tutuluyor; grafik ikinci günden sonra çıkar. Takibe alırsan her gün kontrol edilir.", 13f, muted))
        }
        historyBlock("Fiyat geçmişi", History.offer(this, o.link))

        // Aynı ürün başka sitelerde
        if (o.kind == Kind.MATCH) {
            val others = offers.filter { it.kind == Kind.MATCH && offerKey(it) != offerKey(o) }.sortedBy { it.price }.take(8)
            if (others.isNotEmpty()) {
                col.addView(label("Aynı ürün başka yerlerde", 15f, ink, true).apply { setPadding(0, dp(20), 0, dp(4)) })
                others.forEach { x ->
                    col.addView(LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, dp(12), 0, dp(12))
                        // Dokununca o sitedeki ürün sayfası açılır; uzun basınca İzci'deki sayfası
                        setOnClickListener { openLink(x) }
                        setOnLongClickListener { productSheet(x); true }
                        addView(label("● ", 12f, siteColors[x.site] ?: muted))
                        addView(label(x.site + if (x.seller.isNotEmpty()) " · ${x.seller}" else "", 14f, ink).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END },
                            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                        addView(label(money(x.price), 15f, if (x.price < o.price) green else ink, true))
                    })
                    col.addView(View(this).apply { setBackgroundColor(line) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
                }
            }
        }

        resultsScroll.post { resultsScroll.scrollTo(0, 0) }
    }

    /** Marka: başlığın ilk kelimesi, büyük harfle ("METONTECH") */
    private fun brandOf(title: String): String =
        title.trim().split(Regex("\\s+")).firstOrNull()?.trim('|', ',', '-', '(', ')', '"')?.takeIf { w -> w.any { it.isLetter() } }
            ?.uppercase(Locale("tr", "TR")).orEmpty()

    private var resultsScrollY = 0

    private fun backToResults() {
        if (offers.isEmpty()) { goHome(search = true); return }
        filterBar.visibility = View.VISIBLE
        status.visibility = View.VISIBLE
        chipsScroll.visibility = if (running) View.VISIBLE else View.GONE
        render(final = finished)
        val y = resultsScrollY
        resultsScroll.post { resultsScroll.scrollTo(0, y) }
    }

    /** Takip/alarm ürününün İzci sayfası: fotoğraf, fiyat, geçmiş, siteler; bildirim aç/kapat, ara ve güncelle, sil */
    private var itemBack = "alarms"

    private fun alarmPage(gid: Long, focusId: Long) {
        if (running) return
        val all = Alarms.load(this)
        val items = if (gid != 0L) all.filter { it.groupId == gid } else all.filter { it.id == focusId }
        val a = items.firstOrNull { it.id == focusId } ?: items.firstOrNull() ?: run { showAlarms(); return }
        screen = "item"
        filterBar.visibility = View.GONE
        chipsScroll.visibility = View.GONE
        status.visibility = View.GONE
        results.removeAllViews()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(6), dp(4), dp(6), dp(16)) }
        results.addView(col)
        col.addView(label(if (itemBack == "home") "‹  Listem" else if (a.watch) "‹  Takip listem" else "‹  Alarmlarım", 14f, gold, true).apply {
            setPadding(0, dp(6), 0, dp(10))
            setOnClickListener { itemGoBack() }
        })
        col.addView(ImageView(this).apply {
            background = rounded(Color.WHITE, 16)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            scaleType = ImageView.ScaleType.FIT_CENTER
            if (a.image.isNotEmpty()) loadImage(a.image, this, Uri.parse(a.link).let { u -> "${u.scheme}://${u.host}/" })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220)))
        brandOf(a.title).takeIf { it.isNotEmpty() }?.let { col.addView(label(it, 13f, muted, true).apply { letterSpacing = 0.08f; setPadding(0, dp(16), 0, 0) }) }
        col.addView(label(a.title, 17f, ink, true).apply { setPadding(0, dp(4), 0, dp(10)) })
        // Site, fiyatın hemen üstünde
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(6))
            addView(pill(a.site, Color.WHITE, siteColors[a.site] ?: ink, bold = true, size = 12f))
        })
        col.addView(label(if (a.lastPrice > 0) money(a.lastPrice) else "?", 30f,
            if (a.lastPrice > 0 && a.lastPrice < a.startPrice) green else ink, true))
        if (a.seller.isNotEmpty()) col.addView(label("Satıcı: ${a.seller}" + if (a.sellers > 1) " · ${a.sellers} satıcı arasında en ucuz" else "", 13f, muted)
            .apply { setPadding(0, dp(4), 0, 0) })
        a.unitPrice()?.let { col.addView(label(Alarms.unitText(it, a.unitName), 13f, muted).apply { setPadding(0, dp(4), 0, 0) }) }
        val t = if (a.unitTarget > 0) Alarms.unitText(a.unitTarget, a.unitName) else if (a.target > 0) money(a.target) else null
        if (!a.watch) col.addView(label(if (t != null) "Hedef: $t" else "Hedef: her ucuzlamada haber ver", 13f, ink, true).apply { setPadding(0, dp(6), 0, 0) })

        // İşlemler
        val acts = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(16), 0, 0) }
        fun act(icon: Int, text: String, on: Boolean, action: () -> Unit) {
            acts.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = rounded(if (on) greenBg else surface, 14, line)
                setPadding(dp(4), dp(12), dp(4), dp(10))
                contentDescription = text
                setOnClickListener { action() }
                addView(ImageView(this@MainActivity).apply {
                    setImageResource(icon)
                    imageTintList = android.content.res.ColorStateList.valueOf(if (on) green else ink)
                }, LinearLayout.LayoutParams(dp(24), dp(24)))
                addView(label(text, 12f, if (on) green else ink, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0) })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
        }
        val ids = items.map { it.id }.toSet()
        // Takip et: takipteyse çıkarma sorusu; alarmdaysa bildirimsiz takibe çevirir
        act(R.drawable.ic_watch, if (a.watch) "Takipte" else "Takip et", a.watch) {
            if (a.watch) {
                AlertDialog.Builder(this).setTitle("Takipten çıkarılsın mı?")
                    .setPositiveButton("Çıkar") { _, _ ->
                        if (gid != 0L) Alarms.removeGroup(this, gid) else Alarms.remove(this, a.id)
                        itemGoBack()
                    }.setNegativeButton("Vazgeç", null).show()
            } else {
                Alarms.save(this, Alarms.load(this).map { if (it.id in ids) it.copy(watch = true) else it })
                Toast.makeText(this, "Takibe çevrildi: bildirim gelmeyecek", Toast.LENGTH_SHORT).show()
                alarmPage(gid, a.id)
            }
        }
        // Alarm kur: hedef fiyat sorulur; alarm varsa hedef değiştirilir ya da alarm silinir
        act(R.drawable.ic_bell, if (a.watch) "Alarm kur" else "Alarm var", !a.watch) {
            val unit = a.unitAmount > 0
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(8), dp(22), 0) }
            val cur = if (unit) a.unitPrice() else a.lastPrice.takeIf { it > 0 }
            if (cur != null) box.addView(label("Şu an: " + (if (unit) Alarms.unitText(cur, a.unitName) else money(cur)), 13f, muted))
            val tIn = EditText(this).apply {
                hint = if (unit) "Hedef (TL/${a.unitName})" else "Hedef fiyat (TL)"
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                val t0 = if (unit) a.unitTarget else a.target
                if (!a.watch && t0 > 0) setText(Alarms.unitText(t0, "").substringBefore(" TL"))
            }
            box.addView(tIn)
            box.addView(label("Boş bırakırsan her ucuzlamada haber veririm.", 12f, muted).apply { setPadding(0, dp(4), 0, 0) })
            AlertDialog.Builder(this).setTitle(if (a.watch) "Fiyat alarmı kur" else "Alarm")
                .setView(box)
                .setPositiveButton("Kaydet") { _, _ ->
                    val t = tIn.text.toString().replace(".", "").replace(",", ".").toDoubleOrNull() ?: 0.0
                    Alarms.save(this, Alarms.load(this).map {
                        if (it.id in ids) it.copy(watch = false, target = if (unit) 0.0 else t, unitTarget = if (unit) t else 0.0) else it
                    })
                    askNotificationPermission()
                    Toast.makeText(this, "Alarm kuruldu", Toast.LENGTH_SHORT).show()
                    alarmPage(gid, a.id)
                }
                .apply {
                    if (!a.watch) setNeutralButton("Alarmı sil") { _, _ ->
                        if (gid != 0L) Alarms.removeGroup(this@MainActivity, gid) else Alarms.remove(this@MainActivity, a.id)
                        itemGoBack()
                    }
                }
                .setNegativeButton("Vazgeç", null).show()
        }
        act(R.drawable.ic_open, "Siteye git", false) { try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(a.link))) } catch (e: Exception) {} }
        // Tek ilan: aynı ürünü diğer sitelerde ara; sonuçlarda zile dokunup bütün sitelerde izlemeye geçilir
        act(R.drawable.ic_share, "Link ekle", false) { attachFromClipboard(a) }
        col.addView(acts)

        // Fiyat geçmişi
        fun historyBlock(title: String, pts: List<Pair<Long, Double>>, fmt: (Double) -> String) {
            col.addView(label(title, 15f, ink, true).apply { setPadding(0, dp(20), 0, dp(6)) })
            if (pts.size >= 2) {
                col.addView(Sparkline(this, pts, green, line), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)))
                val days = (History.today() - pts.first().first + 1).toInt()
                col.addView(label("Son $days gün · en düşük ${fmt(pts.minOf { it.second })} · en yüksek ${fmt(pts.maxOf { it.second })}", 12f, muted)
                    .apply { setPadding(0, dp(4), 0, 0) })
            } else col.addView(label("Grafik ikinci günden sonra çıkar; fiyat her kontrolde günlük kaydediliyor.", 13f, muted))
        }
        if (gid != 0L) {
            val unit = a.unitAmount > 0
            historyBlock("Ürünün en uygun fiyatı", History.get(this, "g:$gid")) { v -> if (unit) Alarms.unitText(v, a.unitName) else money(v) }
        }
        historyBlock(if (gid != 0L) "Bu ilanın fiyatı (${a.site})" else "Fiyat geçmişi", History.offer(this, a.link)) { money(it) }

        // Ürün alarmında diğer siteler
        if (items.size > 1) {
            col.addView(label("Bütün siteler", 15f, ink, true).apply { setPadding(0, dp(20), 0, dp(4)) })
            val unit = a.unitAmount > 0
            items.sortedBy { Alarms.metric(it) ?: Double.MAX_VALUE }.forEach { x ->
                col.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(12), 0, dp(12))
                    // Dokununca o sitedeki ürün sayfası açılır; uzun basınca bu sayfada o ilana geçilir
                    setOnClickListener { try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(x.link))) } catch (e: Exception) {} }
                    setOnLongClickListener {
                        AlertDialog.Builder(this@MainActivity).setTitle("${x.site} linki")
                            .setItems(arrayOf("Bu linkin ayrıntısına geç", "Bu linki karttan çıkar")) { _, w ->
                                if (w == 0) alarmPage(gid, x.id)
                                else {
                                    Alarms.remove(this@MainActivity, x.id)
                                    val rest = Alarms.load(this@MainActivity).filter { it.groupId == gid }
                                    if (rest.isEmpty()) goHome() else alarmPage(gid, rest.first().id)
                                }
                            }.show()
                        true
                    }
                    addView(label("● ", 12f, siteColors[x.site] ?: muted))
                    addView(label(x.site + if (x.seller.isNotEmpty()) " · ${x.seller}" else "", 14f, if (x.id == a.id) gold else ink, x.id == a.id).apply {
                        maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(label(Alarms.metric(x)?.let { if (unit) Alarms.unitText(it, x.unitName) else money(it) } ?: "?", 14f, ink, true))
                })
                col.addView(View(this).apply { setBackgroundColor(line) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
            }
        }
        resultsScroll.post { resultsScroll.scrollTo(0, 0) }
    }

    private fun itemGoBack() {
        if (itemBack == "home") goHome() else showAlarms(itemBack)
    }

    /** Tek dokunuşla takip: aynı ürün birden çok sitedeyse hepsi, değilse bu ilan. Bildirim yok. */
    private fun quickWatch(o: Offer) {
        try {
            val group = if (o.kind == Kind.MATCH) groupCandidates(o) else emptyList()
            val unit = offerUnit(o)
            if (group.size >= 2) {
                val gid = System.currentTimeMillis()
                val items = group.mapIndexed { i, c ->
                    val cu = offerUnit(c)
                    Alarm(id = gid + i, title = c.title, site = c.site, link = c.link, image = c.image,
                        startPrice = c.price, lastPrice = c.price, lowest = c.price, target = 0.0, wantStock = false, inStock = true,
                        lastCheck = 0L, status = "henüz kontrol edilmedi",
                        unitAmount = if (unit != null) cu?.first ?: 0.0 else 0.0, unitName = if (unit != null) cu?.second.orEmpty() else "",
                        groupId = gid, groupName = identity?.label ?: query, groupQuery = userInput, watch = true)
                }
                Alarms.addGroup(this, items)
                Toast.makeText(this, "Takibe alındı: ${items.size} sitede", Toast.LENGTH_SHORT).show()
            } else {
                Alarms.add(this, Alarm(id = System.currentTimeMillis(), title = o.title, site = o.site, link = o.link, image = o.image,
                    startPrice = o.price, lastPrice = o.price, lowest = o.price, target = 0.0, wantStock = false, inStock = true,
                    lastCheck = 0L, status = "henüz kontrol edilmedi",
                    unitAmount = unit?.first ?: 0.0, unitName = unit?.second.orEmpty(), watch = true))
                Toast.makeText(this, "Takibe alındı", Toast.LENGTH_SHORT).show()
            }
            if (offers.isNotEmpty() && !running && screen != "product") render(final = finished)
        } catch (e: Exception) {
            Toast.makeText(this, "Takibe alınamadı: ${e.message}", Toast.LENGTH_LONG).show()
        }
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
            setOnClickListener { productSheet(o) }
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
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(if (dark) c("#6B5A1E") else c("#F5DFA0"), if (dark) c("#2F3E4E") else c("#E7E5E4")))
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
        val keepY = if (screen == "profile") resultsScroll.scrollY else 0
        screen = "profile"
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
        head.addView(label("Profil", 26f, ink, true).apply {
            typeface = try { resources.getFont(R.font.outfit_black) } catch (e: Exception) { Typeface.DEFAULT_BOLD }
        })
        results.addView(head)
        // Profil kartı: baş harfler, ad, e-posta, liste özeti
        val all = try { Alarms.load(this) } catch (e: Exception) { emptyList() }
        val es = wishEntries(all)
        results.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(surface, 20, line)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setOnClickListener { profileDialog() }
                addView(label(initials(), 20f, accentOn, true).apply {
                    gravity = Gravity.CENTER
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(gold) }
                }, LinearLayout.LayoutParams(dp(56), dp(56)).apply { marginEnd = dp(14) })
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(label(profileName().ifEmpty { "Adını ekle" }, 17f, ink, true))
                    addView(label(profileMail().ifEmpty { "Dokunup ad ve e-posta yaz" }, 13f, muted))
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(label("Düzenle", 13f, gold, true))
            })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(16), 0, 0)
                fun stat(n: Int, t: String, mode: String) = addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    background = rounded(greyBg, 14)
                    setPadding(dp(4), dp(10), dp(4), dp(10))
                    setOnClickListener { if (mode == "home") { homeFilter = "home"; goHome() } else showAlarms(mode) }
                    addView(label("$n", 20f, ink, true).apply { gravity = Gravity.CENTER })
                    addView(label(t, 12f, muted).apply { gravity = Gravity.CENTER })
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = if (mode != "watch") dp(8) else 0 })
                stat(es.size, "ürün", "home")
                stat(es.count { !it.watch }, "alarm", "alarms")
                stat(es.count { it.watch }, "takip", "watch")
            })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })

        // Görünüm
        val look = settingsCard("Görünüm")
        choiceRow(look, "Tema", "Uygulamanın açık ya da koyu renkte görünmesi", listOf("Telefonla aynı", "Açık", "Koyu"),
            settings.getInt("theme", 0)) { i ->
            settings.edit().putInt("theme", i).apply()
            handler.post { recreate() }
        }

        // Bildirimler ve arka plan
        val perm = settingsCard("Bildirimler ve arka plan")
        val nm = getSystemService(android.app.NotificationManager::class.java)
        val notifOk = nm?.areNotificationsEnabled() == true
        actionRow(perm, "Bildirimler", notifOk, "Fiyat düştüğünde ya da ürün stoğa girdiğinde haber al", if (notifOk) "Yönet" else "Aç") {
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
        actionRow(perm, "Arka planda çalışma", batteryOk, "Uygulama kapalıyken de fiyatların kontrol edilmesi için gerekir", if (batteryOk) "Yönet" else "İzin ver") {
            if (!batteryOk) openIntent(
                Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
                Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS), appDetails())
            else openIntent(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS), appDetails())
        }
        if (android.os.Build.MANUFACTURER.lowercase(Locale.ROOT).let { it.contains("xiaomi") || it.contains("redmi") || it.contains("poco") })
            actionRow(perm, "Otomatik başlatma", null, "Telefon yeniden açıldığında takibin kesintisiz sürmesini sağlar", "Aç") {
                openIntent(
                    Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
                    Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT),
                    appDetails())
            }
        actionRow(perm, "Diğer izinler", null, "Tüm uygulama izinlerini telefonun ayarlarından yönet", "Aç") { openIntent(appDetails()) }

        results.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(26), 0, dp(12))
            addView(label("İzci", 14f, ink, true))
            addView(label("En uygun fiyatı senin için bulur, izler, haber verir", 12f, muted).apply { setPadding(0, dp(2), 0, 0) })
            addView(label("Sürüm ${Update.currentName(this@MainActivity)}", 11f, muted).apply { setPadding(0, dp(4), 0, 0) })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        resultsScroll.post { resultsScroll.scrollTo(0, keepY) }
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
        val best = Alarms.groupBest(items)
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
                            offer: Offer? = null, replaceGroup: Long = 0L, compareName: String? = null, extras: List<Offer> = emptyList()) {
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
            (if (price != null) (if (compareName != null) "Şu an ${money(price)} · $site" else "Dokunduğun ilan: ${money(price)} · $site")
             else "Şu an fiyat okunamadı · $site") +
                (if (unit != null && price != null) " · ${Alarms.unitText(price / unit.first, unit.second)}" else "") +
                if (available == false) " · stokta yok" else "",
            13f, muted, true
        ).apply { setPadding(0, dp(6), 0, dp(10)) })

        val existing = Alarms.load(this).firstOrNull { it.link.substringBefore('?') == link.substringBefore('?') }
        // Alarm mı, sadece takip mi: kullanıcı seçer (varsayılan yok; kayıtlıysa mevcut hali)
        var chosenWatch: Boolean? = existing?.watch
        val alarmBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var dialogRef: AlertDialog? = null
        val modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(2), 0, dp(10)) }
        val optViews = mutableListOf<LinearLayout>()
        fun paintModes() {
            optViews.forEachIndexed { i, v ->
                val on = chosenWatch == (i == 1)
                v.background = rounded(if (on) greenBg else surface, 14, if (on) gold else line, if (on) 2 else 1)
            }
            alarmBox.visibility = if (chosenWatch == false) View.VISIBLE else View.GONE
            dialogRef?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = chosenWatch != null
        }
        listOf(Triple(R.drawable.ic_bell, "Alarm kur", "Fiyat düşünce bildirim"), Triple(R.drawable.ic_watch, "Sadece takip", "Bildirim yok")).forEachIndexed { i, (ic, t, sub) ->
            val v = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(12), dp(6), dp(10))
                setOnClickListener { chosenWatch = i == 1; paintModes() }
                addView(ImageView(this@MainActivity).apply {
                    setImageResource(ic)
                    imageTintList = android.content.res.ColorStateList.valueOf(gold)
                }, LinearLayout.LayoutParams(dp(26), dp(26)))
                addView(label(t, 14f, ink, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0) })
                addView(label(sub, 11f, muted).apply { gravity = Gravity.CENTER })
            }
            optViews.add(v)
            modeRow.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i == 0) marginEnd = dp(8) })
        }
        box.addView(modeRow)
        box.addView(alarmBox)

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
            alarmBox.addView(label("Hedef nasıl olsun?", 13f, ink, true).apply { setPadding(0, dp(8), 0, 0) })
            modeGroup.addView(mTotal); modeGroup.addView(mUnit)
            modeGroup.check(mUnit.id)
            modeGroup.setOnCheckedChangeListener { _, _ -> setHint() }
            alarmBox.addView(modeGroup)
        }
        setHint()
        alarmBox.addView(label("Hedef fiyat", 13f, ink, true))
        alarmBox.addView(target)
        existing?.let { ex -> val t0 = if (ex.unitTarget > 0) ex.unitTarget else ex.target; if (t0 > 0) target.setText(Alarms.unitText(t0, "").substringBefore(" TL")) }
        val stock = android.widget.CheckBox(this).apply {
            text = "Stoğa girince de haber ver"
            isChecked = existing?.wantStock ?: (available == false)
        }
        alarmBox.addView(stock)
        box.addView(label("Fiyatlar ${Alarms.hours(this)} saatte bir kontrol edilir.", 12f, muted)
            .apply { setPadding(0, dp(6), 0, 0) })
        dialogRef = AlertDialog.Builder(this)
            .setTitle(if (existing != null) "Listende var — güncelle" else "Listeye ekle")
            .setView(box)
            .setPositiveButton(if (existing != null) "Kaydet" else "Ekle") { _, _ -> try {
                if (replaceGroup != 0L) Alarms.removeGroup(this, replaceGroup)
                val watchOnly = chosenWatch == true
                val t = if (watchOnly) 0.0 else target.text.toString().replace(".", "").replace(",", ".").toDoubleOrNull() ?: 0.0
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
                            watch = watchOnly,
                        )
                    }
                    Alarms.addGroup(this, items)
                    Toast.makeText(this, "Listene eklendi: ${items.size} sitede izleniyor", Toast.LENGTH_SHORT).show()
                } else {
                    val p = price ?: 0.0
                    val newId = existing?.id ?: System.currentTimeMillis()
                    Alarms.add(this, Alarm(
                        id = newId, title = title, site = site, link = Links.clean(link), image = image,
                        startPrice = existing?.startPrice ?: p, lastPrice = p, lowest = if (p > 0) p else 0.0,
                        target = if (byUnit) 0.0 else t, wantStock = stock.isChecked, inStock = available,
                        lastCheck = 0L, status = "henüz kontrol edilmedi",
                        unitAmount = if (unit != null) unit.first else 0.0, unitName = unit?.second.orEmpty(),
                        unitTarget = if (byUnit) t else 0.0, watch = watchOnly,
                    ))
                    // Barkoddan seçilen diğer siteler: aynı karta
                    extras.forEach { x ->
                        Alarms.load(this).firstOrNull { it.id == newId || (it.groupId != 0L && Alarms.load(this).any { m -> m.id == newId && m.groupId == it.groupId }) }
                            ?.let { tgt -> joinGroup(tgt, newListing(x.title, x.link, x.site, x.image, x.price, true, null), quiet = true) }
                    }
                    Toast.makeText(this, if (existing != null) "Güncellendi" else if (watchOnly) "Takibe alındı" else "Alarm kuruldu", Toast.LENGTH_SHORT).show()
                }
                if (!watchOnly) askNotificationPermission()
                if (compareName != null && !running) goHome()
                else if (offers.isNotEmpty() && !running) render(final = finished) else if (!running) showHome()
            } catch (e: Exception) {
                AlertDialog.Builder(this).setTitle("Alarm kurulamadı")
                    .setMessage(e.javaClass.simpleName + ": " + (e.message ?: ""))
                    .setNegativeButton("Kapat", null).show()
            } }
            .apply {
                if (existing != null) setNeutralButton("Listeden çıkar") { _, _ ->
                    if (existing.groupId != 0L) Alarms.removeGroup(this@MainActivity, existing.groupId)
                    else Alarms.remove(this@MainActivity, existing.id)
                    if (compareName != null && !running) goHome()
                    else if (offers.isNotEmpty() && !running) render(final = finished)
                } else if (compareName != null && Alarms.load(this@MainActivity).isNotEmpty()) setNeutralButton("Listemdeki ürüne ekle") { _, _ ->
                    chooseCard(title) { target ->
                        joinGroup(target, newListing(title, link, site, image, price, available, null))
                        showHome()
                    }
                }
            }
            .setNegativeButton("Vazgeç", null)
            .show()
        paintModes()
    }

    private fun askNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7)
        }
    }

    /** Alarmlarım ekranı */
    @Volatile private var autoChecking = false

    /** "alarms": bildirimli alarmlar · "watch": sadece takip (bildirim yok) */
    private var alarmView = "alarms"

    /** Alarmlar / Takip sekmeleri: ana sayfa kartlarının süzülmüş hali */
    private fun showAlarms(mode: String = alarmView) {
        alarmView = mode
        homeFilter = if (mode == "watch") "watch" else "alarms"
        goHome()
    }

    private fun showAlarmsOld(mode: String = alarmView) {
        if (running) return
        alarmView = mode
        val watchMode = mode == "watch"
        screen = mode
        filterBar.visibility = View.GONE
        chipsScroll.visibility = View.GONE
        progress.visibility = View.GONE
        status.visibility = View.GONE
        results.removeAllViews()
        val list = Alarms.load(this).filter { it.watch == watchMode }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }
        head.addView(label((if (watchMode) "Takip listem" else "Alarmlarım") + if (list.isNotEmpty()) " · ${productCount(list)}" else "", 20f, ink, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        // Sayfa açılınca fiyatlar 2 saatten eskiyse arka planda sessizce yenilenir
        val newest = list.maxOfOrNull { it.lastCheck } ?: 0L
        if (list.isNotEmpty() && !autoChecking && System.currentTimeMillis() - newest > 2 * 3_600_000L) {
            autoChecking = true
            Thread {
                try { Alarms.checkAll(applicationContext) } catch (e: Exception) {}
                handler.post {
                    autoChecking = false
                    if (screen == mode && !running) showAlarms(mode)
                }
            }.start()
        }
        results.addView(head)
        // Kısa tanıtım satırı
        results.addView(label(if (watchMode)
            "Fiyatını merak ettiğin ürünler burada. İzci fiyatları ve geçmişi senin için düzenli kaydeder; bildirim göndermez."
        else
            "Beklediğin fiyata inen ya da yeniden stoğa giren ürünlerden anında haberin olur.", 13f, muted)
            .apply { setPadding(dp(4), 0, dp(4), dp(10)) })
        if (list.isEmpty()) {
            results.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                background = rounded(surface, 20, line)
                setPadding(dp(20), dp(28), dp(20), dp(24))
                addView(FrameLayout(this@MainActivity).apply {
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(greenBg) }
                    addView(ImageView(this@MainActivity).apply {
                        setImageResource(if (watchMode) R.drawable.ic_watch else R.drawable.ic_bell)
                        imageTintList = android.content.res.ColorStateList.valueOf(gold)
                    }, FrameLayout.LayoutParams(dp(28), dp(28)).apply { gravity = Gravity.CENTER })
                }, LinearLayout.LayoutParams(dp(64), dp(64)))
                addView(label(if (watchMode) "Takip listen henüz boş" else "Henüz alarm kurmadın", 17f, ink, true)
                    .apply { gravity = Gravity.CENTER; setPadding(0, dp(14), 0, dp(6)) })
                addView(label(if (watchMode)
                    "Bir ürünü aradıktan sonra ürün sayfasında “Takip et”e dokun. Fiyatını ve fiyat geçmişini buradan izlersin."
                else
                    "Bir ürünün sayfasında “Alarm kur”a dokun, hedef fiyatını belirle, gerisini İzci'ye bırak. " +
                        "Trendyol, Hepsiburada gibi uygulamalarda Paylaş → İzci ile de ürün ekleyebilirsin.", 13f, muted)
                    .apply { gravity = Gravity.CENTER; setLineSpacing(dp(2).toFloat(), 1f) })
                addView(label("Listeme dön", 14f, accentOn, true).apply {
                    background = rounded(gold, 22)
                    setPadding(dp(22), dp(11), dp(22), dp(11))
                    setOnClickListener { goHome() }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18) })
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
            return
        }
        val df = java.text.SimpleDateFormat("d MMM HH:mm", Locale("tr", "TR"))
        // Akordiyon: kapalıyken küçük satır, dokununca ayrıntılar açılır
        fun accordion(key: String, image: String, link: String, title: String, sub: String, price: String, priceColor: Int,
                      openPage: () -> Unit, detail: (LinearLayout) -> Unit) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(surface, 14, line)
            }
            val body = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), 0, dp(14), dp(12))
                visibility = if (key in expandedAlarms) View.VISIBLE else View.GONE
            }
            // Büyük ok: akordiyonu açar/kapatır. Satırın geri kalanı (görsel, ad) ürün sayfasını açar.
            val chev = label(if (key in expandedAlarms) "▴" else "▾", 24f, ink, true).apply {
                gravity = Gravity.CENTER
                contentDescription = "Ayrıntıları aç"
                background = rounded(greyBg, 22)
                setOnClickListener {
                    val open = body.visibility != View.VISIBLE
                    body.visibility = if (open) View.VISIBLE else View.GONE
                    text = if (open) "▴" else "▾"
                    if (open) expandedAlarms.add(key) else expandedAlarms.remove(key)
                }
            }
            card.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(8), dp(10), dp(8))
                setOnClickListener { openPage() }
                addView(ImageView(this@MainActivity).apply {
                    background = rounded(Color.WHITE, 8)
                    setPadding(dp(2), dp(2), dp(2), dp(2))
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    if (image.isNotEmpty()) loadImage(image, this, Uri.parse(link).let { u -> "${u.scheme}://${u.host}/" })
                }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) })
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(label(title, 14f, ink, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                    addView(label(sub, 12f, muted).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(2), 0, 0) })
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(label(price, 14f, priceColor, true).apply { setPadding(dp(8), 0, dp(10), 0) })
                addView(chev, LinearLayout.LayoutParams(dp(44), dp(44)))
            })
            detail(body)
            card.addView(body)
            results.addView(card, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(2), dp(3), dp(2), dp(3)) })
        }
        fun actions(body: LinearLayout, openPage: () -> Unit, delete: () -> Unit) {
            body.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, 0)
                addView(label("Ürün sayfası", 13f, accentOn, true).apply {
                    background = rounded(gold, 12)
                    setPadding(dp(14), dp(8), dp(14), dp(8))
                    setOnClickListener { openPage() }
                })
                addView(View(this@MainActivity), LinearLayout.LayoutParams(0, 1, 1f))
                addView(label("Sil", 13f, red, true).apply {
                    setPadding(dp(12), dp(8), dp(4), dp(8))
                    setOnClickListener { delete() }
                })
            })
        }
        // Ürün alarmları (birden çok site)
        for ((gid, items) in list.filter { it.groupId != 0L }.groupBy { it.groupId }) {
            val best = Alarms.groupBest(items) ?: items.first()
            val unit = best.unitAmount > 0
            val fmt = { v: Double -> if (unit) Alarms.unitText(v, best.unitName) else money(v) }
            accordion("g$gid", best.image, best.link, best.groupName.ifEmpty { best.title },
                "${items.size} site · en uygun ${best.site}", Alarms.metric(best)?.let { fmt(it) } ?: "?", green,
                { itemBack = alarmView; alarmPage(gid, best.id) }) { body ->
                val t = if (best.unitTarget > 0) best.unitTarget else best.target
                if (!best.watch) body.addView(label(if (t > 0) "Hedef: ${fmt(t)}" else "Hedef: her ucuzlamada haber ver", 12f, muted).apply { setPadding(0, dp(2), 0, dp(4)) })
                items.sortedBy { Alarms.metric(it) ?: Double.MAX_VALUE }.forEach { a ->
                    body.addView(LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(0, dp(7), 0, dp(7))
                        setOnClickListener { try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(a.link))) } catch (e: Exception) {} }
                        addView(label("● ", 12f, siteColors[a.site] ?: muted))
                        addView(label(a.site, 13f, ink), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                        addView(label((Alarms.metric(a)?.let { fmt(it) } ?: "?") + if (a.status.startsWith("fiyat okunamadı")) " · okunamadı" else "", 13f, muted))
                    })
                }
                val gh = History.get(this, "g:$gid")
                if (gh.size >= 2) {
                    body.addView(Sparkline(this, gh, green, line), LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(6) })
                    body.addView(label("Geçmiş ${gh.size} gün · en düşük ${fmt(gh.minOf { it.second })} · en yüksek ${fmt(gh.maxOf { it.second })}", 11f, muted))
                }
                if (items.any { it.lastCheck > 0 }) body.addView(label("Son kontrol ${df.format(java.util.Date(items.maxOf { it.lastCheck }))}", 11f, muted)
                    .apply { setPadding(0, dp(4), 0, 0) })
                actions(body, { itemBack = alarmView; alarmPage(gid, best.id) }) { Alarms.removeGroup(this, gid); showAlarms() }
            }
        }
        // Tek ilanlar
        for (a in list.filter { it.groupId == 0L }) {
            accordion("a${a.id}", a.image, a.link, a.title, a.site + when (a.inStock) { false -> " · stokta yok"; true -> " · stokta"; else -> "" },
                if (a.lastPrice > 0) money(a.lastPrice) else "?", if (a.lastPrice > 0 && a.lastPrice < a.startPrice) green else ink,
                { itemBack = alarmView; alarmPage(0L, a.id) }) { body ->
                val chips = FlowLayout(this, dp(6)).apply { setPadding(0, dp(2), 0, 0) }
                if (a.startPrice > 0) chips.addView(pill("başta ${money(a.startPrice)}", ink, greyBg))
                if (a.lowest > 0 && a.lowest < a.startPrice) chips.addView(pill("en düşük ${money(a.lowest)}", green, greenBg))
                if (!a.watch) chips.addView(pill(when {
                    a.unitTarget > 0 -> "hedef ${Alarms.unitText(a.unitTarget, a.unitName)}"
                    a.target > 0 -> "hedef ${money(a.target)}"
                    else -> "her düşüşte"
                }, ink, amberBg))
                a.unitPrice()?.let { chips.addView(pill(Alarms.unitText(it, a.unitName), ink, greyBg)) }
                body.addView(chips)
                val oh = History.offer(this, a.link)
                if (oh.size >= 2) body.addView(Sparkline(this, oh, green, line), LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(40)).apply { topMargin = dp(6) })
                body.addView(label((if (a.lastCheck > 0) "Son kontrol ${df.format(java.util.Date(a.lastCheck))} · " else "") + a.status, 11f, muted)
                    .apply { setPadding(0, dp(6), 0, 0) })
                actions(body, { itemBack = alarmView; alarmPage(0L, a.id) }) { Alarms.remove(this, a.id); showAlarms() }
            }
        }
    }

    private val expandedAlarms = mutableSetOf<String>()

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
                        raw.replace(" ", "").let { c -> c.length in 8..14 && c.all { it.isDigit() } } -> barcodeSearch(raw.replace(" ", ""))
                        raw.isNotEmpty() -> Toast.makeText(this, "Bu kod bir ürün barkodu değil", Toast.LENGTH_SHORT).show()
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

    /** Paylaşılan ya da yapıştırılan link: ürünü okur; attachTo verilirse o karta ekler */
    private fun handleSharedText(text: String, attachTo: Alarm? = null) {
        val url = Regex("https?://\\S+").find(text)?.value?.trimEnd('.', ',', ')') ?: run {
            Toast.makeText(this, "Paylaşılan yazıda ürün linki yok", Toast.LENGTH_LONG).show()
            return
        }
        if (!onList() && screen != "item") goHome()
        status.visibility = View.VISIBLE
        status.text = "Paylaşılan ürün okunuyor…"
        progress.visibility = View.VISIBLE
        Thread {
            // Kısa/takip linki (Trendyol adj.st, ty.gl, amzn.eu…) → gerçek ürün sayfası
            val real = try { Links.resolve(url) } catch (e: Exception) { url }
            handler.post {
                if (Links.isProductPage(real)) readShared(real, text, attachTo)
                // Olmadıysa linki gizli tarayıcıda açıp gittiği ürün sayfasını yakala
                else resolveInBrowser(url) { found -> readShared(found ?: real, text, attachTo) }
            }
        }.start()
    }

    /** Linki görünmez bir tarayıcıda açar; ürün sayfasına (ya da uygulama linkine) ulaşınca adresi verir */
    @SuppressLint("SetJavaScriptEnabled")
    private fun resolveInBrowser(url: String, done: (String?) -> Unit) {
        var finished = false
        val wv = WebView(this)
        fun finish(u: String?) {
            if (finished) return
            finished = true
            handler.post { detachHidden(wv) }
            done(u)
        }
        fun check(u: String?): Boolean {
            if (u == null) return false
            if (Links.isProductPage(u)) { finish(Links.clean(u)); return true }
            Links.embedded(u)?.let { e -> if (Links.isProductPage(e)) { finish(Links.clean(e)); return true } }
            return false
        }
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        attachHidden(wv)
        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val u = request?.url?.toString() ?: return false
                if (check(u)) return true
                // Uygulama/mağaza linklerine gitme (ty://, intent://, market://)
                return !u.startsWith("http")
            }
            override fun onPageFinished(view: WebView?, u: String?) { check(u) }
        }
        handler.postDelayed({ finish(null) }, 12_000)
        try { wv.loadUrl(url) } catch (e: Exception) { finish(null) }
    }


    private fun attachHidden(wv: WebView) {
        try {
            wv.alpha = 0f
            (window.decorView as ViewGroup).addView(wv, 0, ViewGroup.LayoutParams(dp(360), dp(640)).also { })
            wv.translationX = -dp(2000).toFloat()
        } catch (e: Exception) {}
    }

    private fun detachHidden(wv: WebView) {
        try { wv.stopLoading(); (wv.parent as? ViewGroup)?.removeView(wv); wv.destroy() } catch (e: Exception) {}
    }

    /** Sayfayı görünmez tarayıcıda açıp okur (düz okumaya sayfa göstermeyen siteler için) */
    private fun readInBrowser(url: String, done: (Alarms.PageInfo?) -> Unit) =
        PageReader.read(this, url, attach = { attachHidden(it) }, done = done)

    /** Düz okuma eksik kaldıysa tarayıcıyla tamamlar */
    private fun readProduct(url: String, done: (Alarms.PageInfo?) -> Unit) {
        Thread {
            val info = try { Alarms.fetch(url) } catch (e: Exception) { null }
            handler.post {
                val good = info != null && info.price != null && info.image.isNotEmpty() && !Links.looksLikeUrl(info.title) && !Alarms.multiSeller(url)
                if (good) done(info)
                else readInBrowser(url) { b ->
                    if (b == null) { done(info); return@readInBrowser }
                    done(Alarms.PageInfo(b.price ?: info?.price, b.inStock ?: info?.inStock,
                        b.title.takeIf { !Links.looksLikeUrl(it) } ?: info?.title.orEmpty(),
                        b.image.ifEmpty { info?.image.orEmpty() },
                        b.finalUrl.takeIf { Links.isProductPage(it) } ?: info?.finalUrl ?: url,
                        if ((b.price ?: info?.price) != null) "fiyat okundu" else "fiyat okunamadı", b.seller, b.sellers))
                }
            }
        }.start()
    }

    /** Düz okumayla fiyatı/adı/resmi gelmeyen kayıtları tarayıcıyla okuyup yeniden kontrol eder (bildirimler dahil) */
    private fun repairEntries(then: () -> Unit) {
        val ids = Alarms.load(this).filter { Alarms.needsBrowser(it) }
        PageReader.readAll(this, ids, attach = { attachHidden(it) }) { pre ->
            val failed = ids.map { it.id }.filter { it !in pre }.toSet()
            Thread {
                try { if (pre.isNotEmpty()) Alarms.checkAll(applicationContext, pre.keys, pre) } catch (e: Exception) {}
                // Tarayıcıyla okunamayanlar düz okumayla denenir
                try { if (failed.isNotEmpty()) Alarms.checkAll(applicationContext, failed) } catch (e: Exception) {}
                handler.post { then() }
            }.start()
        }
    }

    /** "Ürün Adı Fiyatı, Yorumları - Trendyol" → "Ürün Adı" */
    private fun cleanTitle(t: String) = t.split(Regex("\\s[-|–]\\s")).first()
        .replace(Regex("(?i)\\b(fiyatı|fiyatları|yorumları|özellikleri|satın al)\\b"), " ")
        .replace(Regex("[,\\s]+$"), "").replace(Regex("\\s+"), " ").trim()

    /** Ürün sayfasını okuyup "Listeye ekle" penceresini açar */
    private fun readShared(url: String, text: String, attachTo: Alarm? = null) {
        readProduct(url) { info ->
            run {
                progress.visibility = View.GONE
                status.text = ""
                status.visibility = View.GONE
                val finalUrl = Links.clean(info?.finalUrl?.takeIf { Links.isProductPage(it) } ?: url)
                if (!Links.isProductPage(finalUrl) && info?.price == null) {
                    AlertDialog.Builder(this)
                        .setTitle("Bu linki okuyamadım")
                        .setMessage("Link bir ürün sayfasına gitmiyor. Ürünü sitenin kendisinde (tarayıcıda) açıp linkini kopyalayıp “Link yapıştır”a dokunmayı dene.")
                        .setPositiveButton("Tamam", null).show()
                    return@run
                }
                val host = Uri.parse(finalUrl).host.orEmpty()
                val site = sites.firstOrNull { host.contains(it.name.lowercase(Locale.ROOT)) }?.name
                    ?: host.removePrefix("www.").substringBefore('.').replaceFirstChar { it.uppercase() }
                // Ürün adı: sayfa başlığı (site adı ve "Fiyatı" gibi ekler atılır); yoksa paylaşılan yazı
                val rawTitle = info?.title?.takeIf { !Links.looksLikeUrl(it) }
                    ?: Regex("https?://\\S+").replace(text, "").trim().takeIf { !Links.looksLikeUrl(it) } ?: "$site ürünü"
                val name = cleanTitle(rawTitle).ifEmpty { "$site ürünü" }
                if (attachTo != null) {
                    joinGroup(attachTo, newListing(name, finalUrl, site, info?.image.orEmpty(), info?.price, info?.inStock, info))
                    if (screen == "item") alarmPage(Alarms.load(this).firstOrNull { it.id == attachTo.id }?.groupId ?: 0L, attachTo.id) else showHome()
                    return@run
                }
                alarmDialog(name, finalUrl, site, info?.image.orEmpty(), info?.price, info?.inStock,
                    compareName = name.split(" ").take(8).joinToString(" "))
            }
        }
    }

    // ---------- Rapor ----------

    private fun buildReport(matches: List<Offer>, others: List<Offer>) {
        report.setLength(0)
        val total = android.os.SystemClock.elapsedRealtime() - startedAt
        report.append("İZCİ · DENEME 53\nGiriş: ").append(userInput)
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
        cm.setPrimaryClip(ClipData.newPlainText("İzci raporu", report.toString()))
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
