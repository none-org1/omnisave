# AGENTS.md — read me first (any agent working in this repo)

## What this project is
**Omnisave** — a local-first "save anything you scroll" product with two clients sharing one record model:
1. **Browser extension (MV3, vanilla JS/CSS)** — floating bubble on social/content sites, auto-captures context in realtime.
2. **Android app (Kotlin + Jetpack Compose)** — same bubble over any app via display-over-other-apps.

Full spec: `PRD.md` (what) · `PLAN.md` (how, phases P1→P4).

## Current state
**P1+P1.5+P2 built, P3 (Android) built (2026-11-10) — extension syntax-checked; Android NOT yet compiled (Gradle untouched, no SDK run).** Extension = MV3, Shadow-DOM bubble w/ realtime SPA re-capture + category toggles (omnisaveSources), save sheet (reference + ≤30s memo), tick-on-success, IndexedDB (`lib/db.js` `listAll` full library), library page (bottom search + platform/type/memo filters + delete + memo playback), settings w/ Gemini enrich + sync config UI + export/import + storage row, recent popup, dev selfcheck, generated icons (`design/logo.ps1`). Sync engine E2E (AES-GCM/PBKDF2-120k) live both sides. Android (`android/`, Kotlin+Compose, OK to open in Android Studio): BubbleService overlay + clipboard auto-capture + memo Room, bottom search + filters, onboarding permission flow, ShareReceiverActivity, GemClient, Sync.kt envelope-compatible. **Next: user loads extension unpacked & verifies; user opens android/ in Android Studio (AGP 8.5.2/Kotlin 2.0.20 — Gradle wrapper not bundled); then on-device verify both sides of sync.**

## What to do now
1. Open `PLAN.md` → find the current phase (start = **P1, extension MVP**).
2. Build within the declared repo layout (extension/, android/, design/). Don't invent parallel structures.
3. Dependency: before UI work, read the tokens/design section of `PLAN.md` and keep every screen on it (frosted surfaces, single blue accent, hairline borders, 150–250ms springs).
4. Data model lives in `PLAN.md` (`Item`) — both clients must keep this shape exactly; if changed, update PLAN.md in the same diff.
5. Complete the phase's self-check before writing the next phase.

## What "done" means per separate feature (from PRD)
- Save from YouTube Shorts, long video, X, LinkedIn, Discord, Reddit, Notion, Instagram and plain web → tick animation on success, ✕ + reason on failure.
- Reference text searchable; audio optional, ≤30s, compressed.
- No URL pasting anywhere in the flow — auto-capture must update on every in-page navigation.
- Storage stays local, no server, no account.

## Doc sync rule (every feature)
Adding or changing any feature = one diff that includes:
1. Product files for the feature,
2. Updated `PLAN.md` (phase status/decisions) + `PRD.md` (requirement text) if scope changed,
3. This file's "Current state" if it changed.
A feature is not "done" until its docs land with its code. Any new screen **must** reuse the design tokens in `PLAN.md` (ui-ux-pro-max/ui-styling data) before writing CSS — no per-screen ad-hoc colors.

## Guardrules (hard—verify)
- `.opencode/skills/` and `.opencode/` are config-only; never touch their contents from feature code.
- Never start commands with `pip`, `npm i` extra runtime deps into the extension (zero-build vanilla only); Android uses properly declared Gradle deps only.
- No server **we run**: user-supplied free-tier Firebase config (sync) and Gemini key (enrich) are the only allowed keys; both stay optional. Sync data must be AES-GCM sealed with the sync-code-derived key before upload — never upload plaintext saves.
- Screen-reader visible names on every icon control; touch targets ≥44px; never drop the tick-on-success.
- Content-script UI (bubble/save sheet) uses **Shadow DOM**; avoid per-site fragile selectors whenever possible (URL/meta patterns first).

## Other gotcha notes
- **No Android Studio needed on this machine for APKs**: `.github/workflows/build-apk.yml` builds `app-debug.apk` on every GitHub push (Gradle 8.7, Java 17, no wrapper) **and publishes it as a Release so README's link `https://github.com/none-org1/omnisave/releases/latest/download/app-debug.apk` always serves the newest build directly on the phone** (repo: none-org1/omnisave).
- ui-ux-pro-max's search CLI needs Python 3 — not installed on this machine. Until then, verify the curated tokens live in PLAN.md (do not re-derive).
- Windows machine: prefer `python` over `python3` if Python ever is installed; PowerShell for shell commands.
- Working dir is NOT a git repo at the moment of writing; if you initialize git later, keep .opencode/ unignored only if hooks/skills must ship with repo.
