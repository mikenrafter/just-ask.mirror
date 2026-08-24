# Agent notes — Just Ask

## Publishing `:sdk` to GitHub Packages

There is **no CI**. Publish from a machine with the Android toolchain and `gh` logged in.

One-time token scopes (upload fails with 401 without these):

```bash
gh auth refresh -h github.com -s write:packages,read:packages
```

Then:

```bash
# From repo root (uses nix develop + just-ask-fhs when flake.nix is present)
./scripts/publish-github-packages.sh
# or: make publish
```

What the script does:

1. Reads `VERSION_NAME` from `gradle.properties` (override with `VERSION_NAME=…`).
2. Sets `GITHUB_TOKEN` / `GITHUB_ACTOR` from `gh auth token` and `gh api user`.
3. Runs `:sdk:publishReleasePublicationToGitHubPackagesRepository`.
4. Creates and pushes annotated tag `v$VERSION_NAME` (pass `--no-tag` to skip).

Coordinates: `dev.justask:sdk:$VERSION_NAME`  
Repo: `https://maven.pkg.github.com/mikenrafter/just-ask`

### Bumping a release

1. Edit `VERSION_NAME` in `gradle.properties`.
2. Commit.
3. Run `./scripts/publish-github-packages.sh`.
4. Bump the same version in every consumer (e.g. Reverb `implementation("dev.justask:sdk:…")`).

Do not republish an existing version to GitHub Packages — bump instead.

## Consuming the SDK

GitHub Packages usually needs a token even for public packages. Before Gradle resolve:

```bash
export GITHUB_TOKEN="$(gh auth token)"
export GITHUB_ACTOR="$(gh api user -q .login)"   # optional; defaults to mikenrafter in consumers
```

Or set `gpr.user` / `gpr.key` in `~/.gradle/gradle.properties`.

## Local sibling `includeBuild`

Prefer the published artifact. `includeBuild("../just-ask")` is only for emergency local iteration — do not leave it in consumer `settings.gradle.kts` on shared branches.
