# Changelog

HPM reads the version and release notes from `packageManifest.json`; this
file is the history for people reading the repo.

## 1.4.1 — unreleased

- `positionFresh` follows the server's `position_fresh` when the server
  sends it. During an area hold (a quiet phone last confirmed at one of
  the member's saved places) `safetyStatus` stays `at_area`, as the server
  says, and `positionFresh` now reads `false` — it read `true` before.
  Servers that don't send the field get the previous behaviour.
- Entering a hold, the descriptionText reads "<name> was last confirmed
  at a saved place <N> min ago".
- No new attributes or capabilities. `presence` and `currentArea` are
  unchanged.
- The app, member driver and area driver header lines now carry the
  package version (they had stayed at 1.3.1 through 1.4.0).
- `tests/driver_harness.groovy`: an off-hub harness for the member
  driver's safety and freshness rendering.

## 1.4.0 — 2026-09-02

See the release notes in `packageManifest.json`.
