# PositionGuard — Hubitat Elevation Integration

Privacy-first family location for Hubitat. PositionGuard exposes your
family group's presence as native Hubitat presence devices, with
area-level granularity — home, school, grandma's house — instead of
exact coordinates.

Built for Hubitat users who want reliable family presence detection
without handing their family's location data to third-party services.

---

## What's different about this

- **Area-level, not coordinate-level.** PositionGuard reports presence
  as "in this area" or "not in this area" — never exact GPS coordinates.
- **One install per person.** Family members install the PositionGuard
  app on their phone once. They don't need Hubitat accounts or any
  Hubitat configuration on their device.
- **Works around iOS Private Wi-Fi Address rotation.** Position is
  reported by the phone, not detected by the router, so iPhones report
  reliably regardless of MAC randomization.
- **Privacy-first by design.** No data sale, no ads, no third-party
  trackers. Sharing can be paused per-person at any time.

---

## What this integration provides

One parent app (**PositionGuard**) that holds your API key, polls the
PositionGuard API, and manages two kinds of child device:

- One **member device** per group member, using the **PositionGuard
  Member** driver — presence, current area, and Safety Zone status.
- One **area count device** per area in each selected group, using the
  **PositionGuard Area** driver — how many members are currently inside
  that area.

Each member device implements Hubitat's `PresenceSensor` and `Refresh`
capabilities and exposes:

| Attribute | Values | Meaning |
|---|---|---|
| `presence` | `present` / `not present` | Whether the member is in the device's designated **presence area** (see below) |
| `currentArea` | area name, `away`, or `unknown` | The member's area-level state — see the table below |
| `areaSince` | ISO-8601 UTC timestamp | When the hub observed the current area state begin |
| `areaSinceLocal` | `yyyy-MM-dd HH:mm:ss`, hub-local | The same instant as `areaSince`, rendered in the hub's local time zone — for dashboards |
| `sharingStatus` | `active` / `disabled` | Whether the member has paused location sharing |
| `safetyStatus` | `at_area` / `in_zone` / `out_of_zone` / `unknown` | The member's position relative to their own **usual area** — see "Safety Zone status" below |
| `outsideUsualArea` | `true` / `false` | `true` exactly when the member is **confirmed outside their usual area** — a direct Rule Machine trigger, no comparison logic needed |
| `positionFresh` | `true` / `false` | Whether the member's most recent position is recent enough for PositionGuard to trust it. `false` means the phone has gone quiet |
| `positionAgeSeconds` | integer | Seconds since the member's last reported position, as of the last poll |

Every state change carries a human-readable `descriptionText` (e.g.
"Sally arrived at School"), so device event logs and notifications read
naturally.

### `currentArea` values

| `currentArea` value | Meaning |
|---|---|
| *area name* | Member is inside that area |
| `away` | Member is sharing, and in no defined area |
| `unknown` | Member's sharing is paused — no current knowledge |

`away` follows Hubitat convention for dashboards and event logs. (The
Home Assistant integration names the same state in HA's own jargon;
cross-platform verbatim matching is not a goal.)

### The presence area

Hubitat presence is binary, unlike PositionGuard's multi-area model.
Each member device therefore has a **Presence area** preference: the
area name that maps to `present` (matched case-insensitively). Set it
per device on the device page — e.g. "Home" for most members, "School"
if you want a device that means "Sally is at school".

**Default when unset:** an area named "Home" is used. If the member is
never in an area named "Home", their presence stays `not present`. For
any-area automations, use the `currentArea` attribute instead of
`presence`.

### Safety Zones status

PositionGuard computes each member a **usual area** — a zone
derived from that member's own saved places, weighted by where they
actually spend time. Two attributes expose the member's position
relative to it:

| `safetyStatus` value | Meaning |
|---|---|
| `at_area` | At one of their saved places |
| `in_zone` | Not at a saved place, but inside their usual area |
| `out_of_zone` | A fresh position exists, and it is outside their usual area |
| `unknown` | Nothing trustworthy to report — see below |

