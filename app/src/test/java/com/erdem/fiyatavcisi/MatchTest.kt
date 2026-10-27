package com.erdem.fiyatavcisi

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Eşleştirme testleri: gerçek ilan adları + beklenen karar.
 * Her güncellemede çalışır; biri bile bozulursa APK yayınlanmaz.
 * Yeni bir hata görülünce buraya bir satır eklenir, böylece bir daha geri gelemez.
 */
class MatchTest {

    /** Testler için arama durumu: uygulamadaki aramanın aynısı, ekransız */
    private class Search(q: String) : MatchLogic {
        override var query = q
        override var identity: Identity? = null
        override var queryAmount: Amount? = null
        override var acceptedMods: Set<String> = emptySet()
        override var requiredSeries: Set<String> = emptySet()
        override var bookMode = false
        override val offers = mutableListOf<Offer>()
        override var traitCat = "Diğer"
    }

    private fun offer(title: String, price: Double = 100.0) =
        Offer(site = "Test", title = title, price = price, listPrice = 0.0, basket = false, rating = "", ratingCount = "", link = "https://x/" + title.hashCode(), raw = title)

    /** Bir arama ve ilanları: başlık -> beklenen sonuç. Aynı aramadaki ilanlar birlikte sınıflandırılır (gerçekteki gibi). */
    private data class Case(val query: String, val expect: List<Pair<String, Set<Kind>>>)

    private val same = setOf(Kind.MATCH)
    private val notSame = Kind.values().toSet() - Kind.MATCH
    private val sameOrPack = setOf(Kind.MATCH, Kind.OTHER_PACKAGE)
    private val differentModel = setOf(Kind.DIFFERENT_MODEL)
    private val possible = setOf(Kind.POSSIBLE, Kind.MATCH)

