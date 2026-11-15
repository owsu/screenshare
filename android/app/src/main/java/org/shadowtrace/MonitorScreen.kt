package org.shadowtrace

import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.SystemClock
import android.provider.Settings
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.content.res.ColorStateList
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.*
import org.json.JSONObject
import org.json.JSONArray
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Modernize dashboard adapted to native Android. All evidence comes from the API. */
class MonitorScreen(context: Context, deviceId: String, onRefresh: () -> Unit, onToggle: () -> Unit) : LinearLayout(context) {
    private val ink = ModernizeTheme.ink
    private val muted = ModernizeTheme.muted
    private val teal = ModernizeTheme.action
    private val paper = ModernizeTheme.canvas
    private val line = ModernizeTheme.border
    private val mint = ModernizeTheme.primaryLight
    private val amber = Color.rgb(135, 77, 17)
    private val pages = FrameLayout(context)
    private val navButtons = mutableListOf<Button>()
    private val sections = mutableListOf<ScrollView>()
    private val modeButtons = mutableListOf<Button>()
    private lateinit var heroTitle: TextView
    private lateinit var heroDetail: TextView
    private lateinit var heroBadge: TextView
    private lateinit var action: Button
    private lateinit var activityText: TextView
    private lateinit var networkCount: TextView
    private lateinit var transitionCount: TextView
    private lateinit var updatedText: TextView
    private lateinit var modeHelp: TextView
    private lateinit var networks: LinearLayout
    private lateinit var networkFreshness: TextView
    private lateinit var radar: GuideGraphic
    private lateinit var scanHealth: TextView
    private lateinit var locationHealth: TextView
    private lateinit var syncHealth: TextView
    private lateinit var scanTiming: TextView
    private lateinit var refresh: Button
    private lateinit var cacheList: LinearLayout
    private var newestScanElapsed: Long? = null
    private var newestFixElapsed: Long? = null
    private var accuracy: Float? = null
    private var lastSyncElapsed: Long? = null
    private var cacheCount = 0
    private var freshCount = 0
    private var lastLocalSignature = ""
    private val localAges = mutableListOf<Pair<TextView,Long>>()
    private var cachedResults = JSONArray()
    private var cachedAtElapsed = 0L
    private val serverTracks = linkedMapOf<String,JSONObject>()
    private val knownNames = mutableMapOf<String,String>()
    private var monitoring = false
    val endpoint = EditText(context)
    val token = EditText(context)
    var selectedMode = "auto"
        private set

