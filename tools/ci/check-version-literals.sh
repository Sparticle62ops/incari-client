#!/usr/bin/env bash
# Fails if shipped code spells out the pinned Minecraft version ("26.3") as a string literal.
# incari.properties is the one place it's written: Gradle reads it, and code takes it from the
# generated IncariBuild.MINECRAFT_VERSION or the game's SharedConstants. A literal left behind is a
# silent bug on the next version bump (docs/updating.md). Comments, tests and docs may name it.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

version=$(sed -n 's/^minecraft\.version=//p' incari.properties | tr -d '[:space:]')
if [ -z "$version" ]; then
  echo "No minecraft.version in incari.properties" >&2
  exit 1
fi

# The version as a whole quoted string: "26.3" or '26.3', not "26.3.1" or "incari/26.3".
pattern="[\"']${version//./[.]}[\"']"

hits=$(
  git ls-files -z -- '*.java' '*.kt' '*.kts' '*.ts' '*.js' '*.mjs' '*.rs' \
    ':!:**/src/test/**' ':!:**/tests/**' ':!:**/tests.rs' ':!:**/*.test.ts' ':!:patches/**' \
  | xargs -0 -r awk -v pat="$pattern" '
      {
        line = $0
        sub(/^[ \t]*\|/, "", line)               # Kotlin trimMargin() templates
        if (line ~ /^[ \t]*(\/\/|\/\*|\*|#)/) next # comment lines
        if (line ~ pat) print FILENAME ":" FNR ": " $0
      }'
)

if [ -n "$hits" ]; then
  echo "$hits"
  echo "Don't hard-code the Minecraft version ${version}: use IncariBuild.MINECRAFT_VERSION," >&2
  echo "SharedConstants.getCurrentVersion(), or read incari.properties (Gradle)." >&2
  exit 1
fi
echo "No hard-coded Minecraft version ${version} in shipped code."
