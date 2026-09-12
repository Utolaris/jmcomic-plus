#!/usr/bin/env bash
# 发布版 JSON 反射兼容性校验：
# 1. 用 build-tools 的 dexdump 导出 minifyReleaseWithR8 产物；
# 2. 校验 Gson 反序列化的 DTO 实例字段在混淆后仍保留原名
#    （登录数据 User、备份文件 BackupFile/BackupMeta、服务器响应包装 ResponseWrapper、
#    远程配置 RemoteSetting、下载缓存 config 等）。
# 用法：先 `./gradlew :app:minifyReleaseWithR8`，再 `./scripts/check-release-json-fields.sh`。
set -euo pipefail
cd "$(dirname "$0")/.."

DEXDUMP="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}/build-tools/37.0.0/dexdump"
DEX="app/build/intermediates/dex/release/minifyReleaseWithR8/classes.dex"
OUT="build/release_dexdump.txt"

[ -x "$DEXDUMP" ] || { echo "dexdump 不存在: $DEXDUMP"; exit 1; }
[ -f "$DEX" ] || { echo "缺少 R8 产物，请先跑 :app:minifyReleaseWithR8"; exit 1; }

"$DEXDUMP" "$DEX" > "$OUT"
python3 scripts/check-release-json-fields.py "$OUT"
