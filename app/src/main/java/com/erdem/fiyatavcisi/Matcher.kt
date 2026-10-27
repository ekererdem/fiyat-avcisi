package com.erdem.fiyatavcisi

import java.text.NumberFormat
import java.util.Locale

/*
 * Eşleştirme çekirdeği: ilan adlarını aramayla karşılaştırıp "aynı ürün / başka paket / farklı model…" kararını verir.
 * Android'den bağımsızdır; app/src/test altındaki testler bu dosyayı doğrudan çalıştırır.
 */
// Başlıkta geçerse "aksesuar olabilir" sayılan kelimeler
val accessoryWords = listOf(
    "uyumlu", "kapak", "kulaklık ucu", "uçları", "askı", "temizleme", "kalemi", "yedek bıçak", "yedek başlık", "yedek bicak", "kablo", "kılıf", "kilif", "adaptör", "adaptor",
    "koruyucu", "stand", "şarj aleti", "sarj aleti", "şarj cihazı", "çanta", "sticker",
    "replacement", "compatible", "blades", "blade kit", "charger", "cable", "case for",
    "cüzdan", "kartlık", "anahtarlık", "kordon", "kayış", "bağcık", "çorap", "tişört", "şapka", "poster",
    "lego", "oyuncak", "maket", "puzzle", "figür",
)

