# Omnisave — PRD

Tagline: *Save anything you scroll — once tap, one reference line.*

## Problem
Saving a reel, post, article, doc or full webpage is multi-step (share → app → note → paste, or browser bookmark bar dumps into a haystack). Six months later you can't find it because there's no context of *why* you saved it.

## Solution
A tiny always-present **bubble** that appears on the platforms where you consume content. One tap opens a sheet where the URL/context is **already auto-captured**; you only type one reference line (or hum a ≤30s audio note) and hit save. Everything lives **locally, compressed**. The **Omnisave library** (large UI) gives one bottom search bar + filter chips. Optional **Gemini (free model)** key auto-enriches saves with tags/summaries.

## Users
Single-user, local-first. Content consumers on X, LinkedIn, Discord, YouTube (Shorts + videos), Reddit, Notion, Instagram, generic web.

## Core requirements

### R1 — Bubble (capture surface)
- Small draggable circle on every normal web page (extension) / over any app (Android). Remembers position. Max ~28px visual, ≥44px hit area. Never blocks site UI, never ever hijacks clicks.
- Must run in background: service (MV3 SW / Android foreground service).

### R2 — Auto-context, realtime
- Captures `{url, title, platform, type}` automatically and **re-captures on every in-app navigation** (SPA jumps, video switch, Shorts→Shorts). No stale URL ever — the user must never paste a URL.
- Platform edges: YouTube → videoId + timestamp + isShort; X → article/post preview; generic → OG/fallback meta; doc/website/website-data classification.

### R3 — Save with reference
- Save sheet: auto-filled source chip + **reference input** (primary action) + optional audio memo (≤30s). Saving without entering anything must still work (auto title).
- **Success feedback = tick logo** animating on bubble (and sheet closes). Failure = ✕ with one-line reason.

### R4 — Local-first storage, optimized
- IndexedDB (extension) / Room (Android). Audio memo compressed mono AAC. Dedup: same-canonical-URL save within 15 min = update, not duplicate. No server of ours, no account; export/import JSON.

### R8 — Cross-device sync (extension ↔ Android)
- Setup = paste your own **free Firebase (Spark tier)** project config + generate a **sync code** (any passphrase) in settings on each device. Nothing else. No server we run, no signup through Omnisave.
- **E2E-encrypted:** items are AES-GCM sealed with a key derived from the sync code (PBKDF2, 120k iterations) before upload; the database operator reads ciphertext only.
- Behaviour: save on laptop → appears on phone (realtime, usually <2s); edit reference on phone → updates laptop; delete anywhere = removed everywhere (tombstone, 30-day purge); offline saves sync next time both devices are reachable. Delta pulls only; audio memos sync as compressed blobs in the envelope.
- Sync is **off by default** and always optional — saving works fully offline.

### R5 — Library (everything-saved screen)
- Bottom **search bar** (searches titles, references, URLs) sticky and frosted-glass.
- **Filter chips**: platform, type (short/video/article/post/doc/image/website), has-audio, date.
- Apple-clean UI: frosted surfaces, SF-style type, one blue accent, hairline borders, 150–250ms springy motion.

### R6 — Settings
- Gemini API key slot (free-tier model default `gemini-2.0-flash`), auto-tag/summary toggle.
- Capture toggles: **only Shorts/videos**, **X articles**, general browsing, documents. Per-platform granularity.

### R7 — Android-specific
- First-open permission flow (display-over-other-apps, mic, notifications) with plain-language explainer + direct jump to system settings.
- Share-target: "Share → Omnisave" from any social app (bubble covers the rest).

## Success criteria
- You can save from YouTube (Shorts + long), X, LinkedIn, Discord, Reddit, Notion, Instagram, any website — each edit ends with a tick.
- Reopening the library, the item is searchable by what you typed as reference and by platform/type filters.
- Storage stays lean (throttle audio, dedupe, no full-page dumps by default).
- Zero URL pasting in the whole flow.
- Save on laptop → item appears on phone within seconds if sync is on (and vice versa); sync off = identical behaviour to before.

## Performance budget (Android — hard numbers)
| Resource | Budget | Rule |
|---|---|---|
| Audio memo | ≤30s @ 24kbps mono AAC (~90KB max), compressed at record time | never stored as raw PCM |
| Text/thumb | page preview ≤200 chars; thumbs recompressed ≤480px ~60% quality | no full-page HTML dumps |
| Storage | typical library <5MB / 100 saves (text saves ~2KB each) | "storage used" row in settings |
| RAM | overlay service idle <40MB, foreground <80MB | Room touched only on save; no polling loops; no image cache >50MB |
| Battery | bubble = persistent overlay only | network only on save with Gemini on |
| List | Compose `LazyColumn` paginated | never load whole DB into memory |

All data stays in **local storage** (Room DB + app files dir). No cloud, no account.

## Out of scope v1
Our own backend/accounts (user's own Firebase config = allowed path), transcription (fine later via Gemini), auto-capture-without-tap, widgets. P4 ideas live in PLAN.md.
