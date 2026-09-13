package com.par9uet.jm.backup
import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.par9uet.jm.data.models.LocalSetting
import com.par9uet.jm.utils.logError
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

// 备份保护方式
const val BACKUP_PROTECTION_NONE = "none"
const val BACKUP_PROTECTION_PASSWORD = "password"
const val BACKUP_PROTECTION_PATTERN = "pattern"
const val BACKUP_PROTECTION_BOTH = "both"

// 备份文件格式版本：
// v1 旧格式仅 LocalSetting；v2 多内容格式；v3 新增缓存目录备份；
// v4 移除快速凭据摘要，凭据正确性只靠派生密钥解密 AES-GCM 成功与否判断。
// 受保护备份在 data.ciphertext 中放 AES-GCM 密文；meta.encryptionSalt 为 PBKDF2 盐。
const val BACKUP_FORMAT_VERSION = 4

/** Oldest format this reader accepts; unknown versions are rejected. */
const val BACKUP_MIN_SUPPORTED_VERSION = 1

private const val PBKDF2_ITERATIONS = 120_000
private const val GCM_IV_SIZE_BYTES = 12
private const val MAX_BACKUP_BYTES = 32L * 1024L * 1024L

/**
 * 用户选择要备份的内容类型。
 */
data class BackupContentOptions(
    val includeLocalSetting: Boolean = true,
    val includeComicCache: Boolean = false,
) {
    val isEmpty: Boolean get() = !includeLocalSetting && !includeComicCache
}

/**
 * 备份文件元信息：包含版本、时间戳、保护方式与备份内容标记。
 *
 * v4 起不再写入 [passwordHash]/[patternHash]：无盐快速摘要允许离线一次 SHA-256 猜测，
 * 绕过慢速 PBKDF2。字段保留仅为读取 v1–v3 旧文件；新文件中恒为 null。
 */
data class BackupMeta(
    val version: Int = BACKUP_FORMAT_VERSION,
    val timestamp: Long = System.currentTimeMillis(),
    val protectionType: String = BACKUP_PROTECTION_NONE,
    val passwordHash: String? = null,
    val patternHash: String? = null,
    val includeLocalSetting: Boolean = true,
    val includeComicCache: Boolean = false,
    val comicCacheCount: Int = 0,
    /** PBKDF2 salt (base64) for content encryption; null for unprotected backups. */
    val encryptionSalt: String? = null,
)

/**
 * 缓存目录备份：单个章节的信息。
 * 不包含图片文件，只保留章节 ID（即漫画 ID）和排序信息。
 */
data class ChapterBackup(
    val id: Int,
    val name: String,
    val sortOrder: Long,
)

/**
 * 缓存目录备份：一个漫画组的信息。
 * 多章节漫画会包含多个 ChapterBackup；单篇漫画 chapters 列表只有一个元素（id 与 groupId 相同）。
 */
data class ComicGroupBackup(
    val id: Int,
    val name: String,
    val authors: List<String>,
    val tags: List<String>,
    val chapters: List<ChapterBackup>,
) {
    val chapterCount: Int get() = chapters.size
}

/**
 * 缓存目录备份整体结构。
 */
data class ComicCacheBackup(
    val groups: List<ComicGroupBackup> = emptyList(),
)

/**
 * 备份文件结构：meta + data。
 * v2+: data 下按内容类型分段。
 * v1（兼容旧文件）: data 直接是 LocalSetting 的 JSON。
 */
data class BackupFile(
    val meta: BackupMeta,
    val data: JsonObject,
)

/**
 * Outcome of reading one content section from an unlocked backup.
 * Distinguishes “section not present” (option off / old format) from “section corrupted”.
 */
sealed class BackupSectionResult<out T> {
    data class Success<T>(val value: T) : BackupSectionResult<T>()
    data object Missing : BackupSectionResult<Nothing>()
    data object Corrupted : BackupSectionResult<Nothing>()
}

class BackupManager {
    private val gson: Gson = GsonBuilder()
        .disableHtmlEscaping()
        .setPrettyPrinting()
        .create()

