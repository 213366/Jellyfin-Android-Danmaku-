package org.jellyfin.mobile.player.danmaku

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONObject

/**
 * 已保存的弹幕剧集匹配信息
 */
data class SavedDanmakuMatch(
    val episodeId: Long,
    val animeTitle: String,
    val episodeTitle: String,
)

/**
 * 弹幕相关设置与匹配记忆，独立于应用主设置存储。
 *
 * 各设置项语义与 jellyfin-danmaku (ede.js) 保持一致：
 * - [sourceFilter]：位掩码，置位表示屏蔽来源（1 B站 / 2 巴哈 / 4 弹弹play / 8 其他）
 * - [modeFilter]：位掩码，置位表示屏蔽类型（1 底部 / 2 顶部 / 4 滚动）
 * - [densityLimit]：0 不限制，1 低，2 中，3 高
 * - [chConvert]：0 不转换，1 简体，2 繁体
 */
@Suppress("TooManyFunctions")
class DanmakuPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("${context.packageName}_danmaku", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) = prefs.edit { putBoolean(KEY_ENABLED, value) }

    /** 弹幕透明度 0.0-1.0 */
    var opacity: Float
        get() = prefs.getFloat(KEY_OPACITY, DEFAULT_OPACITY)
        set(value) = prefs.edit { putFloat(KEY_OPACITY, value.coerceIn(MIN_OPACITY, 1f)) }

    /** 弹幕滚动速度（dp/秒），与插件取值范围一致 */
    var speed: Int
        get() = prefs.getInt(KEY_SPEED, DEFAULT_SPEED)
        set(value) = prefs.edit { putInt(KEY_SPEED, value.coerceIn(MIN_SPEED, MAX_SPEED)) }

    /** 弹幕字号（sp） */
    var fontSizeSp: Int
        get() = prefs.getInt(KEY_FONT_SIZE, DEFAULT_FONT_SIZE)
        set(value) = prefs.edit { putInt(KEY_FONT_SIZE, value.coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)) }

    /** 弹幕显示区域占屏幕高度的比例 0.1-1.0 */
    var heightRatio: Float
        get() = prefs.getFloat(KEY_HEIGHT_RATIO, DEFAULT_HEIGHT_RATIO)
        set(value) = prefs.edit { putFloat(KEY_HEIGHT_RATIO, value.coerceIn(MIN_HEIGHT_RATIO, 1f)) }

    var sourceFilter: Int
        get() = prefs.getInt(KEY_SOURCE_FILTER, 0)
        set(value) = prefs.edit { putInt(KEY_SOURCE_FILTER, value) }

    var modeFilter: Int
        get() = prefs.getInt(KEY_MODE_FILTER, 0)
        set(value) = prefs.edit { putInt(KEY_MODE_FILTER, value) }

    var densityLimit: Int
        get() = prefs.getInt(KEY_DENSITY_LIMIT, 0)
        set(value) = prefs.edit { putInt(KEY_DENSITY_LIMIT, value.coerceIn(0, MAX_DENSITY_LIMIT)) }

    var chConvert: Int
        get() = prefs.getInt(KEY_CH_CONVERT, 0)
        set(value) = prefs.edit { putInt(KEY_CH_CONVERT, value.coerceIn(0, 2)) }

    /** 是否严格防重叠（无空闲轨道时丢弃弹幕，否则随机叠加） */
    var antiOverlap: Boolean
        get() = prefs.getBoolean(KEY_ANTI_OVERLAP, false)
        set(value) = prefs.edit { putBoolean(KEY_ANTI_OVERLAP, value) }

    /** 优先使用服务端弹幕插件（jellyfin-plugin-danmu）提供的 XML 弹幕 */
    var useXmlDanmaku: Boolean
        get() = prefs.getBoolean(KEY_USE_XML, false)
        set(value) = prefs.edit { putBoolean(KEY_USE_XML, value) }

    /** 自定义 API 地址（如自建代理或 misaka_danmu_server），留空使用默认 */
    var customApiBaseUrl: String
        get() = prefs.getString(KEY_CUSTOM_API, "").orEmpty()
        set(value) = prefs.edit { putString(KEY_CUSTOM_API, value.trim()) }

    /** 实际生效的 API 基础地址 */
    val apiBaseUrl: String
        get() = customApiBaseUrl.trim().trimEnd('/').ifEmpty { DEFAULT_API_BASE_URL }

    // ---- 匹配记忆（与 ede.js 的 localStorage 记忆行为对应） ----

    fun getSavedAnime(animeKey: String): Pair<Long, String>? {
        val json = prefs.getString(KEY_PREFIX_ANIME + animeKey, null) ?: return null
        return runCatching {
            val obj = JSONObject(json)
            obj.getLong("animeId") to obj.getString("animeTitle")
        }.getOrNull()
    }

    fun saveAnime(animeKey: String, animeId: Long, animeTitle: String) {
        val json = JSONObject()
            .put("animeId", animeId)
            .put("animeTitle", animeTitle)
            .toString()
        prefs.edit { putString(KEY_PREFIX_ANIME + animeKey, json) }
    }

    fun getSavedEpisode(episodeKey: String): SavedDanmakuMatch? {
        val json = prefs.getString(KEY_PREFIX_EPISODE + episodeKey, null) ?: return null
        return runCatching {
            val obj = JSONObject(json)
            SavedDanmakuMatch(
                episodeId = obj.getLong("episodeId"),
                animeTitle = obj.getString("animeTitle"),
                episodeTitle = obj.getString("episodeTitle"),
            )
        }.getOrNull()
    }

    fun saveEpisode(episodeKey: String, match: SavedDanmakuMatch) {
        val json = JSONObject()
            .put("episodeId", match.episodeId)
            .put("animeTitle", match.animeTitle)
            .put("episodeTitle", match.episodeTitle)
            .toString()
        prefs.edit { putString(KEY_PREFIX_EPISODE + episodeKey, json) }
    }

    /** 当前剧集的弹幕时间轴偏移（秒），正值表示弹幕整体延后 */
    fun getOffset(episodeKey: String): Double =
        prefs.getFloat(KEY_PREFIX_OFFSET + episodeKey, 0f).toDouble()

    fun setOffset(episodeKey: String, value: Double) {
        prefs.edit { putFloat(KEY_PREFIX_OFFSET + episodeKey, value.toFloat()) }
    }

    companion object {
        /** 默认走 ede.js 同款公共 CORS 代理，由代理侧补全 dandanplay 签名 */
        const val DEFAULT_API_BASE_URL = "https://ddplay-api.930524.xyz/cors/https://api.dandanplay.net"

        const val SOURCE_FILTER_BILIBILI = 1
        const val SOURCE_FILTER_GAMER = 2
        const val SOURCE_FILTER_DANDAN = 4
        const val SOURCE_FILTER_OTHER = 8

        const val MODE_FILTER_BOTTOM = 1
        const val MODE_FILTER_TOP = 2
        const val MODE_FILTER_SCROLL = 4

        const val DEFAULT_OPACITY = 0.7f
        const val MIN_OPACITY = 0.1f
        const val DEFAULT_SPEED = 200
        const val MIN_SPEED = 20
        const val MAX_SPEED = 600
        const val DEFAULT_FONT_SIZE = 18
        const val MIN_FONT_SIZE = 8
        const val MAX_FONT_SIZE = 40
        const val DEFAULT_HEIGHT_RATIO = 0.9f
        const val MIN_HEIGHT_RATIO = 0.1f
        const val MAX_DENSITY_LIMIT = 3

        private const val KEY_ENABLED = "danmaku_enabled"
        private const val KEY_OPACITY = "danmaku_opacity"
        private const val KEY_SPEED = "danmaku_speed"
        private const val KEY_FONT_SIZE = "danmaku_font_size"
        private const val KEY_HEIGHT_RATIO = "danmaku_height_ratio"
        private const val KEY_SOURCE_FILTER = "danmaku_source_filter"
        private const val KEY_MODE_FILTER = "danmaku_mode_filter"
        private const val KEY_DENSITY_LIMIT = "danmaku_density_limit"
        private const val KEY_CH_CONVERT = "danmaku_ch_convert"
        private const val KEY_ANTI_OVERLAP = "danmaku_anti_overlap"
        private const val KEY_USE_XML = "danmaku_use_xml"
        private const val KEY_CUSTOM_API = "danmaku_custom_api"
        private const val KEY_PREFIX_ANIME = "danmaku_anime_"
        private const val KEY_PREFIX_EPISODE = "danmaku_episode_"
        private const val KEY_PREFIX_OFFSET = "danmaku_offset_"
    }
}
