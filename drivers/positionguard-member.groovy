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
 *  Version: 1.1.0 — keep in step with packageManifest.json. HPM update
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
 */
void updateFromParent(String areaName, String areaSince, String sharingStatus) {
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
