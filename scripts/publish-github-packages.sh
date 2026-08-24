#!/usr/bin/env bash
# Publish :sdk to GitHub Packages and (by default) create/push git tag v$VERSION_NAME.
#
# Auth: uses `gh auth token` — no Actions required.
# Usage:
#   ./scripts/publish-github-packages.sh           # publish VERSION_NAME from gradle.properties
#   ./scripts/publish-github-packages.sh --no-tag  # publish only
#   VERSION_NAME=0.2.0 ./scripts/publish-github-packages.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

NO_TAG=0
for arg in "$@"; do
  case "$arg" in
    --no-tag) NO_TAG=1 ;;
    -h|--help)
      sed -n '2,12p' "$0"
      exit 0
      ;;
    *)
      echo "Unknown arg: $arg" >&2
      exit 1
      ;;
  esac
done

if ! command -v gh >/dev/null 2>&1; then
  echo "gh CLI is required (https://cli.github.com/)" >&2
  exit 1
fi

if ! gh auth status >/dev/null 2>&1; then
  echo "Not logged in to GitHub. Run: gh auth login" >&2
  exit 1
fi

VERSION_NAME="${VERSION_NAME:-$(grep -E '^VERSION_NAME=' gradle.properties | cut -d= -f2-)}"
if [[ -z "${VERSION_NAME}" ]]; then
  echo "VERSION_NAME is empty — set it in gradle.properties or the environment." >&2
  exit 1
fi

TAG="v${VERSION_NAME}"
export GITHUB_TOKEN
GITHUB_TOKEN="$(gh auth token)"
export GITHUB_ACTOR
GITHUB_ACTOR="$(gh api user -q .login)"

echo "Publishing dev.justask:sdk:${VERSION_NAME} to GitHub Packages as ${GITHUB_ACTOR}…"

# Prefer nix develop + FHS when flake is present (NixOS / this repo's toolchain).
if [[ -f flake.nix ]] && command -v nix >/dev/null 2>&1; then
  nix develop -c just-ask-fhs ./gradlew \
    -PVERSION_NAME="${VERSION_NAME}" \
    :sdk:publishReleasePublicationToGitHubPackagesRepository
else
  ./gradlew \
    -PVERSION_NAME="${VERSION_NAME}" \
    :sdk:publishReleasePublicationToGitHubPackagesRepository
fi

echo "Published: https://github.com/mikenrafter/just-ask/packages"

if [[ "${NO_TAG}" -eq 1 ]]; then
  echo "Skipping tag (--no-tag)."
  exit 0
fi

if git rev-parse "${TAG}" >/dev/null 2>&1; then
  echo "Tag ${TAG} already exists locally."
else
  git tag -a "${TAG}" -m "Just Ask SDK ${VERSION_NAME}"
  echo "Created tag ${TAG}."
fi

# Push tag if origin exists (HTTPS remotes work with gh credentials helper).
if git remote get-url origin >/dev/null 2>&1; then
  git push origin "refs/tags/${TAG}"
  echo "Pushed ${TAG} to origin."
else
  echo "No origin remote — tag is local only."
fi
