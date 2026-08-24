.PHONY: build install install/emulator install-emulator emulator logs crash clear-logs

# Preserve the caller's graphical session when entering nix develop.
SESSION_ENV = \
  DISPLAY="$$DISPLAY" \
  WAYLAND_DISPLAY="$$WAYLAND_DISPLAY" \
  XDG_RUNTIME_DIR="$$XDG_RUNTIME_DIR" \
  XDG_SESSION_TYPE="$$XDG_SESSION_TYPE" \
  XDG_CURRENT_DESKTOP="$$XDG_CURRENT_DESKTOP" \
  QT_QPA_PLATFORM="$$QT_QPA_PLATFORM"

# Route targets through the FHS env so aapt2/emulator binaries resolve their
# glibc paths correctly on NixOS. Three cases:
#   1. Already in FHS (IN_JUST_ASK_FHS set)  → run directly
#   2. In nix develop but not FHS             → wrap with just-ask-fhs
#   3. Outside nix develop entirely           → enter nix develop, then FHS
ifeq ($(IN_JUST_ASK_FHS),1)
  RUN =
else ifeq ($(IN_NIX_SHELL),)
  RUN = $(SESSION_ENV) nix develop --command just-ask-fhs
else
  RUN = just-ask-fhs
endif

# Build the debug APK.
build:
	$(RUN) ./gradlew :app:assembleDebug

# Build and install on a connected device.
install:
	$(RUN) ./gradlew :app:assembleDebug
	adb install -r app/build/outputs/apk/debug/app-debug.apk

# Build, boot (or reuse) the Android emulator, then install Just Ask.
# Requires: nix develop shell (provides ANDROID_SDK_ROOT, adb, emulator, avdmanager).
# Must be launched from a desktop terminal (Wayland/X11). On Wayland, the Nix
# emulator requires QT_QPA_PLATFORM=xcb (set automatically by the script).
#
# install-emulator / install/emulator:
#   Boot (or reuse) emulator, build APK, and install it.
#   Usage: nix develop && make install-emulator
#
# emulator:
#   Boot the emulator only — no build, no install. Runs in the foreground (Ctrl-C to stop).
#   Usage: nix develop && make emulator
#
# Optional env vars (forwarded to scripts/emulator-install.sh):
#   JUST_ASK_AVD_NAME                         default: just_ask_test
#   JUST_ASK_BOOT_SETTLE_SECONDS              default: 10 (only when the script starts the emulator)
#   JUST_ASK_EMULATOR_START_TIMEOUT_SECONDS   default: 300

install/emulator install-emulator:
	@chmod +x scripts/emulator-install.sh
	$(RUN) bash scripts/emulator-install.sh

emulator:
	@chmod +x scripts/emulator-install.sh
	$(RUN) bash scripts/emulator-install.sh --no-install

# Stream logcat filtered to Just Ask tags. Ctrl-C to stop.
logs:
	adb logcat -v time \
		JustAsk:D \
		JustAskLauncher:D \
		JustAskBootService:D \
		dev.justask.app:D \
		dev.justask.sdk:D \
		AndroidRuntime:E \
		*:S

# Dump the crash buffer only.
crash:
	adb logcat -b crash -d

# Clear all logcat buffers.
clear-logs:
	adb logcat -c
	@echo "Logcat cleared."