data class Offer(
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

enum class Kind { MATCH, MUADIL, POSSIBLE, SUSPICIOUS, OTHER_PACKAGE, USED, ACCESSORY, DIFFERENT_MODEL, OTHER }

/** Ürün kimliği: ne yazılırsa yazılsın (barkod, ad, kod) aramanın dayandığı tek ürün */
data class Identity(
    val code: String,        // küçük harf paket kodu: "qp2834/31", "mtjv3tu/a"
    val base: String,        // temel model: "qp2834", "mtjv3"
    val label: String,       // ekranda: "QP2834/31"
    val searchQuery: String, // sitelere sorulacak sabit arama: "Philips QP2834"
    val family: String = "",  // aile adı: "Philips OneBlade 360"
    val sample: String = "",
    val count: Int = 0,
    val price: Double = 0.0,
)

/** Miktar: paket başına miktar (g / ml / adet) ve paket sayısı. "200 gr x 3", "2 x 200 gr", "80'li * 2" */
data class Amount(val per: Double, val unit: String, val mult: Double) {
    val total get() = per * mult
}

val tl = NumberFormat.getNumberInstance(Locale("tr", "TR")).apply {
    maximumFractionDigits = 2
    minimumFractionDigits = 2
}

val tlWhole = NumberFormat.getNumberInstance(Locale("tr", "TR")).apply {
    maximumFractionDigits = 0
}

fun isAccessory(lowerTitle: String): Boolean {
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

val appleCode = Regex("\\b([a-z0-9]{4,5})([a-z]{1,2})/a\\b")

val genericCode = Regex("\\b([a-z]{1,4}[0-9]{3,5})(?:\\s*/\\s*([0-9]{2,3}))?\\b")

// TV/beyaz eşya tipi uzun kodlar: QE55Q60D, 55Q60D, QE55Q60DAUXTK, UE43CU7000
val longCode = Regex("\\b(?=[a-z0-9]*[a-z])(?=[a-z0-9]*[0-9])[a-z0-9]{6,16}\\b")

val unitTail = Regex(".*[0-9](ml|gr|g|kg|lt|l|cl|gb|tb|mb|cm|mm|li|lu|lı|lü|w|mah|hz|mp|x|inc|inch)$")

/** Başlıktaki paket kodları: (paket kodu, temel model) çiftleri */
fun codesIn(title: String): List<Pair<String, String>> {
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
fun codeCore(c: String): String {
    val lead = c.takeWhile { it.isLetter() }.length
    val core = c.drop(lead)
    return if (lead in 1..3 && core.length >= 5 && core.first().isDigit()) core else c
}

/** Aynı model mi: QE55Q60D = 55Q60D = QE55Q60DAUXTK (bölge eki). Paket kodlarında (/31) sadece birebir. */
fun sameCode(a: String, b: String): Boolean {
    if (a == b) return true
    if ('/' in a || '/' in b || '-' in a || '-' in b) return false
    val x = codeCore(a)
    val y = codeCore(b)
    if (x.length < 5 || y.length < 5) return false
    val (s, l) = if (x.length <= y.length) x to y else y to x
    return l.startsWith(s) && l.length - s.length <= 6 && l.substring(s.length).all { it.isLetter() }
}

/** Başlıkta bu kod (ya da bölge ekli/eksiz hali) geçiyor mu */
fun titleHasCode(title: String, code: String): Boolean =
    codesIn(title).any { sameCode(it.first, code) || sameCode(it.second, code) } || norm(title).contains(code.replace("/", ""))

val unitWords = setOf("adet", "li", "lı", "lu", "lü", "kg", "gr", "g", "ml", "lt", "l", "litre", "cl", "mm", "cm",
    "paket", "kutu", "rulo", "yaprak", "parça", "gram", "set", "takım")

// Karşılaştırma sadeleştirilmiş kelimelerle yapılır ("parça" -> "parca")
val unitNorm by lazy { unitWords.map { norm(it) }.toSet() }

// Arama kelimesi olarak şart sayılmayan kelimeler: renk, cinsiyet, konsantrasyon, genel tür adı
val colorWords = setOf("beyaz", "siyah", "kirmizi", "mavi", "lacivert", "gri", "yesil", "sari", "pembe", "mor",
    "turuncu", "kahverengi", "kahve rengi", "bej", "lila", "bordo", "haki", "gumus", "altin", "rose", "white", "black",
    "red", "blue", "grey", "gray", "green", "pink", "silver", "gold rengi")

val softWords = setOf("erkek", "kadin", "bayan", "unisex", "edt", "edp", "edc", "parfum", "extrait", "numara", "beden")

val concWords = setOf("edt", "edp", "edc", "extrait", "parfum")

/** Yazım eşlerini tek biçime indirir: "Eau de Toilette" = EDT, "Ultra Fırsat" bir seri değil */
fun canonText(s: String): String = s.lowercase(Locale("tr", "TR"))
    .replace(Regex("eau\\s+de\\s+toilette"), " edt ")
    .replace(Regex("eau\\s+de\\s+parfum"), " edp ")
    .replace(Regex("eau\\s+de\\s+cologne"), " edc ")
    .replace(Regex("(ultra|mega|süper|super|jumbo)\\s*(fırsat|firsat|ekonomik|paket)"), " fırsatpaketi ")

fun tokensOf(s: String) = canonText(s).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }

// Aynı şeyin farklı yazımları tek biçime iner (ataç = ataş)
// Türkçe harfler tek biçime iner (Itfaiye = İtfaiye, Clınıcal = Clinical); yazım eşleri birleşir (ataç = ataş)
fun norm(s: String) = java.text.Normalizer.normalize(
    s.lowercase(Locale("tr", "TR"))
        .replace('ı', 'i').replace('ş', 's').replace('ç', 'c').replace('ğ', 'g').replace('ö', 'o').replace('ü', 'u'),
    java.text.Normalizer.Form.NFD)
    // Nescafé = Nescafe, Crème = Creme
    .replace(Regex("\\p{Mn}+"), "")
    .replace("atac", "atas").replace(Regex("[^a-z0-9]"), "")

/** Başlığın karşılaştırma için sade hali (EDT yazım eşleri dahil) */
fun canonNorm(s: String) = norm(canonText(s))

// Seriyi/modeli değiştiren kelimeler: başlıkta olup aramada yoksa o ilan başka bir üründür
val modMap = mapOf(
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
val colorMap = mapOf(
    "beyaz" to "beyaz", "white" to "beyaz", "siyah" to "siyah", "black" to "siyah", "kirmizi" to "kırmızı", "red" to "kırmızı",
    "mavi" to "mavi", "blue" to "mavi", "lacivert" to "lacivert", "navy" to "lacivert", "gri" to "gri", "grey" to "gri",
    "gray" to "gri", "yesil" to "yeşil", "green" to "yeşil", "sari" to "sarı", "yellow" to "sarı", "pembe" to "pembe",
    "pink" to "pembe", "mor" to "mor", "purple" to "mor", "turuncu" to "turuncu", "orange" to "turuncu",
    "kahverengi" to "kahverengi", "brown" to "kahverengi", "bej" to "bej", "beige" to "bej", "krem" to "krem",
    "cream" to "krem", "lila" to "lila", "bordo" to "bordo", "haki" to "haki", "gumus" to "gümüş", "silver" to "gümüş",
    "altin" to "altın", "rose" to "pembe",
)

/**
 * Ayırt edici kelimeler kategoriye bağlıdır: "Junior" ayakkabıda çocuk modeli, bebek bezinde sadece beden adıdır;
 * "Pro/Max" elektronikte farklı model, "Külot" bezde farklı üründür. Listede olmayan kelime her kategoride geçerlidir.
 * Kategori bilinmiyorsa (Diğer) hepsi geçerlidir.
 */
val tech = setOf("Elektronik", "Giyim")

val modCats: Map<String, Set<String>> = mapOf(
    // Elektronik ve ayakkabı/giyim modelleri
    "pro" to tech, "plus" to tech, "max" to tech, "mini" to tech, "ultra" to tech, "lite" to tech,
    "fe" to setOf("Elektronik"), "se" to setOf("Elektronik"),
    // Ayakkabı / giyim
    "mid" to setOf("Giyim"), "high" to setOf("Giyim"), "shadow" to setOf("Giyim"), "sculpt" to setOf("Giyim"),
    "lv8" to setOf("Giyim"), "craft" to setOf("Giyim"), "utility" to setOf("Giyim"), "react" to setOf("Giyim"),
    "platform" to setOf("Giyim"), "plt" to setOf("Giyim"), "jewel" to setOf("Giyim"), "lx" to setOf("Giyim"),
    "flyease" to setOf("Giyim"), "retro" to setOf("Giyim"), "nature" to setOf("Giyim"), "nn" to setOf("Giyim"),
    "ps" to setOf("Giyim"), "gs" to setOf("Giyim"), "td" to setOf("Giyim"), "le" to setOf("Giyim"),
    "cocuk" to setOf("Giyim"), "kids" to setOf("Giyim"), "junior" to setOf("Giyim"),
    // Kozmetik / parfüm
    "eau" to setOf("Kozmetik"), "elixir" to setOf("Kozmetik"), "extreme" to setOf("Kozmetik"), "intense" to setOf("Kozmetik"),
    "absolu" to setOf("Kozmetik"), "forte" to setOf("Kozmetik"), "after" to setOf("Kozmetik"), "aftershave" to setOf("Kozmetik"),
    "balm" to setOf("Kozmetik"), "losyon" to setOf("Kozmetik"), "deodorant" to setOf("Kozmetik"), "deo" to setOf("Kozmetik"),
    "jeli" to setOf("Kozmetik"), "sampuan" to setOf("Kozmetik"), "stick" to setOf("Kozmetik", "Market"),
    // Bebek
    "aktif" to setOf("Bebek"), "premium" to setOf("Bebek"), "kulot" to setOf("Bebek"), "pants" to setOf("Bebek"),
    "harmonie" to setOf("Bebek"), "havlu" to setOf("Bebek", "Market"), "mendil" to setOf("Bebek", "Market"),
    // Market (kahve vb.)
    "crema" to setOf("Market"), "filtre" to setOf("Market"), "kafeinsiz" to setOf("Market"), "decaf" to setOf("Market"),
    "aromali" to setOf("Market"), "karamel" to setOf("Market"), "vanilya" to setOf("Market"), "findik" to setOf("Market"),
    "roastery" to setOf("Market"), "icimlik" to setOf("Market"), "kavanoz" to setOf("Market"), "cam" to setOf("Market"),
    "fontanka" to setOf("Market"),
)

/** Aramadaki model numarasına harf eklenmiş hali: "16" ararken "16e", "16s" başka modeldir */
fun suffixTraits(q: String, title: String): Set<String> {
    val nums = tokensOf(q).filter { it.all { c -> c.isDigit() } && it.length <= 3 }.toSet()
    if (nums.isEmpty()) return emptySet()
    val units = setOf("gb", "tb", "mb", "gr", "g", "ml", "li", "lu", "kg", "lt", "l", "mm", "cm", "w", "x", "mp", "hz", "k", "inc", "cl")
    return tokensOf(title).map { norm(it) }.mapNotNull { tk ->
        val n = nums.firstOrNull { tk.startsWith(it) && tk.length > it.length } ?: return@mapNotNull null
        val suf = tk.substring(n.length)
        if (suf.length in 1..2 && suf.all { it.isLetter() } && suf !in units) tk.uppercase() else null
    }.toSet()
}

fun colorsIn(s: String): Set<String> {
    val out = tokensOf(s).mapNotNull { colorMap[norm(it)] }.toMutableSet()
    if (tokensOf(s).any { norm(it) == "panda" }) { out.add("siyah"); out.add("beyaz") }
    return out
}

/** Parfüm konsantrasyonu. Türkçe "parfüm" genel addır; sadece "parfum" (EDP'den yoğun) sayılır. */
fun concOf(s: String) = tokensOf(s).filter { it in concWords || it == "elixir" }.toSet()

val usedRe = Regex("yenilenmiş|yenilenmis|ikinci el|2\\. ?el\\b|teşhir|refurbished|renewed|kullanılmış|outlet ürün|(?<![\\p{L}])[abc] (kalite|grade)(?![\\p{L}])|yenilio")

val knockoffRe = Regex("muadil|esans|alternatif|inspired|esintili|kokusunda|tipi parfüm|benzeri")

/** Türkçe küçük harfte İngilizce "I" noktasız "ı" olur ("ınternational"): kalıplar iki biçimde de denenir */
fun hit(re: Regex, t: String) = re.containsMatchIn(t) || re.containsMatchIn(t.replace('ı', 'i'))

fun amountOf(title: String, prefer: String? = null): Amount? {
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

fun amountLabel(a: Amount): String {
    val per = if (a.per == Math.floor(a.per)) tlWhole.format(a.per) else tl.format(a.per)
    return "$per ${a.unit}" + if (a.mult > 1) " × ${a.mult.toInt()}" else ""
}

/** Paket sayısı: miktardan önce "2 x 200 gr" ya da hemen sonra "x 6", "2 adet", "3'lü", "2 paket".
 *  "10 Kutu 1.000 Adet" gibi toplam yazılan ilanlarda tekrar çarpılmaz. */
fun packMultiplier(t: String, first: Int, last: Int, countUnit: Boolean = false): Double {
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

fun numberOf(s: String) = s.replace(".", "").replace(",", ".").toDoubleOrNull() ?: 0.0

fun countNum(s: String): Double {
    if (s.isBlank()) return 0.0
    Regex("([0-9]+(?:[.,][0-9]+)?)\\s?B").find(s)?.let { return (it.groupValues[1].replace(",", ".").toDoubleOrNull() ?: 0.0) * 1000 }
    return s.replace(".", "").replace(",", "").toDoubleOrNull() ?: 0.0
}

/**
 * Aramayı parçalar:
 *  words = ürün türü/marka kelimeleri, hard = şart olan ölçüler ("no 6" -> no6, "pro 2" -> pro2).
 *  "100 adet", "6 kg" gibi miktarlar şart değildir; birim fiyatla karşılaştırılır.
 *  Renk, cinsiyet ve EDT gibi kelimeler de şart değildir (ilanların çoğu yazmaz).
 */
fun querySpec(q: String): Pair<List<String>, List<String>> {
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
            // "iphone 15" -> iphone15; ama "4 in 1 16.000" gibi sayı arkasından gelen sayı tek başına aranır
            isNum && prev != null && prev.any { it.isLetter() } -> hard.add(prev + t)
            isNum && t.length >= 3 -> hard.add(t)
            t.length >= 3 -> words.add(if (t.length > 5) t.removeSuffix("lar").removeSuffix("ler") else t)
        }
    }
    return words to hard
}

/** Başlıktaki numara/beden değerleri: "4 Numara", "Beden:4", "No:6" */
fun sizeNos(title: String): Set<String> {
    val t = title.lowercase(Locale("tr", "TR"))
    val out = mutableSetOf<String>()
    Regex("(?<![\\p{L}])(?:no|numara|beden|size)\\s*[:.]?\\s*([0-9]{1,2})(?![0-9x×*])").findAll(t).forEach { out.add(it.groupValues[1].trimStart('0')) }
    Regex("(?<![0-9x×*=])([0-9]{1,2})\\s*(?:no|numara|beden)(?![\\p{L}])").findAll(t).forEach { out.add(it.groupValues[1].trimStart('0')) }
    return out
}

/** Aranan kelime başlıkta var mı (numara/beden yazım farklarıyla birlikte) */
fun hasWord(title: String, nt: String, w: String): Boolean {
    Regex("^no([0-9]{1,2})$").find(w)?.let { m -> return m.groupValues[1].trimStart('0') in sizeNos(title) || nt.contains(w) }
    return nt.contains(w)
}

/** Aramadaki zorunlu kelimeler (tür kelimeleri + şart olan ölçüler) */
fun requiredWords(q: String): List<String> = querySpec(q).let { it.first + it.second }

fun genderOf(t: String) = when {
    Regex("(?<![\\p{L}])unisex(?![\\p{L}])").containsMatchIn(t) -> "Unisex"
    Regex("(?<![\\p{L}])(kadın|women|woman|bayan)(?![\\p{L}])").containsMatchIn(t) &&
        Regex("(?<![\\p{L}])(erkek|men|man)(?![\\p{L}])").containsMatchIn(t) -> "Erkek & Kadın"
    Regex("(?<![\\p{L}])(kadın|women|woman|bayan)(?![\\p{L}])").containsMatchIn(t) -> "Kadın"
    Regex("(?<![\\p{L}])(erkek|men|man)(?![\\p{L}])").containsMatchIn(t) -> "Erkek"
    else -> ""
}

/** Kelime ilanda var mı; marka için Amazon'un ad üstündeki marka satırına da bakılır */
fun hasOfferWord(o: Offer, nt: String, w: String, brand: String?): Boolean =
    hasWord(o.title, nt, w) || (w == brand && o.brandHint.isNotEmpty() && canonNorm(o.brandHint).contains(w))

/** Aramanın durumu + sınıflandırma. MainActivity ve testler bunu uygular. */
interface MatchLogic {
    var query: String
    var identity: Identity?
    var queryAmount: Amount?
    var acceptedMods: Set<String>
    var requiredSeries: Set<String>
    var bookMode: Boolean
    val offers: MutableList<Offer>
    /** Eşleştirmede kullanılan kategori; her sınıflandırmanın başında belirlenir */
    var traitCat: String

    fun traitsOf(s: String): Set<String> = tokensOf(s).mapNotNull { w ->
        val k = norm(w)
        val v = modMap[k] ?: return@mapNotNull null
        val cs = modCats[k]
        // "Junior" gibi beden adı olabilecek kelimeler kategori bilinmezken ayırt edici sayılmaz
        if (traitCat == "Diğer") { if (k == "junior") null else v }
        else if (cs == null || traitCat in cs) v else null
    }.toSet()

    /** Eşleşenlerde: birim fiyatı en uygun olanı ve medyanın 3 katından pahalı olanları işaretle */
    /** "Deodorant 150 ml + Stick 45 ml" gibi birden fazla ürün içeren set */
    fun isSet(o: Offer): Boolean {
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

    /** Aramanın kategorisi: aranan yazı ve eşleşen ilan adlarındaki kelimelerden */
    fun detectCategory(): String {
        val t = (query + " " + offers.filter { it.kind == Kind.MATCH }.take(12).joinToString(" ") { it.title })
            .lowercase(Locale("tr", "TR"))
        fun has(re: String) = Regex(re).containsMatchIn(t)
        return when {
            has("bebek bezi|bebek bez|külot bez|prima|pampers|huggies|molfix|sleepy|evy baby|canbebe|biberon|emzik|bebek maması|ıslak havlu|ıslak mendil") -> "Bebek"
            has("parfüm|parfum|\\bedt\\b|\\bedp\\b|deodorant|şampuan|krem|ruj|maskara|serum|losyon|tıraş köpüğü|saç boyası|cilt") -> "Kozmetik"
            has("ayakkabı|sneaker|tişört|t-shirt|gömlek|pantolon|elbise|ceket|mont|eşofman|çanta|terlik|bot\\b|sweatshirt|beden|" +
                "\\bnike\\b|adidas|puma|new balance|converse|\\bvans\\b|skechers|air force|air max|jordan") -> "Giyim"
            has("telefon|iphone|galaxy|xiaomi|redmi|huawei|macbook|airpods|playstation|televizyon|\\btv\\b|qled|oled|laptop|bilgisayar|tablet|ipad|kulaklık|airpods|monitör|kamera|" +
                "playstation|ps5|xbox|akıllı saat|şarj|tıraş makinesi|süpürge|buzdolabı|çamaşır makinesi|bulaşık makinesi|klima|fırın|" +
                "kahve makinesi|oneblade|ekran kartı|ssd|router|modem|yazıcı|akü|powerbank|\\d\\s*mah\\b|jump starter|kompresör|hoparlör|projeksiyon|drone|robot süpürge") -> "Elektronik"
            has("\\d+\\s*(gr|g|kg|ml|lt|l)\\b|kahve|çay|deterjan|yumuşatıcı|makarna|yağ|şeker|un\\b|süt|peynir|çikolata|bisküvi|kağıt havlu|tuvalet kağıdı|su\\b") -> "Market"
            else -> "Diğer"
        }
    }

    fun classify() {
        traitCat = detectCategory()
        queryAmount = amountOf(identity?.searchQuery ?: query)
        val id = identity
        if (id != null) classifyByIdentity(id) else { classifyByWords(); markBrandlessTwins() }
        markSuspiciousAndOrigin()
    }

    /** Kimlikle eşleştirme: aynı paket kodu = eşleşme; aynı model başka paket = ayrı başlık */
    fun classifyByIdentity(id: Identity) {
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

    fun classifyByWords() {
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

    /** Markasız satılan aynı ürün: adı (marka hariç) bulunan eşleşmeyle neredeyse aynı, sayıları da aynıysa "olası" */
    fun markBrandlessTwins() {
        val brand = norm(query.split(" ").firstOrNull().orEmpty())
        if (brand.length < 3) return
        fun toks(t: String) = canonText(t).lowercase(Locale("tr", "TR")).split(Regex("[^\\p{L}\\p{N}]+"))
            .map { norm(it) }.filter { it.isNotEmpty() && it != brand }.toSet()
        val matches = offers.filter { it.kind == Kind.MATCH }.map { toks(it.title) }
        if (matches.isEmpty()) return
        for (o in offers) {
            if (o.kind != Kind.OTHER && o.kind != Kind.ACCESSORY) continue
            if (norm(o.title).contains(brand)) continue
            val t = toks(o.title)
            val nums = t.filter { x -> x.all { it.isDigit() } }
            val twin = matches.any { m ->
                val inter = (t intersect m).size.toDouble()
                val jac = inter / (t union m).size
                jac >= 0.6 && nums.all { it in m }
            }
            if (twin) { o.kind = Kind.POSSIBLE; o.otherModel = "marka yazmıyor, adı aynı" }
        }
    }

    /** Aranan kelimelerin hepsi var: aynı ürün mü, başka seri/renk/miktar mı? */
    fun matchOrVariant(o: Offer, t: String, qMods: Set<String>, qColors: Set<String>, qConc: Set<String>, qAmt: Amount?): Kind {
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

    fun markSuspiciousAndOrigin() {
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
}
