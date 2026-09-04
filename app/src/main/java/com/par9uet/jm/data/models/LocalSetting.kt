package com.par9uet.jm.data.models

const val APP_LOCK_TYPE_PASSWORD = "password"
const val APP_LOCK_TYPE_PATTERN = "pattern"
const val APP_LOCK_UNLOCK_MODE_PASSWORD = "password"
const val APP_LOCK_UNLOCK_MODE_PATTERN = "pattern"
const val APP_LOCK_UNLOCK_MODE_BOTH = "both"

// Supported API endpoints are a static application catalog, not user preferences; only the
// selected value is persisted in LocalSetting.api.
val AVAILABLE_APIS = listOf(
    "https://www.cdnhth.club",
    "https://www.cdnmhwscc.vip",
    "https://www.jmapiproxyxxx.vip",
    "https://www.cdnxxx-proxy.xyz",
    "https://www.jmeadpoolcdn.life",
)

// Supported themes; only the selected value is persisted in LocalSetting.theme.
val AVAILABLE_THEMES = listOf("auto", "light", "dark")

data class BlockedTagTemplate(
    val name: String = "",
    val tagList: List<String> = listOf(),
)

/**
 * Unified persistence DTO for all local settings (one encrypted JSON document). Its shape is
 * shared with backup/restore and legacy migrations, so fields are never removed casually.
 */
data class LocalSetting(
    // 开启后请求网络 API 获取基于登录账号的个性化推荐，可能不稳定
    val preferenceRecommendEnabled: Boolean = true,
    // 选中的 API 节点（候选集合见 [AVAILABLE_APIS]）
    val api: String = AVAILABLE_APIS.first(),
    // auto | light | dark（候选集合见 [AVAILABLE_THEMES]）
    val theme: String = "auto",
    // 阅读页预先加载的图片张数
    val prefetchCount: Int = 3,
    // scroll | page | tap
    val readMode: String = READ_MODE_SCROLL,
    // default | side
    val readTapMode: String = "default",
    val launcherDisguise: String = "default",
    val showComicCacheNotification: Boolean = true,
    // 仅在 showComicCacheNotification 为 true 时有意义
    val showComicCacheNotificationName: Boolean = true,
    val blockedTagList: List<String> = listOf(),
    val blockedTagTemplateList: List<BlockedTagTemplate> = listOf(),
    val appLockEnabled: Boolean = false,
    // 空字符串表示未设置密码
    val appLockPassword: String = "",
    // 密码长度 4-8 位
    val appLockPasswordLength: Int = 4,
    // 空字符串表示未设置图案（点序号拼接，例如 "01246"）
    val appLockPattern: String = "",
    // password | pattern | both；必须与实际存在的凭据一致
    val appLockUnlockMode: String = APP_LOCK_UNLOCK_MODE_PASSWORD,
    val nsfwWarningDismissed: Boolean = false,
    val onboardingCompleted: Boolean = false,
    // 检测到剪贴板包含漫画编码时自动弹出跳转提示
    val clipboardAutoDetectEnabled: Boolean = false,
    // 已登录且今日未签到时在启动后自动签到
    val autoSignInEnabled: Boolean = true,
    // "default" 表示主题默认配色，其余为内置预设 ID 或 custom
    val colorPalettePreset: String = COLOR_PALETTE_PRESET_DEFAULT,
    // 自定义四色（ARGB hex，如 "#FF4F5F7F"）；null 表示跟随预设
    val customColorPrimary: String? = null,
    val customColorSecondary: String? = null,
    val customColorTertiary: String? = null,
    val customColorError: String? = null,
    // DoH 默认启用；dohAutoStart 控制进程启动时自动激活
    val dohEnabled: Boolean = true,
    val dohAutoStart: Boolean = true,
    val dohServerId: String = "tencent",
    val dohCustomServerName: String = "",
    val dohCustomServerUrl: String = "",
    // DoH TLS 校验是否信任用户安装的设备证书
    val dohUseDeviceCertificates: Boolean = true,
    val dohPreferIpv6: Boolean = false,
    // 网格列数：0 自适应，2-6 固定列数
    val homeGridColumns: Int = 0,
    val collectGridColumns: Int = 0,
    val downloadGridColumns: Int = 0,
    val historyGridColumns: Int = 0,
    val searchGridColumns: Int = 0,
    // 内存优化：限制并发解码并降低采样率，缓解低端设备 OOM；并发上限仅在开启时生效
    val readMemoryOptEnabled: Boolean = false,
    val readDecodeConcurrency: Int = 2,
    // 这些标签的漫画不出现在首页推荐中
    val homeExcludedTags: List<String> = listOf(),
)

const val COLOR_PALETTE_PRESET_DEFAULT = "default"
const val COLOR_PALETTE_PRESET_OCEAN = "ocean"
const val COLOR_PALETTE_PRESET_SUNSET = "sunset"
const val COLOR_PALETTE_PRESET_FOREST = "forest"
const val COLOR_PALETTE_PRESET_LAVENDER = "lavender"
const val COLOR_PALETTE_PRESET_CUSTOM = "custom"
const val COLOR_PALETTE_PRESET_MONET = "monet"

const val READ_MODE_SCROLL = "scroll"
const val READ_MODE_PAGE = "page"
const val READ_MODE_TAP = "tap"
