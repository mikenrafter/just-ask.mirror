#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

NO_INSTALL=false
while [[ $# -gt 0 ]]; do
  case "$1" in
    --no-install) NO_INSTALL=true; shift ;;
    *) echo "error: unknown argument: $1" >&2; exit 1 ;;
  esac
done

if [[ "$(uname -m)" == "aarch64" ]]; then
  DEFAULT_ABI="arm64-v8a"
else
  DEFAULT_ABI="x86_64"
fi

AVD_NAME="${JUST_ASK_AVD_NAME:-just_ask_test}"
SYSTEM_IMAGE="${JUST_ASK_SYSTEM_IMAGE:-system-images;android-35;default;${DEFAULT_ABI}}"
BOOT_SETTLE_SECONDS="${JUST_ASK_BOOT_SETTLE_SECONDS:-10}"
EMULATOR_START_TIMEOUT_SECONDS="${JUST_ASK_EMULATOR_START_TIMEOUT_SECONDS:-300}"
EMULATOR_LAUNCH_PROBE_SECONDS="${JUST_ASK_EMULATOR_LAUNCH_PROBE_SECONDS:-20}"

# Preserve the caller's Qt platform preference for the first launch attempt.
ORIGINAL_QT_QPA_PLATFORM="${QT_QPA_PLATFORM:-}"

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "error: required command not found: $1" >&2
    echo "Run this via: make install-emulator" >&2
    exit 1
  fi
}

emulator_device_serial() {
  adb devices | awk '/^emulator-[0-9]+\tdevice$/ { print $1; exit }'
}

emulator_is_running() {
  [[ -n "$(emulator_device_serial)" ]]
}

ensure_graphical_session() {
  if [[ -z "${DISPLAY:-}" && -z "${WAYLAND_DISPLAY:-}" ]]; then
    echo "error: no graphical session detected (DISPLAY and WAYLAND_DISPLAY are unset)" >&2
    echo "Launch this from your desktop terminal so the emulator window can open." >&2
    exit 1
  fi

  echo "Graphical session:"
  [[ -n "${ORIGINAL_QT_QPA_PLATFORM}" ]] && echo "  QT_QPA_PLATFORM (from env)=$ORIGINAL_QT_QPA_PLATFORM"
  [[ -n "${DISPLAY:-}" ]] && echo "  DISPLAY=$DISPLAY"
  [[ -n "${WAYLAND_DISPLAY:-}" ]] && echo "  WAYLAND_DISPLAY=$WAYLAND_DISPLAY"
  [[ -n "${XDG_SESSION_TYPE:-}" ]] && echo "  XDG_SESSION_TYPE=$XDG_SESSION_TYPE"
}

prepare_session_for_qt_platform() {
  local qt_platform="$1"
  export QT_QPA_PLATFORM="$qt_platform"

  if [[ "$qt_platform" == "xcb" && -z "${DISPLAY:-}" && -n "${WAYLAND_DISPLAY:-}" ]]; then
    echo "Using DISPLAY=:0 for QT_QPA_PLATFORM=xcb under Wayland."
    export DISPLAY=":0"
  fi
}

