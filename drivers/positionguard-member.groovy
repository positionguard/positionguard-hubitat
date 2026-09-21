/**
 *  PositionGuard Member — child presence driver for Hubitat Elevation
 *
 *  Created and managed by the PositionGuard parent app. One device per group
 *  member. The parent app does all network I/O and pushes state changes here
 *  via updateFromParent(); this driver never makes HTTP calls.
 *
 *  Hubitat's PresenceSensor capability is binary, so each device maps
 *  "present" to one user-designated area (the "Presence area" preference,
 *  default "Home") and exposes the full area-level state through the
 *  currentArea attribute for Rule Machine:
 *    <area name>  — member is inside that area
 *    "away"       — member is sharing, and in no defined area
 *    "unknown"    — member's sharing is paused; no current area knowledge
 *
 *  A sharing pause freezes presence (a pause must never fire an arrival or
 *  departure automation) while currentArea honestly reports "unknown".
 *
 *  PRIVACY INVARIANT — area-level presence only:
 *  This driver must never receive, store, log, or emit GPS coordinates.
 *  Area names and timestamps are the only location-related data it handles.
 *
 *  Version: 1.5.0 — keep in step with packageManifest.json. HPM update
 *  detection compares the manifest version only; this line is for humans.
 *
 *  MIT License — https://github.com/positionguard/positionguard-hubitat
 */

import groovy.transform.Field

// Sentinel for "sharing, but in no defined area". Hubitat users expect "away"
// on dashboards and in event logs, so this deliberately does not reuse the
// HA integration's HA-jargon state name.
@Field static final String NO_AREA = "away"

// Sentinel for "sharing is paused — area knowledge has genuinely lapsed".
// Distinct from NO_AREA: "away" is a known fact, "unknown" is the absence of one.
@Field static final String AREA_UNKNOWN = "unknown"

metadata {
    definition(
        name: "PositionGuard Member",
        namespace: "positionguard",
        author: "Christer Lundin",
        importUrl: "https://raw.githubusercontent.com/positionguard/positionguard-hubitat/main/drivers/positionguard-member.groovy"
    ) {
        capability "PresenceSensor"
        capability "Refresh"
        capability "Sensor"

        attribute "currentArea", "string"    // area name, "away" (in no area), or "unknown" (sharing paused)
        attribute "areaSince", "string"      // ISO-8601 UTC, when the hub observed the current area state begin
        attribute "areaSinceLocal", "string" // areaSince rendered "yyyy-MM-dd HH:mm:ss" in the hub's local time zone
        attribute "sharingStatus", "string"  // "active" or "disabled" (member paused sharing)
        attribute "safetyStatus", "string"   // at_area | in_zone | out_of_zone | unknown (stale/paused/no-data all render as unknown — Hubitat has no "unavailable")
        attribute "outsideUsualArea", "enum", ["true", "false"] // "true" only on a CONFIRMED out_of_zone
        attribute "positionAgeSeconds", "number" // seconds since the member's last position; null when unknown/paused
        attribute "positionFresh", "enum", ["true", "false"] // server has a fresh position — "false" when stale/paused and during an area hold (at_area from a last-known position); gate cautious rules on this
    }

    preferences {
        input name: "presenceArea", type: "text", title: "Presence area",
            description: "Area name that maps to <b>present</b> (case-insensitive). " +
                "When empty, an area named \"Home\" is used — if this member is never " +
                "in an area named \"Home\", presence stays <b>not present</b>."
        input name: "txtEnable", type: "bool", title: "Enable descriptionText logging",
            defaultValue: true
    }
}

def installed() {
    log.info "${device.displayName} installed — waiting for the first update from the PositionGuard app"
}

def updated() {
    // Before the pause-guard below: the local-time rendering is display-only,
    // so Save Preferences may populate or re-render it even while presence
    // evaluation stays frozen for a sharing pause.
    syncAreaSinceLocal(device.currentValue("areaSince"))
    // The presenceArea preference may have changed — re-evaluate presence
    // against the area we already know, without waiting for the next poll.
    // Never re-evaluate while sharing is paused: presence is frozen during a
    // pause, and the first update after resume applies the new preference.
    if (device.currentValue("sharingStatus") == "disabled") return
    String area = device.currentValue("currentArea")
    if (area != null) {
        evaluatePresence(area)
    }
}

def refresh() {
    // Local recompute only — all network I/O lives in the parent app.
    syncAreaSinceLocal(device.currentValue("areaSince"))
    parent?.pollNow()
}

/**
 *  Called by the parent app when this member's state changes (and once when
 *  the device is created). The parent diffs against last-known state, so this
 *  normally only runs on a real change; the value guards below make repeated
 *  calls harmless.
 *
 *  @param areaName       area name, "away" (in no defined area), or "unknown"
 *                        (sharing paused)
 *  @param areaSince      ISO-8601 UTC timestamp of when the current area state began
 *  @param sharingStatus  "active" or "disabled"
 *  @param safety         [status: at_area|in_zone|out_of_zone|stale,
 *                        area: <name, optional>, ageSeconds: <int, optional>,
 *                        fresh: <boolean, optional — the server's
 *                        position_fresh; absent from older servers>]
 *                        or null when the server sent no safety fields. The
 *                        default keeps an older parent app calling the 3-arg
 *                        shape working: safety then renders as "unknown".
 */
