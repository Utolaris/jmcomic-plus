#!/usr/bin/env bash
# 发布版 JSON 反射兼容性校验：
# 1. 用 build-tools 的 dexdump 导出 minifyReleaseWithR8 产物；
# 2. 校验 Gson 反序列化的 DTO 实例字段在混淆后仍保留原名
#    （登录数据 User、备份文件 BackupFile/BackupMeta、服务器响应包装 ResponseWrapper、
#    远程配置 RemoteSetting、下载缓存 config 等）。
# 用法：先 `./gradlew :app:minifyReleaseWithR8`，再 `./scripts/check-release-json-fields.sh`。
set -euo pipefail
cd "$(dirname "$0")/.."

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ]; then
  for candidate in \
    "$HOME/Library/Android/sdk" \
    "/opt/homebrew/share/android-commandlinetools" \
    "/usr/local/share/android-commandlinetools"
  do
    if [ -d "$candidate/platforms" ]; then SDK="$candidate"; break; fi
  done
fi
[ -n "$SDK" ] || { echo "找不到 Android SDK，请设置 ANDROID_HOME"; exit 1; }

DEXDUMP=""
if [ -d "$SDK/build-tools" ]; then
  # Prefer the newest installed build-tools.
  DEXDUMP="$(ls -1d "$SDK"/build-tools/*/dexdump 2>/dev/null | sort -V | tail -1 || true)"
fi
DEX="app/build/intermediates/dex/release/minifyReleaseWithR8/classes.dex"
OUT="build/release_dexdump.txt"
mkdir -p build

[ -n "$DEXDUMP" ] && [ -x "$DEXDUMP" ] || {
  echo "在 $SDK/build-tools 下找不到可执行的 dexdump（请安装对应 build-tools）"
  exit 1
}
[ -f "$DEX" ] || { echo "缺少 R8 产物，请先跑 :app:minifyReleaseWithR8"; exit 1; }

echo "==> dexdump: $DEXDUMP"
"$DEXDUMP" "$DEX" > "$OUT"
python3 scripts/check-release-json-fields.py "$OUT"
