# Omnisave — Implementation Plan

Save anything you see (reels/shorts/videos, posts, articles, docs, plain web pages) with a **one-tap bubble**, a short **text or audio reference**, stored **locally**, optimized for size, with a **bottom search bar + filters**, and an optional **Gemini (free model)** key for auto-tagging/summarizing. Works on X, LinkedIn, Discord, YouTube (Shorts + videos), Reddit, Notion, Instagram — and anywhere else.

## Architecture (two clients, one shared model)

Every save = one record:

```ts
Item {                      // ← canonical shape, both clients + sync
  id, createdAt, updatedAt, // updatedAt drives delta sync
  type,        // video | short | article | post | doc | image | website
  platform,    // x | linkedin | discord | youtube | reddit | notion | instagram | web
  url,         // canonical, auto-captured (incl. YT timestamp, Shorts flag)
  canonical,
  title, video: {id, t, isShort}, preview,
  refText,     // your one-liner (the thing you write)
  refAudio,    // optional ≤30s audio note (tiny)
  tags[],      // auto from Gemini if key set, else none
  source,      // extension | app
  _remote      // transient: true when the row came from pull → never re-push
}
```

- **A. Browser extension (MV3, vanilla JS/CSS — no framework).** The floating bubble lives *on* X / YouTube / etc. via content script + Shadow DOM. This is the core and ships first.
- **B. Android app (Kotlin + Jetpack Compose).** Native bubble over any app (foreground service + `SYSTEM_ALERT_WINDOW`), same record model, same save flow with tick, `Room` DB, share-target support.
- **C. Cross-device sync (extension ↔ Android app):** no server of ours, no accounts — the user pastes their own **free Firebase (Spark tier)** config and generates a **sync code** once in settings on each device. Saves are **AES-GCM encrypted** before they leave any device (key = PBKDF2(sync code), 120k iterations, salt `omnisave-v1`), stored under `omnisave/<code>/items/`. Merge: per-item id, latest `updatedAt` wins; delete = tombstone `{t:'tombstone'}` (purged after 30 days); pulls are delta-only since `lastSync`; realtime RTDB listeners fire after the initial delta pull. All three clients use `updatedAt` + `_remote` identically.

## Skill usage (already present in `.opencode/skills/`)

| Phase | Skill | Used for |
|---|---|---|
| Design system now | **ui-ux-pro-max** | Style guidance + pre-delivery checklist (tokens, a11y, touch targets). CLI needs Python 3 — not installed; if you install it, we regenerate a formal `design-system/omnisave/MASTER.md` |
| UI build | **ui-styling**, **design-system** | iOS-like token architecture (primitive→semantic→component) for the popup/settings screens |
| Logo | **design** | Omnisave logo/icon (SVG first, app icons later) |

## Design system (Apple-clean, hand-written tokens for now)

- Type: **SF Pro Stack fallback** → `-apple-system, "SF Pro Text", "Segoe UI", system-ui, sans-serif`
- Surfaces: frosted glass (`backdrop-filter: blur(20px) saturate(180%)`, rgba white/black 0.6–0.72), 16–20px radii, hairline borders `rgba(120,120,128,0.32)`
- Accents: single **system blue `#0A84FF`** (dark) / `#007AFF` (light); semantic grays from Apple palette (`#1C1C1E`, `#F2F2F7`)
- Motion: spring-like 150–250ms, `exit-faster-than-enter`, success tick = scale-in + 1s fade (not an alert that blocks)
- Icons: consistent stroke set (Phosphor-style SVGs inlined, no emoji)
- Touch targets ≥ 44px; bubble = 28px visual with 48px hit area

## Phases (each shippable, build in this order)

### P1 — Extension MVP (bubble + save) ← start here — **BUILT 2026-11-10, browser-verification pending**
1. ✅ Scaffold `extension/`: `manifest.json` (all-URLs content script, service worker, `storage`), zero build step.
2. ✅ `content/bubble.js` — Shadow-DOM bubble, drag-positionable (persisted), 28px visual / ≥44px-ish hit feel.
3. ✅ **Auto-context:** capture `{url,title,platform,type,video,preview}` re-fires on every SPA navigation (`pushState`/`replaceState`/`popstate`/`hashchange` + 800ms poll) — the poll compares href+title+video **id only** (never playback time) so no constant churn; YT `videoId`+`t`+`isShort`, X post/article, generic OG/title fallback.
4. ✅ Save sheet: auto-filled source chip, reference input (Enter saves), 🎙 ≤30s memo (MediaRecorder, re-record replaces), Save → tick animation / ✕ + reason.
5. ✅ Success = tick overlay on bubble for ~1.1s (green), errors = red ✕ flash + inline status.
6. ✅ `background/sw.js` + `lib/db.js`: IndexedDB `items` (canonical URL, dedupe window 15min = update refText), badge = save count.
7. ✅ Self-check page `extension/dev/selfcheck.html` (put/count/recent/findByCanonical/remove, prints PASS) + `node --check` on all JS (clean).
8. ✅ **Sync engine (R8) built in `lib/sync.js` + wired to `background/sw.js`**: user's own Firebase config + sync code → AES-GCM sealed envelopes → pushes on save, tombstones on remove, delta pulls + realtime listeners, `_remote` loop-guard, blob↔b64 transport for audio memos, storage-change re-login. Settings UI to paste config = P2. Android side implements the same envelope (PBKDF2-HMAC-SHA256 120k/salt `omnisave-v1`, AES-GCM 256) in P3.

