package com.tubebox.tv.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tubebox.tv.R
import com.tubebox.tv.data.UpdateChecker
import com.tubebox.tv.data.VideoItem
import com.tubebox.tv.data.WatchHistoryStore
import com.tubebox.tv.data.YouTubeRepository
import com.tubebox.tv.update.UpdateManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.Page

class MainActivity : AppCompatActivity() {

    private enum class Section { HOME, MUSIC, GAMING, LIVE, PODCASTS, HISTORY, SEARCH }

    private lateinit var etSearch: EditText
    private lateinit var btnSearch: TextView
    private lateinit var tabHome: TextView
    private lateinit var tabMusic: TextView
    private lateinit var tabGaming: TextView
    private lateinit var tabLive: TextView
    private lateinit var tabPodcasts: TextView
    private lateinit var tabHistory: TextView
    private lateinit var tvSectionStatus: TextView
    private lateinit var rvVideos: RecyclerView
    private lateinit var layoutLoading: View
    private lateinit var layoutMessage: View
    private lateinit var tvMessage: TextView
    private lateinit var btnRetry: TextView

    private lateinit var adapter: VideoGridAdapter
    private lateinit var historyStore: WatchHistoryStore

    private var currentSection = Section.HOME
    private var currentSearchQuery = ""
    private var nextPage: Page? = null
    private var isLoadingMore = false
    private var loadJob: Job? = null
    private var lastBackPressedMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        historyStore = WatchHistoryStore(this)
        bindViews()
        setupGrid()
        setupSearch()
        setupTabs()