    /**
     * 创建备份 JSON 字符串。
     */
    fun createBackup(
        localSetting: LocalSetting?,
        comicCache: ComicCacheBackup? = null,
        options: BackupContentOptions,
        protectionType: String = BACKUP_PROTECTION_NONE,
        password: String? = null,
        pattern: String? = null,
    ): String {
        require(!options.isEmpty) { "至少需要选择一项备份内容" }
        val protected = protectionType != BACKUP_PROTECTION_NONE
        val salt: ByteArray? = if (protected) {
            when (protectionType) {
                BACKUP_PROTECTION_PASSWORD, BACKUP_PROTECTION_BOTH ->
                    requireNotNull(password) { "password must not be null for protection $protectionType" }
                BACKUP_PROTECTION_PATTERN ->
                    requireNotNull(pattern) { "pattern must not be null for protection $protectionType" }
            }
            SecureRandom().generateSeed(16)
        } else {
            null
        }
        val meta = BackupMeta(
            version = BACKUP_FORMAT_VERSION,
            timestamp = System.currentTimeMillis(),
            protectionType = protectionType,
            // v4+ never writes fast credential digests — they are an offline guessing oracle.
            passwordHash = null,
            patternHash = null,
            includeLocalSetting = options.includeLocalSetting,
            includeComicCache = options.includeComicCache && comicCache != null,
            comicCacheCount = comicCache?.groups?.size ?: 0,
            encryptionSalt = salt?.let { Base64.getEncoder().encodeToString(it) },
        )

        val data = JsonObject()
        if (options.includeLocalSetting && localSetting != null) {
            val sanitized = localSetting.copy(
                appLockPassword = "",
                appLockPattern = "",
            )
            data.add("localSetting", gson.toJsonTree(sanitized))
        }
        if (options.includeComicCache && comicCache != null) {
            data.add("comicCache", gson.toJsonTree(comicCache))
        }

        val payload = if (protected && salt != null) {
            val key = deriveKey(password, pattern, salt, structured = true)
            val encrypted = aesGcmEncrypt(key, gson.toJson(data))
            val envelope = JsonObject()
            envelope.addProperty("ciphertext", encrypted)
            envelope
        } else {
            data
        }

        val backup = BackupFile(meta = meta, data = payload)
        return gson.toJson(backup)
    }

    /**
     * 解析备份 JSON 字符串。接受 [BACKUP_MIN_SUPPORTED_VERSION]–[BACKUP_FORMAT_VERSION]；
     * 拒绝未知版本，避免把未来格式静默当成空/损坏内容。
     * 受保护备份的 data 仍是密文，必须先 [unlockBackup]。
     */
    fun parseBackup(json: String): Result<BackupFile> = runCatching {
        val obj = JsonParser.parseString(json).asJsonObject
        val meta = gson.fromJson(obj.getAsJsonObject("meta"), BackupMeta::class.java)
            ?: error("备份文件缺少 meta 字段")
        if (meta.version < BACKUP_MIN_SUPPORTED_VERSION || meta.version > BACKUP_FORMAT_VERSION) {
            error("不支持的备份版本：${meta.version}")
        }
        val data = obj.getAsJsonObject("data") ?: error("备份文件缺少 data 字段")
        BackupFile(meta = meta, data = data)
    }

    /** True when content is AES-GCM encrypted — even if meta.protectionType was stripped. */
    fun isEncrypted(backup: BackupFile): Boolean {
        return backup.data.has("ciphertext")
    }

    /**
     * Decrypts protected content into plaintext sections. Wrong credentials or a tampered
     * blob fail; stripping protectionType does not reveal plaintext.
     *
     * v4 uses length-prefixed credential material; v1–v3 files keep the legacy join encoding.
     */
    fun unlockBackup(
        backup: BackupFile,
        password: String? = null,
        pattern: String? = null,
    ): Result<BackupFile> = runCatching {
        if (!isEncrypted(backup)) return@runCatching backup
        val saltB64 = backup.meta.encryptionSalt
            ?: error("备份缺少加密盐")
        val salt = Base64.getDecoder().decode(saltB64)
        val ciphertext = backup.data.get("ciphertext")?.asString
            ?: error("备份缺少密文")
        val structured = backup.meta.version >= 4
        val key = deriveKey(password, pattern, salt, structured = structured)
        val plain = aesGcmDecrypt(key, ciphertext)
            ?: error("备份密码或图案错误")
        val parsed = JsonParser.parseString(plain)
        if (!parsed.isJsonObject) error("备份内容已损坏")
        backup.copy(data = parsed.asJsonObject)
    }

