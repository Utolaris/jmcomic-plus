package com.par9uet.jm.backup
import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.GsonBuilder
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

// 备份文件格式版本（v1 旧格式仅 LocalSetting；v2 多内容格式；v3 新增缓存目录备份）
// 受保护备份在 data.ciphertext 中放 AES-GCM 密文；meta.encryptionSalt 为 PBKDF2 盐。
const val BACKUP_FORMAT_VERSION = 3

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
            passwordHash = when (protectionType) {
                BACKUP_PROTECTION_PASSWORD, BACKUP_PROTECTION_BOTH -> {
                    requireNotNull(password) { "password must not be null for protection $protectionType" }
                    sha256(password)
                }
                else -> null
            },
            patternHash = when (protectionType) {
                BACKUP_PROTECTION_PATTERN, BACKUP_PROTECTION_BOTH -> {
                    requireNotNull(pattern) { "pattern must not be null for protection $protectionType" }
                    sha256(pattern)
                }
                else -> null
            },
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
            val key = deriveKey(password, pattern, salt)
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
     * 解析备份 JSON 字符串；当前写入 v3，并兼容 v1/v2。
     * 受保护备份的 data 仍是密文，必须先 [unlockBackup]。
     */
    fun parseBackup(json: String): Result<BackupFile> = runCatching {
        val obj = JsonParser.parseString(json).asJsonObject
        val meta = gson.fromJson(obj.getAsJsonObject("meta"), BackupMeta::class.java)
            ?: error("备份文件缺少 meta 字段")
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
        val key = deriveKey(password, pattern, salt)
        val plain = aesGcmDecrypt(key, ciphertext)
            ?: error("备份密码或图案错误")
        val data = JsonParser.parseString(plain).asJsonObject
        backup.copy(data = data)
    }

    /**
     * 从备份中提取 [LocalSetting]，兼容 v1 旧格式。
     * 调用前必须已 [unlockBackup]；未解锁的密文备份返回 null。
     */
    fun extractLocalSetting(backup: BackupFile): LocalSetting? {
        if (isEncrypted(backup)) return null
        // v2 格式：data.localSetting
        val obj = backup.data.getAsJsonObject("localSetting")
        if (obj != null) return gson.fromJson(obj, LocalSetting::class.java)
        // v1 旧格式：data 直接是 LocalSetting
        if (backup.meta.version <= 1) {
            return runCatching { gson.fromJson(backup.data, LocalSetting::class.java) }.getOrNull()
        }
        return null
    }

    /**
     * 从备份中提取缓存目录备份信息。
     * v1/v2 旧备份无此段，返回空。未解锁的密文备份也返回空。
     * 畸形段（非对象）返回空，不在 UI 回调里抛 ClassCastException。
     */
    fun extractComicCache(backup: BackupFile): ComicCacheBackup {
        if (isEncrypted(backup)) return ComicCacheBackup()
        val element = backup.data.get("comicCache") ?: return ComicCacheBackup()
        if (!element.isJsonObject) return ComicCacheBackup()
        return runCatching {
            gson.fromJson(element, ComicCacheBackup::class.java) ?: ComicCacheBackup()
        }.getOrDefault(ComicCacheBackup())
    }

    fun needsPassword(backup: BackupFile): Boolean {
        if (backup.meta.passwordHash != null) return true
        // Encrypted with unknown metadata: ask for a password first (pattern-only fallback below).
        if (isEncrypted(backup) && backup.meta.patternHash == null &&
            backup.meta.protectionType != BACKUP_PROTECTION_PATTERN
        ) {
            return true
        }
        return backup.meta.protectionType == BACKUP_PROTECTION_PASSWORD ||
            backup.meta.protectionType == BACKUP_PROTECTION_BOTH
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
     * 校验密码（用于恢复时的核验）。
     * 哈希缺失但密文仍在时，接受尝试；真正门闩是 [unlockBackup]。
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

    private fun deriveKey(password: String?, pattern: String?, salt: ByteArray): SecretKeySpec {
        val material = listOfNotNull(
            password?.takeIf { it.isNotEmpty() },
            pattern?.takeIf { it.isNotEmpty() },
        ).joinToString(separator = "|")
        require(material.isNotEmpty()) { "保护备份需要密码或图案" }
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
