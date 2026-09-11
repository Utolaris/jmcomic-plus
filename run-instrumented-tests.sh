#!/usr/bin/env bash
# 在真机（或模拟器）上跑 androidTest 插桩测试，用 adb 直接驱动 instrumentation。
#
# 和 ./gradlew connectedDebugAndroidTest 的区别：这里编译安装一次之后，
# 可以用 adb shell am instrument 反复只跑某一个类或某一条用例，
# 不用每次都重新打包；配合 -l 还能把用例执行期间的 logcat 一起抓下来。
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APK_DIR="$PROJECT_DIR/app/build/outputs/apk"
GRADLE_FLAGS="-Dhttp.proxyHost= -Dhttp.proxyPort= -Dhttps.proxyHost= -Dhttps.proxyPort="

APPLICATION_ID="jmcomic.debug"
TEST_PACKAGE="jmcomic.debug.test"
RUNNER="androidx.test.runner.AndroidJUnitRunner"

# Gradle 找不到 SDK 时只会报一句含糊的 "SDK location not found"。
# 这里按顺序探测常见安装位置，让脚本在没有配 ANDROID_HOME 的机器上也能直接跑。
detect_sdk() {
  if [ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME" ]; then
    printf '%s\n' "$ANDROID_HOME"; return 0
  fi
  if [ -n "${ANDROID_SDK_ROOT:-}" ] && [ -d "$ANDROID_SDK_ROOT" ]; then
    printf '%s\n' "$ANDROID_SDK_ROOT"; return 0
  fi
  local candidate
  for candidate in \
    "$HOME/Library/Android/sdk" \
    "/opt/homebrew/share/android-commandlinetools" \
    "/usr/local/share/android-commandlinetools" \
    "$HOME/Android/Sdk"
  do
    if [ -d "$candidate/platforms" ]; then
      printf '%s\n' "$candidate"; return 0
    fi
  done
  return 1
}

DEVICE=""
CLASS_FILTER=""
PACKAGE_FILTER=""
METHOD_FILTER=""
NO_BUILD=0
WITH_LOGCAT=0

usage() {
  cat <<'USAGE'
用法：./run-instrumented-tests.sh [选项] [设备序列号]

选项：
  -c <类名>     只跑一个测试类，例如 com.par9uet.jm.cache.atom.CacheFilesDeviceTest
  -p <包名>     只跑一个包下的全部测试，例如 com.par9uet.jm.database
  -m <方法名>   配合 -c 只跑其中一条用例
  -l            同时抓取用例执行期间的 logcat（写到 build/instrumented-logcat.txt）
  --no-build    跳过编译安装，直接用手机上已有的 APK 跑
  -h, --help    显示本帮助

不带 -c/-p 时运行全部插桩测试。设备序列号可用 adb devices -l 查看；
只连了一台设备时可以省略。

示例：
  ./run-instrumented-tests.sh                                  # 全量
  ./run-instrumented-tests.sh -p com.par9uet.jm.worker         # 一个包
  ./run-instrumented-tests.sh -c com.par9uet.jm.database.FavoriteStoreRealDatabaseTest -m chineseSearch
  ./run-instrumented-tests.sh --no-build -c com.par9uet.jm.cache.atom.CacheFilesDeviceTest
USAGE
}

die() { printf '%s\n' "$*" >&2; exit 1; }

is_emulator() {
  case "$1" in emulator-*) return 0 ;; esac
  [ "$(adb -s "$1" shell getprop ro.kernel.qemu </dev/null 2>/dev/null | tr -d '\r\n' || true)" = "1" ]
}

list_devices() {
  local serial state
  while read -r serial state; do
    [ -z "$serial" ] && continue
    [ "$state" != "device" ] && continue
    printf '%s\n' "$serial"
  done < <(adb devices | sed -n '2,$p')
}

select_device() {
  local requested="${1:-}" state devices count
  if [ -n "$requested" ]; then
    state="$(adb -s "$requested" get-state 2>/dev/null || true)"
    [ "$state" = "device" ] || die "设备 '$requested' 不可用（状态：${state:-未连接}）。"
    printf '%s\n' "$requested"
    return 0
  fi
  devices="$(list_devices)"
  [ -n "$devices" ] || die "没有检测到 adb 设备。请连接手机并授权调试，或用 adb devices -l 查看序列号。"
  count="$(printf '%s\n' "$devices" | wc -l | tr -d ' ')"
  [ "$count" -eq 1 ] || die "检测到多台设备，请指定序列号：
$(printf '%s\n' "$devices" | sed 's/^/    /')"
  printf '%s\n' "$devices"
}