void updateFromParent(String areaName, String areaSince, String sharingStatus, Map safety = null) {
    String area = areaName ?: NO_AREA
    String prevArea = device.currentValue("currentArea")
    boolean paused = (sharingStatus == "disabled")

    // The sharing-status change carries the narrative for a pause, so the
    // currentArea flip to "unknown" below doesn't read like a real move.
    if (sharingStatus && sharingStatus != device.currentValue("sharingStatus")) {
        String desc = paused ?
            "${device.displayName}'s location sharing is paused" :
            "${device.displayName}'s location sharing resumed"
        sendEvent(name: "sharingStatus", value: sharingStatus, descriptionText: desc)
        logText(desc)
    }

    if (area != prevArea) {
        String desc = areaChangeDescription(area, prevArea)
        sendEvent(name: "currentArea", value: area, descriptionText: desc)
        // A flip to "unknown" was already narrated by the pause event above.
        if (area != AREA_UNKNOWN) {
            logText(desc)
        }
    }

    if (areaSince && areaSince != device.currentValue("areaSince")) {
        sendEvent(name: "areaSince", value: areaSince,
            descriptionText: "${device.displayName} current area since ${areaSince}")
    }
    // Outside the change-guard above: devices that predate areaSinceLocal
    // backfill on their first parent update, and a hub time-zone change
    // re-renders on the next update rather than sticking to the old zone.
    syncAreaSinceLocal(areaSince ?: device.currentValue("areaSince"))

    if (paused) {
        // Presence holds its last value: a pause must never fire an arrival
        // or departure automation. A member first seen while already paused
        // has nothing to hold — default to "not present", because a false
        // arrival is worse than a false absence.
        if (device.currentValue("presence") == null) {
            sendPresence("not present")
        }
    } else {
        evaluatePresence(area)
    }

    syncSafety(safety, paused)
}

/**
 *  Emit safetyStatus and the Rule-Machine-friendly outsideUsualArea pair.
 *
 *  safetyStatus renders "unknown" when sharing is paused, when the server sent
 *  no safety fields (feature off, member muted, public-group record), or when
 *  the server reports "stale" — Hubitat has no device-level "unavailable", and a
 *  stale reading (rare once the server's 50-minute threshold lands) means the
 *  phone has been dark for the better part of an hour, which "unknown" conveys
 *  honestly; asserting a last-known tier would answer "where are they" with a
 *  place they may have left. Absence of knowledge must never read as
 *  safely-inside. positionFresh / positionAgeSeconds carry the freshness detail
 *  for rules that want it.
 *
 *  The area hold: a server with SAFETY_STATUS_AREA_HOLD keeps "at_area" through
 *  a phone's silence and sends position_fresh false with the true age.
 *  safetyStatus keeps following the server (no client-side downgrade on age);
 *  positionFresh goes "false" and the descriptionText says it is a last
 *  confirmation: "<name> was last confirmed at a saved place <age> ago" ("45 min", "10 h", "2.5 h").
 *  outsideUsualArea stays strictly two-valued so RM
 *  rules can trigger on it directly: "true" only on a CONFIRMED out_of_zone; a
 *  quiet phone (unknown) is "false", not evidence of being outside.
 */
private void syncSafety(Map safety, boolean paused) {
    String serverStatus = (!paused && safety?.status) ? (safety.status as String) : null
    // No device-level "unavailable" on Hubitat: a "stale" reading renders as
    // "unknown", the same sentinel used for a sharing pause (see the method
    // doc). The description is still taken from the real server status.
    String status = (serverStatus == null || serverStatus == "stale") ? "unknown" : serverStatus
    Integer ageSeconds = (safety?.ageSeconds != null) ? (safety.ageSeconds as Integer) : null
    // Held: the server's at_area rests on a last-known position (area hold).
    // The tier is still the server's; only the narrative changes.
    boolean held = (serverStatus == "at_area" && safety?.fresh == false)
    boolean narrated = false
    if (status != device.currentValue("safetyStatus")) {
        String desc = held ? heldDescription(ageSeconds) : safetyChangeDescription(serverStatus)
        sendEvent(name: "safetyStatus", value: status, descriptionText: desc)
        if (status != "unknown") {
            logText(desc)
            narrated = true
        }
    }

    String outside = (status == "out_of_zone") ? "true" : "false"
    if (outside != device.currentValue("outsideUsualArea")) {
        String desc = (outside == "true") ?
            "${device.displayName} is outside their usual area" :
            "${device.displayName} is no longer flagged outside their usual area"
        // safetyStatus above already logs the human narrative for this same
        // transition (out_of_zone and outsideUsualArea="true" always change
        // together). outsideUsualArea is the Rule-Machine boolean mirror: it
        // emits the event for automations but must NOT re-log the identical
        // "outside their usual area" sentence — that was the doubled log line.
        sendEvent(name: "outsideUsualArea", value: outside, descriptionText: desc)
    }

    // Freshness, exposed unconditionally (independent of the rendered tier) so
    // cautious rules can gate on it. positionFresh follows the SERVER's
    // determination, never a client-side age cut; the threshold lives on the
    // server. When the server sends position_fresh (safety.fresh) that is the
    // answer; older servers don't, and then a non-stale tier means the server
    // had a fresh position. It is "false" whenever safetyStatus is "unknown"
    // (stale, paused, or no data), and during an area hold.
    String fresh
    if (serverStatus == null) {
        fresh = "false"
    } else if (safety.fresh != null) {
        fresh = safety.fresh ? "true" : "false"
    } else {
        fresh = (serverStatus != "stale") ? "true" : "false"
    }
    if (fresh != device.currentValue("positionFresh")) {
        if (held) {
            // Entering a hold while already at_area changes no tier, so this is
            // the event that carries the narrative (once per cycle).
            String desc = heldDescription(ageSeconds)
            sendEvent(name: "positionFresh", value: fresh, descriptionText: desc)
            if (!narrated) logText(desc)
        } else {
            sendEvent(name: "positionFresh", value: fresh)
        }
    }
    if (ageSeconds != device.currentValue("positionAgeSeconds")) {
        sendEvent(name: "positionAgeSeconds", value: ageSeconds)
    }
}

