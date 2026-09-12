#!/usr/bin/env bash
# 在真机（或模拟器）上跑 androidTest 插桩测试，用 adb 直接驱动 instrumentation。
#
# 和 ./gradlew connectedDebugAndroidTest 的区别：这里编译安装一次之后，
# 可以用 adb shell am instrument 反复只跑某一个类或某一条用例，
# 不用每次都重新打包；配合 -l 还能把用例执行期间的 logcat 一起抓下来。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
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
用法：./scripts/run-instrumented-tests.sh [选项] [设备序列号]

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
覆盖安装失败会中止；只有显式 --fresh 才会卸载重装，那时数据会丢。

示例：
  ./scripts/run-instrumented-tests.sh                                  # 全量
  ./scripts/run-instrumented-tests.sh -p com.par9uet.jm.worker         # 一个包
  ./scripts/run-instrumented-tests.sh -c com.par9uet.jm.database.FavoriteStoreRealDatabaseTest -m chineseSearchMatchesTitlesOnRealSqlite
  ./scripts/run-instrumented-tests.sh --no-build -c com.par9uet.jm.cache.atom.CacheFilesDeviceTest
  ./scripts/run-instrumented-tests.sh --fresh                          # 干净安装后再跑（会清掉登录数据）
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
#
# 失败一律报错退出，不替用户做丢数据的决定：安装失败可能是临时的（设备掉线、空间不足），
# 也可能是签名不一致/版本降级。只有后者才需要卸载，而那是 `--fresh` 的活。
install_apk() {
  local serial="$1" package="$2" apk="$3"
  if adb -s "$serial" install -r -t "$apk"; then
    return 0
  fi
  die "覆盖安装 $package 失败（上面是 adb 的报错，安装参数：install -r -t $(basename "$apk")）。
签名不一致或版本降级时，用 --fresh 重新跑：它会先卸载再装，应用数据与登录会话会全部丢失。"
}

build_and_install() {
  local serial="$1"
  echo "==> 编译 debug APK 与 androidTest APK..."
  # 注意：这个函数是在 `[ ... ] || build_and_install ...` 里调的，bash 在 `||` 列表里会
  # 关掉 errexit，所以失败必须自己判——否则编译失败会拿着上一次的 APK 接着装、接着测，
  # 结果看着全绿，测的却是旧包。
  if ! ( cd "$PROJECT_DIR" && ./gradlew $GRADLE_FLAGS assembleDebug assembleDebugAndroidTest --console=plain ); then
    die "编译失败，已中止（不会拿上一次的 APK 去装）。"
  fi

  local app_apk test_apk
  app_apk="$(ls -t "$APK_DIR"/debug/*.apk 2>/dev/null | head -1 || true)"
  test_apk="$(ls -t "$APK_DIR"/androidTest/debug/*.apk 2>/dev/null | head -1 || true)"
  [ -n "$app_apk" ] && [ -n "$test_apk" ] ||
    die "在 $APK_DIR 下找不到两个 APK，先跑一次 assembleDebug assembleDebugAndroidTest。"

  echo "==> 安装 $(basename "$app_apk") 与 $(basename "$test_apk")..."
  if [ "$FRESH_INSTALL" -eq 1 ]; then
    echo "==> --fresh：先卸载 ${APPLICATION_ID}，应用数据（含登录会话）会全部丢失。"
    adb -s "$serial" uninstall "$APPLICATION_ID" >/dev/null 2>&1 || true
    adb -s "$serial" install -r -t "$app_apk"
  else
    install_apk "$serial" "$APPLICATION_ID" "$app_apk"
  fi
  install_apk "$serial" "$TEST_PACKAGE" "$test_apk"
}

# 当前前台窗口，读不到就返回空。末尾的 `|| true` 是必须的：脚本开着 pipefail + set -e，
# 没匹配到 mCurrentFocus 时 grep 的非零退出会顺着管道把调用者（尤其是看门狗）一起带走。
current_focus() {
  adb -s "$1" shell dumpsys window 2>/dev/null \
    | grep mCurrentFocus \
    | head -1 \
    | tr -d '\r' \
    | sed 's/^ *//;s/^mCurrentFocus=//' \
    || true
}