build_and_install() {
  local serial="$1"
  echo "==> 编译 debug APK 与 androidTest APK..."
  ( cd "$PROJECT_DIR" && ./gradlew $GRADLE_FLAGS assembleDebug assembleDebugAndroidTest --console=plain )

  local app_apk test_apk
  app_apk="$(ls -t "$APK_DIR"/debug/*.apk | head -1)"
  test_apk="$(ls -t "$APK_DIR"/androidTest/debug/*.apk | head -1)"

  echo "==> 安装 $(basename "$app_apk") 与 $(basename "$test_apk")..."
  # 换过签名或降级安装会失败，先卸干净再装。
  adb -s "$serial" uninstall "$APPLICATION_ID" >/dev/null 2>&1 || true
  adb -s "$serial" uninstall "$TEST_PACKAGE" >/dev/null 2>&1 || true
  adb -s "$serial" install -r -t "$app_apk"
  adb -s "$serial" install -r -t "$test_apk"
}

main() {
  while [ $# -gt 0 ]; do
    case "$1" in
      -c) CLASS_FILTER="${2:-}"; [ -n "$CLASS_FILTER" ] || die "-c 需要跟一个类名"; shift 2 ;;
      -p) PACKAGE_FILTER="${2:-}"; [ -n "$PACKAGE_FILTER" ] || die "-p 需要跟一个包名"; shift 2 ;;
      -m) METHOD_FILTER="${2:-}"; [ -n "$METHOD_FILTER" ] || die "-m 需要跟一个方法名"; shift 2 ;;
      -l) WITH_LOGCAT=1; shift ;;
      --no-build) NO_BUILD=1; shift ;;
      -h|--help) usage; exit 0 ;;
      -*) die "未知选项：$1（用 -h 查看用法）" ;;
      *) DEVICE="$1"; shift ;;
    esac
  done

  command -v adb >/dev/null 2>&1 || die "找不到 adb，请安装 Android platform-tools。"

  if [ "$NO_BUILD" -eq 0 ]; then
    local sdk
    sdk="$(detect_sdk)" || die "找不到 Android SDK。请设置 ANDROID_HOME，例如
    export ANDROID_HOME=\$HOME/Library/Android/sdk
（Homebrew 安装的 cmdline-tools 通常在 /opt/homebrew/share/android-commandlinetools）"
    export ANDROID_HOME="$sdk"
    echo "==> Android SDK：$sdk"
  fi

  local serial model
  serial="$(select_device "$DEVICE")"
  model="$(adb -s "$serial" shell getprop ro.product.model </dev/null 2>/dev/null | tr -d '\r\n' || true)"
  is_emulator "$serial" && model="${model:-}（模拟器）"
  echo "==> 目标设备：$serial${model:+ $model}"

  [ "$NO_BUILD" -eq 1 ] || build_and_install "$serial"

  local -a instrument_args=(-w -r -e debug false)
  if [ -n "$CLASS_FILTER" ]; then
    if [ -n "$METHOD_FILTER" ]; then
      instrument_args+=(-e class "${CLASS_FILTER}#${METHOD_FILTER}")
    else
      instrument_args+=(-e class "$CLASS_FILTER")
    fi
  elif [ -n "$PACKAGE_FILTER" ]; then
    instrument_args+=(-e package "$PACKAGE_FILTER")
  fi

  local logcat_file="$PROJECT_DIR/build/instrumented-logcat.txt"
  if [ "$WITH_LOGCAT" -eq 1 ]; then
    mkdir -p "$PROJECT_DIR/build"
    adb -s "$serial" logcat -c
  fi

  echo "==> 运行插桩测试：$TEST_PACKAGE/$RUNNER"
  set +e
  adb -s "$serial" shell am instrument "${instrument_args[@]}" "$TEST_PACKAGE/$RUNNER"
  local exit_code=$?
  set -e

  if [ "$WITH_LOGCAT" -eq 1 ]; then
    adb -s "$serial" logcat -d > "$logcat_file"
    echo "==> logcat 已写入 $logcat_file"
  fi

  if [ "$exit_code" -ne 0 ]; then
    echo "==> 插桩测试失败（退出码 $exit_code）"
  else
    echo "==> 插桩测试通过"
  fi
  exit "$exit_code"
}

main "$@"