`outsideUsualArea` is deliberately just `true` / `false`, so a Rule
Machine trigger on it fires exactly when someone is **confirmed
outside their usual area** — and at no other time. `unknown` maps to
`false`: absence of data is never evidence of being outside, and a
dying battery must never set off an outside-zone alert.

`safetyStatus` reads `unknown` in four situations, none of which is
"safely inside":

- **The phone has gone quiet.** No position has been reported recently
  enough for PositionGuard to trust it. `positionFresh` reads `false`
  and `positionAgeSeconds` tells you how long it's been. This is the
  common overnight case — a sleeping phone is not a missing person.
- The member paused sharing.
- No selected group is allowed to carry safety data — the member muted
  the group, or the group is public.
- Safety Zone status isn't live on their account yet.

Rules that want to react to a phone going quiet — a low-battery
reminder, say — should trigger on `positionFresh` changing to `false`,
not on `safetyStatus`. Keep `outsideUsualArea` for what it's for.

### Area member counts

Each area in a selected group gets its own child device (the
**PositionGuard Area** driver) reporting how many members are inside it
right now. The device knows *how many*, never *who* or *where* — it's
built for occupancy rules, not tracking.

| Attribute | Values | Meaning |
|---|---|---|
| `memberCount` | integer | Members currently inside this area, including any whose position is stale |
| `staleCount` | integer | How many of those are counted from a position that has gone quiet |
| `freshCount` | integer | `memberCount` minus `staleCount` — members counted from a position PositionGuard still trusts |
| `countAvailable` | `true` / `false` | Whether the counts are current. `false` when they couldn't be fetched — the numbers then hold their last value rather than dropping to zero |

A member who paused sharing, or who has this area set as a private
place, is not counted — the count only reflects what members have
chosen to share.

Count devices are named `<Group>: <Area> count` (e.g. `Family: Home
count`) and are created and removed automatically as areas are added
to or removed from a selected group.

**Why `staleCount` exists.** A member counted from a stale position is
probably still there — that's the usual reason a phone goes quiet at
home overnight — but "probably" is worth knowing about. If a rule
should act only on confirmed presence, use `freshCount` (which is
exactly `memberCount` minus `staleCount`), and gate it on
`countAvailable` being `true` so an API outage can't be read as an
empty house.

---

## Compatibility

