# Updating Minecraft

How to move the tree to a new release (26.3 → 26.4). Do the steps in order on their own branch.
Each has a check that fails loudly when it's missed.

## 1. Before you start

Read the new version's JSON from Mojang's manifest, without changing anything:

| Look at | Why it matters |
| --- | --- |
| `javaVersion.majorVersion` | TeaVM 0.16 can't read Java 26 class files. A jump stops the browser build until TeaVM catches up |
| `libraries` | New libraries need a `tools/survey/rules.txt` rule; LWJGL, Guava and Gson pins must move (step 4) |
| Netty's version | The browser build links its own Netty (`gradle/libs.versions.toml`). Run the `EventLoopProbe` after any bump |
| ViaVersion and ViaBackwards | A release that knows the new protocol has to exist before servers on it can be joined |
| Horizon (`incari.properties`) | The Rust build uses the same `minecraft.version`. Its pinned commit must support the new release |

## 2. Pin the new version

Change all three lines in `incari.properties` together: `minecraft.version`, `minecraft.versionJson.sha1`
and `minecraft.client.sha1` (from Mojang's version manifest). Nothing else names the version:
code reads `IncariBuild.MINECRAFT_VERSION` or the game's `SharedConstants`, and
`tools/ci/check-version-literals.sh` fails on a `"26.3"` literal in shipped code.

## 3. Rebuild the vendor tree

```sh
./incari setup                 # fetch, decompile, apply: stops at the first conflicting patch
```

- **Patch conflicts:** resolve in `work/client`, `git -C work/client am --continue`, repeat. The new
  tree has no old blobs, so `--3way` can't merge: expect plain conflicts.
- **Replaced classes:** `apply` warns for each class in `patches/classes.txt` whose Mojang original
  changed, and prints the `git diff --no-index` between the two decompiled versions. Bring each
  replacement in line, then `./incari classes --stamp`. `./incari rebuild --check` fails until you do.
- **Gone classes:** a `classes.txt` line for a class that no longer exists stops `apply` with its name.
- **Decompile fixes** (`patches/decompile/`) are the most likely to break. They change no behaviour, so
  redo them from the compile errors rather than merging old hunks.

Then `./incari rebuild` and check `tools/ci/check-patch-budget.sh`.

## 4. Build and gate

| Step | Command |
| --- | --- |
| Library pins | `tools/ci/check-mojang-pins.sh` |
| Java, tests, client compile | `./gradlew check` |
| Shaders | `./gradlew :app-web:shaders`, `tools/ci/check-glsl.sh` |
| TeaVM survey | `./incari survey` (exactly JDK 25), then diff `docs/port-backlog.md` |
| Browser build | `./gradlew :app-web:teavm`, then `:app-web:browserCheck` |
| Nothing copied | `./incari copycheck` |
| Rust build | `./incari build --only rust`, `cargo test --manifest-path rust/Cargo.toml` |

`render-webgl2` breaks first if Mojang changes the `GpuDevice` API. The mesher's parity test catches
a changed section format. Both show up in `./gradlew check`.

## 5. Things that name the version on purpose

Update these by hand; they're data or docs, not code:

- Test fixtures (`"26.3"` in `src/test/`, `tests.rs`, `*.test.ts`).
- Browser checks that join a vanilla server (`app-web/browser-checks/*.steps`, and the Faithful pack
  URL in its README).
- Docs: `AGENTS.md`, `ARCHITECTURE.md`, `README.md`, `CONTRIBUTING.md`, `client/README.md`. A new
  ADR if the update changed a decision.

`git grep -n '26\.3' -- ':!patches' ':!docs/adr'` lists what's left.
