package com.erdem.fiyatavcisi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AmazonPriceTest {
    private val carousel = """
        <div id="sims-consolidated-2_feature_div"><h2>Bu alternatif ürünleri değerlendirin</h2>
        <div class="a-carousel"><span class="a-price"><span class="a-offscreen">799,99 TL</span><span aria-hidden="true">799,99 TL</span></span></div></div>
    """

    @Test fun oneCikanTeklifYok_digerSaticilardanEnUcuz() {
        // Ana fiyat kutusu boş, alternatif ürün karuselinde 799,99 var: karusel okunmamalı
        val html = """
            <div id="corePrice_mobile_feature_div"><span>Öne çıkan teklif yok</span></div>
            $carousel
            <div id="olp_feature_div"><a>Yeni (3) 1.599,99 TL'den başlayan fiyatlarla</a></div>
        """
        assertEquals(1599.99, Alarms.amazonPrice(html)!!, 0.001)
    }

    @Test fun anaFiyatKutusu() {
        val html = """
            $carousel
            <div id="corePrice_feature_div"><span class="a-price a-text-price" data-a-strike="true"><span class="a-offscreen">2.000,00 TL</span><span aria-hidden="true">2.000,00 TL</span></span>
            <span class="a-price priceToPay"><span class="a-offscreen">1.650,00 TL</span></span>
            <span>(23,57 TL / adet)</span></div>
        """
        assertEquals(1650.0, Alarms.amazonPrice(html)!!, 0.001)
    }

    @Test fun sadeceKaruselVarsaFiyatYok() {
        assertNull(Alarms.amazonPrice("<div>Öne çıkan teklif yok</div>$carousel"))
    }
}
