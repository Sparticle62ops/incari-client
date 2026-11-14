# ADR 0074: The browser build never calls Mojang's services on its own

- Status: accepted
- Date: 2026-11-13
- Builds on: ADR 0070 (Microsoft sign-in)

## Context

The browser build started the game as an online client with token `0`. Mojang's authlib then
called out at once and kept retrying: service discovery, the services' public keys, the player's
profile, user properties, the chat key pair, telemetry. Mojang's APIs send no CORS headers
(ADR 0070), so a page can't reach them: every request failed, each one a console error and wasted
work on a slow Chromebook.

## Decision

- **Off in the browser.** `WebMain` calls `OnlineServices.disableMojang()` and starts the game with
  `--offlineDeveloperMode`: no profile, property, key pair or telemetry requests.
- **Offline discovery.** The game's discovery service comes from `OnlineServices.discovery` (one
  hook in `Minecraft`): the offline one, which never fetches. The singleplayer worker's server
  uses it too.
- **Skins without the services** (`ProfileTextures`, one hook in `SkinManager`). With no discovery
  there is no texture host list, so every skin and custom head would stay the default. Textures
  are accepted from Mojang's domains (`*.minecraft.net`, `*.mojang.com`) over http(s), and a
  signed property counts as signed: its signature can't be checked without Mojang's keys.
- **Microsoft sign-in is unchanged.** It reaches Mojang over TLS in the page, only when the player
  signs in or joins an online-mode server.
- The desktop keeps the game's own behaviour.

## Consequences

- No requests to Mojang from the browser build unless the player signs in with Microsoft. Skin
  images still come from `textures.minecraft.net`, where profiles say they are.
- A server can show a player with another real Mojang skin; never an image from another host.
- Other players' chat keys still can't be checked in the browser (no Mojang keys), as before.
- The Rust build makes no Mojang requests, so it has nothing to change.
