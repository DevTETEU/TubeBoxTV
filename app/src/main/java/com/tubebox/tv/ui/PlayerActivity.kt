package com.tubebox.tv.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tubebox.tv.R
import com.tubebox.tv.data.OkHttpDownloader
import com.tubebox.tv.data.PlaybackBundle
import com.tubebox.tv.data.StreamOption
import com.tubebox.tv.data.VideoItem
import com.tubebox.tv.data.WatchHistoryStore
import com.tubebox.tv.data.YouTubeRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_VIDEO_URL = "extra_video_url"
        const val EXTRA_VIDEO_TITLE = "extra_video_title"
        const val EXTRA_VIDEO_UPLOADER = "extra_video_uploader"
        const val EXTRA_VIDEO_THUMB = "extra_video_thumb"

        private const val SEEK_STEP_MS = 10_000L
        private const val HUD_TIMEOUT_MS = 4_500L
    }

    private lateinit var playerRoot: View
    private lateinit var playerView: PlayerView
    private lateinit var layoutLoading: View
    private lateinit var tvLoadingText: TextView
    private lateinit var tvSeekFeedback: TextView
    private lateinit var layoutError: View
    private lateinit var tvPlayerError: TextView
    private lateinit var btnPlayerRetry: TextView
    private lateinit var layoutHud: View
    private lateinit var tvVideoTitle: TextView
    private lateinit var tvVideoChannel: TextView
    private lateinit var tvPosition: TextView
    private lateinit var tvDurationTotal: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var btnRewind: TextView
    private lateinit var btnPlayPause: TextView
    private lateinit var btnForward: TextView
    private lateinit var btnQuality: TextView
    private lateinit var btnRelated: TextView
    private lateinit var layoutRelatedDrawer: View
    private lateinit var rvRelated: RecyclerView

    private lateinit var relatedAdapter: RelatedVideoAdapter
    private lateinit var historyStore: WatchHistoryStore

    private var player: ExoPlayer? = null
    private var currentVideoUrl: String = ""
    private var currentBundle: PlaybackBundle? = null
    private var currentStreamIndex: Int = 0
    private var fallbackTriedIndices = hashSetOf<Int>()
    private var usedInnerTubeFallback = false
    private var loadJob: Job? = null
    private var isSeekingFromUser = false

    private val handler = Handler(Looper.getMainLooper())

    private val hideHudRunnable = Runnable {
        if (player?.isPlaying == true && layoutRelatedDrawer.visibility != View.VISIBLE) {
            layoutHud.visibility = View.GONE
        }
    }

    private val hideFeedbackRunnable = Runnable {
        tvSeekFeedback.visibility = View.GONE
    }

    private val progressUpdater = object : Runnable {
        override fun run() {
            updateProgressUi()
            handler.postDelayed(this, 500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        enterImmersiveMode()

        historyStore = WatchHistoryStore(this)
        bindViews()
        setupControls()
        initPlayer()

        val initialUrl = intent.getStringExtra(EXTRA_VIDEO_URL).orEmpty()
        val initialTitle = intent.getStringExtra(EXTRA_VIDEO_TITLE).orEmpty()
        val initialUploader = intent.getStringExtra(EXTRA_VIDEO_UPLOADER).orEmpty()

        tvVideoTitle.text = initialTitle
        tvVideoChannel.text = initialUploader

        if (initialUrl.isBlank()) {
            finish()
            return
        }

        loadVideo(initialUrl)
    }

    private fun bindViews() {
        playerRoot = findViewById(R.id.playerRoot)
        playerView = findViewById(R.id.playerView)
        layoutLoading = findViewById(R.id.layoutPlayerLoading)
        tvLoadingText = findViewById(R.id.tvLoadingText)
        tvSeekFeedback = findViewById(R.id.tvSeekFeedback)
        layoutError = findViewById(R.id.layoutPlayerError)
        tvPlayerError = findViewById(R.id.tvPlayerError)
        btnPlayerRetry = findViewById(R.id.btnPlayerRetry)
        layoutHud = findViewById(R.id.layoutHud)
        tvVideoTitle = findViewById(R.id.tvVideoTitle)
        tvVideoChannel = findViewById(R.id.tvVideoChannel)
        tvPosition = findViewById(R.id.tvPosition)
        tvDurationTotal = findViewById(R.id.tvDurationTotal)
        seekBar = findViewById(R.id.seekBar)
        btnRewind = findViewById(R.id.btnRewind)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        btnForward = findViewById(R.id.btnForward)
        btnQuality = findViewById(R.id.btnQuality)
        btnRelated = findViewById(R.id.btnRelated)
        layoutRelatedDrawer = findViewById(R.id.layoutRelatedDrawer)
        rvRelated = findViewById(R.id.rvRelated)
    }

    private fun setupControls() {
        relatedAdapter = RelatedVideoAdapter { item ->
            hideRelatedDrawer()
            historyStore.record(item)
            tvVideoTitle.text = item.title
            tvVideoChannel.text = item.uploader
            loadVideo(item.url)
        }
        rvRelated.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        rvRelated.adapter = relatedAdapter

        playerView.setOnClickListener {
            if (layoutRelatedDrawer.visibility == View.VISIBLE) {
                hideRelatedDrawer()
            } else if (layoutHud.visibility == View.VISIBLE) {
                layoutHud.visibility = View.GONE
            } else {
                showHud(focusPlayPause = false)
            }
        }

        btnPlayPause.setOnClickListener {
            togglePlayPause()
            resetHudTimer()
        }

        btnRewind.setOnClickListener {
            seekRelative(-SEEK_STEP_MS)
            resetHudTimer()
        }

        btnForward.setOnClickListener {
            seekRelative(SEEK_STEP_MS)
            resetHudTimer()
        }

        btnQuality.setOnClickListener {
            showQualityDialog()
        }

        btnRelated.setOnClickListener {
            showRelatedDrawer()
        }

        btnPlayerRetry.setOnClickListener {
            if (currentVideoUrl.isNotBlank()) {
                loadVideo(currentVideoUrl)
            }
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val duration = player?.duration ?: 0L
                    if (duration > 0) {
                        val target = (duration * progress) / 1000L
                        tvPosition.text = formatTime(target)
                    }
                    resetHudTimer()
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {
                isSeekingFromUser = true
            }

            override fun onStopTrackingTouch(sb: SeekBar?) {
                isSeekingFromUser = false
                val duration = player?.duration ?: 0L
                if (duration > 0 && sb != null) {
                    val target = (duration * sb.progress) / 1000L
                    player?.seekTo(target)
                }
                resetHudTimer()
            }
        })
    }

    private fun initPlayer() {
        // Tuned buffer for 2GB RAM TV box: fast start (1.5s) and smooth playback without OOM
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                15_000, // min buffer
                40_000, // max buffer
                1_500,  // buffer for playback start
                3_000   // buffer after rebuffer
            )
            .build()

        val exo = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .build()

        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        layoutLoading.visibility = View.VISIBLE
                        tvLoadingText.text = getString(R.string.loading_video)
                    }
                    Player.STATE_READY -> {
                        layoutLoading.visibility = View.GONE
                        layoutError.visibility = View.GONE
                        updatePlayPauseLabel()
                        updateProgressUi()
                    }
                    Player.STATE_ENDED -> {
                        layoutLoading.visibility = View.GONE
                        updatePlayPauseLabel()
                        if (currentBundle?.relatedVideos?.isNotEmpty() == true) {
                            showRelatedDrawer()
                        } else {
                            showHud(focusPlayPause = true)
                        }
                    }
                    Player.STATE_IDLE -> Unit
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updatePlayPauseLabel()
                if (isPlaying) {
                    resetHudTimer()
                } else {
                    handler.removeCallbacks(hideHudRunnable)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val bundle = currentBundle
                if (bundle != null) {
                    // Automatically try next compatible stream option before showing error
                    val nextFallback = bundle.streamOptions.indices.firstOrNull { idx ->
                        idx != currentStreamIndex && !fallbackTriedIndices.contains(idx)
                    }
                    if (nextFallback != null) {
                        val pos = exo.currentPosition.coerceAtLeast(0L)
                        playOption(bundle, nextFallback, resumePositionMs = pos)
                        return
                    }
                }
                if (!usedInnerTubeFallback && currentVideoUrl.isNotBlank()) {
                    usedInnerTubeFallback = true
                    val pos = exo.currentPosition.coerceAtLeast(0L)
                    lifecycleScope.launch {
                        val fb = YouTubeRepository.loadStreamFallbackOnly(currentVideoUrl)
                        if (fb != null && fb.streamOptions.isNotEmpty()) {
                            val merged = fb.copy(
                                relatedVideos = currentBundle?.relatedVideos.orEmpty()
                            )
                            currentBundle = merged
                            fallbackTriedIndices.clear()
                            playOption(merged, merged.defaultIndex, resumePositionMs = pos)
                        } else {
                            showErrorOverlay("Erro ao reproduzir vídeo.\n(${error.errorCodeName})")
                        }
                    }
                    return
                }
                showErrorOverlay("Erro ao reproduzir vídeo.\n(${error.errorCodeName})")
            }
        })

        player = exo
        playerView.player = exo
    }

    private fun loadVideo(videoUrl: String) {
        currentVideoUrl = videoUrl
        currentBundle = null
        fallbackTriedIndices.clear()
        usedInnerTubeFallback = false
        loadJob?.cancel()

        player?.stop()
        player?.clearMediaItems()
        layoutError.visibility = View.GONE
        layoutHud.visibility = View.GONE
        layoutRelatedDrawer.visibility = View.GONE
        layoutLoading.visibility = View.VISIBLE
        tvLoadingText.text = getString(R.string.loading_video)

        loadJob = lifecycleScope.launch {
            runCatching {
                YouTubeRepository.loadStream(videoUrl)
            }.onSuccess { bundle ->
                currentBundle = bundle
                tvVideoTitle.text = bundle.title
                tvVideoChannel.text = bundle.uploader
                relatedAdapter.submitList(bundle.relatedVideos)
                btnRelated.visibility =
                    if (bundle.relatedVideos.isNotEmpty()) View.VISIBLE else View.GONE

                // Save fresh metadata in history
                historyStore.record(
                    VideoItem(
                        url = bundle.videoUrl,
                        title = bundle.title,
                        uploader = bundle.uploader,
                        thumbnailUrl = bundle.thumbnailUrl,
                        durationSeconds = bundle.durationSeconds,
                        viewCount = -1L,
                        isLive = bundle.isLive
                    )
                )

                playOption(bundle, bundle.defaultIndex, resumePositionMs = 0L)
            }.onFailure { err ->
                val msg = err.localizedMessage?.take(140) ?: getString(R.string.error_generic)
                showErrorOverlay("Não foi possível abrir o vídeo.\n($msg)")
            }
        }
    }

    private fun showErrorOverlay(message: String) {
        handler.removeCallbacks(hideHudRunnable)
        layoutLoading.visibility = View.GONE
        layoutHud.visibility = View.GONE
        layoutRelatedDrawer.visibility = View.GONE
        layoutError.visibility = View.VISIBLE
        tvPlayerError.text = message
        btnPlayerRetry.post { btnPlayerRetry.requestFocus() }
    }

    private fun playOption(bundle: PlaybackBundle, index: Int, resumePositionMs: Long) {
        val exo = player ?: return
        val option = bundle.streamOptions.getOrNull(index) ?: return

        currentStreamIndex = index
        fallbackTriedIndices.add(index)
        btnQuality.text = "⚙ ${option.height}p"

        layoutLoading.visibility = View.VISIBLE
        layoutError.visibility = View.GONE

        val dataSourceFactory = OkHttpDataSource.Factory(OkHttpDownloader.instance.client)
            .setUserAgent(option.userAgent)

        val mediaSource = when {
            option.isHls -> {
                HlsMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(option.videoUrl))
            }
            option.audioUrl != null -> {
                val videoSrc = ProgressiveMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(option.videoUrl))
                val audioSrc = ProgressiveMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(option.audioUrl))
                MergingMediaSource(true, videoSrc, audioSrc)
            }
            else -> {
                ProgressiveMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(option.videoUrl))
            }
        }

        exo.setMediaSource(mediaSource)
        exo.prepare()
        if (resumePositionMs > 0L && !bundle.isLive) {
            exo.seekTo(resumePositionMs)
        }
        exo.playWhenReady = true
    }

    private fun showQualityDialog() {
        val bundle = currentBundle ?: return
        if (bundle.streamOptions.isEmpty()) return

        handler.removeCallbacks(hideHudRunnable)
        val labels = bundle.streamOptions.map { it.label }.toTypedArray()

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.select_quality)
            .setSingleChoiceItems(labels, currentStreamIndex) { dialog, which ->
                dialog.dismiss()
                if (which != currentStreamIndex) {
                    val pos = player?.currentPosition?.coerceAtLeast(0L) ?: 0L
                    fallbackTriedIndices.clear()
                    playOption(bundle, which, resumePositionMs = pos)
                    showFeedback(bundle.streamOptions[which].label)
                }
                resetHudTimer()
            }
            .setOnCancelListener { resetHudTimer() }
            .show()
    }

    private fun togglePlayPause() {
        val exo = player ?: return
        if (exo.playbackState == Player.STATE_ENDED) {
            exo.seekTo(0L)
            exo.playWhenReady = true
            showFeedback("▶ Reiniciando")
        } else if (exo.isPlaying) {
            exo.pause()
            showFeedback("⏸ Pausado")
        } else {
            exo.play()
            showFeedback("▶ Reproduzindo")
        }
        updatePlayPauseLabel()
    }

    private fun seekRelative(deltaMs: Long) {
        val exo = player ?: return
        val duration = exo.duration
        val target = (exo.currentPosition + deltaMs).coerceIn(
            0L,
            if (duration > 0) duration else Long.MAX_VALUE
        )
        exo.seekTo(target)
        val label = if (deltaMs > 0) "+10s ⏩" else "⏪ -10s"
        showFeedback(label)
        updateProgressUi()
    }

    private fun updatePlayPauseLabel() {
        val playing = player?.isPlaying == true
        btnPlayPause.text = if (playing) "⏸ Pausar" else "▶ Assistir"
    }

    private fun updateProgressUi() {
        val exo = player ?: return
        if (isSeekingFromUser) return
        val pos = exo.currentPosition.coerceAtLeast(0L)
        val dur = exo.duration.coerceAtLeast(0L)
        tvPosition.text = formatTime(pos)
        if (currentBundle?.isLive == true) {
            tvDurationTotal.text = "AO VIVO"
            seekBar.progress = 1000
        } else if (dur > 0) {
            tvDurationTotal.text = formatTime(dur)
            seekBar.progress = ((pos * 1000L) / dur).toInt().coerceIn(0, 1000)
        }
    }

    private fun showHud(focusPlayPause: Boolean) {
        layoutRelatedDrawer.visibility = View.GONE
        layoutHud.visibility = View.VISIBLE
        updateProgressUi()
        updatePlayPauseLabel()
        if (focusPlayPause) {
            btnPlayPause.post { btnPlayPause.requestFocus() }
        }
        resetHudTimer()
    }

    private fun resetHudTimer() {
        handler.removeCallbacks(hideHudRunnable)
        handler.postDelayed(hideHudRunnable, HUD_TIMEOUT_MS)
    }

    private fun showRelatedDrawer() {
        if (currentBundle?.relatedVideos.isNullOrEmpty()) return
        handler.removeCallbacks(hideHudRunnable)
        layoutHud.visibility = View.GONE
        layoutRelatedDrawer.visibility = View.VISIBLE
        rvRelated.post { rvRelated.getChildAt(0)?.requestFocus() }
    }

    private fun hideRelatedDrawer() {
        layoutRelatedDrawer.visibility = View.GONE
    }

    private fun showFeedback(text: String) {
        tvSeekFeedback.text = text
        tvSeekFeedback.visibility = View.VISIBLE
        handler.removeCallbacks(hideFeedbackRunnable)
        handler.postDelayed(hideFeedbackRunnable, 1200L)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val keyCode = event.keyCode
            val hudVisible = layoutHud.visibility == View.VISIBLE
            val relatedVisible = layoutRelatedDrawer.visibility == View.VISIBLE
            val errorVisible = layoutError.visibility == View.VISIBLE

            // Always allow Back to close overlays first
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                if (relatedVisible) {
                    hideRelatedDrawer()
                    return true
                }
                if (hudVisible) {
                    layoutHud.visibility = View.GONE
                    return true
                }
                return super.dispatchKeyEvent(event)
            }

            if (errorVisible) {
                return super.dispatchKeyEvent(event)
            }

            // Media hardware keys on TV remotes
            when (keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> {
                    togglePlayPause()
                    showHud(focusPlayPause = false)
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    seekRelative(SEEK_STEP_MS)
                    showHud(focusPlayPause = false)
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    seekRelative(-SEEK_STEP_MS)
                    showHud(focusPlayPause = false)
                    return true
                }
            }

            // When overlays are hidden, map D-Pad directly to fast TV controls
            if (!hudVisible && !relatedVisible) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        togglePlayPause()
                        showHud(focusPlayPause = true)
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        seekRelative(-SEEK_STEP_MS)
                        showHud(focusPlayPause = false)
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        seekRelative(SEEK_STEP_MS)
                        showHud(focusPlayPause = false)
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        showHud(focusPlayPause = true)
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (currentBundle?.relatedVideos?.isNotEmpty() == true) {
                            showRelatedDrawer()
                        } else {
                            showHud(focusPlayPause = true)
                        }
                        return true
                    }
                }
            } else if (hudVisible) {
                // Keep HUD alive while user is navigating its buttons
                resetHudTimer()
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = ms / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%d:%02d", minutes, seconds)
        }
    }

    @Suppress("DEPRECATION")
    private fun enterImmersiveMode() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
            )
    }

    override fun onStart() {
        super.onStart()
        handler.post(progressUpdater)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(progressUpdater)
        handler.removeCallbacks(hideHudRunnable)
        handler.removeCallbacks(hideFeedbackRunnable)
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        loadJob?.cancel()
        player?.release()
        player = null
    }
}
