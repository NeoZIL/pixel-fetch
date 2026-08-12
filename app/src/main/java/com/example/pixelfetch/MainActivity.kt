package com.example.pixelfetch

import android.app.Activity
import android.os.Bundle
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.regex.Pattern
import java.util.concurrent.Executors

data class BuildInfo(
    val model: String, val product: String, val device: String,
    val android: String, val id: String, val incremental: String,
    val securityPatch: String, val fingerprint: String,
    val releaseDate: String = "Unknown", val expiryDate: String = "Unknown"
) {
    fun buildDateLabel(): String =
        if (releaseDate != "Unknown") releaseDate else "Date unavailable"
}

class MainActivity : Activity() {
    private val pool = Executors.newSingleThreadExecutor()
    private var resultText = ""

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(R.layout.activity_main)

        val status = findViewById<TextView>(R.id.status)
        val output = findViewById<TextView>(R.id.output)
        val fetch = findViewById<Button>(R.id.fetch)
        val save = findViewById<Button>(R.id.save)
        val cardTitle = findViewById<TextView>(R.id.cardTitle)
        val cardDate = findViewById<TextView>(R.id.cardDate)

        fetch.setOnClickListener {
            fetch.isEnabled = false
            save.isEnabled = false
            status.text = "Fetching Android Developers…"
            output.text = ""

            pool.execute {
                try {
                    val info = fetchLatest()
                    resultText = render(info)
                    runOnUiThread {
                        output.text = resultText
                        cardTitle.text = "${info.model} • ${info.buildDateLabel()}"
                        cardDate.text = "Build Date: ${info.releaseDate}"
                        status.text = "Fetched successfully • no root used"
                        fetch.isEnabled = true
                        save.isEnabled = true
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        status.text = "Fetch failed"
                        output.text = e.message ?: e.toString()
                        fetch.isEnabled = true
                    }
                }
            }
        }