### P2 — Extension library UI (the "app" feel) — **BUILT 2026-11-10**
1. ✅ Full-tab library page `library/`: unified card list, memo playback per item, delete, "Show more" chunks of 50/100.
2. ✅ **Bottom sticky frosted search bar** (titles/references/URLs/tags, realtime) + **filter chips**: platform × type × memo toggle.
3. ✅ Settings screen (same page): Gemini key + model + auto-tag toggle; sync config (paste config JSON, tolerant `{…}` extraction), sync code + on/off; capture category toggles (bubble hides instantly); storage-used row; export/import JSON; erase-all.
4. ✅ Gemini enrich post-save (never blocks the tick): tags/summary merged back, library re-renders via `omnisave:changed` broadcast.
5. ✅ Housekeeping: 15-min canonical dedupe carried from P1, listAll chunked render.

### P3 — Android app MVP (**required client — starts immediately after P1**) — **SOURCE BUILT 2026-11-10; not compiled (no Gradle/SDK run on this machine)**
1. ✅ Kotlin + Compose, single-activity: Onboarding/permissions → Library (bottom search + platform/type/memo chips, LazyColumn) → Settings (Gemini, sync, storage row, erase).
2. ✅ First-open permission flow: `SYSTEM_ALERT_WINDOW`, mic, notifications — plain-language explainers + direct jumps (`ACTION_MANAGE_OVERLAY_PERMISSION` etc.); re-evaluated on resume.
3. ✅ `BubbleService` = foreground FGS (`specialUse|microphone`, PROPERTY_SPECIAL_USE_FGS_SUBTYPE declared), tap → save sheet, clipboard-probe auto-capture (URL/title/video id/Shorts), Runtime memo via MediaRecorder mono AAC 24kbps/16k ≤30s, drawn vector tick on success.
4. ✅ Storage: `Room` (`Item` mirrors the shared `Item` model), compressed memo files in app storage — budget tables apply at device testing.
4b. ✅ **Sync from day one**: `Sync.kt` implements the same E2E envelope as `extension/lib/sync.js` (PBKDF2-HMAC-SHA256 120k, salt `omnisave-v1`, AES-256/GCM, tombstones, `_remote` never re-push, delta `lastSync`, child listeners).
5. ✅ `ShareReceiverActivity`: "Share → Omnisave" from any social app → auto-save → tick toast.
6. ⬜ Logo/icon still placeholder vector; run the **design** skill pass at branding polish.

### P4 — Only-if-useful extras (default: skip until you ask)
Gemini-powered "ask my saved items" search, export to Markdown/Notion, cloud sync, backup reminders, widgets. **Not in scope now.**

## Non-goals v1
No server **and no accounts of ours** (optional sync uses the user's own free Firebase project, E2E-encrypted, per Architecture C), no screenshot capturing of DRM/protected video streams, no auto-capture-everything-without-you-tapping-anything (privacy + storage cost; the bubble is the trigger by design).

## Repo layout
```
extension/            ← MV3 extension (P1–P2)
  manifest.json
  lib/db.js           ← shared storage layer (used by SW, bubble, popup, library)
  background/sw.js
  content/bubble.js   ← bubble + save sheet (.shadow-dom)
  popup/…             ← toolbar popup → library link
  library/…
  assets/icons/
android/            ← P3 (later)
design/              ← tokens.md, logo assets
PLAN.md              ← this file
```

## Risks / notes
- Bubble sitting *on* site pages must never fight site scripts → Shadow DOM, highest-but-polite z-index, `MutationObserver`-resilient re-attach.
- Sites change their DOM weekly → context detection leans on **URL patterns + OpenGraph/title**, not fragile selectors.
- Extension popup can be narrow → full library opens as a regular tab (like your spec: search at bottom of the app screen).
- Audio "_some line_ for reference" = short memo, compressed hard; not transcription (transcription = later via Gemini if you want).
