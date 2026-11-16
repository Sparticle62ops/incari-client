# ADR 0075: Server messages reach the game within a frame in the browser

- Status: proposed
- Date: 2026-11-16
- Builds on: ADR 0009 (WebSocket transport), ADR 0072 (hidden-page wake-ups)

## Context

In the browser a server message went from the socket's callback to a JavaScript queue, to the
game's thread at the next frame's `pollEvents`, to the connection's event loop (a Java thread,
which runs only when the render thread suspends), and back to the game's thread at the next frame.
Measured from the socket's `onmessage` (`desktop-cpu4`, release build, in play): **decoded after
~37 ms, handled by the game after ~40 ms**, two and a half frames.

A profile of the flythrough put the whole network path at ~6% of the page's time (event loop 1.7%,
sending 0.6%, packet handling 3.4%). So the cost to fix was the latency, not the CPU.

Splitting the time by stage showed the wait wasn't in Incari's code. In play, Chrome runs a
zero-delay `setTimeout` set from a socket's `onmessage` **~36 ms late**, and a `MessageChannel`
message ~70 ms late, while animation frames and socket messages come on time. TeaVM wakes every
thread through `setTimeout` (`teavmAsync.offer`), so the connection's thread, woken to read a
message, waited two frames. `EventLoopHopProbe` shows the hand-off itself is immediate when the
page isn't busy.

## Decision

| Change | Where |
| --- | --- |
| The render thread waits for the next frame **or** a socket message (`window.__incariNetWake`), and delivers a message as it comes | platform-web `Dom`, `WebPlatform`, `WebSocketNetwork` |
| The woken connection thread runs at once: the wake calls `incariRunWoken`, which runs the events offered during it (and those they chain, at most 32), not leaving them to their timers [`?netwake=timers`] | app-web `wakeups.ts` (`runWoken`), `boot.ts` |
| A burst of messages reaches the pipeline as one event-loop task with one read-complete | net `NetConnectionChannel` |
| `Inflater.reset` and `Deflater.reset` reset jzlib's stream in place, not a new one per packet; `TInflater` reads and writes a buffer's own array | web-classlib `TInflater`, `TDeflater`, `StreamResets` |
| A whole-array send skips one copy | platform-web `WebSocketNetwork` |

Only the events a wake caused run early. Each still has its timer, which finds nothing to do.
Events offered before the wake (the background pool's runners) stay on their timers.

## Results

`desktop-cpu4` (RX 9060 XT, Chrome 151, CPU throttled 4x), release build, wsbridge server.
Latency is from `onmessage`, in 10 s windows during play (temporary instrumentation):

| | Before | Wake only | Wake + `runWoken` |
| --- | ---: | ---: | ---: |
| Decoded, mean | ~37 ms | ~35 ms | **~2 ms** |
| Handled by the game, mean / p95 | ~40 / ~53 ms | ~38 / ~50 ms | **~4 / ~8 ms** |

Frames, benchmark reports: medians, with ranges. "Without" is 7 runs (before, and the wake
without `runWoken`); "with" is 6:

| | Without `runWoken` | With |
| --- | --- | --- |
| Median fps, flythrough / combat / lobby | 59.8 / 59.5 / 28.1 | 61.0 / 61.8 / 28.0 |
| 1% low, flythrough | 25.9 (24.4–29.4) | 24.7 (22.3–29.7) |
| 1% low, combat | 30.8 (26.1–34.5) | 28.3 (25.3–32.4) |
| 1% low, lobby | 15.9 (14.2–16.5) | 15.2 (14.9–15.9) |
| 99th percentile frame, lobby | 56.1 ms (55.7–57.3) | 58.8 ms (56.7–61.1) |

Median frame rates hold. The slowest frames pay: the lobby's (the busiest traffic) are ~2.5 ms
longer, clear of noise; the 1% lows are 4–8% lower, inside the run-to-run spread but in every
scenario. That is the price of ~34 ms less latency, so `?netwake=timers` turns it off, and the
default is for a reviewer to settle on a Chromebook (`laptop-floor`).

Compression (Wisp's Java servers; Eagler servers and the benchmark send uncompressed),
`InflatePacketProbe`, best of six alternated rounds, per packet: inflate **14.2 → 11.2 µs**,
deflate **74.5 → 60.4 µs**, and no 32 KB window or deflate state allocated per packet.

## Looked at and left

- **One wake each 8 ms:** messages come in bursts, so most were held back, and every message not
  handed over by a wake still waited two frames (decoded ~33 ms). No gain.
- **Running every due event on a wake:** decoded in 0.5 ms, but it pulled the background pool's
  meshing out of its idle slots into the tasks before a frame. Lobby slowest frames +7 ms.
- **`MessageChannel` wake-ups on the page** (ADR 0043): later still in play (~70 ms).
- **Waking the event loop for a sent packet as the frame ends** (marked wake-ups run when the
  frame's callback returns): no change. A packet the game sends reaches its event loop ~3-8 ms
  later in the flythrough and combat and ~26-30 ms in the lobby, with or without it: the send's
  wake isn't held back as a message's was; the loop just waits for the render thread to finish
  its frame. Less would need the pipeline to run on the render thread.
- **Per-message delivery cost** (`SocketDeliveryProbe`): 0.49 µs a message, nearly all the copy
  out of the page's buffer. Not worth changing.