    /**
     * 从备份中提取 [LocalSetting]。调用前必须已 [unlockBackup]。
     * 区分：未包含该段 / 旧版本无此段（Missing）、段损坏（Corrupted）、解析成功（Success）。
     */
    fun extractLocalSetting(backup: BackupFile): BackupSectionResult<LocalSetting> {
        if (isEncrypted(backup)) return BackupSectionResult.Missing
        // v2+ format: data.localSetting
        val element = backup.data.get("localSetting")
        if (element != null) {
            if (!element.isJsonObject) return BackupSectionResult.Corrupted
            return parseLocalSetting(element.asJsonObject)
        }
        // v1 legacy format: data itself is LocalSetting
        if (backup.meta.version <= 1) {
            return parseLocalSetting(backup.data)
        }
        return BackupSectionResult.Missing
    }

    /**
     * 从备份中提取缓存目录备份信息。
     * v1/v2 旧备份无此段 → Missing。未解锁的密文备份 → Missing。
     * 畸形段（非对象、groups 为 null、元素为 null、chapters 为 null）→ Corrupted，
     * 不在 UI 回调里抛 NPE/CCE，也不把损坏降级成“空内容恢复成功”。
     */
    fun extractComicCache(backup: BackupFile): BackupSectionResult<ComicCacheBackup> {
        if (isEncrypted(backup)) return BackupSectionResult.Missing
        val element = backup.data.get("comicCache") ?: return BackupSectionResult.Missing
        if (!element.isJsonObject) return BackupSectionResult.Corrupted
        return parseComicCache(element.asJsonObject)
    }

    fun needsPassword(backup: BackupFile): Boolean {
        // Unencrypted backups never need credentials.
        if (!isEncrypted(backup)) {
            return backup.meta.protectionType == BACKUP_PROTECTION_PASSWORD ||
                backup.meta.protectionType == BACKUP_PROTECTION_BOTH
        }
        if (backup.meta.protectionType == BACKUP_PROTECTION_PASSWORD ||
            backup.meta.protectionType == BACKUP_PROTECTION_BOTH
        ) {
            return true
        }
        if (backup.meta.protectionType == BACKUP_PROTECTION_PATTERN) {
            return false
        }
        // protectionType stripped/unknown: legacy password hash, else assume password first.
        if (backup.meta.passwordHash != null) return true
        return backup.meta.patternHash == null
    }

    fun needsPattern(backup: BackupFile): Boolean {
        if (backup.meta.protectionType == BACKUP_PROTECTION_PATTERN ||
            backup.meta.protectionType == BACKUP_PROTECTION_BOTH
        ) {
            return true
        }
        // protectionType stripped but only a pattern hash remains → still require the pattern.
        return isEncrypted(backup) && backup.meta.patternHash != null
    }

    /**
     * Lightweight password gate used by the UI to decide whether to attempt unlock.
     * v4 files carry no fast digest: any non-empty password is accepted here and the real
     * check is [unlockBackup] (PBKDF2 + AES-GCM tag). Legacy hashes stay optional compatibility.
     */
    fun verifyPassword(backup: BackupFile, password: String): Boolean {
        val expected = backup.meta.passwordHash
            ?: return password.isNotEmpty() && isEncrypted(backup)
        return constantEquals(expected, sha256(password))
    }

    fun verifyPattern(backup: BackupFile, pattern: String): Boolean {
        val expected = backup.meta.patternHash
            ?: return pattern.isNotEmpty() && isEncrypted(backup)
        return constantEquals(expected, sha256(pattern))
    }

