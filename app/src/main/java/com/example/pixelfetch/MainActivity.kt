package com.example.pixelfetch

import android.app.Activity
import android.content.ContentValues
import android.os.Bundle
import android.provider.MediaStore
import android.view.ViewGroup
import android.widget.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

data class DeviceChoice(val model: String, val product: String, val device: String) {
    override fun toString(): String = "$model — $device"
}

data class BuildChoice(
    val model: String,
    val product: String,
    val device: String,
    val id: String,
    val incremental: String,
    val android: String,
    val release: String,
    val securityPatch: String,
    val buildDate: String,
    val fingerprint: String
)

class MainActivity : Activity() {
    private val pool = Executors.newSingleThreadExecutor()
    private lateinit var channelSpinner: Spinner
    private lateinit var deviceSpinner: Spinner
    private lateinit var fetchButton: Button
    private lateinit var status: TextView
    private lateinit var results: LinearLayout
    private var devices: List<DeviceChoice> = emptyList()

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(R.layout.activity_main)

        channelSpinner = findViewById(R.id.channel)
        deviceSpinner = findViewById(R.id.device)
        fetchButton = findViewById(R.id.fetch)
        status = findViewById(R.id.status)
        results = findViewById(R.id.results)

        channelSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf("Canary", "Stable")
        )

        channelSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {}
            override fun onItemSelected(
                parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long
            ) {
                loadDevices()
            }
        }

        fetchButton.setOnClickListener { fetchSelectedBuild() }
        loadDevices()
    }

    private fun loadDevices() {
        val channel = channelSpinner.selectedItem?.toString() ?: "Canary"
        if (channel != "Canary") {
            devices = emptyList()
            deviceSpinner.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("Stable fetch not implemented")
            )
            status.text = "Select Canary for the autopif4-compatible fetch path."
            fetchButton.isEnabled = false
            return
        }

        status.text = "Loading Pixel device lists…"
        fetchButton.isEnabled = false

        pool.execute {
            try {
                val (factoryHtml, otaHtml) = fetchBetaDeviceLists()
                val chosen = if (factoryHtml.length >= otaHtml.length) factoryHtml else otaHtml
                devices = parseDevices(chosen)

                runOnUiThread {
                    deviceSpinner.adapter = ArrayAdapter(
                        this,
                        android.R.layout.simple_spinner_dropdown_item,
                        devices
                    )
                    status.text = "Found ${devices.size} devices"
                    fetchButton.isEnabled = devices.isNotEmpty()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "Device fetch failed: ${e.message}"
                    fetchButton.isEnabled = false
                }
            }
        }
    }

    private fun fetchSelectedBuild() {
        val pos = deviceSpinner.selectedItemPosition
        if (pos !in devices.indices) return

        val chosen = devices[pos]
        status.text = "Fetching Canary build for ${chosen.model}…"
        fetchButton.isEnabled = false
        results.removeAllViews()

        pool.execute {
            try {
                val build = fetchCanary(chosen)
                runOnUiThread {
                    showBuild(build)
                    status.text = "Build fetched successfully"
                    fetchButton.isEnabled = true
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "Build fetch failed: ${e.message}"
                    fetchButton.isEnabled = true
                }
            }
        }
    }

    // Upstream-derived discovery:
    // Android Developers version page -> current Android page ->
    // Factory Image + OTA pages -> use the longer device list.
    private fun fetchBetaDeviceLists(): Pair<String, String> {
        val versions = http("https://developer.android.com/about/versions")

        val latestUrl = Regex(
            """https://developer\.android\.com/about/versions/[^"' ]*[0-9]"""
        ).findAll(versions)
            .map { it.value }
            .maxByOrNull { it.length }
            ?: throw Exception("Could not locate Android version page")

        val latest = http(latestUrl)

        val factoryRel = Regex("""href="([^"]*download[^"]*)"""")
            .findAll(latest)
            .map { it.groupValues[1] }
            .firstOrNull { it.contains("qpr", ignoreCase = true) }
            ?: throw Exception("Factory Image page not found")

        val otaRel = Regex("""href="([^"]*download-ota[^"]*)"""")
            .findAll(latest)
            .map { it.groupValues[1] }
            .firstOrNull { it.contains("qpr", ignoreCase = true) }
            ?: throw Exception("OTA page not found")

        val factory = http(resolveDeveloper(factoryRel))
        val ota = http(resolveDeveloper(otaRel))
        return factory to ota
    }

    private fun parseDevices(html: String): List<DeviceChoice> {
        val list = mutableListOf<DeviceChoice>()

        Regex("""<tr id="([^"]+)".*?</tr>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(html)
            .forEach { match ->
                val device = match.groupValues[1]
                if (!device.matches(Regex("[a-z0-9_]+"))) return@forEach

                val model = Regex("""<td>(.*?)</td>""", RegexOption.DOT_MATCHES_ALL)
                    .find(match.value)
                    ?.groupValues?.get(1)
                    ?.replace(Regex("<[^>]+>"), "")
                    ?.trim()

                if (!model.isNullOrBlank()) {
                    list += DeviceChoice(
                        model = model,
                        product = "${device}_beta",
                        device = device
                    )
                }
            }

        return list.distinctBy { it.product }
    }

    // Upstream-derived Flash Tool -> Flashstation lookup.
    private fun fetchCanary(choice: DeviceChoice): BuildChoice {
        val flashHtml = http("https://flash.android.com/")
        val key = extractFlashKey(flashHtml)
            ?: throw Exception("Could not obtain Flash Tool key")

        val endpoint =
            "https://content-flashstation-pa.googleapis.com/v1/builds" +
                "?product=${enc(choice.product)}&key=${enc(key)}"

        val root = JSONObject(http(endpoint, "https://flash.android.com"))
        val builds = root.optJSONArray("builds")
            ?: throw Exception("No builds returned for ${choice.product}")

        var canary: JSONObject? = null
        for (i in builds.length() - 1 downTo 0) {
            val item = builds.optJSONObject(i) ?: continue
            if (item.optBoolean("canary", false)) {
                canary = item
                break
            }
        }

        val item = canary ?: throw Exception(
            "No Pixel Canary build was returned for ${choice.product}"
        )

        val id = item.optString("releaseCandidateName")
        val incremental = item.optString("buildId")
        if (id.isBlank() || incremental.isBlank()) {
            throw Exception("Incomplete Canary build metadata")
        }

        val android = item.optString("releaseTrackVersionName", "Unknown")
        val factoryUrl = item.optString("factoryImageDownloadUrl")
        val buildDate = if (factoryUrl.isNotBlank()) lastModified(factoryUrl) else "Unknown"

        val canaryId = item.optString("id")
            .removePrefix("canary-")
            .let { if (it.length > 4) it.substring(0, 4) + "-" + it.substring(4) else it }

        val securityPatch = fetchSecurityPatch(canaryId)

        val fingerprint =
            "google/${choice.product}/${choice.device}:CANARY/$id/$incremental:user/release-keys"

        return BuildChoice(
            model = choice.model,
            product = choice.product,
            device = choice.device,
            id = id,
            incremental = incremental,
            android = android,
            release = "CANARY",
            securityPatch = securityPatch,
            buildDate = buildDate,
            fingerprint = fingerprint
        )
    }

    private fun fetchSecurityPatch(canaryId: String): String {
        val bulletin = http("https://source.android.com/docs/security/bulletin/pixel")
        val match = Regex(
            """<td>\Q$canaryId\E</td>\s*<td>([^<]+)</td>""",
            RegexOption.DOT_MATCHES_ALL
        ).find(bulletin)
        return match?.groupValues?.get(1)?.trim()
            ?: if (canaryId.matches(Regex("""\d{4}-\d{2}-\d{2}"""))) canaryId else "Unknown"
    }

    private fun extractFlashKey(html: String): String? {
        val body = Regex("""<body\s+data-client-config=.*""")
            .find(html)?.value ?: return null
        return body.substringAfter(';')
            .substringBefore('&')
            .trim('"', '\'', '>', ' ')
            .takeIf { it.isNotBlank() }
    }

    private fun showBuild(build: BuildChoice) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 18, 18, 18)
        }

        fun label(text: String, size: Float = 14f, bold: Boolean = false): TextView =
            TextView(this).apply {
                this.text = text
                textSize = size
                if (bold) setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(0, 3, 0, 3)
            }

        card.addView(label(build.model, 18f, true))
        card.addView(label("Device: ${build.device}"))
        card.addView(label("Product: ${build.product}"))
        card.addView(label("Build: ${build.id}"))
        card.addView(label("Build date: ${build.buildDate}"))
        card.addView(label("Android: ${build.android}"))
        card.addView(label("Security patch: ${build.securityPatch}"))

        val download = Button(this).apply {
            text = "DOWNLOAD JSON"
            setOnClickListener { saveJson(build) }
        }
        card.addView(download)

        results.addView(
            card,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
    }

    private fun saveJson(build: BuildChoice) {
        val json = JSONObject().apply {
            put("TYPE", "user")
            put("TAGS", "release-keys")
            put("ID", build.id)
            put("BRAND", "google")
            put("DEVICE", build.device)
            put("FINGERPRINT", build.fingerprint)
            put("MANUFACTURER", "Google")
            put("MODEL", build.model)
            put("PRODUCT", build.product)
            put("RELEASE", build.release)
            put("SECURITY_PATCH", build.securityPatch)
            put("DEVICE_INITIAL_SDK_INT", 32)
            put("DEBUG", false)
            put("SDK_INT", 32)
        }

        val safeModel = build.model.replace(Regex("[^A-Za-z0-9]+"), "_")
        val fileName = "${safeModel}_${build.id}.json"

        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "application/json")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/PixelFetch")
        }

        val uri = contentResolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            values
        ) ?: throw IllegalStateException("Could not create download")

        contentResolver.openOutputStream(uri)?.use {
            it.write(json.toString(2).toByteArray(Charsets.UTF_8))
        }

        Toast.makeText(
            this,
            "Saved to Download/PixelFetch/$fileName",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun http(url: String, referer: String? = null): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "GET"
        c.connectTimeout = 20000
        c.readTimeout = 30000
        c.setRequestProperty("User-Agent", "PixelFetch/1.0")
        if (referer != null) c.setRequestProperty("Referer", referer)

        val code = c.responseCode
        if (code !in 200..299) throw Exception("HTTP $code")
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    private fun lastModified(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "HEAD"
        c.connectTimeout = 15000
        c.readTimeout = 15000
        c.connect()

        val raw = c.getHeaderField("Last-Modified") ?: return "Unknown"
        return try {
            val src = SimpleDateFormat(
                "EEE, dd MMM yyyy HH:mm:ss z",
                Locale.US
            )
            val out = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            out.format(src.parse(raw)!!)
        } catch (_: Exception) {
            "Unknown"
        }
    }

    private fun resolveDeveloper(path: String): String =
        if (path.startsWith("http")) path else "https://developer.android.com$path"

    private fun enc(value: String): String =
        URLEncoder.encode(value, "UTF-8")

    override fun onDestroy() {
        pool.shutdownNow()
        super.onDestroy()
    }
}
