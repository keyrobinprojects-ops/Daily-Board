package com.byawarta.dailyboardphone

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.database.Cursor
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.sin

class MainActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("daily_board", MODE_PRIVATE) }
    private val density by lazy { resources.displayMetrics.density }

    private fun dp(value: Int): Int = (value * density).toInt()

    private lateinit var root: FrameLayout
    private lateinit var photoCard: ImageView
    private lateinit var clockBlock: LinearLayout
    private lateinit var tvTime: TextView
    private lateinit var tvDate: TextView
    private lateinit var tvEvent: TextView
    private lateinit var tvBattery: TextView
    private lateinit var batteryIcon: BatteryView
    private lateinit var albumArt: ImageView
    private lateinit var tvSongTitle: TextView
    private lateinit var tvArtist: TextView
    private lateinit var btnPrev: ImageButton
    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnNext: ImageButton
    private lateinit var wave: WaveView
    private lateinit var btnOpenMenu: TextView
    private lateinit var menuPanel: ScrollView
    private lateinit var btnPhoto: Button
    private lateinit var btnPosition: Button
    private lateinit var btnPhotoPos: Button
    private lateinit var btnOrient: Button
    private lateinit var btnNotif: Button
    private lateinit var btnCal: Button
    private lateinit var btnDone: Button
    private lateinit var seekSize: SeekBar

    private var activeController: MediaController? = null
    private var isPlaying = false

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault())
    private val eventFormat = SimpleDateFormat("EEE HH:mm", Locale.getDefault())

    private val positions: List<Pair<String, Int>> = listOf(
        "Top left" to (Gravity.TOP or Gravity.START),
        "Top center" to (Gravity.TOP or Gravity.CENTER_HORIZONTAL),
        "Top right" to (Gravity.TOP or Gravity.END),
        "Middle left" to (Gravity.CENTER_VERTICAL or Gravity.START),
        "Center" to Gravity.CENTER,
        "Middle right" to (Gravity.CENTER_VERTICAL or Gravity.END),
        "Bottom left" to (Gravity.BOTTOM or Gravity.START),
        "Bottom center" to (Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL),
        "Bottom right" to (Gravity.BOTTOM or Gravity.END)
    )
    private var posIndex = 0

    // Photo side: 0 = right, 1 = center, 2 = left
    private val photoSides = listOf("Right", "Center", "Left")
    private var photoSide = 0

    private val pickPhoto = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {
                // Some providers don't support persistable permissions
            }
            prefs.edit().putString("photo_uri", uri.toString()).apply()
            loadPhoto()
        }
    }

    private val calPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) loadNextEvent()
        updateButtons()
    }

    private val clockTick = object : Runnable {
        override fun run() {
            val now = Date()
            tvTime.text = timeFormat.format(now)
            tvDate.text = dateFormat.format(now)
            handler.postDelayed(this, 1000)
        }
    }

    private val mediaTick = object : Runnable {
        override fun run() {
            updateNowPlaying()
            handler.postDelayed(this, 1000)
        }
    }

    private val eventTick = object : Runnable {
        override fun run() {
            loadNextEvent()
            handler.postDelayed(this, 5 * 60 * 1000L)
        }
    }

    // Hides the X button again after a few seconds
    private val hideMenuButton = Runnable {
        btnOpenMenu.visibility = View.GONE
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val percent = if (level >= 0 && scale > 0) level * 100 / scale else 0
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
            tvBattery.text = "$percent%"
            batteryIcon.setBattery(percent, charging)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        hideSystemBars()

        root = findViewById(R.id.rootLayout)
        photoCard = findViewById(R.id.photoCard)
        clockBlock = findViewById(R.id.clockBlock)
        tvTime = findViewById(R.id.tvTime)
        tvDate = findViewById(R.id.tvDate)
        tvEvent = findViewById(R.id.tvEvent)
        tvBattery = findViewById(R.id.tvBattery)
        batteryIcon = findViewById(R.id.batteryIcon)
        albumArt = findViewById(R.id.albumArt)
        tvSongTitle = findViewById(R.id.tvSongTitle)
        tvArtist = findViewById(R.id.tvArtist)
        btnPrev = findViewById(R.id.btnPrev)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        btnNext = findViewById(R.id.btnNext)
        wave = findViewById(R.id.wave)
        btnOpenMenu = findViewById(R.id.btnOpenMenu)
        menuPanel = findViewById(R.id.menuPanel)
        btnPhoto = findViewById(R.id.btnPhoto)
        btnPosition = findViewById(R.id.btnPosition)
        btnPhotoPos = findViewById(R.id.btnPhotoPos)
        btnOrient = findViewById(R.id.btnOrient)
        btnNotif = findViewById(R.id.btnNotif)
        btnCal = findViewById(R.id.btnCal)
        btnDone = findViewById(R.id.btnDone)
        seekSize = findViewById(R.id.seekSize)

        photoCard.clipToOutline = true
        photoCard.outlineProvider = roundOutline(dp(32).toFloat())
        albumArt.clipToOutline = true
        albumArt.outlineProvider = roundOutline(dp(14).toFloat())

        posIndex = prefs.getInt("pos", 0)
        photoSide = prefs.getInt("photo_side", 0)
        applyClockPosition()
        applyOrientation()
        loadPhoto()

        seekSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    prefs.edit().putInt("size", progress).apply()
                    applyPhotoSize()
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        seekSize.progress = prefs.getInt("size", 60)

        // Tap the screen: show the small X for a few seconds
        root.setOnClickListener {
            if (menuPanel.visibility == View.VISIBLE) return@setOnClickListener
            btnOpenMenu.visibility = View.VISIBLE
            handler.removeCallbacks(hideMenuButton)
            handler.postDelayed(hideMenuButton, 3000)
        }

        // Press the X: open the settings menu
        btnOpenMenu.setOnClickListener {
            handler.removeCallbacks(hideMenuButton)
            btnOpenMenu.visibility = View.GONE
            menuPanel.visibility = View.VISIBLE
        }

        btnDone.setOnClickListener {
            menuPanel.visibility = View.GONE
        }

        btnPlayPause.setOnClickListener {
            val tc = activeController?.transportControls ?: return@setOnClickListener
            if (isPlaying) tc.pause() else tc.play()
        }
        btnNext.setOnClickListener { activeController?.transportControls?.skipToNext() }
        btnPrev.setOnClickListener { activeController?.transportControls?.skipToPrevious() }

        btnPhoto.setOnClickListener {
            pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }

        btnPosition.setOnClickListener {
            posIndex = (posIndex + 1) % positions.size
            prefs.edit().putInt("pos", posIndex).apply()
            applyClockPosition()
        }

        btnPhotoPos.setOnClickListener {
            photoSide = (photoSide + 1) % photoSides.size
            prefs.edit().putInt("photo_side", photoSide).apply()
            applyPhotoSize()
            updatePhotoSideButton()
        }

        btnOrient.setOnClickListener {
            val portrait = !prefs.getBoolean("portrait", false)
            prefs.edit().putBoolean("portrait", portrait).apply()
            applyOrientation()
        }

        btnNotif.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        btnCal.setOnClickListener {
            calPermission.launch(Manifest.permission.READ_CALENDAR)
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        handler.post(clockTick)
        handler.post(mediaTick)
        handler.post(eventTick)
        updateButtons()
        updatePhotoSideButton()
        applyPhotoSize()
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(batteryReceiver)
        handler.removeCallbacksAndMessages(null)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        hideSystemBars()
        applyPhotoSize()
    }

    // Hides the status bar and navigation buttons. They come back only if you swipe from the edge.
    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun roundOutline(radius: Float) = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, radius)
        }
    }

    private fun applyOrientation() {
        val portrait = prefs.getBoolean("portrait", false)
        requestedOrientation = if (portrait) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        btnOrient.text = if (portrait) "Orientation: Portrait" else "Orientation: Landscape"
        applyPhotoSize()
    }

    private fun applyClockPosition() {
        val (name, gravity) = positions[posIndex]
        clockBlock.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            gravity
        ).apply { setMargins(dp(56), dp(56), dp(56), dp(56)) }
        clockBlock.gravity = gravity and Gravity.HORIZONTAL_GRAVITY_MASK
        btnPosition.text = "Clock position: $name"
    }

    private fun updatePhotoSideButton() {
        btnPhotoPos.text = "Photo position: ${photoSides[photoSide]}"
    }

    // Keeps the photo's own proportions so nothing is cropped
    private fun applyPhotoSize() {
        root.post {
            if (root.height == 0) return@post
            val fraction = 0.3f + (seekSize.progress / 100f) * 0.7f
            val h = (root.height * 0.8f * fraction).toInt()
            val drawable = photoCard.drawable
            val ratio = if (drawable != null && drawable.intrinsicHeight > 0) {
                drawable.intrinsicWidth.toFloat() / drawable.intrinsicHeight
            } else {
                0.75f
            }
            val w = (h * ratio).toInt()

            val gravity: Int
            var left = 0
            var right = 0
            when (photoSide) {
                0 -> { gravity = Gravity.CENTER_VERTICAL or Gravity.END; right = dp(56) }
                1 -> { gravity = Gravity.CENTER; }
                else -> { gravity = Gravity.CENTER_VERTICAL or Gravity.START; left = dp(56) }
            }
            photoCard.layoutParams = FrameLayout.LayoutParams(w, h, gravity).apply {
                setMargins(left, 0, right, 0)
            }
        }
    }

    private fun loadPhoto() {
        val saved = prefs.getString("photo_uri", null)
        if (saved == null) {
            photoCard.visibility = View.GONE
            return
        }
        photoCard.visibility = View.VISIBLE
        try {
            photoCard.setImageURI(Uri.parse(saved))
        } catch (e: SecurityException) {
            photoCard.setImageDrawable(null)
        }
        applyPhotoSize()
    }

    private fun hasNotificationAccess(): Boolean {
        val nm = getSystemService(NotificationManager::class.java)
        return nm.isNotificationListenerAccessGranted(
            ComponentName(this, MediaListenerService::class.java)
        )
    }

    private fun updateButtons() {
        val calOk = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR) ==
                PackageManager.PERMISSION_GRANTED
        btnCal.text = if (calOk) "Calendar: on" else "Allow calendar"
        btnNotif.text = if (hasNotificationAccess()) "Song info: on" else "Allow song info"
        if (!calOk) tvEvent.text = ""
    }

    private fun loadNextEvent() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR) ==
                PackageManager.PERMISSION_GRANTED
        if (!granted) {
            tvEvent.text = ""
            return
        }
        val now = System.currentTimeMillis()
        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, now)
        ContentUris.appendId(builder, now + 7L * 24 * 60 * 60 * 1000)

        val projection = arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN)
        try {
            contentResolver.query(
                builder.build(),
                projection,
                null,
                null,
                "${CalendarContract.Instances.BEGIN} ASC"
            )?.use { c: Cursor ->
                if (c.moveToFirst()) {
                    val title = c.getString(0) ?: "Event"
                    val begin = c.getLong(1)
                    tvEvent.text = "Next: $title  ·  ${eventFormat.format(Date(begin))}"
                } else {
                    tvEvent.text = "No events this week"
                }
            }
        } catch (e: SecurityException) {
            tvEvent.text = ""
        }
    }

    private fun updateNowPlaying() {
        if (!hasNotificationAccess()) {
            activeController = null
            tvSongTitle.text = "Turn on song info"
            tvArtist.text = "Menu > Allow song info"
            albumArt.visibility = View.GONE
            wave.setPlaying(false)
            setPlayButton(false)
            return
        }

        try {
            val msm = getSystemService(MediaSessionManager::class.java)
            val controllers = msm.getActiveSessions(
                ComponentName(this, MediaListenerService::class.java)
            )
            val controller = controllers.firstOrNull {
                it.playbackState?.state == PlaybackState.STATE_PLAYING
            } ?: controllers.firstOrNull()

            activeController = controller
            val meta = controller?.metadata
            if (controller == null || meta == null) {
                tvSongTitle.text = "Nothing playing"
                tvArtist.text = ""
                albumArt.visibility = View.GONE
                wave.setPlaying(false)
                setPlayButton(false)
                return
            }

            val playing = controller.playbackState?.state == PlaybackState.STATE_PLAYING
            tvSongTitle.text = meta.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Unknown title"
            tvArtist.text = meta.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: meta.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: controller.packageName

            val art = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
            if (art != null) {
                albumArt.setImageBitmap(art)
                albumArt.visibility = View.VISIBLE
            } else {
                albumArt.visibility = View.GONE
            }
            wave.setPlaying(playing)
            setPlayButton(playing)
        } catch (e: SecurityException) {
            tvSongTitle.text = "Turn on song info"
            tvArtist.text = ""
        }
    }

    private fun setPlayButton(playing: Boolean) {
        isPlaying = playing
        btnPlayPause.setImageResource(
            if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        )
    }
}