# Compose 插桩要求 Activity 处于 RESUMED。个人机上通知栏、微信/知乎等任意前台
# 都会让 waitForIdle/waitUntil 永远等不到帧，表现为“UI 测试卡死”而应用本身正常。
# 这里只做无损的环境稳住：亮屏、常亮、收起通知栏/勿扰；不卸载、不禁用用户应用。
prepare_device_for_instrumentation() {
  local serial="$1"
  adb -s "$serial" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
  adb -s "$serial" shell svc power stayon true >/dev/null 2>&1 || true
  # 收起通知栏即可；不要发 HOME——那会把设备停在 Launcher，
  # HyperOS 上随后 instrumentation 拉起的 Activity 容易被压在后台，waitForIdle 永远等不到帧。
  adb -s "$serial" shell input keyevent KEYCODE_BACK >/dev/null 2>&1 || true
  adb -s "$serial" shell cmd statusbar collapse >/dev/null 2>&1 || true
  # 关掉 heads-up，避免 DEVELOPER_IMPORTANT 等通知把 shade 拉下来抢焦点。
  adb -s "$serial" shell settings put global heads_up_notifications_enabled 0 >/dev/null 2>&1 || true
  # 勿扰，降低通知弹出抢前台的概率（跑完恢复）。
  adb -s "$serial" shell cmd notification set_dnd on >/dev/null 2>&1 || true
  adb -s "$serial" shell settings put global zen_mode 2 >/dev/null 2>&1 || true
  # HyperOS 会把非 Launcher 任务压后台；从省电白名单里摘掉，减少被冻结/转后台。
  adb -s "$serial" shell dumpsys deviceidle whitelist +$APPLICATION_ID >/dev/null 2>&1 || true
  adb -s "$serial" shell cmd appops set "$APPLICATION_ID" RUN_ANY_IN_BACKGROUND allow >/dev/null 2>&1 || true
  # HyperOS 4：后台弹出界面（MIUIOP 10021）。默认 ignore 时 instrumentation 拉起的
  # Activity 会被系统直接压回后台，Compose waitForIdle 永远等不到帧。
  adb -s "$serial" shell appops set --user 0 "$APPLICATION_ID" 10021 allow >/dev/null 2>&1 || true
  adb -s "$serial" shell appops set --user 0 "$TEST_PACKAGE" 10021 allow >/dev/null 2>&1 || true
}

teardown_device_after_instrumentation() {
  local serial="$1"
  adb -s "$serial" shell cmd notification set_dnd off >/dev/null 2>&1 || true
  adb -s "$serial" shell settings put global zen_mode 0 >/dev/null 2>&1 || true
  adb -s "$serial" shell settings put global heads_up_notifications_enabled 1 >/dev/null 2>&1 || true
  adb -s "$serial" shell dumpsys deviceidle whitelist -$APPLICATION_ID >/dev/null 2>&1 || true
}

# 整场 instrumentation 期间：收起通知栏，并把测试进程钉在前台。
# HyperOS 上 ComponentActivity 会启动，但通知栏（尤其 DEVELOPER_IMPORTANT）会立刻
# 抢走窗口焦点把 Activity pause，Compose waitForIdle 因此永远等不到帧。
start_foreground_keeper() {
  local serial="$1" log_file="$2"
  (
    events=0
    while :; do
      sleep 1
      focus="$(current_focus "$serial")"
      case "$focus" in
        *"$APPLICATION_ID"*|*"$TEST_PACKAGE"*) continue ;;
        *NotificationShade*|*StatusBar*)
          events=$((events + 1))
          printf '[foreground-keeper] # %s collapse shade (focus=%s)\n' \
            "$events" "${focus:-unknown}" >> "$log_file"
          adb -s "$serial" shell cmd statusbar collapse >/dev/null 2>&1 || true
          adb -s "$serial" shell input keyevent KEYCODE_BACK >/dev/null 2>&1 || true
          continue
          ;;
      esac
      events=$((events + 1))
      # Compose rule 的 Activity 组件名是固定的。
      local_component="$APPLICATION_ID/androidx.activity.ComponentActivity"
      top_component="$(
        adb -s "$serial" shell dumpsys activity activities 2>/dev/null \
          | tr -d '\r' \
          | grep -oE "${APPLICATION_ID}/[A-Za-z0-9_.$]+" \
          | head -1 || true
      )"
      printf '[foreground-keeper] # %s lost focus (%s), resume %s\n' \
        "$events" "${focus:-unknown}" "${top_component:-$local_component}" >> "$log_file"
      adb -s "$serial" shell cmd statusbar collapse >/dev/null 2>&1 || true
      adb -s "$serial" shell am start -n "${top_component:-$local_component}" \
        >/dev/null 2>&1 || true
    done
  ) &
  FOREGROUND_KEEPER_PID=$!
}

