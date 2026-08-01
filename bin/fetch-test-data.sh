#!/usr/bin/env bash
# Provision test fixtures that the suite reads but git does not carry inline.
#
# Ported from the `Tests.Setup` hooks that used to live in build.sbt. Run this
# once before `mvn test` (or `sbt test`); it is idempotent and skips anything
# already present. PreflightSuite points here when data is missing.
set -euo pipefail

cd "$(dirname "$0")/.."

BASE_URL="https://public-test-data.spice-labs.dev"
DL="test_data/download"

# dir | filename | optional post-download command
downloads=(
  "docker_tests|bigtent_2025_03_22_docker.tar|"
  "docker_tests|grinder_bt_pg_docker.tar|"
  "iso_tests|iso_of_archives.iso|"
  "iso_tests|simple.iso|"
  "|sample-tomcat-6.war|"
  "|EnterpriseHelloWorld.ear|"
  "apk_tests|bitbar-sample-app.apk|"
  "gem_tests|java-properties-0.3.0.gem|"
  "deb_tests|hello_2.10-3_arm64.deb|"
  "adg_tests|repo_ea.tgz|tar -xzf ${DL}/adg_tests/repo_ea.tgz -C ${DL}/adg_tests/"
)

fetch() {
  local url="$1" dest="$2" tries=0
  until curl -fL --retry 3 -o "$dest" "$url"; do
    tries=$((tries + 1))
    if [ "$tries" -ge 10 ]; then
      echo "Failed to download $url after $tries attempts. Aborting." >&2
      exit 1
    fi
    echo "Retry $tries for $url" >&2
  done
}

for entry in "${downloads[@]}"; do
  IFS='|' read -r dir item cmd <<<"$entry"
  target_dir="$DL${dir:+/$dir}"
  dest="$target_dir/$item"
  mkdir -p "$target_dir"
  if [ -f "$dest" ]; then
    echo "Already present: $dest"
  else
    echo "Downloading $item ..."
    fetch "$BASE_URL/$item" "$dest"
    if [ -n "$cmd" ]; then
      echo "Post-processing $item ..."
      eval "$cmd"
    fi
  fi
done

# git-LFS fixtures live in the repo but must be pulled to become real content.
if command -v git >/dev/null && git rev-parse --git-dir >/dev/null 2>&1; then
  if git lfs version >/dev/null 2>&1; then
    echo "Running git lfs pull ..."
    git lfs pull
  else
    echo "WARNING: git lfs not installed; LFS fixtures will be pointer files." >&2
    echo "         Install git-lfs and re-run, or see README.md." >&2
  fi
fi

echo "Test data ready."
