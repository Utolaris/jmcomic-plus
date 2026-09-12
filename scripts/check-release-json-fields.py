import re
import sys

# 从 dexdump 输出解析: 类 -> 实例字段名集合
# 注意：本项目的 R8 版本在 mapping.txt 中可能省略"保留原名"成员的字段行，
# 因此字段级兼容性校验必须以 DEX 反汇编为准，不能只看 mapping.txt。
classes = {}
cur = None
in_instance = False
field_re = re.compile(r"name\s*:\s*'([^']+)'")
path = sys.argv[1] if len(sys.argv) > 1 else "build/release_dexdump.txt"
with open(path, encoding="utf-8", errors="replace") as f:
    for line in f:
        m = re.search(r"Class descriptor\s*:\s*'L([^;]+);'", line)
        if m:
            cur = m.group(1).replace("/", ".")
            classes[cur] = []
            in_instance = False
            continue
        if cur is None:
            continue
        if line.startswith("  Instance fields"):
            in_instance = True
            continue
        if line.startswith("  ") and not line.startswith("    "):
            in_instance = False
            continue
        if in_instance:
            fm = field_re.search(line)
            if fm:
                classes[cur].append(fm.group(1))

targets = {
    "com.par9uet.jm.core.model.User": ["id", "username", "password", "avatar", "level", "levelName", "currentLevelExp", "nextLevelExp", "currentCollectCount", "maxCollectCount", "jCoin"],
    "com.par9uet.jm.core.model.RemoteSetting": ["imgHost"],
    "com.par9uet.jm.core.model.SignInData": ["dailyId", "dateMap"],
    "com.par9uet.jm.core.network.ResponseWrapper": ["code", "data", "errorMsg"],
    "com.par9uet.jm.backup.BackupFile": ["meta", "data"],
    "com.par9uet.jm.backup.BackupMeta": ["version", "timestamp", "protectionType", "passwordHash", "patternHash", "includeLocalSetting", "includeComicCache", "comicCacheCount"],
    "com.par9uet.jm.backup.ComicCacheBackup": ["groups"],
    "com.par9uet.jm.backup.ComicGroupBackup": ["id", "name", "authors", "tags", "chapters"],
    "com.par9uet.jm.backup.ChapterBackup": ["id", "name", "sortOrder"],
    "com.par9uet.jm.cache.DownloadComicCacheConfig": ["id", "title", "authors", "tags", "coverPath", "cachePath", "chapters"],
    "com.par9uet.jm.cache.DownloadComicCacheChapter": ["id", "name", "path", "imageCount", "status"],
    "com.par9uet.jm.cache.CacheImageEntry": ["name", "path"],
}
ok = True
for cls, exp in targets.items():
    if cls not in classes:
        print("!! 类不存在于 DEX:", cls)
        ok = False
        continue
    fields = classes[cls]
    missing = [e for e in exp if e not in fields]
    if missing:
        ok = False
        print("!! %s 缺字段: %s（实际: %s）" % (cls, missing, fields))
    else:
        print("OK %s: %d/%d 字段原名" % (cls, len(exp), len(exp)))
print("")
print("结论:", "全部实例字段保留原名，发布版 JSON 反射序列化兼容" if ok else "存在字段被混淆/移除！")
sys.exit(0 if ok else 1)