    fun readFromUri(context: Context, uri: Uri): String? {
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                // Cap backup size while reading so a huge/abusive file cannot OOM first.
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    total += read
                    if (total > MAX_BACKUP_BYTES) {
                        logError("BackupManager", "备份文件过大：$total")
                        return@use null
                    }
                    out.write(buffer, 0, read)
                }
                out.toByteArray().toString(Charsets.UTF_8)
            }
        }.getOrElse {
            logError("BackupManager", "读取备份文件失败: ${it.message}")
            null
        }
    }

    /**
     * 从 DownloadComicDao 的数据生成缓存目录备份。
     * 只备份漫画编号与章节信息，不备份图片文件本身。
     */
    suspend fun buildComicCacheBackup(
        allDownloads: List<com.par9uet.jm.database.model.DownloadComic>
    ): ComicCacheBackup {
        // 按 groupId 聚合（单篇漫画 groupId=0，以自身 id 作为组 ID）
        val grouped = allDownloads.groupBy { it.groupId.takeIf { g -> g != 0 } ?: it.id }
        val groups = grouped.map { (groupId, items) ->
            val first = items.first()
            val chapters = items
                .sortedBy { it.createTime }
                .map { item ->
                    ChapterBackup(
                        id = item.id,
                        name = item.chapterName,
                        sortOrder = item.createTime,
                    )
                }
            ComicGroupBackup(
                id = groupId,
                name = first.groupName.ifBlank { first.name },
                authors = first.authorList,
                tags = first.tagList,
                chapters = chapters,
            )
        }.sortedBy { it.id }
        return ComicCacheBackup(groups = groups)
    }

    fun writeToUri(context: Context, uri: Uri, content: String): Boolean {
        return runCatching {
            context.contentResolver.openOutputStream(uri)?.use { output ->
                output.write(content.toByteArray(Charsets.UTF_8))
                true
            } ?: false
        }.getOrElse {
            logError("BackupManager", "写入备份文件失败: ${it.message}")
            false
        }
    }

    private fun parseLocalSetting(obj: JsonObject): BackupSectionResult<LocalSetting> {
        val setting = try {
            gson.fromJson(obj, LocalSetting::class.java)
        } catch (_: Exception) {
            return BackupSectionResult.Corrupted
        } ?: return BackupSectionResult.Corrupted
        return if (isValidLocalSetting(setting)) {
            BackupSectionResult.Success(setting)
        } else {
            BackupSectionResult.Corrupted
        }
    }

    /**
     * Gson can assign null to Kotlin non-null fields (`{"blockedTagList":null}`); treat any
     * such assignment as corrupted instead of letting it escape into typed call sites.
     */
    @Suppress("SENSELESS_COMPARISON")
    private fun isValidLocalSetting(setting: LocalSetting): Boolean {
        if (setting.api == null || setting.theme == null) return false
        if (setting.blockedTagList == null) return false
        if (setting.blockedTagTemplateList == null) return false
        if (setting.homeExcludedTags == null) return false
        if (setting.appLockPassword == null || setting.appLockPattern == null) return false
        if (setting.blockedTagTemplateList.any { it == null || it.name == null || it.tagList == null }) {
            return false
        }
        if (setting.blockedTagList.any { it == null }) return false
        if (setting.homeExcludedTags.any { it == null }) return false
        return true
    }

    private fun parseComicCache(obj: JsonObject): BackupSectionResult<ComicCacheBackup> {
        val groupsElement = obj.get("groups") ?: return BackupSectionResult.Corrupted
        if (groupsElement.isJsonNull || !groupsElement.isJsonArray) {
            return BackupSectionResult.Corrupted
        }
        val groups = parseGroupArray(groupsElement.asJsonArray)
            ?: return BackupSectionResult.Corrupted
        return BackupSectionResult.Success(ComicCacheBackup(groups = groups))
    }

    private fun parseGroupArray(array: JsonArray): List<ComicGroupBackup>? {
        val groups = ArrayList<ComicGroupBackup>(array.size())
        for (item in array) {
            val group = parseGroup(item) ?: return null
            groups += group
        }
        return groups
    }

    private fun parseGroup(element: JsonElement): ComicGroupBackup? {
        if (element.isJsonNull || !element.isJsonObject) return null
        val obj = element.asJsonObject
        val id = obj.get("id")?.takeIf { !it.isJsonNull }?.asInt ?: return null
        val name = obj.get("name")?.takeIf { !it.isJsonNull }?.asString ?: return null
        val authors = parseStringList(obj.get("authors")) ?: return null
        val tags = parseStringList(obj.get("tags")) ?: return null
        val chaptersElement = obj.get("chapters") ?: return null
        if (chaptersElement.isJsonNull || !chaptersElement.isJsonArray) return null
        val chapters = ArrayList<ChapterBackup>(chaptersElement.asJsonArray.size())
        for (item in chaptersElement.asJsonArray) {
            chapters += parseChapter(item) ?: return null
        }
        return ComicGroupBackup(
            id = id,
            name = name,
            authors = authors,
            tags = tags,
            chapters = chapters,
        )
    }

    private fun parseChapter(element: JsonElement): ChapterBackup? {
        if (element.isJsonNull || !element.isJsonObject) return null
        val obj = element.asJsonObject
        val id = obj.get("id")?.takeIf { !it.isJsonNull }?.asInt ?: return null
        val name = obj.get("name")?.takeIf { !it.isJsonNull }?.asString ?: return null
        val sortOrder = obj.get("sortOrder")?.takeIf { !it.isJsonNull }?.asLong ?: return null
        return ChapterBackup(id = id, name = name, sortOrder = sortOrder)
    }

    private fun parseStringList(element: JsonElement?): List<String>? {
        if (element == null || element.isJsonNull || !element.isJsonArray) return null
        val result = ArrayList<String>(element.asJsonArray.size())
        for (item in element.asJsonArray) {
            if (item.isJsonNull) return null
            result += item.asString
        }
        return result
    }

    private fun sha256(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun constantEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var result = 0
        for (i in a.indices) {
            result = result or (a[i].code xor b[i].code)
        }
        return result == 0
    }

    /**
     * Structured PBKDF2 material for v4+: each credential is length-prefixed so password+pattern
     * concatenations cannot be confused (password "a|2:bc" ≠ password "a" + pattern "bc").
     */
    private fun encodeCredentialMaterial(password: String?, pattern: String?): String {
        val parts = listOfNotNull(
            password?.takeIf { it.isNotEmpty() },
            pattern?.takeIf { it.isNotEmpty() },
        )
        require(parts.isNotEmpty()) { "保护备份需要密码或图案" }
        return parts.joinToString(separator = "|") { "${it.length}:$it" }
    }

    /** v1–v3 material: plain join. Kept only so old encrypted files still unlock. */
    private fun encodeLegacyCredentialMaterial(password: String?, pattern: String?): String {
        val material = listOfNotNull(
            password?.takeIf { it.isNotEmpty() },
            pattern?.takeIf { it.isNotEmpty() },
        ).joinToString(separator = "|")
        require(material.isNotEmpty()) { "保护备份需要密码或图案" }
        return material
    }

    private fun deriveKey(
        password: String?,
        pattern: String?,
        salt: ByteArray,
        structured: Boolean,
    ): SecretKeySpec {
        val material = if (structured) {
            encodeCredentialMaterial(password, pattern)
        } else {
            encodeLegacyCredentialMaterial(password, pattern)
        }
        val spec = PBEKeySpec(material.toCharArray(), salt, PBKDF2_ITERATIONS, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return try {
            SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun aesGcmEncrypt(key: SecretKeySpec, plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(cipher.iv + encrypted)
    }

    private fun aesGcmDecrypt(key: SecretKeySpec, payload: String): String? {
        return try {
            val bytes = Base64.getDecoder().decode(payload)
            if (bytes.size <= GCM_IV_SIZE_BYTES) return null
            val iv = bytes.copyOfRange(0, GCM_IV_SIZE_BYTES)
            val encrypted = bytes.copyOfRange(GCM_IV_SIZE_BYTES, bytes.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (_: GeneralSecurityException) {
            null
        } catch (_: Exception) {
            null
        }
    }
}
