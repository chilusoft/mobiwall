#!/usr/bin/env bash
set -euo pipefail

# Deletes all releases except the latest one.
# Requires the `gh` CLI to be authenticated (via GH_TOKEN or `gh auth login`).
#
# Usage:
#   GH_TOKEN=xxx bash scripts/clean_old_releases.sh

command -v gh >/dev/null 2>&1 || { echo "Error: 'gh' CLI is not installed." >&2; exit 1; }
command -v jq >/dev/null 2>&1 || { echo "Error: 'jq' is not installed." >&2; exit 1; }

# Fetch all releases sorted by creation date (newest first), output: "<tag> <name>"
releases=$(gh release list --limit 100 --json tagName,name --jq '.[] | "\(.tagName) \(.name)"')

if [[ -z "$releases" ]]; then
  echo "No releases found. Nothing to delete."
  exit 0
fi

# Skip the first line (the latest release)
latest=$(echo "$releases" | head -n 1 | awk '{print $1}')
echo "Keeping latest release: $latest"

echo "$releases" | tail -n +2 | while read -r tag name; do
  echo "Deleting release: $tag ($name)"
  gh release delete "$tag" --yes --cleanup-tag
done

echo "Cleanup complete. Only the latest release remains."