    init {
        orientation = VERTICAL
        setBackgroundColor(paper)
        setOnApplyWindowInsetsListener { _, insets ->
            val bars = if (android.os.Build.VERSION.SDK_INT >= 30)
                insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            else null
            @Suppress("DEPRECATION")
            setPadding(bars?.left ?: insets.systemWindowInsetLeft, bars?.top ?: insets.systemWindowInsetTop,
                bars?.right ?: insets.systemWindowInsetRight, bars?.bottom ?: insets.systemWindowInsetBottom)
            insets
        }
        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(22), dp(14), dp(22), dp(14))
        }
        val brand = column()
        brand.addView(label("WiSafe",24,ink,true).apply { letterSpacing = -.025f })
        brand.addView(label("Wireless awareness",12,muted))
        header.addView(brand,LayoutParams(0,LayoutParams.WRAP_CONTENT,1f))
        header.addView(label("ANDROID",10,teal,true).apply {
            background = shape(mint,7); setPadding(dp(10),dp(7),dp(10),dp(7)); letterSpacing = .08f
        })
        addView(header)
        addView(pages, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        val monitor = page()
        heading(monitor,"Overview")
        val hero = card(Color.WHITE, 22)
        val heroRow = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        heroBadge = label("MONITORING PAUSED", 10, teal, true).apply {
            letterSpacing = .06f; background = shape(mint,7)
            setPadding(dp(9),dp(7),dp(9),dp(7))
        }
        heroRow.addView(heroBadge,LayoutParams(0,LayoutParams.WRAP_CONTENT,1f))
        radar = GuideGraphic(context, 0, false).apply { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        heroRow.addView(radar,LayoutParams(dp(80),dp(56)))
        hero.addView(heroRow)
        heroTitle = label("Awareness starts here", 24, ink, true).apply { setPadding(0,dp(12),0,0) }
        heroDetail = label("See networks your phone detects. Learn when shared movement becomes evidence.", 14, muted)
        heroDetail.setPadding(0, dp(10), 0, dp(20))
        hero.addView(heroTitle)
        hero.addView(heroDetail)
        action = button("Start monitoring", teal, Color.WHITE).apply { setOnClickListener { onToggle() } }
        hero.addView(action)
        monitor.addView(hero)
        monitor.addView(label("Only while this app is open", 12, muted).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(22))
        })

        val metrics = LinearLayout(context).apply { orientation = HORIZONTAL }
        fun metric(title: String): TextView {
            val box = card(if (metrics.childCount == 0) mint else ModernizeTheme.secondaryLight, 18).apply {
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(4), 0, dp(4), 0) }
            }
            val value = label("—", 30, ink, true)
            box.addView(value)
            box.addView(label(title, 12, muted))
            metrics.addView(box)
            return value
        }
        networkCount = metric("Cached networks")
        networkCount.contentDescription = "Number of access points in the phone's scan cache. These may be stale."
        transitionCount = metric("Movement links")
        updatedText = label("No session data yet", 12, muted).apply { setPadding(dp(4), dp(10), 0, dp(22)) }
        val health = card(Color.WHITE, 18)
        health.addView(label("Collection health", 17, ink, true))
        scanHealth = label("Wi-Fi · start a session to check", 14, ink)
        locationHealth = label("Location · not checked yet", 14, ink)
        syncHealth = label("Server · not connected yet", 14, ink)
        listOf(scanHealth, locationHealth, syncHealth).forEach { it.setPadding(0,dp(10),0,0); health.addView(it) }
        scanTiming = label("Android controls when fresh scans are available.", 12, muted).apply { setPadding(0,dp(14),0,dp(10)) }
        health.addView(scanTiming)
        refresh = button("Request a Wi-Fi scan", teal, Color.WHITE).apply { setOnClickListener { onRefresh() } }
        health.addView(refresh)
        monitor.addView(health)
        monitor.addView(label("Session activity",17,ink,true).apply { setPadding(dp(4),dp(4),0,dp(12)) })
        monitor.addView(metrics)
        monitor.addView(updatedText)
        note(monitor, "Sitting still? Zero movement links is expected.",
            "Nearby networks can still appear above. Sharing a room doesn't increase a score. Tap Learn to see how WiSafe separates proximity from movement.")
        heading(monitor, "Detection mode")
        val modeRow = LinearLayout(context).apply {
            background = shape(line, 16); setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        listOf("Auto", "Active", "Passive").forEachIndexed { index, name ->
            val control = button(name, line, muted).apply {
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
                setPadding(dp(4), 0, dp(4), 0)
                setOnClickListener { selectMode(index) }
            }
            modeButtons.add(control)
            modeRow.addView(control)
        }
        monitor.addView(modeRow)
        modeHelp = label("", 13, muted).apply { setPadding(0, dp(10), 0, dp(22)) }
        monitor.addView(modeHelp)
        selectMode(0)
        val activity = card(Color.WHITE, 18)
        activity.addView(label("SESSION STATUS", 11, teal, true))
        activityText = label("Connect your backend in Setup, then start monitoring.", 14, ink).apply {
            setPadding(0, dp(8), 0, 0)
            accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
        }
        activity.addView(activityText)
        monitor.addView(activity)
        note(monitor, "Evidence, not intent", "Co-movement cannot establish malicious intent. Missing observations do not mean you are safe.")

        val networkPage = page()
        heading(networkPage, "Observed networks")
        networkPage.addView(label("Review co-movement evidence for each access point. Scores measure evidence, not a probability of stalking.", 14, muted).apply {
            setPadding(0, 0, 0, dp(20))
        })
        networkFreshness = label("No monitoring session is active.", 13, muted).apply { setPadding(0, 0, 0, dp(16)) }
        networkPage.addView(networkFreshness)
        heading(networkPage, "On your phone")
        networkPage.addView(label("Highest evidence first · unsynced networks last",12,muted).apply {
            setPadding(0,0,0,dp(12))
        })
        cacheList = column()
        cacheList.addView(label("Start a session to see Wi-Fi results here, even while GPS is still settling.",14,muted))
        networkPage.addView(cacheList)
        note(networkPage, "Reading these results", "A network name can be shared by many access points. WiSafe tracks their individual BSSIDs. Cached results may describe networks you are no longer near.")
        heading(networkPage, "Earlier observations")
        networks = column()
        networkPage.addView(networks)
        emptyNetworks()

        val learn = page()
        buildLearning(learn)

        val settings = page()
        heading(settings, "Connect your backend")
        settings.addView(label("Your server address is remembered. Your API token stays in memory for this launch.", 14, muted).apply {
            setPadding(0, 0, 0, dp(20))
        })
        val connection = card(Color.WHITE, 20)
        field(connection, "Server URL", endpoint, "http://10.0.2.2:8000", false)
        endpoint.setText(context.getSharedPreferences("collector",Context.MODE_PRIVATE).getString("server_url","http://10.0.2.2:8000"))
        field(connection, "API token", token, "Enter your bearer token", true)
        connection.addView(label("Emulator: 10.0.2.2  ·  USB phone: localhost\nRemote connections require HTTPS.", 12, muted).apply {
            setPadding(0, dp(12), 0, dp(12))
        })
        connection.addView(button("Done · back to Monitor", teal, Color.WHITE).apply {
            setOnClickListener {
                context.getSharedPreferences("collector",Context.MODE_PRIVATE).edit().putString("server_url",endpoint.text.toString().trim()).apply()
                endpoint.clearFocus(); token.clearFocus(); showPage(0)
                val keyboard = context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                keyboard.hideSoftInputFromWindow(windowToken, 0)
            }
        })
        settings.addView(connection)
        note(settings, "Before your first session", "Allow precise location, turn on Wi-Fi and location services, and keep WiSafe open while monitoring.")
        settings.addView(button("Open Wi-Fi controls", mint, ink).apply {
            setOnClickListener { context.startActivity(Intent(Settings.Panel.ACTION_WIFI)) }
        })
        settings.addView(button("Open location settings", Color.WHITE, ink).apply {
            setOnClickListener { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
        })
        note(settings, "How the modes work", "Auto follows detected motion. Active still requires motion evidence. Passive prevents score growth while you stay in one place.")
        settings.addView(label("DEVICE ID", 11, muted, true))
        settings.addView(label(deviceId, 12, ink).apply { setTextIsSelectable(true); setPadding(0, dp(6), 0, dp(12)) })
        settings.addView(button("Open-source licenses",Color.WHITE,muted).apply {
            setOnClickListener {
                val license = context.assets.open("licenses/modernize.txt").bufferedReader().use { it.readText() }
                android.app.AlertDialog.Builder(context).setTitle("Modernize · AdminMart")
                    .setMessage("Native design adaptation of Modernize React Lite, listed on 21st.dev.\n\n$license")
                    .setPositiveButton("Close",null).show()
            }
        })

        val navigation = LinearLayout(context).apply {
            orientation = HORIZONTAL
            background = shape(Color.WHITE, 0)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        listOf("Home", "Networks", "Learn", "Setup").forEachIndexed { index, name ->
            val control = button(name, Color.WHITE, muted).apply {
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(3), 0, dp(3), 0) }
                setPadding(dp(4), 0, dp(4), 0)
                textSize = 12f
                minHeight = dp(66)
                compoundDrawablePadding = dp(3)
                setOnClickListener { showPage(index) }
            }
            navButtons.add(control)
            navigation.addView(control)
        }
        addView(navigation)
        val guided = context.getSharedPreferences("collector",Context.MODE_PRIVATE).getBoolean("guide_complete",false)
        showPage(if (guided) 0 else 2)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun shape(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }
    private fun column() = LinearLayout(context).apply { orientation = VERTICAL }
    private fun label(value: String, size: Int, color: Int, bold: Boolean = false) = TextView(context).apply {
        text = value; textSize = size.toFloat(); setTextColor(color)
        typeface = Typeface.create(Typeface.create("sans-serif",Typeface.NORMAL),if (bold) 600 else 400,false)
        setLineSpacing(dp(3).toFloat(), 1f)
    }
    private fun button(value: String, color: Int, textColor: Int) = Button(context).apply {
        text = value; textSize = 14f; isAllCaps = false
        setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled),intArrayOf()),intArrayOf(muted,textColor)))
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        minHeight = dp(52); minimumHeight = dp(52)
        stateListAnimator = null
        background = StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled),shape(line,8))
            addState(intArrayOf(),RippleDrawable(ColorStateList.valueOf(0x22000000),shape(color,8),null))
        }
        setPadding(dp(16), dp(10), dp(16), dp(10))
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
    }
    private fun card(color: Int, padding: Int) = column().apply {
        background = shape(color, 14).apply { setStroke(dp(1),if (color == Color.WHITE) line else color) }
        elevation = if (color == Color.WHITE) dp(2).toFloat() else 0f
        setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(16) }
    }
    private fun page(): LinearLayout {
        val content = column().apply { setPadding(dp(20), dp(22), dp(20), dp(24)) }
        val scroll = ScrollView(context).apply { isFillViewport = true; addView(content); clipToPadding = false }
        sections.add(scroll)
        pages.addView(scroll, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        return content
    }
    private fun heading(parent: LinearLayout, value: String) {
        parent.addView(label(value, 19, ink, true).apply {
            isAccessibilityHeading = true; setPadding(0, 0, 0, dp(14))
        })
    }
    private fun note(parent: LinearLayout, title: String, value: String) {
        val note = card(ModernizeTheme.primaryLight,16)
        note.addView(label(title, 14, ink, true))
        note.addView(label(value, 13, muted).apply { setPadding(0, dp(6), 0, 0) })
        parent.addView(note)
    }
    private fun field(parent: LinearLayout, title: String, input: EditText, hintText: String, secret: Boolean) {
        val name = label(title, 13, ink, true)
        input.id = View.generateViewId(); name.labelFor = input.id
        parent.addView(name)
        input.apply {
            hint = hintText; textSize = 15f; setTextColor(ink); setHintTextColor(muted)
            inputType = InputType.TYPE_CLASS_TEXT or if (secret) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_VARIATION_URI
            typeface = Typeface.create("sans-serif",Typeface.NORMAL)
            setSingleLine(true)
            importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO
            background = shape(Color.WHITE,7).apply { setStroke(dp(1),line) }
            setPadding(dp(12),dp(10),dp(12),dp(10))
            setOnFocusChangeListener { _, focused ->
                background = shape(Color.WHITE,7).apply { setStroke(dp(1),if (focused) teal else line) }
            }
            minHeight = dp(56)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(16) }
        }
        parent.addView(input)
    }
    private fun showPage(index: Int) {
        sections.forEachIndexed { i, page -> page.visibility = if (i == index) VISIBLE else GONE }
        navButtons.forEachIndexed { i, control ->
            control.isSelected = i == index
            control.background = RippleDrawable(ColorStateList.valueOf(0x22000000), shape(if (i == index) mint else Color.WHITE, 8), null)
            control.setTextColor(if (i == index) ink else muted)
            control.setCompoundDrawables(null,NavigationGlyph(i,if (i == index) teal else muted,dp(21)),null,null)
        }
    }
    fun showSetup() = showPage(3)
    private fun selectMode(index: Int) {
        selectedMode = listOf("auto", "active", "passive")[index]
        modeButtons.forEachIndexed { i, control ->
            control.isSelected = i == index
            control.background = RippleDrawable(ColorStateList.valueOf(0x22000000), shape(if (i == index) Color.WHITE else line, 12), null)
            control.setTextColor(if (i == index) ink else muted)
        }
        modeHelp.text = listOf("Adjusts to your movement. A good place to start.",
            "Look for co-movement while travelling. Motion checks still apply.",
            "For staying in one place. Nearby networks will not increase scores.")[index]
    }
    fun setMonitoring(value: Boolean) {
        monitoring = value
        radar.isActivated = value
        radar.layoutParams = LayoutParams(dp(80),dp(56))
        heroTitle.textSize = if (value) 23f else 26f
        action.text = if (value) "Pause monitoring" else "Start monitoring"
        endpoint.isEnabled = !value; token.isEnabled = !value
        if (!value) {
            scanHealth.text = "Wi-Fi · monitoring paused"
            locationHealth.text = "Location · updates paused"
            syncHealth.text = "Server · session paused"
            scanTiming.text = "Start a session to collect current observations."
            refresh.isEnabled = true
            networkFreshness.text = "Monitoring paused. Previous observations may be out of date."
            heroBadge.text = "MONITORING PAUSED"
            heroTitle.text = "Monitoring is paused"
            heroDetail.text = "Start a session for current coverage. Previous observations may be out of date."
        }
    }
    fun message(value: String) {
        activityText.text = value
        if (monitoring) {
            networkFreshness.text = "Coverage is not confirmed. Displayed evidence may be out of date."
            heroBadge.text = "WAITING FOR COVERAGE"
            heroTitle.text = if (value.startsWith("Connection") || value.startsWith("Server") || value.startsWith("Upload")) "Let's reconnect" else "Gathering observations"
            syncHealth.text = value
        }
        heroDetail.text = value
    }
    fun render(state: JSONObject) {
        val observing = state.optString("coverage") == "observing"
        val diagnostics = state.optJSONObject("diagnostics")
        networkFreshness.text = if (observing) "Fresh monitoring data received. Scores decay over time."
            else "No new usable observation in the latest check. Each scan record shows its age below."
        heroBadge.text = if (observing) "MONITORING · OBSERVING" else "LIMITED COVERAGE"
        heroTitle.text = if (observing) "A clearer view nearby" else "Waiting for fresh data"
        heroDetail.text = if (observing) "Fresh scan and location paired. Staying still won't increase scores."
            else when (diagnostics?.optString("location_status")) {
                "missing", "stale" -> "Wi-Fi results can appear while your phone finds a current location. Check the live inputs below."
                "inaccurate" -> "Wi-Fi collection continues. GPS needs a more precise fix before movement can be assessed."
                "inconsistent_motion" -> "A location jump was rejected. Waiting for a consistent fix; no evidence was added."
                else -> "Listening for Android's next scan. Cached results aren't new evidence. Check scan age below."
            }
        activityText.text = if (observing) "Connected · ${state.optString("mode").replaceFirstChar { it.uppercase() }} mode detected"
            else "Connected, but monitoring coverage is insufficient. Keep Wi-Fi and location enabled."
        val tracks = state.optJSONArray("tracks") ?: org.json.JSONArray()
        var transitions = 0
        for (i in 0 until tracks.length()) transitions += tracks.getJSONObject(i).optInt("transitions")
        transitionCount.text = transitions.toString()
        lastSyncElapsed = SystemClock.elapsedRealtime()
        syncHealth.text = "Server · connected · ${state.optInt("queued_batches")} batches waiting"
        activityText.text = when (diagnostics?.optString("location_status")) {
            "inaccurate" -> "Your location is too imprecise for movement evidence. Networks are still visible. A window or an open outdoor area may improve GPS."
            "missing", "stale" -> "Waiting for a recent location fix. Wi-Fi results still appear in Networks."
            "inconsistent_motion" -> "A location jump was rejected. It won't add movement evidence."
            else -> if (state.optString("mode") == "passive") "Staying in one place: nearby networks don't increase scores. Zero movement links is expected."
                else if (!observing) "No new usable scan this cycle. Check scan age above; repeated cached results never earn evidence."
                else "Fresh scan and location paired. Movement links require reappearance at a meaningfully different location."
        }
        serverTracks.clear()
        for (i in 0 until tracks.length()) {
            val track = tracks.getJSONObject(i)
            serverTracks[track.optString("bssid").lowercase()] = track
            networkName(track.optString("ssid"))?.let { knownNames.putIfAbsent(track.optString("bssid").lowercase(),it) }
        }
        renderNetworkLists()
        tick()
    }

    private fun networkName(value: String): String? = value.takeIf {
        it.isNotBlank() && !it.equals("<unknown ssid>",true) && it.any { character -> character != '\u0000' }
    }

    private fun addIdentity(parent: LinearLayout, ap: JSONObject, cached: Boolean) {
        val key = ap.optString("bssid").lowercase()
        val current = networkName(ap.optString("ssid"))
        val name = current ?: knownNames[key]
        parent.addView(label(name ?: "Network name unavailable",17,ink,true))
        if ((current == null && name != null) || (!cached && ap.optBoolean("ssid_is_historical")))
            parent.addView(label("Last known SSID for this BSSID",12,muted))
        parent.addView(label("BSSID · ${ap.optString("bssid")}",12,muted))
    }

    private fun addEvidence(parent: LinearLayout, track: JSONObject?) {
        val evidence = column().apply {
            background = shape(mint,8)
            setPadding(dp(12),dp(12),dp(12),dp(12))
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12); bottomMargin = dp(12) }
        }
        if (track == null) {
            evidence.addView(label("Evidence not synced yet",13,muted,true))
            evidence.addView(label("A score will appear after this BSSID reaches the server.",12,muted))
        } else {
            val score = track.optDouble("score",0.0)
            val known = track.optBoolean("known_infrastructure")
            val color = if (score >= 10 && !known) amber else teal
            evidence.addView(label(if (known) "KNOWN INFRASTRUCTURE" else when {
                score >= 30 -> "REPEATED CO-MOVEMENT"
                score >= 10 -> "POSSIBLE CO-MOVEMENT"
                else -> "LOW EVIDENCE"
            },11,color,true))
            evidence.addView(label("${String.format(Locale.getDefault(),"%.1f",score)} / 100 evidence",20,ink,true))
            evidence.addView(ProgressBar(context,null,android.R.attr.progressBarStyleHorizontal).apply {
                max = 100; progress = score.toInt().coerceIn(0,100)
                progressTintList = ColorStateList.valueOf(color)
                progressBackgroundTintList = ColorStateList.valueOf(line)
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT,dp(8)).apply { topMargin = dp(8); bottomMargin = dp(8) }
            })
            val count = track.optInt("transitions")
            evidence.addView(label("$count confirmed movement ${if (count == 1) "link" else "links"}",13,muted))
            if (!track.optBoolean("location_ready")) evidence.addView(label("Waiting for a usable location pairing · no score added",12,muted))
            evidence.addView(label("Last synced evidence · updates when the server responds",12,muted))
        }
        parent.addView(evidence)
    }

    private fun renderNetworkLists() {
        renderCachedNetworks()
        val cachedKeys = (0 until cachedResults.length()).map { cachedResults.getJSONObject(it).optString("bssid").lowercase() }.toSet()
        val earlier = serverTracks.filterKeys { it !in cachedKeys }.values
        networks.removeAllViews()
        if (earlier.isEmpty()) {
            networks.addView(label(if (cachedKeys.isEmpty()) "No earlier observations yet." else "All stored networks are shown in the cards above.",13,muted))
        }
        for (track in earlier) {
            val item = card(Color.WHITE, 20).apply {
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) }
            }
            addIdentity(item,track,false)
            addEvidence(item,track)
            item.addView(label("Not in the current phone scan cache",12,muted))
            networks.addView(item)
        }
    }
    private fun emptyNetworks() {
        val empty = card(Color.WHITE, 24)
        empty.addView(label("No observations yet", 20, ink, true))
        empty.addView(label("Fresh Wi-Fi results create records. Reliable location is needed only for movement evidence. Your phone's scan cache appears separately above.", 14, muted).apply {
            setPadding(0, dp(10), 0, dp(20))
        })
        empty.addView(button("Go to Monitor", teal, Color.WHITE).apply { setOnClickListener { showPage(0) } })
        networks.addView(empty)
    }

    fun localSnapshot(results: JSONArray, fix: Location?) {
        val now = SystemClock.elapsedRealtime()
        cacheCount = results.length()
        newestScanElapsed = (0 until results.length()).map {
            now - (results.getJSONObject(it).optDouble("age_seconds")*1000).toLong()
        }.maxOrNull()
        newestFixElapsed = fix?.elapsedRealtimeNanos?.div(1_000_000)
        accuracy = if (fix?.hasAccuracy() == true) fix.accuracy else null
        networkCount.text = cacheCount.toString()
        // Keep this list independent of server connectivity and GPS acceptance.
        val signature = results.toString()
        if (signature != lastLocalSignature) {
            lastLocalSignature = signature
            cachedResults = results
            cachedAtElapsed = now
            for (i in 0 until results.length()) {
                val ap = results.getJSONObject(i)
                networkName(ap.optString("ssid"))?.let { knownNames[ap.optString("bssid").lowercase()] = it }
            }
            renderNetworkLists()
        }
        tick()
    }

    private fun renderCachedNetworks() {
            val results = cachedResults
            cacheList.removeAllViews(); localAges.clear()
            if (results.length() == 0) cacheList.addView(label("No Wi-Fi scan results yet. Check Wi-Fi, precise location permission and the scan status on Home.",14,muted))
            val ranked = (0 until results.length()).map { results.getJSONObject(it) }.sortedWith(
                compareByDescending<JSONObject> {
                    // Missing evidence is distinct from an evaluated zero score.
                    serverTracks[it.optString("bssid").lowercase()]?.optDouble("score",-1.0) ?: -1.0
                }.thenBy { it.optString("bssid").lowercase() }
            )
            for (ap in ranked) {
                val item = card(Color.WHITE,16).apply {
                    layoutParams = LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
                }
                addIdentity(item,ap,true)
                val strength = ap.optInt("rssi_dbm")
                item.addView(label("${if (strength >= -60) "Strong" else if (strength >= -75) "Moderate" else "Weak"} signal · $strength dBm",13,teal))
                addEvidence(item,serverTracks[ap.optString("bssid").lowercase()])
                val ageLabel = label("",12,muted)
                item.addView(ageLabel)
                localAges.add(ageLabel to (cachedAtElapsed-(ap.optDouble("age_seconds")*1000).toLong()))
                cacheList.addView(item)
            }
    }

    fun tick() {
        if (!monitoring) return
        val now = SystemClock.elapsedRealtime()
        val scanAge = newestScanElapsed?.let { ((now-it)/1000).coerceAtLeast(0) }
        freshCount = localAges.count { now-it.second <= 30000 }
        scanHealth.text = when {
            scanAge == null -> "Wi-Fi · waiting for the first scan"
            scanAge > 30 -> "Wi-Fi · $cacheCount cached results · latest is ${ageText(scanAge)} old"
            else -> "Wi-Fi · $freshCount recent / $cacheCount cached · latest ${ageText(scanAge)} ago"
        }
        val fixAge = newestFixElapsed?.let { ((now-it)/1000).coerceAtLeast(0) }
        locationHealth.text = when {
            fixAge == null || accuracy == null -> "Location · waiting for a fix (Wi-Fi can still appear)"
            fixAge > 15 -> "Location · last fix ${ageText(fixAge)} ago · needs an update"
            accuracy!! > 50 -> "Location · ±${accuracy!!.toInt()} m · needs ≤50 m for evidence"
            else -> "Location · ±${accuracy!!.toInt()} m · ready for pairing"
        }
        updatedText.text = "Phone checked every 5 seconds · ${if (scanAge == null) "no scan received yet" else "latest scan ${ageText(scanAge)} ago"}"
        localAges.forEach { (view, at) ->
            val age = ((now-at)/1000).coerceAtLeast(0)
            view.text = "${if (age <= 30) "Recent scan" else "Cached scan · not new evidence"} · ${ageText(age)} ago"
        }
        if (lastSyncElapsed != null && now-lastSyncElapsed!! > 15000)
            syncHealth.text = "Server · last response ${ageText((now-lastSyncElapsed!!)/1000)} ago · retrying"
    }

    fun scanSchedule(seconds: Long, note: String) {
        scanTiming.text = "$note\nNext scan request in ${seconds}s. Android may delay it."
        refresh.isEnabled = monitoring && seconds == 0L
        refresh.text = if (seconds > 0) "Next request in ${seconds}s" else "Request a Wi-Fi scan"
    }

    private fun ageText(seconds: Long) = if (seconds < 60) "${seconds}s" else "${seconds/60}m ${seconds%60}s"

    private fun buildLearning(parent: LinearLayout) {
        heading(parent,"A little clarity goes a long way")
        parent.addView(label("Six short lessons. Understand what WiSafe sees, what a score means, and when to wait.",14,muted).apply { setPadding(0,0,0,dp(20)) })
        val lesson = card(Color.WHITE,20)
        val progress = label("",11,teal,true)
        val graphic = GuideGraphic(context,0,false)
        val title = label("",23,ink,true)
        val body = label("",15,muted).apply { setPadding(0,dp(12),0,dp(16)) }
        val takeaway = label("",14,teal,true).apply { setPadding(0,0,0,dp(18)) }
        val back = button("Previous",Color.WHITE,ink)
        val next = button("Next lesson",teal,Color.WHITE)
        val titles = listOf("Meet your wireless surroundings", "Names aren't identities", "Same room? That's just proximity.",
            "Movement tells a different story", "Sometimes you're just on the same bus", "Fresh data matters. Evidence fades.")
        val descriptions = listOf(
            "Your phone detects Wi-Fi access points, such as routers and hotspots. WiSafe reads those scan results without joining a network. It needs your permission to collect location.",
            "Two hotspots can have the same name (SSID). WiSafe keeps each BSSID separate. A changed BSSID starts a new track; we cannot assume it belongs to the same person.",
            "Studying in a library? A nearby hotspot can stay visible for hours. Passive mode prevents score growth. Nearby is not the same as following.",
            "Imagine walking to another place and seeing the same BSSID again. At least 200 metres beyond the location accuracy margins can add a movement link. Another location adds more evidence.",
            "A bus hotspot naturally travels with its passengers. When several access points share a transition, each earns less evidence. Even one travelling hotspot cannot establish malicious intent.",
            "Android limits scan requests. Re-reading the same cached result doesn't create new evidence. Check the live scan age. Scores halve every hour, and missing coverage never means safety.")
        val takeaways = listOf("Permission → scan → location → evidence", "Same name ≠ same access point", "Stationary session → no score growth",
            "New place + same BSSID → possible co-movement", "Shared travel → reduced evidence", "Scan age and coverage matter more than a zero score")
        var step = 0
        fun update() {
            progress.text = "QUICK START  ·  ${step+1} OF ${titles.size}"
            graphic.step = step
            graphic.contentDescription = takeaways[step]
            title.text = titles[step]; body.text = descriptions[step]; takeaway.text = takeaways[step]
            back.isEnabled = step > 0
            next.text = if (step == titles.lastIndex) "Finish guide · open Home" else "Next lesson"
            parent.post { (parent.parent as? ScrollView)?.smoothScrollTo(0,0) }
        }
        lesson.addView(progress)
        lesson.addView(graphic,LayoutParams(LayoutParams.MATCH_PARENT,dp(180)))
        lesson.addView(title); lesson.addView(body); lesson.addView(takeaway)
        lesson.addView(next); lesson.addView(back)
        back.setOnClickListener { if (step > 0) { step--; update() } }
        next.setOnClickListener {
            if (step < titles.lastIndex) { step++; update() }
            else {
                context.getSharedPreferences("collector",Context.MODE_PRIVATE).edit().putBoolean("guide_complete",true).apply()
                showPage(0)
            }
        }
        update(); parent.addView(lesson)
        parent.addView(button("Explore the app first",mint,ink).apply { setOnClickListener { showPage(0) } })
        note(parent,"Try a scenario", "Tap a situation to see how the detector responds. These are teaching examples, not live observations.")
        val answer = label("Choose an example below.",14,ink).apply { setPadding(dp(12),dp(12),dp(12),dp(12)) }
        val practice = GuideGraphic(context,2,false)
        parent.addView(practice,LayoutParams(LayoutParams.MATCH_PARENT,dp(130)))
        val examples = listOf(
            Triple("Studying in a library",2,"Passive mode: no score growth. Sharing a room for a long time is not movement evidence."),
            Triple("Same hotspot after a walk",3,"A new movement link may be recorded if distance, timing and location accuracy checks pass. Intent is still unknown."),
            Triple("Sharing Wi-Fi on a bus",4,"Co-movement can be legitimate. Multiple APs sharing the transition reduce each increment. A score does not identify a stalker."))
        examples.forEach { (name,scene,explanation) ->
            parent.addView(button(name,Color.WHITE,ink).apply { setOnClickListener {
                practice.step = scene; answer.text = explanation
                answer.post { answer.requestRectangleOnScreen(Rect(0,0,answer.width,answer.height),false) }
            } })
        }
        answer.accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
        parent.addView(answer)
    }
}