qt_platform_attempts() {
  local -a platforms=()
  local seen_xcb=false

  if [[ -n "${ORIGINAL_QT_QPA_PLATFORM}" ]]; then
    platforms+=("$ORIGINAL_QT_QPA_PLATFORM")
    [[ "$ORIGINAL_QT_QPA_PLATFORM" == "xcb" ]] && seen_xcb=true
  fi

  if [[ "$seen_xcb" == false ]]; then
    platforms+=("xcb")
  fi

  if [[ ${#platforms[@]} -eq 0 ]]; then
    platforms=("xcb")
  fi

  printf '%s\n' "${platforms[@]}"
}

try_launch_emulator() {
  local qt_platform="$1"
  shift
  local -a emulator_args=("$@")

  prepare_session_for_qt_platform "$qt_platform"

  {
    echo ""
    echo "===== launch attempt: QT_QPA_PLATFORM=$qt_platform ====="
  } >>/tmp/just-ask-emulator.log

  echo "Forking emulator with QT_QPA_PLATFORM=$qt_platform"
  emulator "${emulator_args[@]}" >>/tmp/just-ask-emulator.log 2>&1 &
  local pid=$!
  disown

  local elapsed=0
  while (( elapsed < EMULATOR_LAUNCH_PROBE_SECONDS )); do
    if emulator_is_running; then
      echo "Emulator registered with adb (QT_QPA_PLATFORM=$qt_platform, pid=$pid)"
      return 0
    fi
    if ! kill -0 "$pid" 2>/dev/null; then
      echo "Emulator exited early with QT_QPA_PLATFORM=$qt_platform (see /tmp/just-ask-emulator.log)"
      return 1
    fi
    sleep 1
    elapsed=$((elapsed + 1))
  done

  while (( elapsed < EMULATOR_START_TIMEOUT_SECONDS )); do
    if emulator_is_running; then
      echo "Emulator registered with adb (QT_QPA_PLATFORM=$qt_platform, pid=$pid)"
      return 0
    fi
    if ! kill -0 "$pid" 2>/dev/null; then
      echo "Emulator exited with QT_QPA_PLATFORM=$qt_platform before adb registration"
      return 1
    fi
    sleep 2
    elapsed=$((elapsed + 2))
  done

  if kill -0 "$pid" 2>/dev/null; then
    echo "Stopping emulator pid=$pid after launch timeout"
    kill "$pid" 2>/dev/null || true
    wait "$pid" 2>/dev/null || true
  fi
  return 1
}

wait_for_emulator_boot() {
  local serial="$1"
  local settle_after_boot="${2:-false}"
  echo "Waiting for emulator $serial to finish booting..."
  adb -s "$serial" wait-for-device

  local elapsed=0
  until [[ "$(adb -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; do
    if (( elapsed >= EMULATOR_START_TIMEOUT_SECONDS )); then
      echo "error: emulator did not finish booting within ${EMULATOR_START_TIMEOUT_SECONDS}s" >&2
      exit 1
    fi
    sleep 2
    elapsed=$((elapsed + 2))
  done

  if [[ "$settle_after_boot" == true ]]; then
    echo "Emulator boot complete; settling for ${BOOT_SETTLE_SECONDS}s..."
    sleep "$BOOT_SETTLE_SECONDS"
  else
    echo "Emulator boot complete."
  fi
}

ensure_tools() {
  require_cmd adb
  require_cmd emulator
  require_cmd avdmanager
  require_cmd sdkmanager

  if [[ -z "${ANDROID_SDK_ROOT:-}" && -z "${ANDROID_HOME:-}" ]]; then
    echo "error: ANDROID_SDK_ROOT or ANDROID_HOME must be set" >&2
    echo "Run via: nix develop, then make install-emulator" >&2
    exit 1
  fi
}

ensure_system_image() {
  if sdkmanager --list_installed 2>/dev/null | grep -Fq "$SYSTEM_IMAGE"; then
    echo "System image present: $SYSTEM_IMAGE"
    return
  fi

  echo "Installing system image: $SYSTEM_IMAGE"
  yes | sdkmanager "$SYSTEM_IMAGE"
}

ensure_avd() {
  if avdmanager list avd -c | grep -Fxq "$AVD_NAME"; then
    echo "AVD present: $AVD_NAME"
    return
  fi

  echo "Creating AVD: $AVD_NAME"
  echo "no" | avdmanager create avd \
    -n "$AVD_NAME" \
    -k "$SYSTEM_IMAGE" \
    -d pixel_6
}

build_app() {
  echo "Building Just Ask (debug)..."
  ./gradlew :app:assembleDebug
}

EMULATOR_STARTED_BY_SCRIPT=false

start_emulator_if_needed() {
  if emulator_is_running; then
    echo "Emulator already running: $(emulator_device_serial)"
    EMULATOR_STARTED_BY_SCRIPT=false
    return
  fi

  EMULATOR_STARTED_BY_SCRIPT=true

  ensure_graphical_session

  local -a emulator_args=(
    "@${AVD_NAME}"
    -no-snapshot-load
    -no-boot-anim
    -gpu swiftshader_indirect
  )

  if [[ -r /dev/kvm ]]; then
    emulator_args+=(-accel on)
  else
    emulator_args+=(-accel off)
  fi

  local qt_platform
  local launched=false
  while IFS= read -r qt_platform; do
    if try_launch_emulator "$qt_platform" "${emulator_args[@]}"; then
      launched=true
      break
    fi
    echo "Launch with QT_QPA_PLATFORM=$qt_platform failed; trying next platform..."
  done < <(qt_platform_attempts)

  if [[ "$launched" != true ]]; then
    echo "error: emulator failed to start with all Qt platform attempts" >&2
    echo "See /tmp/just-ask-emulator.log for details" >&2
    exit 1
  fi

  echo "Emulator process running (log: /tmp/just-ask-emulator.log)"
}

install_apk() {
  local apk="$1"
  if [[ ! -f "$apk" ]]; then
    echo "error: APK not found: $apk" >&2
    exit 1
  fi
  echo "Installing $(basename "$apk")..."
  adb install -r "$apk"
}

install_all_apks() {
  local serial
  serial="$(emulator_device_serial)"
  if [[ -z "$serial" ]]; then
    echo "error: no running emulator found for install" >&2
    exit 1
  fi

  echo "Installing APKs on $serial..."
  install_apk "$ROOT/app/build/outputs/apk/debug/app-debug.apk"
}

main() {
  ensure_tools
  ensure_system_image
  ensure_avd

  if [[ "$NO_INSTALL" == true ]]; then
    ensure_graphical_session

    local -a emulator_args=(
      "@${AVD_NAME}"
      -no-snapshot-load
      -no-boot-anim
      -gpu swiftshader_indirect
    )
    if [[ -r /dev/kvm ]]; then
      emulator_args+=(-accel on)
    else
      emulator_args+=(-accel off)
    fi

    local qt_platform exit_code=0
    while IFS= read -r qt_platform; do
      prepare_session_for_qt_platform "$qt_platform"
      echo "Launching emulator with QT_QPA_PLATFORM=$qt_platform"
      emulator "${emulator_args[@]}" >>/tmp/just-ask-emulator.log 2>&1 || exit_code=$?
      if (( exit_code != 134 )); then
        return $exit_code
      fi
      echo "Emulator crashed (exit $exit_code) with QT_QPA_PLATFORM=$qt_platform; trying next platform..."
      exit_code=0
    done < <(qt_platform_attempts)

    echo "error: emulator failed to start with all Qt platform attempts" >&2
    echo "See /tmp/just-ask-emulator.log for details" >&2
    exit 1
  fi

  build_app
  start_emulator_if_needed

  local serial
  serial="$(emulator_device_serial)"
  wait_for_emulator_boot "$serial" "$EMULATOR_STARTED_BY_SCRIPT"
  install_all_apks

  echo ""
  echo "Done. Installed Just Ask on $serial."
}

main "$@"
