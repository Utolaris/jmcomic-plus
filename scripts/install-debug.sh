#!/usr/bin/env bash
# 编译 debug APK 并一键安装到手机。
#
# adb 会把正在运行的模拟器和真机一起列出来，裸 `adb install` 遇到两台设备会直接报错，
# 所以这里自动跳过模拟器只选真机；要指定设备时把序列号作为参数传进来。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
APK_DIR="$PROJECT_DIR/app/build/outputs/apk/debug"
GRADLE_FLAGS="-Dhttp.proxyHost= -Dhttp.proxyPort= -Dhttps.proxyHost= -Dhttps.proxyPort="

usage() {
  cat <<'USAGE'
用法：./scripts/install-debug.sh [设备序列号]

不带参数时自动选择已连接的真机（模拟器会被忽略）。
序列号可以用 adb devices -l 查看。
USAGE
}

# 列出所有设备并标注真机/模拟器，回答「哪一台才是我的手机」。
device_table() {
  local serial state details kind
  # `adb devices -l` 在设备状态后面还有 model 等字段，所以要多读一个变量接住剩余内容。
  while read -r serial state details; do
    if [ -z "$serial" ]; then
      continue
    fi
    if [ "$state" != "device" ]; then
      printf '    %s [%s]\n' "$serial" "$state"
      continue
    fi
    if is_emulator "$serial"; then
      kind="模拟器"
    else
      kind="真机"
    fi
    printf '    %s [%s]\n' "$serial" "$kind"
  done < <(adb devices -l | sed -n '2,$p')
}

# 模拟器的序列号固定是 emulator-<port>，再加 getprop 兜底，避免无线调试的真机被误判。
# adb shell 会接管标准输入，所以这里显式 </dev/null，免得吃掉调用方循环里的设备列表。
is_emulator() {
  local serial="$1" qemu characteristics
  case "$serial" in
    emulator-*) return 0 ;;
  esac
  qemu="$(adb -s "$serial" shell getprop ro.kernel.qemu </dev/null 2>/dev/null | tr -d '\r\n' || true)"
  if [ "$qemu" = "1" ]; then
    return 0
  fi
  characteristics="$(adb -s "$serial" shell getprop ro.build.characteristics </dev/null 2>/dev/null | tr -d '\r\n' || true)"
  case "$characteristics" in
    *emulator*) return 0 ;;
  esac
  return 1
}

list_phones() {
  local serial state details
  while read -r serial state details; do
    if [ -z "$serial" ] || [ "$state" != "device" ]; then
      continue
    fi
    if is_emulator "$serial"; then
      continue
    fi
    printf '%s\n' "$serial"
  done < <(adb devices | sed -n '2,$p')
}

select_device() {
  local requested="${1:-}" state
  if [ -n "$requested" ]; then
    # 先确认设备在线，免得白编译一轮才在安装时报错。
    state="$(adb -s "$requested" get-state 2>/dev/null || true)"
    if [ "$state" != "device" ]; then
      echo "设备 '$requested' 不可用（当前状态：${state:-未连接}）。当前 adb 设备：" >&2
      device_table >&2
      return 1
    fi
    if is_emulator "$requested"; then
      echo "注意：$requested 是模拟器，不是真机。" >&2
    fi
    printf '%s\n' "$requested"
    return 0
  fi
  local phones count
  phones="$(list_phones)"
  if [ -z "$phones" ]; then
    echo "没有检测到已连接的真机（模拟器会被忽略）。当前 adb 设备：" >&2
    device_table >&2
    echo "手机请打开「开发者选项 → 无线调试」并完成配对；USB 连接时确认已授权本机调试。" >&2
    return 1
  fi
  count="$(printf '%s\n' "$phones" | wc -l | tr -d ' ')"
  if [ "$count" -gt 1 ]; then
    echo "检测到多台真机，请把序列号作为参数指定其中一台：" >&2
    printf '%s\n' "$phones" | sed 's/^/    /' >&2
    return 1
  fi
  printf '%s\n' "$phones"
}

main() {
  case "${1:-}" in
    -h|--help)
      usage
      return 0
      ;;
  esac
  if ! command -v adb >/dev/null 2>&1; then
    echo "找不到 adb，请安装 Android platform-tools 并确保它在 PATH 中。" >&2
    return 1
  fi

  local serial model
  serial="$(select_device "${1:-}")" || return 1
  model="$(adb -s "$serial" shell getprop ro.product.model 2>/dev/null | tr -d '\r\n' || true)"

  cd "$PROJECT_DIR"
  echo "==> 目标设备：$serial${model:+（${model}）}"
  echo "==> 编译 debug APK..."
  ./gradlew $GRADLE_FLAGS assembleDebug --console=plain

  local apk_file
  apk_file="$(ls -t "$APK_DIR"/*.apk | head -1)"
  echo "==> 安装 $(basename "$apk_file")..."
  adb -s "$serial" install -r -t "$apk_file"
  echo "==> 完成，已安装到 $serial"
}

main "$@"