stop_foreground_keeper() {
  if [ -n "${FOREGROUND_KEEPER_PID:-}" ]; then
    kill "$FOREGROUND_KEEPER_PID" 2>/dev/null || true
    wait "$FOREGROUND_KEEPER_PID" 2>/dev/null || true
    FOREGROUND_KEEPER_PID=""
  fi
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
    foreground="$(current_focus "$serial")"
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

# 从输出里取用例数。优先汇总行（OK (N tests) / Tests run: N）和运行期间的 numtests；
# 都没有时才退而数"每通过一条打一行"的 STATUS_CODE: 0。全都取不到就返回空——
# 空表示数不出来，调用方按失败处理，不能当成"跑了 0 条"也不能当成通过。
instrumentation_case_count() {
  local summary traces
  summary="$({ grep -oE 'OK \([0-9]+ tests?\)' "$1" || true
              grep -oE 'Tests run: [0-9]+' "$1" || true
              grep -oE 'numtests=[0-9]+' "$1" || true
            } 2>/dev/null | grep -oE '[0-9]+' | sort -n | tail -1 || true)"
  if [ -n "$summary" ]; then
    printf '%s' "$summary"
    return 0
  fi
  traces="$(grep -c 'INSTRUMENTATION_STATUS_CODE: 0' "$1" 2>/dev/null || true)"
  if [ "${traces:-0}" -gt 0 ]; then
    printf '%s' "$traces"
  fi
  return 0
}

# 判定一次插桩结果：成功时返回 0 并打印大于零的用例数，失败时打印原因并返回 1。
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
  if [ -z "$reasons" ]; then
    case "$cases" in
      '')
        # 数不出用例数就不能算通过：正常的 AndroidJUnitRunner 一定会留下 OK (N tests)、
        # numtests=N 或每用例一行 STATUS_CODE: 0，什么都没有说明这次输出不可信。
        reasons="
  - 数不出用例数，也没看到任何用例通过的记录，无法确认这次跑了什么" ;;
      0)
        reasons="
  - 一条用例都没跑到（检查 -c 的类名、-m 的方法名是否写对，-p 的包名下是否有用例）" ;;
    esac
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
  prepare_device_for_instrumentation "$serial"
  echo "==> 设备已准备（亮屏/常亮/收通知栏/勿扰）；跑测期间请勿操作手机"
  # Compose rule 用的是 androidx.activity.ComponentActivity，不是主界面。
  # 先把它拉到前台占住任务栈；force-stop 会让 HyperOS 随后把新 Activity 直接压到
  # Launcher 后面（任务栈里甚至找不到 activity）。
  adb -s "$serial" shell am force-stop "$APPLICATION_ID" >/dev/null 2>&1 || true
  sleep 0.5
  adb -s "$serial" shell am start -n "$APPLICATION_ID/androidx.activity.ComponentActivity" \
    >/dev/null 2>&1 || true
  sleep 1
  echo "==> 当前前台：$(current_focus "$serial")"
  local keeper_log="$PROJECT_DIR/build/foreground-keeper.log"
  : > "$keeper_log"
  start_foreground_keeper "$serial" "$keeper_log"
  local stall_file="$output_file.stall"
  rm -f "$stall_file"
  start_stall_watchdog "$serial" "$output_file" "$stall_file" "$STALL_SECONDS" &
  local watchdog_pid=$!
  set +e
  adb -s "$serial" shell am instrument "${instrument_args[@]}" "$TEST_PACKAGE/$RUNNER" 2>&1 | tee "$output_file"
  local adb_status=${PIPESTATUS[0]}
  set -e
  stop_foreground_keeper
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
  teardown_device_after_instrumentation "$serial"

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
    echo "==> 插桩测试通过"
  fi
  return 0
}

main "$@"