/**
 * Battery icon drawn in code. Shows the charge level and a bolt while charging.
 */
class BatteryView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val d = resources.displayMetrics.density
    private var level = 0
    private var charging = false

    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * d
        color = 0xB3FFFFFF.toInt()
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFFFFFFF.toInt()
    }
    private val boltPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF000000.toInt()
    }
    private val body = RectF()
    private val nub = RectF()
    private val fillRect = RectF()
    private val boltPath = Path()

    fun setBattery(percent: Int, isCharging: Boolean) {
        level = percent.coerceIn(0, 100)
        charging = isCharging
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = 1.5f * d
        val nubW = 2.5f * d

        body.set(pad, pad, w - nubW - pad, h - pad)
        canvas.drawRoundRect(body, 4f * d, 4f * d, outlinePaint)

        nub.set(w - nubW, h * 0.3f, w, h * 0.7f)
        canvas.drawRoundRect(nub, d, d, fillPaint)

        val inset = 3f * d
        val maxWidth = body.width() - inset * 2
        fillRect.set(
            body.left + inset,
            body.top + inset,
            body.left + inset + maxWidth * level / 100f,
            body.bottom - inset
        )
        if (fillRect.width() > 0f) {
            canvas.drawRoundRect(fillRect, 1.5f * d, 1.5f * d, fillPaint)
        }

        if (charging) {
            val s = body.height() * 0.85f
            val cx = body.centerX()
            val cy = body.centerY()
            boltPath.reset()
            boltPath.moveTo(cx + s * 0.15f, cy - s * 0.5f)
            boltPath.lineTo(cx - s * 0.3f, cy + s * 0.1f)
            boltPath.lineTo(cx, cy + s * 0.1f)
            boltPath.lineTo(cx - s * 0.15f, cy + s * 0.5f)
            boltPath.lineTo(cx + s * 0.3f, cy - s * 0.1f)
            boltPath.lineTo(cx, cy - s * 0.1f)
            boltPath.close()
            canvas.drawPath(boltPath, boltPaint)
        }
    }
}