- **Hubitat Elevation**: platform version 2.3.0 or later
- **PositionGuard app**: latest version on the
  [App Store](https://apps.apple.com/app/id6758687496) or
  [Google Play](https://play.google.com/store/apps/details?id=com.positionguard.app).
  Safety Zone status and area counts require iOS 2.0 / Android 0.9 or
  later.
- **Hubitat Package Manager (HPM)**: recommended for installation,
  though manual install is supported

---

## Account and usage limits

The integration requires a PositionGuard account (free) and an API key
from [dev.positionguardai.com](https://dev.positionguardai.com). Free
tier covers a typical household:

- Up to 3 groups
- Up to 3 areas per group
- Up to 10 members per group

An optional paid tier on iOS raises these limits (unlimited groups and
members, up to 20 areas per group) for larger setups.

The integration polls the PositionGuard API once per minute by default
(configurable to 5 or 10 minutes), which is well within the API's rate
limits — no tuning needed. If you also run the Home Assistant
integration, use a separate API key per integration so keys can be
rotated independently.

---

## Installation

You'll go through three places: the PositionGuard app (to set up your
account and family), the developer portal (to mint an API key), and
your Hubitat hub (to install this integration).

### 1. Install the PositionGuard app and set up your family

1. Install PositionGuard from the [App Store](https://apps.apple.com/app/id6758687496)
   or [Google Play](https://play.google.com/store/apps/details?id=com.positionguard.app).
2. Sign in with your phone number (SMS verification).
3. Create a family group. Default name is "Family"; rename if you like.
4. Add areas to the group: at minimum a "Home" area centered on your
   house. Add others as needed (work, school, grandma's house, etc.).
5. Invite family members to the group. They install the app and accept
   the invite. Sharing can be paused per-person at any time.
6. Confirm positions update on the app's map before checking Hubitat.
   Location sharing is **off by default** for every member (including
   you) — each person turns it on in the app when they're ready. A
   member who hasn't enabled sharing yet appears in Hubitat as
   `currentArea: unknown` / `sharingStatus: disabled`, which is the
   integration working, not a bug.

> **Setting up several family phones at once?** Account signup verifies each
> phone number by SMS, and the verification provider applies anti-abuse limits
> that can flag many signups done back-to-back. Onboarding a whole household?
> Stagger the signups — a couple of phones, then a break. If a number does get
> temporarily blocked, don't retry repeatedly (retries can extend the cooldown);
> wait a few hours and it clears on its own.

### 2. Get your API key from the developer portal

1. Visit [dev.positionguardai.com](https://dev.positionguardai.com).
2. Sign in with the same phone number you used for the app.
3. Click **Create API key**, give it a descriptive name (e.g.,
   "Hubitat").
4. Copy the key — it's shown once only. Store it somewhere safe (a
   password manager works well).

### 3a. Install via Hubitat Package Manager (recommended)

1. Install [Hubitat Package Manager](https://hubitatpackagemanager.hubitatcommunity.com/)
   if you don't already have it.
2. Open HPM → **Install** → **From a URL**.
3. Paste the manifest URL:
   `https://raw.githubusercontent.com/positionguard/positionguard-hubitat/main/packageManifest.json`
4. Follow the prompts. HPM installs both the app and the driver, and
   will offer future updates.

### 3b. Manual installation (without HPM)

1. In Hubitat, open **Drivers Code** → **New Driver** → **Import**, and
   paste:
   `https://raw.githubusercontent.com/positionguard/positionguard-hubitat/main/drivers/positionguard-member.groovy`
   Save. (Install the driver first — the app creates child devices with
   it.)
2. Open **Apps Code** → **New App** → **Import**, and paste:
   `https://raw.githubusercontent.com/positionguard/positionguard-hubitat/main/apps/positionguard-app.groovy`
   Save.

**Updating manually:** an update must cover both files — updating only
one leaves the app and driver on mismatched versions, which shows up as
a red `MissingMethodException` in the logs on member updates until they
match again. Driver first here too: open **Drivers Code** →
PositionGuard Member → **Import** → Save, then **Apps Code** →
PositionGuard → **Import** → Save. The import URL is remembered from
installation, so each is a one-click re-fetch — no pasting needed.

### 4. Configure the integration

1. Go to **Apps** → **Add User App** → **PositionGuard**.
2. Enter your API key. It is validated against PositionGuard
   immediately — you can't proceed with an invalid key.
3. Select which group(s) to integrate. You can select multiple groups;
   a member in several selected groups still gets a single device.
4. Press **Done**. Within one poll interval (about a minute), one
   presence device per member appears, named after the member, plus
   one count device per area in each selected group.
5. On each member's device page, set the **Presence area** preference
   if something other than "Home" should count as present.

Renaming a member in PositionGuard updates the Hubitat device's *name*
automatically; the device itself (and your rules) are preserved. The
device *label* is yours: set one on the device page and it sticks —
the integration never overwrites it. Clear the label to display the
PositionGuard nickname again.
Members removed from all selected groups have their device deleted.

---

## Rule Machine examples

**Welcome someone home** — trigger on the presence capability:
*Trigger:* `Sally presence · arrives` → *Action:* turn on the hallway
lights. (With Sally's Presence area left at the default "Home".)

**Notify when a child reaches school** — trigger on the custom
attribute: *Trigger:* Custom Attribute → device `Sally` → attribute
`currentArea` → value `School` → *Action:* send "Sally arrived at
school". Because `currentArea` carries every area transition, one rule
per area is all you need — no extra devices.

**Announce any transition** — trigger on `currentArea` *changed* and
use `%value%` (the new area, `away`, or `unknown`) in the notification
text.

**Alert when someone is outside their usual area** — trigger on the
Safety Zone boolean directly: *Trigger:* Custom Attribute → device
`Sally` → attribute `outsideUsualArea` → value `true` → *Action:* send
"Sally is outside her usual area". No comparison logic needed — the
attribute is `true` only on a confirmed `out_of_zone`, so this rule
never fires on a quiet phone or missing data (both read as `unknown`).

**Away mode when the house is empty** — trigger on the Home area's
count device: *Trigger:* Custom Attribute → device `Family: Home count`
→ attribute `memberCount` → value `0` → *Action:* set mode to Away. Add
a condition that `countAvailable` is `true`, so an API outage — where
the count holds its last value rather than dropping to zero — is never
read as an empty house. One device, one attribute, no per-member logic.
To act only on confirmed presence, trigger on `freshCount` → `0`
instead (or add a condition that `staleCount` is `0`).

---

## Polling and reliability

- Polls every minute by default; 5- and 10-minute intervals are
  available in the app's settings. Changes typically reflect within
  one poll interval.
- **Transient failures** (network down, timeouts, server errors, rate
  limiting): child devices hold their last-known state, a warning is
  logged, and presence never flaps. Normal updates resume on the next
  successful poll.
- **Invalid or revoked API key**: polling stops, the error is shown at
  the top of the app, and a single error is logged (no once-a-minute
  spam). Re-enter and validate the key in the app to resume.

### What happens when someone pauses sharing

A pause is a meaningful unknown, not an error state — the same
semantic direction as the Home Assistant integration. When a member
pauses sharing in the PositionGuard app, on the next poll:

- **`presence` holds its last value.** A pause must never fire a
  departure (or arrival) automation, so presence-based rules stay
  quiet for the whole pause.
- **`currentArea` becomes `unknown`.** Area knowledge genuinely
  lapsed, and that staleness is visible on dashboards rather than a
  stale area name. Rules triggered on `currentArea` may fire at this
  point — area knowledge really did change.
- **`sharingStatus` becomes `disabled`**, and one event narrates the
  transition ("Sally's location sharing is paused"). To react to
  pauses explicitly, trigger on this attribute.

A member first seen while already paused (e.g. added to a group
during their pause) starts as `unknown` / `disabled` / `not present`:
there is no prior presence to hold, and not-present is the safe
default because a false arrival is worse than a false absence.

When sharing resumes, real values return on the next poll. If the
member's area or presence changed while paused, those events fire
once at that point — the change is real; it just became knowable.

---

## Privacy

PositionGuard is built privacy-first. The integration shows presence at
**area level only**, never exact coordinates.

What's shared with your hub:
- Whether each member is in any area of a selected group
- Which specific area, if any, they're in (`currentArea`)
- Whether sharing is active or paused
- The member's Safety Zone status and position freshness — status
  words, booleans, and a duration; never a location
- How many members are in each area of a selected group (a count only)

What's never shared:
- Exact GPS coordinates
- Movement history outside of area transitions
- Any data about non-family-members nearby

Concretely, for this Hubitat integration: no coordinate data ever
appears in any attribute, event, log line, or state variable — at any
log level, including debug. The integration calls three endpoints:
the group list, the group members, and the per-area member counts.
Their responses contain area *names* and *counts* only; the
integration never requests the endpoint that describes area geometry.
You can verify this in the source — it's a grep away. The Safety Zone
attributes keep the same contract: `safetyStatus`, `outsideUsualArea`,
and `positionFresh` are a status word and two booleans, and
`positionAgeSeconds` is a duration — the usual area's location, size,
and shape are computed and kept by PositionGuard and never reach the
hub in any form. Area counts are likewise a number: which members make
up that number is not part of the response.

When a family member pauses sharing in the app, the integration
respects this immediately (see "What happens when someone pauses
sharing" above).

---

## Limitations

This integration is **read-only**. From Hubitat, you cannot create,
modify, or delete groups, areas, or members; change sharing
permissions; or send messages or invitations. These actions remain in
the PositionGuard app where group members manage their own privacy
directly.

The integration is polling-based (the PositionGuard backend has no
webhook support), so state changes appear within one poll interval —
about a minute at the default setting.

---

## Questions, feedback, bug reports

Use the
[Discussions](https://github.com/positionguard/positionguard-hubitat/discussions)
tab for questions or feedback, and
[Issues](https://github.com/positionguard/positionguard-hubitat/issues)
for bug reports.

For information about PositionGuard the app or the developer portal,
visit [positionguardai.com](https://positionguardai.com).

---

## License

MIT — see [LICENSE](LICENSE).