        save.setOnClickListener {
            openFileOutput("pixel-fetch.txt", MODE_PRIVATE).use {
                it.write(resultText.toByteArray(Charsets.UTF_8))
            }
            status.text = "Saved to app-private storage"
        }
    }

    private fun fetchLatest(): BuildInfo {
        val versions = http("https://developer.android.com/about/versions")
        val latestPath = first(
            Regex("""href="(/about/versions/[^"]*[0-9])""""),
            versions
        ) ?: first(
            Regex("""href="(/about/versions/[0-9]{2})""""),
            versions
        ) ?: throw Exception("Could not find latest Android version page")

        val latest = http("https://developer.android.com$latestPath")

        val downloadLinks = Regex("""href="([^"]*download[^"]*)"""")
            .findAll(latest).map { it.groupValues[1] }
            .filter { !it.contains("download-ota") && !it.contains("ota") }
            .toList()

        val otaLinks = Regex("""href="([^"]*download-ota[^"]*)"""")
            .findAll(latest).map { it.groupValues[1] }.toList()

        val fiUrl = downloadLinks.map { abs(it) }.firstOrNull()
            ?: throw Exception("Could not find Pixel factory-image page")
        val otaUrl = otaLinks.map { abs(it) }.firstOrNull()

        val fiHtml = http(fiUrl)
        val otaHtml = otaUrl?.let { http(it) } ?: ""

        val fiProducts = products(fiHtml)
        val otaProducts = products(otaHtml)

        // Match the upstream selection rule: use OTA list when it is longer.
        val src = if (otaProducts.size > fiProducts.size) otaHtml else fiHtml
        val rows = deviceRows(src)
        if (rows.isEmpty()) throw Exception("No Pixel device rows found")

        val selected = rows.random()
        val model = selected.first
        val product = selected.second
        val device = product.removeSuffix("_beta")

        val flash = http("https://flash.android.com/")
        val key = extractFlashKey(flash)
            ?: throw Exception("Could not obtain the current Flash Tool client key")

        val stationUrl =
            "https://content-flashstation-pa.googleapis.com/v1/builds?product=${enc(product)}&key=${enc(key)}"
        val station = http(stationUrl, "https://flash.android.com")

        val canary = latestCanary(station)
            ?: throw Exception("No Pixel Canary build was returned for $product")

        val id = canary.optString("releaseCandidateName")
        val incremental = canary.optString("buildId")
        if (id.isBlank() || incremental.isBlank()) throw Exception("Canary build fields missing")

        val android = canary.optString("releaseTrackVersionName", "Unknown")
        val factory = canary.optString("factoryImageDownloadUrl")
        val releaseDate = if (factory.isNotBlank()) lastModified(factory) else null
        val expiryDate = releaseDate?.let { addDays(it, 42) } ?: "Unknown"

        val canaryId = canary.optString("id")
            .removePrefix("canary-")
            .let { if (it.length >= 4) it.substring(0, 4) + "-" + it.substring(4) else it }

        val secHtml = http("https://source.android.com/docs/security/bulletin/pixel")
        val securityPatch = Regex("""<td>\Q$canaryId\E</td>\s*<td>([^<]+)</td>""")
            .find(secHtml)?.groupValues?.get(1)?.trim()
            ?: "$canaryId-05"

        val fingerprint = "google/$product/$device:CANARY/$id/$incremental:user/release-keys"

        return BuildInfo(model, product, device, android, id, incremental,
            securityPatch, fingerprint, releaseDate ?: "Unknown", expiryDate)
    }

    private fun latestCanary(json: String): JSONObject? {
        val root = JSONObject(json)
        val builds = root.optJSONArray("builds") ?: return null
        for (i in builds.length() - 1 downTo 0) {
            val b = builds.optJSONObject(i) ?: continue
            if (b.optBoolean("canary", false)) return b
        }
        return null
    }

    private fun deviceRows(html: String): List<Pair<String,String>> {
        val result = mutableListOf<Pair<String,String>>()
        Regex("""<tr id="([^"]+)".*?</tr>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(html).forEach { m ->
                val product = m.groupValues[1]
                val cells = Regex("""<td>(.*?)</td>""", RegexOption.DOT_MATCHES_ALL)
                    .findAll(m.value).map { strip(it.groupValues[1]) }.toList()
                if (product.isNotBlank() && cells.isNotEmpty() && product.contains(Regex("""^[a-z0-9_]+$""")))
                    result += cells.first() to "${product}_beta"
            }
        return result.distinct()
    }

    private fun products(html: String): List<String> =
        Regex("""<tr id="([^"]+)"""").findAll(html).map { it.groupValues[1] }.toList()

    private fun extractFlashKey(html: String): String? {
        val m = Regex("""<body\s+data-client-config=.*""").find(html)?.value ?: return null
        return m.substringAfter(';', "").substringBefore('&', "").trim('"', '\'', '>', ' ')
            .takeIf { it.isNotBlank() }
    }

    private fun lastModified(url: String): String? {
        val c = open(url)
        c.requestMethod = "HEAD"
        c.connect()
        val v = c.getHeaderField("Last-Modified") ?: return null
        return v
    }

    private fun addDays(date: String, days: Int): String = "Calculated from $date (+$days days)"

    private fun http(url: String, referer: String? = null): String {
        val c = open(url)
        referer?.let { c.setRequestProperty("Referer", it) }
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    private fun open(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "GET"
        c.connectTimeout = 15000
        c.readTimeout = 25000
        c.setRequestProperty("User-Agent", "PixelFetch/1.0 (Android)")
        c.setRequestProperty("Accept", "text/html,application/json")
        c.instanceFollowRedirects = true
        return c
    }

    private fun abs(path: String) =
        if (path.startsWith("http")) path else "https://developer.android.com" + path

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    private fun first(r: Regex, s: String) = r.find(s)?.groupValues?.get(1)

    private fun strip(s: String) = s.replace(Regex("<[^>]+>"), "").trim()

    private fun render(b: BuildInfo) = """
        Pixel Canary Build
        ==========================
        Model:            ${b.model}
        Product:          ${b.product}
        Device:           ${b.device}
        Android:          ${b.android}
        Build ID:         ${b.id}
        Incremental:      ${b.incremental}
        Security Patch:   ${b.securityPatch}
        Canary Released:  ${b.releaseDate}
        Estimated Expiry: ${b.expiryDate}

        Fingerprint:
        ${b.fingerprint}

        Source:
        Android Developers
        Google Flash Tool / Flashstation
        Pixel Update Bulletins
    """.trimIndent()
}
