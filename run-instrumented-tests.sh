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
FRESH_INSTALL=0
START_APP=0
WITH_LOGCAT=0
STALL_SECONDS=180

usage() {
  cat <<'USAGE'
用法：./run-instrumented-tests.sh [选项] [设备序列号]

选项：
  -c <类名>     只跑一个测试类，例如 com.par9uet.jm.cache.atom.CacheFilesDeviceTest
  -p <包名>     只跑一个包下的全部测试，例如 com.par9uet.jm.database
  -m <方法名>   配合 -c 只跑其中一条用例
  -l            同时抓取用例执行期间的 logcat（写到 build/instrumented-logcat.txt）
  --no-build    跳过编译安装，直接用手机上已有的 APK 跑
  --fresh       先卸载应用再装（应用数据、登录会话会全部丢失；默认是覆盖安装并保留数据）
  --start-app   跑之前先把应用切到前台（UI 用例需要：别的应用在前台时 Compose 的等待不会返回）
  --stall <秒>  多久没有新输出就判定卡死并中止，默认 180
  -h, --help    显示本帮助

不带 -c/-p 时运行全部插桩测试。设备序列号可用 adb devices -l 查看；
只连了一台设备时可以省略。

结果以输出流为准（adb 的退出码不可靠：用例失败、类名写错、runner 没起来都可能返回 0），
原始输出留在 build/instrumented-output.txt，用例数为 0 也算失败。

安装默认是覆盖安装（`install -r`），应用的登录会话、设置和下载记录都会留着；
只有覆盖失败或显式 --fresh 才会卸载重装，那时数据会丢。

示例：
  ./run-instrumented-tests.sh                                  # 全量
  ./run-instrumented-tests.sh -p com.par9uet.jm.worker         # 一个包
  ./run-instrumented-tests.sh -c com.par9uet.jm.database.FavoriteStoreRealDatabaseTest -m chineseSearch
  ./run-instrumented-tests.sh --no-build -c com.par9uet.jm.cache.atom.CacheFilesDeviceTest
  ./run-instrumented-tests.sh --fresh                          # 干净安装后再跑（会清掉登录数据）
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

# 覆盖安装优先。`install -r` 会保留应用数据——登录会话、设置、下载记录都在里面，
# 而"先卸载再装"会把这些全清掉，装完是个没登录的干净应用（UI 用例因此跑不起来）。
# 只有覆盖失败（签名不一致、版本降级）才卸载重装，并明确提示数据没了。
install_apk() {
  local serial="$1" package="$2" apk="$3"
  if adb -s "$serial" install -r -t "$apk"; then return 0; fi
  echo "==> 覆盖安装 $package 失败（签名不一致或版本降级），改成卸载后重装：应用数据会全部丢失。"
  adb -s "$serial" uninstall "$package" >/dev/null 2>&1 || true
  adb -s "$serial" install -r -t "$apk"
}

build_and_install() {
  local serial="$1"
  echo "==> 编译 debug APK 与 androidTest APK..."
  ( cd "$PROJECT_DIR" && ./gradlew $GRADLE_FLAGS assembleDebug assembleDebugAndroidTest --console=plain )

  local app_apk test_apk
  app_apk="$(ls -t "$APK_DIR"/debug/*.apk | head -1)"
  test_apk="$(ls -t "$APK_DIR"/androidTest/debug/*.apk | head -1)"

  echo "==> 安装 $(basename "$app_apk") 与 $(basename "$test_apk")..."
  if [ "$FRESH_INSTALL" -eq 1 ]; then
    echo "==> --fresh：先卸载 $APPLICATION_ID，应用数据（含登录会话）会全部丢失。"
    adb -s "$serial" uninstall "$APPLICATION_ID" >/dev/null 2>&1 || true
    adb -s "$serial" install -r -t "$app_apk"
  else
    install_apk "$serial" "$APPLICATION_ID" "$app_apk"
  fi
  install_apk "$serial" "$TEST_PACKAGE" "$test_apk"
}

# 盯着 instrumentation 的输出：长时间没有新内容就判定卡死，主动中止并把当时的前台窗口记下来。
#
# UI 用例最容易这样：Compose 的等待要求当前界面处于 resumed，别的应用（聊天、通知全屏意图）
# 一旦抢到前台，等待就永远不返回，`am instrument` 会一直挂着——看起来像"测试跑不完"。
# 卡死记录写到单独的 [report_file]：tee 也在写同一份输出，两个写者各持自己的偏移会互相覆盖，
# 所以由调用方在收尾时再合并。
start_stall_watchdog() {
  local serial="$1" watch_file="$2" report_file="$3" stall_seconds="$4"
  local size last_size=-2 now last_change foreground
  last_change="$(date +%s)"
  while :; do
    sleep 5
    size="$(wc -c < "$watch_file" 2>/dev/null || echo -1)"
    now="$(date +%s)"
    if [ "$size" != "$last_size" ]; then
      last_size="$size"
      last_change="$now"
      continue
    fi
    [ $((now - last_change)) -ge "$stall_seconds" ] || continue
    foreground="$(adb -s "$serial" shell dumpsys window 2>/dev/null | grep mCurrentFocus | head -1 | tr -d '\r' | sed 's/^ *//')"
    {
      printf '\n==> 卡死判定：%ss 没有新的 instrumentation 输出，脚本主动中止。\n' "$stall_seconds"
      printf '==> 卡死时的前台窗口：%s\n' "${foreground:-未知}"
      printf '%s\n' '==> UI 用例请用 --start-app 先把应用切到前台，并保证跑的过程中没人操作手机。'
    } > "$report_file"
    printf '==> 卡死判定：%ss 没有新输出，正在中止（前台窗口：%s）\n' "$stall_seconds" "${foreground:-未知}"
    adb -s "$serial" shell am force-stop "$APPLICATION_ID" >/dev/null 2>&1 || true
    adb -s "$serial" shell am force-stop "$TEST_PACKAGE" >/dev/null 2>&1 || true
    return 0
  done
}

# 从输出里取用例数。汇总行和运行期间的 numtests 都会出现，取最大值。
# 取不到就返回空——那说明输出格式不认识，此时不拿它当失败依据。
instrumentation_case_count() {
  { grep -oE 'OK \([0-9]+ tests?\)' "$1" || true
    grep -oE 'Tests run: [0-9]+' "$1" || true
    grep -oE 'numtests=[0-9]+' "$1" || true
  } 2>/dev/null | grep -oE '[0-9]+' | sort -n | tail -1
}

# 判定一次插桩结果：成功时返回 0 并打印用例数（可能为空），失败时打印原因并返回 1。
#
# 不能只看 adb 的退出码：用例失败、筛选条件一条都没匹配到、甚至 runner 没起来，
# `adb shell am instrument` 都可能返回 0，于是失败被报成"通过"。这里一律以输出流里的
# 信号为准：出现任何"确定失败"的标记、或者一条用例都没跑到，都算失败。
assess_instrumentation() {
  local output="$1" adb_status="$2" cases reasons=""

  [ "$adb_status" -eq 0 ] || reasons="$reasons
  - adb 退出码 ${adb_status}（设备断连，或命令根本没跑起来）"
  if grep -q 'INSTRUMENTATION_FAILED' "$output" 2>/dev/null; then
    reasons="$reasons
  - instrumentation 没能启动（runner 不存在，或测试进程崩溃）"
  fi
  if grep -q 'shortMsg=' "$output" 2>/dev/null; then
    reasons="$reasons
  - instrumentation 异常退出"
  fi
  if grep -q 'FAILURES!!!' "$output" 2>/dev/null; then
    reasons="$reasons
  - 有用例失败"
  fi
  # 单条用例的通过是 0，失败是 -2、抛异常是 -1（-3/-4 是被忽略/假设不成立，不算失败）。
  if grep -qE 'INSTRUMENTATION_STATUS_CODE: -[12]' "$output" 2>/dev/null; then
    reasons="$reasons
  - 有用例断言失败或抛异常"
  fi
  if grep -qE 'Failures: [1-9]|Errors: [1-9]' "$output" 2>/dev/null; then
    reasons="$reasons
  - 汇总行报告了失败"
  fi
  if grep -q '卡死判定' "$output" 2>/dev/null; then
    reasons="$reasons
  - 长时间没有输出被判为卡死（多半是别的应用抢了前台，卡死时的前台窗口见输出文件）"
  fi
  if ! grep -q 'INSTRUMENTATION_CODE: -1' "$output" 2>/dev/null; then
    reasons="$reasons
  - 输出里没有 INSTRUMENTATION_CODE: -1，测试没有正常跑完"
  fi

  cases="$(instrumentation_case_count "$output" || true)"
  if [ -z "$reasons" ] && [ -n "$cases" ] && [ "$cases" -eq 0 ]; then
    reasons="
  - 一条用例都没跑到（检查 -c 的类名、-m 的方法名是否写对，-p 的包名下是否有用例）"
  fi

  if [ -n "$reasons" ]; then
    printf '%s' "$reasons"
    return 1
  fi
  printf '%s' "$cases"
  return 0
}

main() {
  while [ $# -gt 0 ]; do
    case "$1" in
      -c) CLASS_FILTER="${2:-}"; [ -n "$CLASS_FILTER" ] || die "-c 需要跟一个类名"; shift 2 ;;
      -p) PACKAGE_FILTER="${2:-}"; [ -n "$PACKAGE_FILTER" ] || die "-p 需要跟一个包名"; shift 2 ;;
      -m) METHOD_FILTER="${2:-}"; [ -n "$METHOD_FILTER" ] || die "-m 需要跟一个方法名"; shift 2 ;;
      -l) WITH_LOGCAT=1; shift ;;
      --no-build) NO_BUILD=1; shift ;;
      --fresh) FRESH_INSTALL=1; shift ;;
      --start-app) START_APP=1; shift ;;
      --stall)
        STALL_SECONDS="${2:-}"
        case "$STALL_SECONDS" in ''|*[!0-9]*) die "--stall 需要一个秒数（例如 --stall 120）" ;; esac
        shift 2 ;;
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
  local output_file="$PROJECT_DIR/build/instrumented-output.txt"
  mkdir -p "$PROJECT_DIR/build"
  if [ "$WITH_LOGCAT" -eq 1 ]; then
    adb -s "$serial" logcat -c
  fi

  echo "==> 运行插桩测试：$TEST_PACKAGE/$RUNNER"
  echo "==> 超过 ${STALL_SECONDS}s 没有新输出会判定卡死并主动中止（--stall 可改）"
  if [ "$START_APP" -eq 1 ]; then
    # UI 用例要应用在前台：先自己拉起来，避免一上来就被别的应用压在后台。
    adb -s "$serial" shell monkey -p "$APPLICATION_ID" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
    sleep 2
    echo "==> 当前前台：$(adb -s "$serial" shell dumpsys window 2>/dev/null | grep mCurrentFocus | head -1 | tr -d '\r' | sed 's/^ *//')"
  fi
  local stall_file="$output_file.stall"
  rm -f "$stall_file"
  start_stall_watchdog "$serial" "$output_file" "$stall_file" "$STALL_SECONDS" &
  local watchdog_pid=$!
  set +e
  adb -s "$serial" shell am instrument "${instrument_args[@]}" "$TEST_PACKAGE/$RUNNER" 2>&1 | tee "$output_file"
  local adb_status=${PIPESTATUS[0]}
  set -e
  kill "$watchdog_pid" 2>/dev/null || true
  wait "$watchdog_pid" 2>/dev/null || true
  # tee 已经收工，这时再合并看门狗写下的卡死记录。
  if [ -f "$stall_file" ]; then
    cat "$stall_file" >> "$output_file"
  fi

  if [ "$WITH_LOGCAT" -eq 1 ]; then
    adb -s "$serial" logcat -d > "$logcat_file"
    echo "==> logcat 已写入 $logcat_file"
  fi

  local verdict
  set +e
  verdict="$(assess_instrumentation "$output_file" "$adb_status")"
  local verdict_status=$?
  set -e

  echo "==> 原始输出已写入 $output_file"
  if [ "$verdict_status" -ne 0 ]; then
    echo "==> 插桩测试失败：$verdict"
    return 1
  fi
  if [ -n "$verdict" ]; then
    echo "==> 插桩测试通过（$verdict 条用例）"
  else
    echo "==> 插桩测试通过（用例数未知，没有解析到汇总行）"
  fi
  return 0
}

main "$@"
