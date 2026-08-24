{
  description = "Just Ask Android SDK and orchestrator app";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flakelight.url = "github:nix-community/flakelight";
  };

  outputs = { flakelight, nixpkgs, ... }@inputs:
    flakelight ./. {
      inherit inputs;
      systems = [ "x86_64-linux" "aarch64-linux" ];

      devShell = pkgs:
        let
          pkgsUnfree = import nixpkgs {
            inherit (pkgs) system;
            config = {
              allowUnfree = true;
              android_sdk.accept_license = true;
            };
          };

          androidComposition = pkgsUnfree.androidenv.composeAndroidPackages {
            cmdLineToolsVersion = "11.0";
            buildToolsVersions = [ "34.0.0" ];
            platformVersions = [ "35" ];
            includeNDK = false;
            includeCmake = false;
            includeEmulator = true;
            includeSystemImages = true;
            systemImageTypes = [ "default" ];
            abiVersions =
              if pkgs.system == "aarch64-linux" then [ "arm64-v8a" ]
              else [ "x86_64" ];
            includeSources = false;
            extraLicenses = [
              "android-sdk-license"
              "android-sdk-preview-license"
              "android-sdk-arm-dbt-license"
            ];
          };
          androidSdk = androidComposition.androidsdk;

          # FHS environment so dynamically-linked Android SDK binaries (aapt2,
          # emulator) can resolve their glibc paths on NixOS.
          fhs = pkgs.buildFHSEnv {
            name = "just-ask-fhs";
            targetPkgs = p: with p; [
              jdk21_headless
              android-tools
              git
              gnumake
              bashInteractive
              unzip
              zlib
              libcxx
              ncurses5
            ];
            profile = ''
              export IN_JUST_ASK_FHS=1
              export JAVA_HOME="${pkgs.jdk21_headless}"
              export ANDROID_HOME="${androidSdk}/libexec/android-sdk"
              export ANDROID_SDK_ROOT="$ANDROID_HOME"
              export GRADLE_OPTS="-Dorg.gradle.project.android.aapt2FromMavenOverride=${androidSdk}/libexec/android-sdk/build-tools/34.0.0/aapt2"
              [ -f gradlew ] && chmod +x gradlew
            '';
            runScript = pkgs.writeShellScript "just-ask-fhs-run" ''
              if [[ $# -eq 0 ]]; then
                exec bash
              else
                exec "$@"
              fi
            '';
          };

        in
        {
          packages = [
            fhs
            androidSdk
            pkgs.android-tools
            pkgs.gnumake
          ];

          shellHook = ''
            export ANDROID_HOME="${androidSdk}/libexec/android-sdk"
            export ANDROID_SDK_ROOT="$ANDROID_HOME"
            export JAVA_HOME="${pkgs.jdk21_headless}"
            export PATH="$JAVA_HOME/bin:$PATH"

            # Keep local.properties pointed at the current Nix SDK derivation.
            ${pkgs.gnused}/bin/sed -i \
              -e 's|^sdk\.dir=.*|sdk.dir=${androidSdk}/libexec/android-sdk|' \
              local.properties 2>/dev/null || true
            grep -q '^sdk\.dir=' local.properties 2>/dev/null || \
              echo 'sdk.dir=${androidSdk}/libexec/android-sdk' >> local.properties

            if [[ -z "$IN_JUST_ASK_FHS" ]]; then
              if [[ $- == *i* ]]; then
                echo "Entering Just Ask FHS dev shell..."
                exec just-ask-fhs
              fi
              # Non-interactive (nix develop -c cmd): Makefile targets route
              # through just-ask-fhs automatically — no action needed here.
            else
              echo ""
              echo "Just Ask — Android devShell (FHS)"
              echo "  ANDROID_SDK_ROOT = $ANDROID_SDK_ROOT"
              echo "  JAVA_HOME        = $JAVA_HOME"
              echo ""
              echo "Build:              make build"
              echo "Install (device):   make install"
              echo "Install (emulator): make install-emulator"
              echo ""
            fi
          '';
        };
    };
}