/**
 * Animated wavy lines. Moves while music plays and goes flat when paused.
 */
class WaveView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private var playing = false
    private var phase = 0f
    private var amp = 0f
    private val path = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
    }
    private val colors = intArrayOf(0xFFFFFFFF.toInt(), 0x99FFFFFF.toInt(), 0x55FFFFFF.toInt())

    fun setPlaying(value: Boolean) {
        playing = value
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val target = if (playing) height * 0.36f else height * 0.05f
        amp += (target - amp) * 0.1f
        phase += if (playing) 0.12f else 0.02f

        val midY = height / 2f
        val steps = 40
        for (i in 0 until 3) {
            paint.color = colors[i]
            path.reset()
            val freq = 1.5f + i * 0.6f
            val offset = phase + i * 1.3f
            for (s in 0..steps) {
                val x = width * s / steps.toFloat()
                val angle = (x / width * freq * 2 * Math.PI + offset).toDouble()
                val y = midY + amp * (1f - i * 0.25f) * sin(angle).toFloat()
                if (s == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            canvas.drawPath(path, paint)
        }
        postInvalidateOnAnimation()
    }
}

/**
 * Quick Settings tile. Tap it to open Daily Board.
 */
class DailyBoardTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = "Daily Board"
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pending = PendingIntent.getActivity(
                this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}

/**
 * Lets the app read the active media sessions. Does nothing else.
 */
class MediaListenerService : NotificationListenerService()