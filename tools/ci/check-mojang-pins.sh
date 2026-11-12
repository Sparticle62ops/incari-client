#!/usr/bin/env bash
# Fails if a library pin in gradle/libs.versions.toml that must match Minecraft's version JSON
# doesn't. Incari compiles against these jars and the game runs with Mojang's copies, so after a
# Minecraft bump they have to move with it (docs/updating.md). Needs ./incari fetch first.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

version=$(sed -n 's/^minecraft\.version=//p' incari.properties | tr -d '[:space:]')
json="work/cache/versions/${version}/${version}.json"
if [ ! -f "$json" ]; then
  echo "No ${json}. Run ./incari fetch first." >&2
  exit 1
fi

# <libs.versions.toml key> <Maven group:artifact in the version JSON>
python3 -I - "$json" gradle/libs.versions.toml <<'EOF'
import json, re, sys

pins = {
    "lwjgl": "org.lwjgl:lwjgl",
    "guava": "com.google.guava:guava",
    "gson": "com.google.code.gson:gson",
}

with open(sys.argv[1]) as f:
    mojang = {}
    for lib in json.load(f)["libraries"]:
        parts = lib["name"].split(":")
        if len(parts) == 3:
            mojang[parts[0] + ":" + parts[1]] = parts[2]

with open(sys.argv[2]) as f:
    toml = f.read()
section = toml.split("[versions]", 1)[1].split("\n[", 1)[0]
ours = dict(re.findall(r'^([\w-]+)\s*=\s*"([^"]+)"', section, re.M))

bad = []
for key, coordinate in pins.items():
    want = mojang.get(coordinate)
    have = ours.get(key)
    if want is None:
        bad.append(f"{coordinate} is no longer in Minecraft's version JSON (libs.versions.toml: {key} = {have})")
    elif have != want:
        bad.append(f'{key} = "{have}" in gradle/libs.versions.toml, but Minecraft ships {coordinate}:{want}')

if bad:
    print("\n".join(bad))
    print("Move these pins to Minecraft's versions (see their comments in gradle/libs.versions.toml).", file=sys.stderr)
    sys.exit(1)
print("Library pins match Minecraft's version JSON: " + ", ".join(f"{k} {ours[k]}" for k in pins))
EOF