    private val cases = listOf(
        // --- Bebek bezi: "Junior" 6 numaranın adı, farklı seri değil; Külot ve Aktif Bebek farklı seri
        Case("Prima premium care 6", listOf(
            "Prima Bebek Bezi Premium Care 6 Beden 105 Adet Aylık Fırsat Paketi." to same,
            "Prima Premium Care 6 Numara Junior 105'li Bel Bantlı Bez" to same,
            "Prima Premium Care 6 Numara Junior 70'li Bel Bantlı Bez" to same,
            "Prima Bebek Bezi Premium Care 6 Beden No Junior 105 Adet" to same,
            "Prima Premium Care 6 Numara Bebek Bezi (3 x 35) Toplam 105 Adet" to same,
            "Prima Kulot Bebek Bezi Premium Care 6 Numara 28 Adet (2 paketi)" to differentModel,
            "Prima Premium Care 6 Numara Junior 28'li Külot Bez" to differentModel,
            "Prima Bebek Bezi Aktif Bebek 6 Numara Mega Fırsat Paketi 76 Adet" to notSame,
            "Prima Premium Care Aylık Fırsat Paketi Bebek Bezi 6 Numara 93 Adet" to same,
            "Prima Premium Care Aylık Fırsat Paketi 6 Numara 13-18 kg 93 Adet" to same,
            "Prima Premium Care 5 Numara 120 Adet Bebek Bezi" to notSame,
        )),
        // --- Adet aramasında farklı paket ayrı başlıkta
        Case("prima premium care 6 105 adet", listOf(
            "Prima Bebek Bezi Premium Care 6 Beden 105 Adet Aylık Fırsat Paketi" to same,
            "Prima Premium Care 6 Numara Junior 70'li Bel Bantlı Bez" to setOf(Kind.OTHER_PACKAGE),
        )),
        // --- Arka arkaya sayılar: "4 in 1 16.000" ilk sayıyla birleşmemeli
        Case("metontech 4 in 1 16.000", listOf(
            "METONTECH 4 in 1 Şarjlı Akü Takviye Cihazı | 16.000 mAh | Akım Koruma | Güçlü Seri | Lastik Şişirme, LED Fener" to same,
            "METONTECH 4'ü 1 Arada Şarjlı Akü Takviye Cihazı | Tank Seri | 10.000 mAh | Ultra Dayanıklı" to notSame,
            "4 İn 1 Şarjlı Akü Takviye Cihazı 16.000 Mah Akım Koruma Güçlü Seri Lastik Şişirme, Led Fener" to possible,
            "Karaca x Refika Mastermaid Power Multifunctional 10 in 1 Swiss Cream Mutfak Robotu" to notSame,
        )),
        // --- Telefon: Pro / Plus farklı model, kılıf aksesuar, başka nesil farklı
        Case("iphone 16 128gb", listOf(
            "Apple iPhone 16 128GB Siyah" to same,
            "Apple iPhone 16 128 GB Beyaz Akıllı Telefon" to same,
            "Apple iPhone 16 Pro 128GB Natürel Titanyum" to differentModel,
            "Apple iPhone 16 Plus 128 GB Pembe" to differentModel,
            "iPhone 16 Uyumlu Şeffaf Silikon Kılıf" to setOf(Kind.ACCESSORY),
            "Apple iPhone 15 128GB Siyah" to notSame,
        )),
        // --- Kahve: farklı gramaj ayrı paket; Crema / kafeinsiz farklı ürün
        Case("nescafe gold 200 gr", listOf(
            "Nescafe Gold 200 gr" to same,
            "Nescafe Gold Çözünebilir Kahve 200 g Eko Paket" to same,
            "Nescafe Gold 100 gr" to setOf(Kind.OTHER_PACKAGE),
            "Nescafe Gold Crema 200 gr" to differentModel,
            "Nescafe Gold Kafeinsiz 200 g" to differentModel,
        )),
        // --- Parfüm: EDT / EDP / Elixir / deodorant farklı; muadil gösterilmez
        Case("dior sauvage edt 100 ml", listOf(
            "Dior Sauvage EDT 100 ml Erkek Parfüm" to same,
            "Dior Sauvage Eau de Toilette 100 ml" to same,
            "Dior Sauvage EDP 100 ml Erkek Parfüm" to notSame,
            "Dior Sauvage Elixir 60 ml" to notSame,
            "Dior Sauvage Deodorant Stick 75 g" to notSame,
            "Sauvage Muadil Erkek Parfüm 100 ml" to notSame,
        )),
        // --- Ayakkabı: Mid / Shadow / GS (çocuk) farklı model
        Case("nike air force 1 07", listOf(
            "Nike Air Force 1 '07 Erkek Beyaz Spor Ayakkabı" to same,
            "Nike Air Force 1 Mid '07 Erkek Ayakkabı" to differentModel,
            "Nike Air Force 1 Shadow Kadın Ayakkabı" to differentModel,
            "Nike Air Force 1 GS Junior Beyaz Ayakkabı" to differentModel,
        )),
        // --- Adetli aparat: "6 parça" ürün adının parçası
        Case("trim sökme aparat 6 parça", listOf(
            "Oto Trim Sökme Aparatı 6 Parça Döşeme Sökücü Set" to same,
        )),
        // --- Model kodu: başka kod farklı model, yedek parça aksesuar
        Case("philips oneblade qp2834/20", listOf(
            "Philips OneBlade QP2834/20 Tıraş Makinesi" to sameOrPack,
            "Philips OneBlade QP2824/20 Tıraş Makinesi" to differentModel,
            "Philips OneBlade Yedek Bıçak QP210/50" to setOf(Kind.ACCESSORY),
        )),
    )

    @Test
    fun eslestirme() {
        val fails = mutableListOf<String>()
        for (c in cases) {
            val s = Search(c.query)
            c.expect.forEach { (t, _) -> s.offers.add(offer(t)) }
            s.classify()
            s.offers.zip(c.expect).forEach { (o, e) ->
                if (o.kind !in e.second)
                    fails.add("\"${c.query}\" → \"${o.title}\": beklenen ${e.second.joinToString("/")}, çıkan ${o.kind}" +
                        if (o.otherModel.isNotEmpty()) " (${o.otherModel})" else "")
            }
        }
        val total = cases.sumOf { it.expect.size }
        java.io.File("build/match-report.txt").apply { parentFile?.mkdirs() }.writeText(
            "Eşleştirme testi: ${total - fails.size}/$total doğru\n" + fails.joinToString("\n"))
        assertTrue("Eşleştirme hataları (${fails.size}/$total):\n" + fails.joinToString("\n"), fails.isEmpty())
    }
}