        selectSection(Section.HOME)
        checkAppUpdate()
    }

    private fun checkAppUpdate() {
        lifecycleScope.launch {
            val update = UpdateChecker.checkForUpdate() ?: return@launch
            if (isFinishing || isDestroyed) return@launch
            val notes = if (update.releaseNotes.isNotBlank()) "\n\n${update.releaseNotes}" else ""
            AlertDialog.Builder(this@MainActivity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Nova versão disponível (v${update.latestVersion})")
                .setMessage("Uma atualização do TubeBox está disponível no GitHub Releases.$notes")
                .setPositiveButton("Baixar") { _, _ ->
                    runCatching {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(update.downloadUrl)))
                    }
                }
                .setNegativeButton("Depois", null)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        if (currentSection == Section.HISTORY) {
            loadHistory()
        }
    }

    private fun bindViews() {
        etSearch = findViewById(R.id.etSearch)
        btnSearch = findViewById(R.id.btnSearch)
        tabHome = findViewById(R.id.tabHome)
        tabMusic = findViewById(R.id.tabMusic)
        tabGaming = findViewById(R.id.tabGaming)
        tabLive = findViewById(R.id.tabLive)
        tabPodcasts = findViewById(R.id.tabPodcasts)
        tabHistory = findViewById(R.id.tabHistory)
        tvSectionStatus = findViewById(R.id.tvSectionStatus)
        rvVideos = findViewById(R.id.rvVideos)
        layoutLoading = findViewById(R.id.layoutLoading)
        layoutMessage = findViewById(R.id.layoutMessage)
        tvMessage = findViewById(R.id.tvMessage)
        btnRetry = findViewById(R.id.btnRetry)

        btnRetry.setOnClickListener { reloadCurrentSection() }
    }

    private fun setupGrid() {
        adapter = VideoGridAdapter(
            onVideoClick = { item -> openPlayer(item) },
            onNearEnd = { loadMoreIfAvailable() }
        )
        rvVideos.layoutManager = GridLayoutManager(this, 3)
        rvVideos.adapter = adapter
        rvVideos.setHasFixedSize(false)
        rvVideos.itemAnimator = null
    }

    private fun setupSearch() {
        etSearch.setOnEditorActionListener { _, actionId, event ->
            val isEnter = event != null &&
                event.action == KeyEvent.ACTION_DOWN &&
                (event.keyCode == KeyEvent.KEYCODE_ENTER || event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER)
            if (actionId == EditorInfo.IME_ACTION_SEARCH || isEnter) {
                triggerSearch()
                true
            } else {
                false
            }
        }

        btnSearch.setOnClickListener { triggerSearch() }
    }

    private fun setupTabs() {
        tabHome.setOnClickListener { selectSection(Section.HOME) }
        tabMusic.setOnClickListener { selectSection(Section.MUSIC) }
        tabGaming.setOnClickListener { selectSection(Section.GAMING) }
        tabLive.setOnClickListener { selectSection(Section.LIVE) }
        tabPodcasts.setOnClickListener { selectSection(Section.PODCASTS) }
        tabHistory.setOnClickListener { selectSection(Section.HISTORY) }
    }

    private fun triggerSearch() {
        val query = etSearch.text?.toString()?.trim().orEmpty()
        if (query.isEmpty()) {
            etSearch.requestFocus()
            return
        }
        hideKeyboard()

        // Allow pasting direct YouTube links straight into the search box
        if (query.startsWith("http://") || query.startsWith("https://") ||
            query.contains("youtube.com/watch") || query.contains("youtu.be/")
        ) {
            val normalizedUrl = if (query.startsWith("http")) query else "https://$query"
            openPlayer(
                VideoItem(
                    url = normalizedUrl,
                    title = "Vídeo do YouTube",
                    uploader = "",
                    thumbnailUrl = "",
                    durationSeconds = 0L,
                    viewCount = -1L,
                    isLive = false
                )
            )
            return
        }

        currentSearchQuery = query
        selectSection(Section.SEARCH)
    }

    private fun selectSection(section: Section) {
        currentSection = section
        nextPage = null
        isLoadingMore = false
        updateTabSelection(section)
        reloadCurrentSection()
    }

    private fun updateTabSelection(section: Section) {
        tabHome.isSelected = section == Section.HOME
        tabMusic.isSelected = section == Section.MUSIC
        tabGaming.isSelected = section == Section.GAMING
        tabLive.isSelected = section == Section.LIVE
        tabPodcasts.isSelected = section == Section.PODCASTS
        tabHistory.isSelected = section == Section.HISTORY
    }

    private fun reloadCurrentSection() {
        loadJob?.cancel()

        if (currentSection == Section.HISTORY) {
            loadHistory()
            return
        }

        tvSectionStatus.text = when (currentSection) {
            Section.HOME -> "Destaques no Brasil"
            Section.MUSIC -> "Música em alta"
            Section.GAMING -> "Jogos em alta"
            Section.LIVE -> "Transmissões ao vivo"
            Section.PODCASTS -> "Podcasts"
            Section.SEARCH -> "Busca: \"$currentSearchQuery\""
            Section.HISTORY -> "Assistidos recentemente"
        }

        showLoading()
        loadJob = lifecycleScope.launch {
            runCatching {
                when (currentSection) {
                    Section.HOME -> YouTubeRepository.loadHomeFeed()
                    Section.MUSIC -> YouTubeRepository.loadKiosk(YouTubeRepository.KIOSK_MUSIC)
                    Section.GAMING -> YouTubeRepository.loadKiosk(YouTubeRepository.KIOSK_GAMING)
                    Section.LIVE -> YouTubeRepository.loadKiosk(YouTubeRepository.KIOSK_LIVE)
                    Section.PODCASTS -> YouTubeRepository.loadKiosk(YouTubeRepository.KIOSK_PODCASTS)
                    Section.SEARCH -> YouTubeRepository.search(currentSearchQuery)
                    Section.HISTORY -> error("Handled above")
                }
            }.onSuccess { page ->
                nextPage = page.nextPage
                if (page.items.isEmpty()) {
                    val emptyMsg = if (currentSection == Section.SEARCH) {
                        getString(R.string.empty_results)
                    } else {
                        getString(R.string.error_generic)
                    }
                    showMessage(emptyMsg, showRetry = currentSection != Section.SEARCH)
                } else {
                    adapter.submitList(page.items)
                    showGrid()
                    rvVideos.scrollToPosition(0)
                    if (currentSection == Section.SEARCH || currentFocus == null) {
                        rvVideos.post { rvVideos.getChildAt(0)?.requestFocus() }
                    }
                }
            }.onFailure { err ->
                val detail = err.localizedMessage?.take(120).orEmpty()
                val msg = if (detail.isNotBlank()) {
                    "${getString(R.string.error_generic)}\n($detail)"
                } else {
                    getString(R.string.error_generic)
                }
                showMessage(msg, showRetry = true)
            }
        }
    }

    private fun loadHistory() {
        tvSectionStatus.text = "Assistidos recentemente"
        val items = historyStore.getAll()
        if (items.isEmpty()) {
            adapter.submitList(emptyList())
            showMessage(getString(R.string.empty_history), showRetry = false)
        } else {
            adapter.submitList(items)
            showGrid()
        }
    }

    private fun loadMoreIfAvailable() {
        val pageToken = nextPage ?: return
        if (isLoadingMore) return
        if (currentSection == Section.HOME || currentSection == Section.HISTORY) return

        isLoadingMore = true
        lifecycleScope.launch {
            runCatching {
                when (currentSection) {
                    Section.SEARCH -> YouTubeRepository.search(currentSearchQuery, pageToken)
                    Section.MUSIC -> YouTubeRepository.loadKiosk(YouTubeRepository.KIOSK_MUSIC, pageToken)
                    Section.GAMING -> YouTubeRepository.loadKiosk(YouTubeRepository.KIOSK_GAMING, pageToken)
                    Section.LIVE -> YouTubeRepository.loadKiosk(YouTubeRepository.KIOSK_LIVE, pageToken)
                    Section.PODCASTS -> YouTubeRepository.loadKiosk(YouTubeRepository.KIOSK_PODCASTS, pageToken)
                    else -> null
                }
            }.onSuccess { more ->
                if (more != null) {
                    nextPage = more.nextPage
                    adapter.appendItems(more.items)
                }
            }
            isLoadingMore = false
        }
    }

    private fun openPlayer(item: VideoItem) {
        historyStore.record(item)
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_VIDEO_URL, item.url)
            putExtra(PlayerActivity.EXTRA_VIDEO_TITLE, item.title)
            putExtra(PlayerActivity.EXTRA_VIDEO_UPLOADER, item.uploader)
            putExtra(PlayerActivity.EXTRA_VIDEO_THUMB, item.thumbnailUrl)
        }
        startActivity(intent)
    }

    private fun showLoading() {
        layoutLoading.visibility = View.VISIBLE
        layoutMessage.visibility = View.GONE
        rvVideos.visibility = View.INVISIBLE
    }

    private fun showGrid() {
        layoutLoading.visibility = View.GONE
        layoutMessage.visibility = View.GONE
        rvVideos.visibility = View.VISIBLE
    }

    private fun showMessage(text: String, showRetry: Boolean) {
        layoutLoading.visibility = View.GONE
        rvVideos.visibility = View.GONE
        layoutMessage.visibility = View.VISIBLE
        tvMessage.text = text
        btnRetry.visibility = if (showRetry) View.VISIBLE else View.GONE
        if (showRetry) {
            btnRetry.post { btnRetry.requestFocus() }
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(etSearch.windowToken, 0)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (currentSection != Section.HOME) {
            selectSection(Section.HOME)
            tabHome.requestFocus()
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastBackPressedMs < 2000L) {
            super.onBackPressed()
        } else {
            lastBackPressedMs = now
            Toast.makeText(this, R.string.back_to_exit, Toast.LENGTH_SHORT).show()
        }
    }
}
