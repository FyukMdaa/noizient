package dev.noizient

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID

class PlaybackService : Service() {
    companion object {
        internal var current: PlaybackService? = null
            private set
        internal var error: String? = null
            private set
        internal const val PLAY = "dev.noizient.PLAY"
        private const val PAUSE = "dev.noizient.PAUSE"
        private const val STOP = "dev.noizient.STOP"
        private const val CHANNEL = "playback"
        private val LOGIC_IDS = arrayOf("beat.tight-kick", "texture.surf-swell", "texture.lofi-grain-cloud", "texture.bird-cloud")
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var store: Store
    private lateinit var session: MediaSession
    private lateinit var manager: AudioManager
    private lateinit var focus: AudioFocusRequest
    private lateinit var wakeLock: PowerManager.WakeLock
    private var handle = 0L
    private var seed = 0L
    private var intro = 10
    private var resumeAfterFocus = false
    private var ending = false
    private var hasFocus = false
    private var timerDeadline = 0.0
    private var recordingPhase = ""
    private val eventsBuffer = DoubleArray(240 * 4)
    private val recent = ArrayDeque<JSONObject>()
    internal var mode = Mode.Focus
        private set
    internal var playing = false
        private set
    internal var values = mode.defaults.copyOf()
        private set
    internal val active get() = handle != 0L && !ending
    internal val elapsed get() = if (handle == 0L) 0.0 else Native.status(handle).coerceAtLeast(0) / 1000.0
    internal val inIntro get() = elapsed < intro * 60
    internal val remaining get() = if (timerDeadline > 0) (timerDeadline - elapsed).coerceAtLeast(0.0) else null

    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pausePlayback()
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (handle == 0L || ending) return
            when (Native.status(handle)) {
                -1 -> {
                    error = "音声出力が切断されました。再生し直してください。"
                    end()
                    return
                }
                -2 -> { end(); return }
            }
            collect()
            val phase = if (mode == Mode.Focus) "continuous" else if (elapsed < intro * 60) "intro" else "background"
            if (phase != recordingPhase) {
                recordingPhase = phase
                mark("state.$phase", phase)
            }
            handler.postDelayed(this, 150)
        }
    }

    @Suppress("UnspecifiedRegisterReceiverFlag")
    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        manager = getSystemService(AudioManager::class.java)
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "noizient:playback").apply { setReferenceCounted(false) }
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes).setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener({ change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS -> {
                        hasFocus = false
                        pausePlayback()
                    }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                        val previouslyPlaying = playing
                        pausePlayback(releaseFocus = false)
                        resumeAfterFocus = previouslyPlaying
                    }
                    AudioManager.AUDIOFOCUS_GAIN -> if (resumeAfterFocus) {
                        hasFocus = true
                        resumeAfterFocus = false
                        resumePlayback()
                    }
                }
            }, handler).build()
        session = MediaSession(this, "noizient").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = resumePlayback()
                override fun onPause() = pausePlayback()
                override fun onStop() = end()
            }, handler)
            setSessionActivity(activityIntent())
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "noizient 再生", NotificationManager.IMPORTANCE_LOW)
        )
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(noisy, filter, RECEIVER_NOT_EXPORTED)
        else registerReceiver(noisy, filter)
        current = this
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent.action) {
            STOP -> end()
            PAUSE -> pausePlayback()
            PLAY -> when {
                ending -> Unit
                active -> resumePlayback()
                else -> startPlayback(intent)
            }
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun activityIntent() = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun serviceIntent(action: String) = PendingIntent.getService(
        this, action.hashCode(), Intent(this, PlaybackService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE
    )

    private fun notification(): Notification = Notification.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_audio).setContentTitle("noizient").setContentText(mode.title)
        .setContentIntent(activityIntent()).setOnlyAlertOnce(true)
        .setVisibility(Notification.VISIBILITY_PUBLIC)
        .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1))
        .addAction(Notification.Action.Builder(
            if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
            if (playing) "一時停止" else "再生", serviceIntent(if (playing) PAUSE else PLAY)
        ).build())
        .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "停止", serviceIntent(STOP)).build())
        .build()

    // Media session notifications are exempt from the Android 13 notification permission.
    @Suppress("NotificationPermission")
    private fun publish() {
        session.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP)
            .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                (elapsed * 1000).toLong(), if (playing) 1f else 0f, SystemClock.elapsedRealtime()).build())
        getSystemService(NotificationManager::class.java).notify(1, notification())
    }

    private fun startPlayback(intent: Intent) {
        mode = Mode.from(intent.getStringExtra("mode") ?: store.mode.key)
        intro = store.intro
        error = null
        session.setMetadata(MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, "noizient")
            .putString(MediaMetadata.METADATA_KEY_ARTIST, mode.title).build())
        session.isActive = true
        startForeground(1, notification())
        if (!requestFocus()) {
            error = "オーディオフォーカスを取得できませんでした。"
            end()
            return
        }
        seed = if (intent.hasExtra("seed")) intent.getLongExtra("seed", 0)
            else SecureRandom().nextInt().toLong() and 0xffffffffL
        values = intent.getDoubleArrayExtra("values")?.takeIf { it.size == mode.defaults.size }
            ?: chooseMix(store.values(mode), mode, store.history(), seed)
        intro = intent.getIntExtra("intro", intro).takeIf { it in introOptions } ?: intro
        val timer = store.timer(mode) * 60
        timerDeadline = timer.toDouble()
        handle = runCatching {
            Native.open(Native.load(assets), mode == Mode.Sleep, seed, intro * 60, values, timer)
        }.getOrDefault(0)
        if (handle == 0L) {
            error = "ステレオ音声を開始できませんでした。"
            end()
            return
        }
        playing = true
        wakeLock.acquire()
        mark("session.start", JSONArray(values.toList()))
        publish()
        handler.post(ticker)
    }

    private fun requestFocus(): Boolean {
        if (!hasFocus) hasFocus = manager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return hasFocus
    }

    internal fun resumePlayback() {
        if (!active || playing || !requestFocus()) return
        playing = true
        wakeLock.acquire()
        Native.pause(handle, false)
        mark("transport.resume", true)
        publish()
    }

    internal fun pausePlayback() = pausePlayback(releaseFocus = true)

    private fun pausePlayback(releaseFocus: Boolean) {
        resumeAfterFocus = false
        if (releaseFocus && hasFocus) {
            manager.abandonAudioFocusRequest(focus)
            hasFocus = false
        }
        if (!active || !playing) return
        playing = false
        Native.pause(handle, true)
        mark("transport.pause", true)
        if (wakeLock.isHeld) wakeLock.release()
        publish()
    }

    internal fun end() {
        if (ending) return
        ending = true
        resumeAfterFocus = false
        playing = false
        handler.removeCallbacks(ticker)
        if (handle == 0L) {
            stopSelf()
        } else {
            Native.pause(handle, true)
            publish()
            handler.postDelayed({
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }, 320)
        }
    }

    internal fun adjust(index: Int, value: Double) {
        if (!active) return
        values[index] = value
        Native.configure(handle, values)
        mark("control." + when (index) {
            in 0..2 -> "mix-$index"
            in 3..14 -> layerKeys[index-3]
            15 -> "volume"
            16 -> "pattern"
            else -> "experimental"
        }, value, coalesce = true)
    }

    internal fun timer(minutes: Int) {
        if (!active) return
        val seconds = minutes * 60
        timerDeadline = if (seconds > 0) elapsed + seconds else 0.0
        Native.timer(handle, seconds)
        mark("control.timer", seconds)
    }

    private fun collect() {
        val count = Native.events(handle, eventsBuffer)
        repeat(count) { index ->
            val offset = index * 4
            val id = eventsBuffer[offset + 1].toInt()
            addEvent(JSONObject().put("time", eventsBuffer[offset]).put("logic", LOGIC_IDS[id])
                .put(if (id == 0) "strength" else "durationSeconds", eventsBuffer[offset + 2])
                .put(if (id == 0) "periodSeconds" else "panPosition", eventsBuffer[offset + 3]))
        }
    }

    private fun addEvent(event: JSONObject, coalesce: Boolean = false) {
        if (coalesce && recent.lastOrNull()?.let {
                it.optString("logic") == event.optString("logic") &&
                    event.optDouble("time") - it.optDouble("time") < .12
            } == true) recent.removeLast()
        recent.addLast(event)
        if (recent.size > 240) recent.removeFirst()
    }

    private fun mark(logic: String, value: Any, coalesce: Boolean = false) {
        addEvent(JSONObject().put("time", elapsed).put("logic", logic).put("value", value), coalesce)
    }

    internal fun rating(sign: Int): Boolean {
        if (!active) return false
        collect()
        val seconds = elapsed
        val addons = JSONObject()
        layerKeys.forEachIndexed { index, key -> addons.put(key, values[index+3]) }
        addons.put("pattern", patterns[values[16].toInt()]).put("experimental", values[17] != 0.0)
        val record = JSONObject().put("id", UUID.randomUUID().toString()).put("time", Instant.now().toString())
            .put("mode", mode.key).put("sign", sign).put("engine", mode.engine)
            .put("overlayEngine", "ambient-overlay-v0.7").put("seed", seed).put("elapsed", seconds)
            .put("mix", JSONArray(values.take(3))).put("addons", addons).put("volume", values[15])
            .put("introSeconds", intro * 60).put("sourcePosition", sourcePosition(mode, seconds, intro))
            .put("phase", recordingPhase).put("scope", "recent").put("windowSeconds", 120)
            .put("recentEvents", JSONArray(recent.filter { it.optDouble("time") >= seconds - 120 }.sortedBy { it.optDouble("time") }))
            .put("reason", "").put("memo", "")
        store.saveHistory(store.history() + record)
        return true
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (handle != 0L) Native.close(handle)
        if (hasFocus) manager.abandonAudioFocusRequest(focus)
        if (wakeLock.isHeld) wakeLock.release()
        session.release()
        unregisterReceiver(noisy)
        if (current === this) current = null
        super.onDestroy()
    }
}
