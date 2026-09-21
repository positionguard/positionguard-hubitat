# Changelog

HPM reads the version and release notes from `packageManifest.json`; this
file is the history for people reading the repo.

## 1.5.0 — unreleased

- Area devices get a "move here" button. The PositionGuard Area driver
  now declares `Momentary` and `PushableButton` (with `Actuator`); one
  `push(buttonNumber = 1)` serves both `push()` and `push(1)`. Pushing it
  asks the app to call `POST /areas/{id}/move` with no request body, which
  moves the area to the position PositionGuard already holds for the key's
  owner.
- Opt-in twice. The app preference "Allow this hub to move areas" (off by
  default) is checked before any request. The key must also have been
  created with `areas:move`; the hub can't ask the API for a key's scopes,
  so it learns this from the `MISSING_SCOPE` reply and shows that reply
  as-is.
- Two new attributes on area devices: `lastMoveResult` (a sentence: the
  outcome, or the API's `error` message unchanged) and `lastMoveAt`
  (ISO-8601 UTC, set only when the centre actually changed). A move reads
  "Hotel moved 341 km (212 mi) to your phone's location"; under 1 km it
  uses metres and feet.
- The move path has its own response handling and never calls
  `handleAuthFailure`, for any status, 401 included. A refused or failed
  move can't stop polling or touch presence devices.
- Every area device gets the button, including areas the key's owner
  didn't create (PositionGuard's 403 is shown) and both devices of an
  area linked to two groups (either one moves the same area).
- No coordinates anywhere: the request has no body, and the response
  carries a distance and an age.
- `numberOfButtons` is set to 1 on install, on save, and on the first
  count update, so devices created before 1.5.0 pick it up without being
  re-created.
- Harness: `tests/hub_harness.groovy` gains 13 move scenarios (preference
  off, request shape, moved, moved:false, the six refusals, 403 not-owner,
  403 `MISSING_SCOPE`, 401, 404, 429, timeout, no coordinates), with the
  polling-intact check after each 403/401. New
  `tests/area_driver_harness.groovy` covers the driver's button and result
  events.

## 1.4.1 — unreleased

- `positionFresh` follows the server's `position_fresh` when the server
  sends it. During an area hold (a quiet phone last confirmed at one of
  the member's saved places) `safetyStatus` stays `at_area`, as the server
  says, and `positionFresh` now reads `false` — it read `true` before.
  Servers that don't send the field get the previous behaviour.
- Entering a hold, the descriptionText reads "<name> was last confirmed
  at a saved place <age> ago", with the age in minutes under 90 min
  ("45 min ago") and in hours after, to one decimal when not whole
  ("10 h ago", "2.5 h ago").
- No new attributes or capabilities. `presence` and `currentArea` are
  unchanged.
- The app, member driver and area driver header lines now carry the
  package version (they had stayed at 1.3.1 through 1.4.0).
- `tests/driver_harness.groovy`: an off-hub harness for the member
  driver's safety and freshness rendering.

## 1.4.0 — 2026-09-02

See the release notes in `packageManifest.json`.
