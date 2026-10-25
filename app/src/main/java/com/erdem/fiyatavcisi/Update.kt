package com.erdem.fiyatavcisi

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Uygulama içinden güncelleme (Kopilot'taki gibi): GitHub'daki "latest" sürümünün notundan
 * "Sürüm 0.44-test (44)" okunur; yeniyse APK uygulamanın içinde indirilir, telefon sadece onay ister.
 */
object Update {
    const val APK = "https://github.com/ekererdem/fiyat-avcisi/releases/download/latest/FiyatAvcisi.apk"
    private const val API = "https://api.github.com/repos/ekererdem/fiyat-avcisi/releases/tags/latest"
    private val VER = Regex("""Sürüm\s+([\w.\-]+)\s*\((\d+)\)""")

    private fun prefs(c: Context) = c.getSharedPreferences("guncelleme", Context.MODE_PRIVATE)

    fun current(c: Context): Long = try {
        c.packageManager.getPackageInfo(c.packageName, 0).longVersionCode
    } catch (e: Exception) { 0 }

    fun currentName(c: Context): String = try {
        c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: ""
    } catch (e: Exception) { "" }

    /** Son bakışta bulunan, henüz kurulmamış yeni sürümün adı; yoksa null */
    fun pending(c: Context): String? {
        val p = prefs(c)
        return if (p.getLong("code", 0) > current(c)) p.getString("name", null) else null
    }

    fun lastCheck(c: Context) = prefs(c).getLong("checked", 0)

    /** Arka planda GitHub'a bakar; sonuç ana iş parçacığında (bulunamazsa name null) */
    fun check(a: Activity, done: (name: String?, code: Long, error: String?) -> Unit) {
        Thread {
            var name: String? = null
            var code = 0L
            var err: String? = null
            try {
                val c = URL(API).openConnection() as HttpURLConnection
                c.connectTimeout = 10000
                c.readTimeout = 15000
                c.setRequestProperty("Accept", "application/vnd.github+json")
                if (c.responseCode != 200) throw Exception("GitHub yanıtı ${c.responseCode}")
                val body = c.inputStream.bufferedReader().use { it.readText() }
                VER.find(JSONObject(body).optString("body"))?.let { m ->
                    name = m.groupValues[1]
                    code = m.groupValues[2].toLong()
                    prefs(a).edit().putLong("code", code).putString("name", name)
                        .putLong("checked", System.currentTimeMillis()).apply()
                }
            } catch (e: Exception) {
                err = e.message ?: e.javaClass.simpleName
            }
            a.runOnUiThread { if (!a.isFinishing) done(name, code, err) }
        }.start()
    }

    @Volatile private var busy = false

    /**
     * Yeni sürümü tarayıcı açmadan indirip kurar; ilk seferde Android "bu uygulamanın kurmasına izin ver" ister.
     * İlerleme: 0–100, boyut bilinmiyorsa -1, olmadıysa -2.
     */
    fun install(a: Activity, progress: (Int) -> Unit): Boolean {
        if (!a.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(a, "Fiyat Avcısı'nın güncelleme kurmasına izin ver, sonra geri gelip tekrar dokun.", Toast.LENGTH_LONG).show()
            try {
                a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.packageName)))
            } catch (e: Exception) { browser(a) }
            return false
        }
        if (busy) return true
        busy = true
        val app = a.applicationContext
        Thread {
            var ok = false
            var s: PackageInstaller.Session? = null
            try {
                val inst = app.packageManager.packageInstaller
                val sp = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                sp.setAppPackageName(app.packageName)
                s = inst.openSession(inst.createSession(sp))
                val c = URL(APK).openConnection() as HttpURLConnection
                c.instanceFollowRedirects = true
                c.connectTimeout = 15000
                c.readTimeout = 30000
                val len = c.contentLengthLong
                c.inputStream.use { input ->
                    s.openWrite("fiyatavcisi.apk", 0, if (len > 0) len else -1).use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var last = -9
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            done += n
                            val pct = if (len > 0) (done * 100 / len).toInt() else -1
                            if (pct != last) {
                                last = pct
                                a.runOnUiThread { if (!a.isFinishing) progress(pct) }
                            }
                        }
                        s.fsync(out)
                    }
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val cb = PendingIntent.getBroadcast(app, 41, Intent(app, UpdateReceiver::class.java), flags)
                s.commit(cb.intentSender)
                s.close()
                s = null
                ok = true
            } catch (e: Exception) {
                try { s?.abandon() } catch (x: Exception) {}
            }
            busy = false
            if (!ok) a.runOnUiThread {
                if (!a.isFinishing) progress(-2)
                Toast.makeText(app, "Güncelleme indirilemedi, tarayıcıdan indiriliyor", Toast.LENGTH_LONG).show()
                browser(a)
            }
        }.start()
        return true
    }

    fun browser(a: Activity) {
        try { a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(APK))) } catch (e: Exception) {}
    }
}

/** Kurulum onay ekranını açar; hata olursa söyler */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        when (val st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try { c.startActivity(confirm) } catch (e: Exception) {
                    Toast.makeText(c, "Güncelleme onayı açılamadı", Toast.LENGTH_LONG).show()
                }
            }
            PackageInstaller.STATUS_SUCCESS, PackageInstaller.STATUS_FAILURE_ABORTED -> {}
            else -> {
                val msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Toast.makeText(c, "Güncelleme kurulamadı ($st)" + (msg?.let { ": $it" } ?: ""), Toast.LENGTH_LONG).show()
            }
        }
    }
}
