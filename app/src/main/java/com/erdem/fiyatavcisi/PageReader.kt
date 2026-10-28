package com.erdem.fiyatavcisi

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Ürün sayfasını görünmez bir tarayıcıda açıp ad, resim, fiyat ve stok okur.
 * Trendyol gibi düz okumaya sayfayı göstermeyen siteler için. Uygulama içinde de, arka plan kontrolünde de çalışır.
 */
object PageReader {
    private val main = Handler(Looper.getMainLooper())

    private val js = """
        (function(){
          function meta(p){var e=document.querySelector('meta[property="'+p+'"],meta[name="'+p+'"]');return e?(e.getAttribute('content')||''):'';}
          function num(t){if(!t)return null;t=String(t).replace(/[^0-9.,]/g,'');
            if(t.indexOf(',')>=0){t=t.replace(/\./g,'').replace(',','.');}
            else if(/^\d{1,3}(\.\d{3})+$/.test(t)){t=t.replace(/\./g,'');}
            var v=parseFloat(t);return v>0?v:null;}
          var name='',img=meta('og:image'),price=null,stock=null;
          var lds=document.querySelectorAll('script[type="application/ld+json"]');
          for(var i=0;i<lds.length;i++){try{
            var j=JSON.parse(lds[i].textContent);var arr=[].concat(j['@graph']||j);
            for(var k=0;k<arr.length;k++){var o=arr[k];if(!o)continue;var t=[].concat(o['@type']||[]).join(',');
              if(t.indexOf('Product')<0)continue;
              if(o.name)name=o.name;
              if(!img&&o.image){var im=[].concat(o.image)[0];img=typeof im=='string'?im:(im&&(im.url||im.contentUrl))||'';}
              var of=[].concat(o.offers||[]);
              for(var f=0;f<of.length;f++){var x=of[f];if(!x)continue;var pv=num(x.price||x.lowPrice);
                if(pv&&price==null)price=pv;if(x.availability)stock=/InStock|LimitedAvailability/.test(x.availability);}
            }}catch(e){}}
          if(price==null){var a=meta('product:price:amount')||meta('og:price:amount');if(a)price=parseFloat(a)||null;}
          if(price==null){
            var sels=['.prc-dsc','.product-price-container .prc-dsc','.campaign-price','.price-view-discounted','.price-view-original',
              '[data-test-id="price-current-price"]','[data-testid="price-current-price"]','#offering-price','.price-current',
              '.product-price','#corePrice_feature_div .a-offscreen','.a-price .a-offscreen','.newPrice ins','.unf-p-summary-price'];
            for(var s2=0;s2<sels.length&&price==null;s2++){var e=document.querySelector(sels[s2]);if(e)price=num(e.textContent);}
          }
          var h1=document.querySelector('h1');
          if(!name)name=meta('og:title')||(h1?h1.textContent:'')||document.title||'';
          return JSON.stringify({title:name.replace(/\s+/g,' ').trim(),image:img||'',price:price,inStock:stock,url:location.href});
        })()
    """.trimIndent()

    private fun parse(raw: String?): JSONObject? = try { JSONObject(JSONTokener(raw).nextValue() as String) } catch (e: Exception) { null }

    /** Ana iş parçacığında çağrılmalı */
    @SuppressLint("SetJavaScriptEnabled")
    fun read(ctx: Context, url: String, attach: ((WebView) -> Unit)? = null, done: (Alarms.PageInfo?) -> Unit) {
        var finished = false
        var tries = 0
        var best: Alarms.PageInfo? = null
        val wv = try { WebView(ctx) } catch (e: Exception) { done(null); return }
        fun finish() {
            if (finished) return
            finished = true
            try { wv.stopLoading(); (wv.parent as? android.view.ViewGroup)?.removeView(wv); wv.destroy() } catch (e: Exception) {}
            done(best)
        }
        lateinit var poll: Runnable
        poll = Runnable {
            if (finished) return@Runnable
            tries++
            wv.evaluateJavascript(js) { raw ->
                val j = parse(raw)
                if (j != null) {
                    val price = j.optDouble("price", 0.0).takeIf { it > 0 }
                    val info = Alarms.PageInfo(price, if (j.isNull("inStock")) null else j.optBoolean("inStock"),
                        j.optString("title"), j.optString("image"), j.optString("url", url),
                        if (price != null) "fiyat okundu" else "fiyat okunamadı")
                    val b = best
                    if (b == null || (b.price == null && price != null) || (b.image.isEmpty() && info.image.isNotEmpty())) best = info
                    if (price != null && info.image.isNotEmpty() && !Links.looksLikeUrl(info.title)) { finish(); return@evaluateJavascript }
                }
                if (tries < 10) main.postDelayed(poll, 1200) else finish()
            }
        }
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.userAgentString = wv.settings.userAgentString.replace("; wv", "")
        attach?.invoke(wv)
        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean =
                !(request?.url?.toString() ?: "").startsWith("http")
            override fun onPageFinished(view: WebView?, u: String?) {
                main.removeCallbacks(poll); main.postDelayed(poll, 1200)
            }
        }
        main.postDelayed({ finish() }, 20_000)
        try { wv.loadUrl(url) } catch (e: Exception) { finish() }
    }

    /** Kayıtları sırayla okur; okunabilenleri id → sayfa bilgisi olarak verir */
    fun readAll(ctx: Context, items: List<Alarm>, attach: ((WebView) -> Unit)? = null, done: (Map<Long, Alarms.PageInfo>) -> Unit) {
        val out = mutableMapOf<Long, Alarms.PageInfo>()
        val queue = items.toMutableList()
        fun next() {
            val a = queue.removeFirstOrNull() ?: run { done(out); return }
            read(ctx, a.link, attach) { info ->
                if (info != null && (info.price != null || info.image.isNotEmpty() || !Links.looksLikeUrl(info.title))) out[a.id] = info
                next()
            }
        }
        main.post { next() }
    }
}