private String heldDescription(Integer ageSeconds) {
    if (ageSeconds == null) {
        return "${device.displayName} was last confirmed at a saved place; no newer position"
    }
    // Minutes under 90 min ("45 min"), otherwise hours to one decimal with a
    // whole number shown bare ("10 h", "2.5 h").
    String age
    if (ageSeconds < 90 * 60) {
        age = "${Math.max(1L, Math.round(ageSeconds / 60.0d))} min"
    } else {
        BigDecimal hours = (ageSeconds / 3600.0d).toBigDecimal().setScale(1, java.math.RoundingMode.HALF_UP)
        age = "${hours.stripTrailingZeros().toPlainString()} h"
    }
    return "${device.displayName} was last confirmed at a saved place ${age} ago"
}

private String safetyChangeDescription(String status) {
    switch (status) {
        case "at_area":     return "${device.displayName} is at a saved place"
        case "in_zone":     return "${device.displayName} is in their usual area"
        case "out_of_zone": return "${device.displayName} is outside their usual area"
        case "stale":       return "${device.displayName} has no recent position"
        default:            return "${device.displayName}'s safety status is unknown"
    }
}

private String areaChangeDescription(String area, String prevArea) {
    if (area == AREA_UNKNOWN) {
        return "${device.displayName}'s current area is unknown (sharing paused)"
    }
    if (area == NO_AREA) {
        boolean prevReal = prevArea && prevArea != NO_AREA && prevArea != AREA_UNKNOWN
        return prevReal ?
            "${device.displayName} left ${prevArea}" :
            "${device.displayName} is not in any area"
    }
    // Coming out of a pause we re-learn the area rather than observe an arrival.
    return (prevArea == AREA_UNKNOWN) ?
        "${device.displayName} is at ${area}" :
        "${device.displayName} arrived at ${area}"
}

/**
 *  Emit areaSinceLocal: the areaSince instant rendered in the hub's local
 *  time zone. areaSince itself must stay ISO-8601 UTC — automations may
 *  depend on that exact format — so local time is a companion attribute,
 *  never a change to the original.
 */
private void syncAreaSinceLocal(String areaSinceUtc) {
    if (!areaSinceUtc) return
    String local = toLocalTime(areaSinceUtc)
    if (local && local != device.currentValue("areaSinceLocal")) {
        sendEvent(name: "areaSinceLocal", value: local,
            descriptionText: "${device.displayName} current area since ${local} (hub local time)")
    }
}

private String toLocalTime(String isoUtc) {
    try {
        // The parent generates every areaSince value with this exact format;
        // parse must pin UTC or SimpleDateFormat assumes hub-local time.
        def parser = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'")
        parser.setTimeZone(TimeZone.getTimeZone("UTC"))
        Date instant = parser.parse(isoUtc)
        return instant.format("yyyy-MM-dd HH:mm:ss", location?.timeZone ?: TimeZone.getDefault())
    } catch (e) {
        log.warn "${device.displayName}: could not render '${isoUtc}' as local time (${e})"
        return null
    }
}

private void evaluatePresence(String area) {
    String target = settings.presenceArea?.trim() ?: "Home"
    String value = area?.equalsIgnoreCase(target) ? "present" : "not present"
    if (value != device.currentValue("presence")) {
        sendPresence(value)
    }
}

private void sendPresence(String value) {
    String desc = "${device.displayName} is ${value}"
    sendEvent(name: "presence", value: value, descriptionText: desc)
    logText(desc)
}

private void logText(String msg) {
    if (txtEnable != false) log.info msg
}
