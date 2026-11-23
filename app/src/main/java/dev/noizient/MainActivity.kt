package dev.noizient

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private lateinit var store: Store
    private lateinit var selected: Mode
    private lateinit var page: LinearLayout
    private lateinit var mixer: LinearLayout
    private lateinit var details: LinearLayout
    private lateinit var status: TextView
    private lateinit var play: Button
    private lateinit var stop: Button
    private lateinit var like: Button
    private lateinit var dislike: Button
    private lateinit var focus: Button
    private lateinit var sleep: Button
    private var expanded = false
    private var wide = false
    private var displayedSession: PlaybackService? = null
    private var feedback: String? = null
    private var pending: Mode? = null
    private var waitingUntil = 0L
    private var resumeRecord: JSONObject? = null
    private val handler = Handler(Looper.getMainLooper())
    private val dark get() = selected == Mode.Sleep ||
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    private val canvas get() = rgb(if (dark) "#101214" else "#E8EBED")
    private val surface get() = rgb(if (dark) "#191C1F" else "#F6F7F8")
    private val ink get() = rgb(if (dark) "#E7E9EB" else "#192124")
    private val quiet get() = rgb(if (dark) "#AAB4BA" else "#506064")
    private val border get() = rgb(if (dark) "#3C454B" else "#CBD2D6")
    private val accent get() = rgb(if (dark) "#A9C2D1" else "#245E60")

    private val ticker = object : Runnable {
        override fun run() {
            val service = PlaybackService.current
            val live = service?.takeIf { it.active }
            if (service == null && pending != null) {
                val mode = pending ?: selected
                pending = null
                launch(mode, resumeRecord)
                resumeRecord = null
            }
            if (displayedSession !== live) {
                displayedSession = live
                rebuildMixer()
            }
            if (live != null) waitingUntil = 0
            val busy = pending != null || android.os.SystemClock.uptimeMillis() < waitingUntil
            play.isEnabled = !busy
            stop.isEnabled = live != null
            val active = live?.takeIf { it.mode == selected }
            like.isEnabled = active != null
            dislike.isEnabled = active != null
            play.text = when {
                busy -> "準備中"
                active?.playing == true -> "一時停止"
                else -> "再生"
            }
            status.text = when {
                active != null -> {
                    val state = when {
                        !active.playing -> "一時停止"
                        active.mode == Mode.Focus -> "再生中"
                        active.inIntro -> "導入"
                        else -> "安定"
                    }
                    val timer = active.remaining?.let { "  ·  残り ${clock(it)}" }.orEmpty()
                    "$state  ·  ${clock(active.elapsed)}$timer"
                }
                busy -> "音声を準備中"
                else -> PlaybackService.error ?: "停止中"
            }
            handler.postDelayed(this, 180)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC
        store = Store(this)
        selected = PlaybackService.current?.takeIf { it.active }?.mode ?: store.mode
        wide = resources.configuration.screenWidthDp >= 720
        expanded = savedInstanceState?.getBoolean("details") ?: wide
        buildPage()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("details", expanded)
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        handler.post(ticker)
    }

    override fun onStop() {
        handler.removeCallbacks(ticker)
        super.onStop()
    }

    private fun rgb(value: String) = Color.parseColor(value)
    private fun dp(value: Int) = (resources.displayMetrics.density * value).roundToInt()
    private fun clock(seconds: Double): String {
        val s = seconds.toInt()
        return String.format(Locale.ROOT, "%02d:%02d", s / 60, s % 60)
    }

    private fun background(fill: Int, radius: Int = 12, stroke: Int? = null) =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radius).toFloat()
            stroke?.let { setStroke(dp(1), it) }
        }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
    private fun text(value: String, size: Float = 15f, color: Int = ink) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
    }

    private fun heading(value: String) = text(value, 19f).apply {
        setTypeface(null, Typeface.BOLD)
        if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        setPadding(0, 0, 0, dp(10))
    }

    private fun button(label: String, primary: Boolean = false, click: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        minHeight = dp(48)
        minimumWidth = 0
        setPadding(dp(10), 0, dp(10), 0)
        background = android.graphics.drawable.RippleDrawable(
            ColorStateList.valueOf(if (dark) 0x30FFFFFF else 0x22000000),
            this@MainActivity.background(if (primary) accent else surface, 9, if (primary) null else border), null
        )
        setTextColor(if (primary) if (dark) Color.BLACK else Color.WHITE else ink)
        setOnClickListener { click() }
        isSoundEffectsEnabled = false
    }

    private fun LinearLayout.weighted(vararg views: View) {
        views.forEachIndexed { index, view ->
            addView(view, LinearLayout.LayoutParams(0, -2, 1f).apply {
                if (index > 0) marginStart = dp(8)
            })
        }
    }

    private fun LinearLayout.divider() {
        addView(View(this@MainActivity).apply { setBackgroundColor(border) },
            LinearLayout.LayoutParams(-1, dp(1)))
    }

    private fun LinearLayout.card(content: LinearLayout) {
        content.background = this@MainActivity.background(surface, 14, border)
        content.setPadding(dp(20), dp(18), dp(20), dp(18))
        addView(content, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
    }

    private fun buildPage() {
        window.statusBarColor = canvas
        window.navigationBarColor = canvas
        window.decorView.systemUiVisibility = if (dark) 0 else
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        page = column().apply { setPadding(dp(5), dp(8), dp(5), dp(8)) }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(canvas)
            isFillViewport = true
            addView(page)
            if (Build.VERSION.SDK_INT >= 35) setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
        setContentView(scroll)
        val header = row().apply {
            setPadding(dp(15), 0, dp(15), dp(9))
            addView(text("noizient", 27f).apply {
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                letterSpacing = -.04f
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(button("履歴") { showHistory() })
        }
        page.addView(header)
        val left = column()
        val right = column()
        if (wide) {
            page.addView(row().apply {
                gravity = Gravity.TOP
                addView(left, LinearLayout.LayoutParams(0, -2, 1.35f))
                addView(right, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
            })
        } else page.addView(left)
        left.card(column().apply {
            focus = button("Focus", selected == Mode.Focus) { changeMode(Mode.Focus) }
            sleep = button("Sleep", selected == Mode.Sleep) { changeMode(Mode.Sleep) }
            focus.isSelected = selected == Mode.Focus
            sleep.isSelected = selected == Mode.Sleep
            addView(row().apply { weighted(focus, sleep) })
            addView(text(if (selected == Mode.Focus) "集中のための持続音" else "入眠前のサウンドスケープ", 23f).apply {
                setPadding(0, dp(22), 0, dp(6))
            })
            status = text("停止中", 15f, quiet)
            addView(status)
            play = button("再生", true) { togglePlayback() }
            stop = button("停止") {
                pending = null
                PlaybackService.current?.end()
            }
            stop.isEnabled = false
            addView(row().apply {
                setPadding(0, dp(15), 0, dp(11))
                weighted(play, stop)
            })
            val message = text(feedback.orEmpty(), 14f, quiet)
            fun rate(sign: Int) {
                if (PlaybackService.current?.rating(sign) == true) {
                    feedback = if (sign > 0) "「好き」を記録しました" else "「苦手」を記録しました"
                    message.text = feedback
                }
            }
            like = button("好き") { rate(1) }.apply { isEnabled = false }
            dislike = button("苦手") { rate(-1) }.apply { isEnabled = false }
            addView(row().apply { weighted(like, dislike) })
            message.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            addView(message)
        })
        mixer = column()
        left.card(mixer)
        details = column()
        (if (wide) right else left).card(details)
        rebuildMixer()
    }

    private fun rebuildMixer() {
        mixer.removeAllViews()
        details.removeAllViews()
        val values = PlaybackService.current?.takeIf { it.active && it.mode == selected }?.values ?: store.values(selected)
        mixer.addView(heading("配合"))
        val labels = if (selected == Mode.Focus) listOf("持続音と上音", "背景のざわめき", "主音の脈動")
            else listOf("低い響き", "背景の質感", "導入のチャイム")
        labels.forEachIndexed { index, title -> slider(mixer, title, index, values[index].toInt()) }
        mixer.divider()
        slider(mixer, "音量", 15, values[15].toInt())
        if (selected == Mode.Sleep) mixer.addView(button("導入時間　${store.intro}分") {
            choose("導入時間（次回の再生から）", introOptions.map { "${it}分" }, introOptions.indexOf(store.intro)) {
                store.intro = introOptions[it]
                rebuildMixer()
            }
        })
        val timer = store.timer(selected)
        mixer.addView(button("終了タイマー　${if (timer == 0) "なし" else "${timer}分"}") {
            choose("終了タイマー", timerOptions.map { if (it == 0) "なし" else "${it}分" }, timerOptions.indexOf(timer)) {
                val minutes = timerOptions[it]
                store.timer(selected, minutes)
                PlaybackService.current?.takeIf { it.mode == selected }?.timer(minutes)
                rebuildMixer()
            }
        })
        details.addView(button(if (expanded) "追加レイヤーを閉じる" else "追加レイヤーを開く") {
            expanded = !expanded
            rebuildMixer()
        })
        if (!expanded) return
        val titles = listOf("低音ビート", "テンポ", "アタック", "タイトさ", "さざなみ", "Lo-fiクラックル",
            "鳥のさえずり", "ざわつき", "イベントの多動度", "左右の揺れ幅", "左右移動の速さ", "クラックルの柔らかさ")
        titles.forEachIndexed { i, title ->
            if (i > 0) details.divider()
            slider(details, title, i + 3, values[i + 3].toInt(), if (i == 1) 6 else 0, if (i == 1) " BPM" else "%")
        }
        details.addView(button("ビート　${patterns[values[16].toInt()]}") {
            choose("ビートパターン", patterns, values[16].toInt()) {
                update(16, it.toDouble())
                rebuildMixer()
            }
        })
        details.addView(button("実験モード　${if (values[17] > 0) "オン" else "オフ"}") {
            update(17, if (values[17] > 0) 0.0 else 1.0)
            rebuildMixer()
        })
    }

    private fun slider(container: LinearLayout, title: String, index: Int, value: Int, min: Int = 0, unit: String = "%") {
        val amount = text("$value$unit", 15f, quiet)
        container.addView(row().apply {
            setPadding(0, dp(8), 0, 0)
            addView(text(title), LinearLayout.LayoutParams(0, -2, 1f))
            addView(amount)
        })
        val bar = SeekBar(this).apply {
            this.min = min
            max = 100
            progress = value
            keyProgressIncrement = 1
            thumb = null
            splitTrack = false
            tickMark = null
            background = null
            setPadding(0, 0, 0, 0)
            minimumHeight = dp(48)
            val track = this@MainActivity.background(border, 1).apply { setSize(dp(1), dp(4)) }
            val fill = this@MainActivity.background(accent, 1).apply { setSize(dp(1), dp(4)) }
            progressDrawable = LayerDrawable(arrayOf(track, ClipDrawable(fill, Gravity.START, ClipDrawable.HORIZONTAL))).apply {
                setId(0, android.R.id.background)
                setId(1, android.R.id.progress)
                for (layer in 0..1) {
                    setLayerHeight(layer, dp(4))
                    setLayerGravity(layer, Gravity.FILL_HORIZONTAL or Gravity.CENTER_VERTICAL)
                }
            }
            progress = min
            progress = value
            contentDescription = title
            setOnFocusChangeListener { view, focused ->
                view.background = if (focused) this@MainActivity.background(Color.TRANSPARENT, 6, accent) else null
            }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    amount.text = "$progress$unit"
                    if (fromUser) update(index, progress.toDouble())
                }
            })
        }
        container.addView(bar, LinearLayout.LayoutParams(-1, dp(48)))
    }

    private fun update(index: Int, value: Double) {
        store.value(selected, index, value)
        PlaybackService.current?.takeIf { it.mode == selected }?.adjust(index, value)
    }

    private fun choose(title: String, items: List<String>, selected: Int, action: (Int) -> Unit) {
        AlertDialog.Builder(this).setTitle(title).setSingleChoiceItems(items.toTypedArray(), selected) { dialog, choice ->
            dialog.dismiss()
            action(choice)
        }.setNegativeButton("閉じる", null).show()
    }

    private fun changeMode(mode: Mode) {
        if (mode == selected) return
        val wasPlaying = PlaybackService.current?.playing == true
        selected = mode
        store.mode = mode
        feedback = null
        buildPage()
        if (wasPlaying) queue(mode) else PlaybackService.current?.end()
    }

    private fun togglePlayback() {
        val service = PlaybackService.current
        when {
            service?.active == true && service.mode == selected && service.playing -> service.pausePlayback()
            service?.active == true && service.mode == selected -> service.resumePlayback()
            else -> queue(selected)
        }
    }

    private fun queue(mode: Mode, record: JSONObject? = null) {
        waitingUntil = android.os.SystemClock.uptimeMillis() + 1600
        val service = PlaybackService.current
        if (service != null) {
            pending = mode
            resumeRecord = record
            service.end()
        } else launch(mode, record)
    }

    private fun launch(mode: Mode, record: JSONObject? = null) {
        val intent = Intent(this, PlaybackService::class.java).setAction(PlaybackService.PLAY).putExtra("mode", mode.key)
        if (record != null) {
            val values = store.values(mode)
            val mix = record.getJSONArray("mix")
            val addons = record.getJSONObject("addons")
            (0..2).forEach { values[it] = mix.optDouble(it, values[it]) }
            layerKeys.forEachIndexed { i, key -> values[i+3] = addons.optDouble(key, values[i+3]) }
            values[15] = record.optDouble("volume", values[15])
            values[16] = patterns.indexOf(addons.optString("pattern")).coerceAtLeast(0).toDouble()
            values[17] = if (addons.optBoolean("experimental")) 1.0 else 0.0
            intent.putExtra("values", values).putExtra("seed", record.getLong("seed"))
                .putExtra("intro", record.optInt("introSeconds") / 60)
        }
        startForegroundService(intent)
    }

    private fun showHistory() {
        val records = store.history().asReversed()
        val positive = records.count { it.optInt("sign") > 0 }
        val title = "履歴　好き $positive 件・苦手 ${records.size - positive} 件"
        val labels = records.map {
            val mode = Mode.from(it.optString("mode"))
            val rating = if (it.optInt("sign") > 0) "好き" else "苦手"
            "${mode.title}　$rating　${it.optString("time").take(16).replace('T', ' ')}"
        }
        val dialog = AlertDialog.Builder(this).setTitle(title)
            .setNeutralButton("JSONを書き出す") { _, _ ->
                @Suppress("DEPRECATION")
                startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/json"
                    putExtra(Intent.EXTRA_TITLE, "noizient.json")
                }, 1)
            }.setNegativeButton("閉じる", null)
        if (records.isEmpty()) dialog.setMessage("評価はまだありません。")
        else dialog.setItems(labels.toTypedArray()) { _, index -> editRating(records[index]) }
        dialog.show()
    }

    private fun editRating(record: JSONObject) {
        val form = column().apply { setPadding(dp(20), 0, dp(20), dp(16)) }
        val reason = EditText(this).apply { hint = "理由"; setText(record.optString("reason")) }
        val memo = EditText(this).apply { hint = "メモ"; setText(record.optString("memo")) }
        form.addView(reason)
        form.addView(memo)
        val scopes = listOf("recent", "now", "session")
        var scope = scopes.indexOf(record.optString("scope")).coerceAtLeast(0)
        val scopeNames = listOf("直近120秒", "現在", "セッション全体")
        val scopeButton = button(scopeNames[scope]) {}
        scopeButton.setOnClickListener {
            choose("評価対象", scopeNames, scope) { choice ->
                scope = choice
                scopeButton.text = scopeNames[choice]
            }
        }
        form.addView(scopeButton)
        val events = record.optJSONArray("recentEvents")
        form.addView(text("経過 ${clock(record.optDouble("elapsed", 0.0))}　·　シード ${record.optLong("seed")}", 14f))
        form.addView(text("配合 ${record.optJSONArray("mix")}", 14f))
        form.addView(text(record.optJSONObject("addons")?.toString(2).orEmpty(), 13f))
        for (i in 0 until (events?.length() ?: 0)) {
            val event = events?.optJSONObject(i) ?: continue
            form.addView(text("${clock(event.optDouble("time", 0.0))}　${event.optString("logic")}", 14f))
        }
        form.addView(button("同じ配合・シードで再生") {
            selected = Mode.from(record.optString("mode"))
            store.mode = selected
            buildPage()
            queue(selected, record)
        })
        val id = record.optString("id")
        AlertDialog.Builder(this).setTitle(if (record.optInt("sign") > 0) "好き" else "苦手")
            .setView(ScrollView(this).apply { addView(form) })
            .setNegativeButton("閉じる", null)
            .setNeutralButton("削除") { _, _ ->
                AlertDialog.Builder(this).setMessage("この評価を削除しますか？").setNegativeButton("キャンセル", null)
                    .setPositiveButton("削除") { _, _ -> store.saveHistory(store.history().filter { it.optString("id") != id }) }.show()
            }
            .setPositiveButton("保存") { _, _ ->
                record.put("reason", reason.text.toString().trim()).put("memo", memo.text.toString().trim()).put("scope", scopes[scope])
                store.saveHistory(store.history().map { if (it.optString("id") == id) record else it })
            }.show()
    }

    @Deprecated("Uses the platform document picker callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 1 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        runCatching {
            checkNotNull(contentResolver.openOutputStream(uri)).bufferedWriter().use { it.write(store.export().toString(2)) }
        }.onFailure {
            AlertDialog.Builder(this).setMessage("JSONを書き出せませんでした。").setPositiveButton("閉じる", null).show()
        }
    }
}
