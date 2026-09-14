package org.jellyfin.mobile.player.danmaku

import android.annotation.SuppressLint
import android.content.Context
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.SeekBar
import androidx.appcompat.app.AlertDialog
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.mobile.R
import org.jellyfin.mobile.databinding.DialogDanmakuSettingsBinding
import org.jellyfin.mobile.player.source.JellyfinMediaSource
import org.jellyfin.mobile.utils.toast
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.component.inject
import timber.log.Timber
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.max

/**
 * 弹幕功能编排：匹配、拉取、过滤处理、设置菜单。
 *
 * 功能与设置项对齐 jellyfin-danmaku (ede.js)：
 * 自动/手动匹配、时间轴偏移、透明度/字号/速度/显示区域、
 * 类型过滤、来源过滤、密度限制、防重叠、简繁转换、
 * 服务端 XML 弹幕（jellyfin-plugin-danmu）、自定义 API 地址。
 */
@Suppress("TooManyFunctions", "TooGenericExceptionCaught")
class DanmakuController(
    private val context: Context,
    private val danmakuView: DanmakuView,
    private val scope: CoroutineScope,
) : KoinComponent {

    private val preferences: DanmakuPreferences by inject()
    private val client: DandanplayClient by inject()
    private val apiClient: ApiClient = get()

    private var currentItemId: UUID? = null
    private var currentItem: BaseItemDto? = null
    private var animeKey: String? = null
    private var episodeKey: String? = null
    private var episodeIndex: Int = 1
    private var rawComments: List<DanmakuComment> = emptyList()
    private var currentMatch: SavedDanmakuMatch? = null
    private var loadJob: Job? = null
    private var settingsDialog: BottomSheetDialog? = null

    init {
        applyViewConfig()
        danmakuView.danmakuVisible = preferences.enabled
    }

    // ---- 生命周期入口 ----

    fun onMediaSourceChanged(mediaSource: JellyfinMediaSource) {
        if (mediaSource.itemId == currentItemId) return

        loadJob?.cancel()
        currentItemId = mediaSource.itemId
        currentItem = mediaSource.item
        rawComments = emptyList()
        currentMatch = null
        danmakuView.clear()

        val item = mediaSource.item
        animeKey = (item?.seasonId ?: mediaSource.itemId).toString()
        episodeIndex = item?.indexNumber ?: 1
        episodeKey = "${animeKey}_$episodeIndex"

        applyViewConfig()
        danmakuView.danmakuVisible = preferences.enabled

        if (preferences.enabled) {
            reload(showToast = false)
        }
    }

    fun destroy() {
        loadJob?.cancel()
        settingsDialog?.dismiss()
        settingsDialog = null
    }

    // ---- 加载流程 ----

    private fun reload(showToast: Boolean = true) {
        val itemId = currentItemId ?: return
        loadJob?.cancel()
        loadJob = scope.launch {
            try {
                // 优先使用服务端弹幕插件提供的 XML 弹幕
                if (preferences.useXmlDanmaku) {
                    val baseUrl = apiClient.baseUrl
                    if (baseUrl != null) {
                        val xmlComments = client.getPluginXmlComments(
                            serverBaseUrl = baseUrl,
                            itemId = itemId.toString(),
                            accessToken = apiClient.accessToken,
                        )
                        if (!xmlComments.isNullOrEmpty()) {
                            rawComments = xmlComments
                            currentMatch = SavedDanmakuMatch(
                                episodeId = -1,
                                animeTitle = context.getString(R.string.danmaku_source_server_xml),
                                episodeTitle = "",
                            )
                            applyProcessedComments()
                            notifyLoaded()
                            return@launch
                        }
                    }
                }

                val match = autoMatch()
                if (match == null) {
                    if (showToast) context.toast(R.string.danmaku_no_match)
                    return@launch
                }
                currentMatch = match
                rawComments = client.getComments(preferences.apiBaseUrl, match.episodeId, preferences.chConvert)
                applyProcessedComments()
                notifyLoaded()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Failed to load danmaku")
                if (showToast) context.toast(R.string.danmaku_load_failed)
            }
        }
    }

    @Suppress("ReturnCount")
    private suspend fun autoMatch(): SavedDanmakuMatch? {
        val episodeKey = episodeKey ?: return null
        val animeKey = animeKey ?: return null
        val item = currentItem ?: return null

        // 匹配记忆优先
        preferences.getSavedEpisode(episodeKey)?.let { saved -> return saved }

        val animeName = buildAnimeName(item) ?: return null
        var results = client.searchEpisodes(preferences.apiBaseUrl, animeName)

        // 回退1：尝试用系列的原始标题搜索
        if (results.isEmpty()) {
            val originalTitle = fetchOriginalTitle(item.seriesId ?: item.id)
            if (!originalTitle.isNullOrBlank() && originalTitle != animeName) {
                results = client.searchEpisodes(preferences.apiBaseUrl, originalTitle)
            }
        }

        // 回退2：若带季号搜索无结果或结果不匹配，回退用纯 seriesName 搜索
        val season = item.parentIndexNumber ?: 1
        if (results.isEmpty() || (results.size > 1 && season > 1 && !results.any { anime ->
            matchesSeason(anime.animeTitle, season)
        })) {
            val seriesOnly = item.seriesName ?: item.name
            if (!seriesOnly.isNullOrBlank() && seriesOnly != animeName) {
                val fallbackResults = client.searchEpisodes(preferences.apiBaseUrl, seriesOnly)
                // 取回退结果中能匹配季号的（否则合并两套结果做选择）
                if (fallbackResults.isNotEmpty()) {
                    results = (results.toList() + fallbackResults.toList())
                        .distinctBy { anime -> anime.animeId }
                }
            }
        }

        if (results.isEmpty()) return null

        // 优先使用记忆的番剧
        val savedAnime = preferences.getSavedAnime(animeKey)
        val animeIdx = savedAnime
            ?.let { (animeId, _) -> results.indexOfFirst { anime -> anime.animeId == animeId } }
            ?.takeIf { idx -> idx >= 0 }
            ?: selectBestAnime(results, item, animeName)
        if (animeIdx == null) return null
        val anime = results[animeIdx]
        if (anime.episodes.isEmpty()) return null

        // 过滤出标准正片剧集（跳过 Preview/Special/Credit 等非正片）
        val standardEps = filterStandardEpisodes(anime.episodes)
        val mainEpisodes = if (standardEps.isNotEmpty()) standardEps else anime.episodes

        // 处理弹幕库不从第 1 话开始的情况（与 ede.js 一致）
        val firstStandard = findFirstStandardEpisode(mainEpisodes)
        val initialEp = firstStandard
            ?.let { EPISODE_NUMBER_REGEX.find(it.episodeTitle)?.groupValues?.getOrNull(1)?.toIntOrNull() }
            ?: 1
        val epIdx = if (episodeIndex < initialEp) episodeIndex - 1 else episodeIndex - initialEp
        val episode = mainEpisodes.getOrNull(epIdx) ?: return null

        val match = SavedDanmakuMatch(
            episodeId = episode.episodeId,
            animeTitle = anime.animeTitle,
            episodeTitle = episode.episodeTitle,
        )
        preferences.saveEpisode(episodeKey, match)
        return match
    }

    private fun buildAnimeName(item: BaseItemDto): String? {
        var name = item.seriesName ?: item.name ?: return null
        val season = item.parentIndexNumber
        if (season != null && season > 1) {
            name += " $season"
        }
        return name
    }

    /**
     * 智能选择弹幕匹配的番剧，优先级：
     * 1. 季号匹配 —— animeTitle 包含对应季号表述（如"第2季"、"2期"）
     * 2. 剧集覆盖 —— episode 列表能覆盖当前集号的优先
     * 3. 类型 —— TV/OVA 优先于 Movie
     * 4. 默认取第一个
     */
    @Suppress("ReturnCount")
    private fun selectBestAnime(
        results: List<DandanplayClient.AnimeResult>,
        item: BaseItemDto,
        searchKey: String,
    ): Int? {
        if (results.size == 1) return 0

        val season = item.parentIndexNumber ?: 1
        val episodeIndex = item.indexNumber ?: 1

        // 季号匹配优先：有季号时直接找匹配的番剧
        if (season > 1) {
            val seasonMatchIdx = results.indexOfFirst { anime ->
                matchesSeason(anime.animeTitle, season)
            }
            if (seasonMatchIdx >= 0) return seasonMatchIdx
        }

        // 按剧集覆盖度 + 类型偏好排序；平局时优先标题最接近搜索词的结果
        val scores = results.mapIndexed { idx, anime ->
            val standardEps = filterStandardEpisodes(anime.episodes)
            val allEps = if (standardEps.isNotEmpty()) standardEps else anime.episodes
            val firstEp = findFirstStandardEpisode(allEps)
            val initialEp = firstEp
                ?.let { EPISODE_NUMBER_REGEX.find(it.episodeTitle)?.groupValues?.getOrNull(1)?.toIntOrNull() }
                ?: 1
            val maxEp = initialEp + allEps.size - 1
            val coversEpisode = episodeIndex in initialEp..maxEp

            // 分数越小越优先
            var score = 100000
            if (coversEpisode) score -= 50000
            if (anime.type == "tvseries") score -= 10000 // TV 优先
            if (season == 1 && !hasSeasonIndicator(anime.animeTitle)) score -= 5000

            // 平局裁决：标题包含搜索词的优先（匹配更精确），
            // 权重高于 TV 类型偏好（-10000），确保跨季名同集号时选对季
            if (anime.animeTitle.contains(searchKey, ignoreCase = true) ||
                searchKey.contains(anime.animeTitle, ignoreCase = true)) {
                score -= 20000
            }

            // 标题越短越优先（通常更接近原始搜索词）
            score += anime.animeTitle.length

            idx to score
        }

        return scores.minByOrNull { it.second }?.first
    }

    /** animeTitle 中是否包含第 N 季的常见表述 */
    private fun matchesSeason(title: String, season: Int): Boolean {
        val patterns = listOf(
            "第${season}季",
            "第 ${season} 季",
            "${season}期",
            "第${season}期",
            "Season $season",
            " $season",
        )
        return patterns.any { pattern ->
            title.contains(pattern, ignoreCase = true)
        }
    }

    /** animeTitle 中是否含有季号表述（判断是否是多季番剧的某一季） */
    private fun hasSeasonIndicator(title: String): Boolean {
        return SEASON_INDICATOR_REGEX.containsMatchIn(title)
    }

    private suspend fun fetchOriginalTitle(seriesId: UUID?): String? {
        if (seriesId == null) return null
        return runCatching {
            withContext(Dispatchers.IO) {
                apiClient.userLibraryApi.getItem(itemId = seriesId).content.originalTitle
            }
        }.getOrNull()
    }

    private fun notifyLoaded() {
        val match = currentMatch ?: return
        val title = listOf(match.animeTitle, match.episodeTitle)
            .filter(String::isNotBlank)
            .joinToString(" - ")
        context.toast(context.getString(R.string.danmaku_loaded_toast, rawComments.size, title))
    }

    // ---- 弹幕预处理（过滤/去重/密度/偏移） ----

    private fun applyProcessedComments() {
        danmakuView.setComments(processComments())
    }

    private fun processComments(): List<DanmakuComment> {
        val sourceFilter = preferences.sourceFilter
        val modeFilter = preferences.modeFilter
        val offset = episodeKey?.let(preferences::getOffset) ?: 0.0

        val seen = HashSet<String>(rawComments.size)
        val filtered = rawComments
            .filter { comment ->
                seen.add("${comment.timeSeconds},${comment.mode},${comment.colorRgb}|${comment.text}") &&
                    !isSourceBlocked(comment.source, sourceFilter) &&
                    !isModeBlocked(comment.mode, modeFilter)
            }
            .sortedBy(DanmakuComment::timeSeconds)

        val densityLimited = when {
            preferences.densityLimit > 0 -> applyDensityLimit(filtered, preferences.densityLimit)
            else -> filtered
        }

        return when {
            offset != 0.0 -> densityLimited.map { comment ->
                comment.copy(timeSeconds = comment.timeSeconds + offset)
            }
            else -> densityLimited
        }
    }

    private fun isSourceBlocked(source: DanmakuSource, filter: Int): Boolean = when (source) {
        DanmakuSource.BILIBILI -> filter and DanmakuPreferences.SOURCE_FILTER_BILIBILI != 0
        DanmakuSource.GAMER -> filter and DanmakuPreferences.SOURCE_FILTER_GAMER != 0
        DanmakuSource.DANDAN -> filter and DanmakuPreferences.SOURCE_FILTER_DANDAN != 0
        DanmakuSource.OTHER -> filter and DanmakuPreferences.SOURCE_FILTER_OTHER != 0
    }

    private fun isModeBlocked(mode: DanmakuMode, filter: Int): Boolean = when (mode) {
        DanmakuMode.BOTTOM -> filter and DanmakuPreferences.MODE_FILTER_BOTTOM != 0
        DanmakuMode.TOP -> filter and DanmakuPreferences.MODE_FILTER_TOP != 0
        DanmakuMode.SCROLL -> filter and DanmakuPreferences.MODE_FILTER_SCROLL != 0
    }

    /**
     * 密度限制，算法与 ede.js preProcessDanmaku 保持一致：
     * 以「弹幕滚动一屏所需时长」为时间桶，限制每个桶内的滚动/固定弹幕条数。
     */
    @Suppress("MagicNumber")
    private fun applyDensityLimit(comments: List<DanmakuComment>, level: Int): List<DanmakuComment> {
        val metrics = context.resources.displayMetrics
        val width = danmakuView.width.takeIf { it > 0 } ?: metrics.widthPixels
        val height = danmakuView.height.takeIf { it > 0 } ?: metrics.heightPixels
        val fontPx = preferences.fontSizeSp * metrics.scaledDensity
        val speedPx = preferences.speed * metrics.density

        val durationSec = max(1.0, ceil(width / speedPx.toDouble()))
        val lines = max(1, (height * preferences.heightRatio / (fontPx * 1.35f)).toInt() - 1)
        val scrollLimit = (9 - level * 2) * lines
        val verticalLimit = max(lines - 1, 1)

        val scrollBuckets = HashMap<Long, Int>()
        val verticalBuckets = HashMap<Long, Int>()

        return comments.filter { comment ->
            val bucket = ceil(comment.timeSeconds / durationSec).toLong()
            when (comment.mode) {
                DanmakuMode.SCROLL -> {
                    val count = (scrollBuckets[bucket] ?: 0) + 1
                    scrollBuckets[bucket] = count
                    count <= scrollLimit
                }
                else -> {
                    val count = (verticalBuckets[bucket] ?: 0) + 1
                    verticalBuckets[bucket] = count
                    count <= verticalLimit
                }
            }
        }
    }

    private fun applyViewConfig() {
        danmakuView.applyConfig(
            DanmakuView.Config(
                opacity = preferences.opacity,
                fontSizeSp = preferences.fontSizeSp,
                speedDpPerSecond = preferences.speed,
                heightRatio = preferences.heightRatio,
                antiOverlap = preferences.antiOverlap,
            ),
        )
    }

    // ---- 手动匹配 ----

    private fun showManualMatchDialog() {
        val editText = EditText(context).apply {
            setText(currentItem?.let(::buildAnimeName).orEmpty())
            setSelection(text.length)
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.danmaku_search_title)
            .setView(editText)
            .setPositiveButton(R.string.danmaku_search_action) { _, _ ->
                val name = editText.text.toString().trim()
                if (name.isNotEmpty()) searchAndSelect(name)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun searchAndSelect(name: String) {
        scope.launch {
            try {
                val results = client.searchEpisodes(preferences.apiBaseUrl, name)
                if (results.isEmpty()) {
                    context.toast(R.string.danmaku_no_match)
                    return@launch
                }
                showAnimeSelectDialog(results)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Danmaku search failed")
                context.toast(R.string.danmaku_load_failed)
            }
        }
    }

    private fun showAnimeSelectDialog(results: List<DandanplayClient.AnimeResult>) {
        val titles = results
            .map { anime -> "${anime.animeTitle}（${anime.typeDescription}）" }
            .toTypedArray()
        AlertDialog.Builder(context)
            .setTitle(R.string.danmaku_select_anime)
            .setItems(titles) { _, which -> showEpisodeSelectDialog(results[which]) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showEpisodeSelectDialog(anime: DandanplayClient.AnimeResult) {
        if (anime.episodes.isEmpty()) {
            context.toast(R.string.danmaku_no_match)
            return
        }
        val titles = anime.episodes.map(DandanplayClient.EpisodeResult::episodeTitle).toTypedArray()
        AlertDialog.Builder(context)
            .setTitle(R.string.danmaku_select_episode)
            .setItems(titles) { _, which -> onManualMatchSelected(anime, anime.episodes[which]) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun onManualMatchSelected(anime: DandanplayClient.AnimeResult, episode: DandanplayClient.EpisodeResult) {
        val animeKey = animeKey ?: return
        val episodeKey = episodeKey ?: return
        preferences.saveAnime(animeKey, anime.animeId, anime.animeTitle)
        val match = SavedDanmakuMatch(
            episodeId = episode.episodeId,
            animeTitle = anime.animeTitle,
            episodeTitle = episode.episodeTitle,
        )
        preferences.saveEpisode(episodeKey, match)
        currentMatch = match

        loadJob?.cancel()
        loadJob = scope.launch {
            try {
                rawComments = client.getComments(preferences.apiBaseUrl, match.episodeId, preferences.chConvert)
                applyProcessedComments()
                notifyLoaded()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Failed to load danmaku")
                context.toast(R.string.danmaku_load_failed)
            }
        }
    }

    // ---- 设置菜单 ----

    @SuppressLint("SetTextI18n")
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    fun showSettings() {
        settingsDialog?.dismiss()

        val binding = DialogDanmakuSettingsBinding.inflate(LayoutInflater.from(context))
        val dialog = BottomSheetDialog(context)
        dialog.setContentView(binding.root)
        settingsDialog = dialog

        // 记录进入时需要「重新拉取」的设置，关闭时对比
        val initialChConvert = preferences.chConvert
        val initialUseXml = preferences.useXmlDanmaku
        val initialCustomApi = preferences.customApiBaseUrl
        val initialSourceFilter = preferences.sourceFilter
        val initialModeFilter = preferences.modeFilter
        val initialDensity = preferences.densityLimit
        val initialOffset = episodeKey?.let(preferences::getOffset) ?: 0.0

        with(binding) {
            // 开关
            danmakuEnabledSwitch.isChecked = preferences.enabled
            danmakuEnabledSwitch.setOnCheckedChangeListener { _, checked ->
                preferences.enabled = checked
                danmakuView.danmakuVisible = checked
                if (checked && rawComments.isEmpty() && currentItemId != null) {
                    reload()
                }
            }

            // 匹配信息
            val match = currentMatch
            danmakuMatchInfo.text = when {
                match != null -> context.getString(
                    R.string.danmaku_match_info,
                    listOf(match.animeTitle, match.episodeTitle).filter(String::isNotBlank).joinToString(" - "),
                )
                else -> context.getString(R.string.danmaku_match_none)
            }
            danmakuMatchButton.setOnClickListener {
                dialog.dismiss()
                showManualMatchDialog()
            }
            danmakuReloadButton.setOnClickListener {
                dialog.dismiss()
                reload()
            }

            // 时间轴偏移
            danmakuOffsetValue.setText(initialOffset.toString())
            danmakuOffsetMinus.setOnClickListener {
                val value = danmakuOffsetValue.text.toString().toDoubleOrNull() ?: 0.0
                danmakuOffsetValue.setText((value - OFFSET_STEP_SECONDS).toString())
            }
            danmakuOffsetPlus.setOnClickListener {
                val value = danmakuOffsetValue.text.toString().toDoubleOrNull() ?: 0.0
                danmakuOffsetValue.setText((value + OFFSET_STEP_SECONDS).toString())
            }

            // 样式（实时生效）
            setupSeekBar(
                seekBar = danmakuOpacitySeekbar,
                min = MIN_OPACITY_PERCENT,
                max = MAX_PERCENT,
                value = (preferences.opacity * MAX_PERCENT).toInt(),
                updateLabel = { value ->
                    danmakuOpacityLabel.text = context.getString(R.string.danmaku_opacity_label, value)
                },
            ) { value ->
                preferences.opacity = value / MAX_PERCENT.toFloat()
                applyViewConfig()
            }
            setupSeekBar(
                seekBar = danmakuFontSizeSeekbar,
                min = DanmakuPreferences.MIN_FONT_SIZE,
                max = DanmakuPreferences.MAX_FONT_SIZE,
                value = preferences.fontSizeSp,
                updateLabel = { value ->
                    danmakuFontSizeLabel.text = context.getString(R.string.danmaku_font_size_label, value)
                },
            ) { value ->
                preferences.fontSizeSp = value
                applyViewConfig()
            }
            setupSeekBar(
                seekBar = danmakuSpeedSeekbar,
                min = DanmakuPreferences.MIN_SPEED,
                max = DanmakuPreferences.MAX_SPEED,
                value = preferences.speed,
                updateLabel = { value ->
                    danmakuSpeedLabel.text = context.getString(R.string.danmaku_speed_label, value)
                },
            ) { value ->
                preferences.speed = value
                applyViewConfig()
            }
            setupSeekBar(
                seekBar = danmakuHeightSeekbar,
                min = MIN_HEIGHT_PERCENT,
                max = MAX_PERCENT,
                value = (preferences.heightRatio * MAX_PERCENT).toInt(),
                updateLabel = { value ->
                    danmakuHeightLabel.text = context.getString(R.string.danmaku_display_area_label, value)
                },
            ) { value ->
                preferences.heightRatio = value / MAX_PERCENT.toFloat()
                applyViewConfig()
            }

            // 类型过滤（勾选 = 显示）
            danmakuModeScroll.isChecked = preferences.modeFilter and DanmakuPreferences.MODE_FILTER_SCROLL == 0
            danmakuModeTop.isChecked = preferences.modeFilter and DanmakuPreferences.MODE_FILTER_TOP == 0
            danmakuModeBottom.isChecked = preferences.modeFilter and DanmakuPreferences.MODE_FILTER_BOTTOM == 0

            // 来源过滤（勾选 = 显示）
            danmakuSourceBilibili.isChecked = preferences.sourceFilter and DanmakuPreferences.SOURCE_FILTER_BILIBILI == 0
            danmakuSourceGamer.isChecked = preferences.sourceFilter and DanmakuPreferences.SOURCE_FILTER_GAMER == 0
            danmakuSourceDandan.isChecked = preferences.sourceFilter and DanmakuPreferences.SOURCE_FILTER_DANDAN == 0
            danmakuSourceOther.isChecked = preferences.sourceFilter and DanmakuPreferences.SOURCE_FILTER_OTHER == 0

            // 密度限制
            val densityButtons = listOf(danmakuDensity0, danmakuDensity1, danmakuDensity2, danmakuDensity3)
            densityButtons.getOrNull(preferences.densityLimit)?.isChecked = true

            // 简繁转换
            val chButtons = listOf(danmakuCh0, danmakuCh1, danmakuCh2)
            chButtons.getOrNull(preferences.chConvert)?.isChecked = true

            // 其他开关
            danmakuAntiOverlapSwitch.isChecked = preferences.antiOverlap
            danmakuUseXmlSwitch.isChecked = preferences.useXmlDanmaku
            danmakuCustomApiInput.setText(preferences.customApiBaseUrl)

            dialog.setOnDismissListener {
                settingsDialog = null

                // 收集并保存
                var modeFilter = 0
                if (!danmakuModeBottom.isChecked) modeFilter = modeFilter or DanmakuPreferences.MODE_FILTER_BOTTOM
                if (!danmakuModeTop.isChecked) modeFilter = modeFilter or DanmakuPreferences.MODE_FILTER_TOP
                if (!danmakuModeScroll.isChecked) modeFilter = modeFilter or DanmakuPreferences.MODE_FILTER_SCROLL
                preferences.modeFilter = modeFilter

                var sourceFilter = 0
                if (!danmakuSourceBilibili.isChecked) {
                    sourceFilter = sourceFilter or DanmakuPreferences.SOURCE_FILTER_BILIBILI
                }
                if (!danmakuSourceGamer.isChecked) sourceFilter = sourceFilter or DanmakuPreferences.SOURCE_FILTER_GAMER
                if (!danmakuSourceDandan.isChecked) {
                    sourceFilter = sourceFilter or DanmakuPreferences.SOURCE_FILTER_DANDAN
                }
                if (!danmakuSourceOther.isChecked) sourceFilter = sourceFilter or DanmakuPreferences.SOURCE_FILTER_OTHER
                preferences.sourceFilter = sourceFilter

                preferences.densityLimit = densityButtons.indexOfFirst { button -> button.isChecked }.coerceAtLeast(0)
                preferences.chConvert = chButtons.indexOfFirst { button -> button.isChecked }.coerceAtLeast(0)
                preferences.antiOverlap = danmakuAntiOverlapSwitch.isChecked
                preferences.useXmlDanmaku = danmakuUseXmlSwitch.isChecked
                preferences.customApiBaseUrl = danmakuCustomApiInput.text.toString()

                val offset = danmakuOffsetValue.text.toString().toDoubleOrNull() ?: initialOffset
                episodeKey?.let { key -> preferences.setOffset(key, offset) }

                applyViewConfig()

                val needsRefetch = preferences.chConvert != initialChConvert ||
                    preferences.useXmlDanmaku != initialUseXml ||
                    preferences.customApiBaseUrl != initialCustomApi
                val needsReprocess = preferences.sourceFilter != initialSourceFilter ||
                    preferences.modeFilter != initialModeFilter ||
                    preferences.densityLimit != initialDensity ||
                    offset != initialOffset

                when {
                    needsRefetch && preferences.enabled -> reload()
                    needsReprocess && rawComments.isNotEmpty() -> applyProcessedComments()
                }
            }
        }

        dialog.show()
    }

    private fun setupSeekBar(
        seekBar: SeekBar,
        min: Int,
        max: Int,
        value: Int,
        updateLabel: (Int) -> Unit,
        onValueChanged: (Int) -> Unit,
    ) {
        seekBar.max = max - min
        seekBar.progress = (value - min).coerceIn(0, max - min)
        updateLabel(value)
        seekBar.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    updateLabel(progress + min)
                    if (fromUser) onValueChanged(progress + min)
                }

                override fun onStartTrackingTouch(bar: SeekBar) = Unit

                override fun onStopTrackingTouch(bar: SeekBar) = Unit
            },
        )
    }

    companion object {
        /** 匹配标准剧集标题格式：第X话、第X話、第X集（允许空格） */
        private val EPISODE_NUMBER_REGEX = Regex("""第\s*(\d+)\s*[话話集]""")
        private val SEASON_INDICATOR_REGEX = Regex("""第\s*\d+\s*季|\d+期|Season\s+\d+""", RegexOption.IGNORE_CASE)
        private const val OFFSET_STEP_SECONDS = 0.5
        private const val MAX_PERCENT = 100
        private const val MIN_OPACITY_PERCENT = 10
        private const val MIN_HEIGHT_PERCENT = 10
    }

    /**
     * 从剧集列表中找出第一个标准格式的剧集（跳过 Preview/Special/Credit 等非正片）
     */
    private fun findFirstStandardEpisode(episodes: List<DandanplayClient.EpisodeResult>): DandanplayClient.EpisodeResult? {
        return episodes.firstOrNull { ep ->
            EPISODE_NUMBER_REGEX.containsMatchIn(ep.episodeTitle)
        }
    }

    /**
     * 过滤出标准格式的正片剧集列表（排除 Preview/Special/Credit 等）
     */
    private fun filterStandardEpisodes(episodes: List<DandanplayClient.EpisodeResult>): List<DandanplayClient.EpisodeResult> {
        return episodes.filter { ep ->
            EPISODE_NUMBER_REGEX.containsMatchIn(ep.episodeTitle)
        }
    }
}
