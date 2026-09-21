/**
 *  PositionGuard Area — child count device for Hubitat Elevation
 *
 *  Created and managed by the PositionGuard parent app. One device per
 *  (group, area). Exposes how many of the group's members are currently inside
 *  the area, for Rule Machine:
 *    memberCount    — members inside now, stale positions included
 *    staleCount     — the subset whose last position is stale
 *    freshCount     — memberCount - staleCount
 *    areaName       — the area's name
 *    groupName      — the group's name
 *    countAvailable — "true" when the numbers are current; "false" when the
 *                     server withheld the count (public group, archived area) or
 *                     it could not be fetched. RM rules should gate on
 *                     countAvailable == "true" before trusting memberCount, the
 *                     same way an unavailable HA entity is not read as 0.
 *
 *  ABSENT IS NOT ZERO: memberCount == 0 is a real empty area (drive away mode);
 *  a withheld or unfetched count sets countAvailable = "false" and never fires a
 *  false 0. The last numbers are left in place while unavailable so a rule can
 *  see both "how many were last known" and "is that current".
 *
 *  MOVE HERE (1.5.0): push() asks the parent app to move this area to the
 *  phone's last reported position (POST /areas/{id}/move, no body). Both
 *  Momentary (push()) and PushableButton (push(1)) are declared so dashboards
 *  that recognise either one can offer a button; one method serves both.
 *  The parent reports back through moveResult():
 *    lastMoveResult — a sentence a person can read: the outcome, or the API's
 *                     refusal message exactly as it sent it
 *    lastMoveAt     — ISO-8601 UTC time of the last move that changed the
 *                     area's centre; untouched by refusals and by moved:false
 *
 *  PRIVACY INVARIANT — area-level only: this driver never receives, stores, logs,
 *  or emits GPS coordinates. It handles names, counts, and move outcomes
 *  (a distance, never a place).
 *
 *  Version: 1.5.0 — keep in step with packageManifest.json. HPM update detection
 *  compares the manifest version only; this line is for humans.
 *
 *  MIT License — https://github.com/positionguard/positionguard-hubitat
 */

metadata {
    definition(
        name: "PositionGuard Area",
        namespace: "positionguard",
        author: "Christer Lundin",
        importUrl: "https://raw.githubusercontent.com/positionguard/positionguard-hubitat/main/drivers/positionguard-area.groovy"
    ) {
        capability "Sensor"
        capability "Actuator"
        capability "Momentary"        // push()
        capability "PushableButton"   // push(buttonNumber); numberOfButtons, pushed

        attribute "memberCount", "number"    // members inside now (stale included)
        attribute "staleCount", "number"     // subset with a stale last position
        attribute "freshCount", "number"     // memberCount - staleCount
        attribute "areaName", "string"
        attribute "groupName", "string"
        attribute "countAvailable", "enum", ["true", "false"] // is memberCount current?
        attribute "lastMoveResult", "string" // readable outcome of the last move request
        attribute "lastMoveAt", "string"     // ISO-8601 UTC, last move that changed the centre
    }

    preferences {
        input name: "txtEnable", type: "bool", title: "Enable descriptionText logging",
            defaultValue: true
    }
}

def installed() {
    log.info "${device.displayName} installed — waiting for counts from the PositionGuard app"
    sendEvent(name: "countAvailable", value: "false")
    ensureButtonCount()
}

def updated() {
    ensureButtonCount()
}

/**
 *  Called by the parent app when this area's counts change. A null memberCount
 *  means the server withheld the count (public group, archived area) or it could
 *  not be fetched — the device is marked unavailable rather than shown a
 *  misleading number. staleCount / freshCount may be null independently.
 */
void updateCounts(Integer memberCount, Integer staleCount, Integer freshCount,
                  String areaName, String groupName) {
    // Devices created before 1.5.0 never ran installed() with the button in
    // it; the first poll after the driver update gives them numberOfButtons.
    ensureButtonCount()

    if (areaName != null && areaName != device.currentValue("areaName")) {
        sendEvent(name: "areaName", value: areaName)
    }
    if (groupName != null && groupName != device.currentValue("groupName")) {
        sendEvent(name: "groupName", value: groupName)
    }

    if (memberCount == null) {
        markUnavailable()
        return
    }

    if (device.currentValue("countAvailable") != "true") {
        sendEvent(name: "countAvailable", value: "true")
    }
    setNumber("memberCount", memberCount)
    if (staleCount != null) setNumber("staleCount", staleCount)
    if (freshCount != null) setNumber("freshCount", freshCount)
}

/**
 *  Mark the count unknown without disturbing the last-known numbers. Idempotent:
 *  only emits (and logs) on the transition to unavailable, so a backend that has
 *  been down for a while does not spam the log every poll.
 */
void markUnavailable() {
    if (device.currentValue("countAvailable") != "false") {
        sendEvent(name: "countAvailable", value: "false",
            descriptionText: "${device.displayName} member count is unavailable")
        if (txtEnable != false) log.info "${device.displayName} member count unavailable"
    }
}

/**
 *  "Move here". Momentary calls push(); PushableButton calls push(n). The
 *  default parameter compiles to both overloads, so one body serves both
 *  capabilities. This device has one button, so any buttonNumber is button 1.
 *  Whether the move is allowed at all is the parent app's decision — it checks
 *  its own preference and reports every outcome back through moveResult().
 */
void push(buttonNumber = 1) {
    sendEvent(name: "pushed", value: 1, isStateChange: true,
        descriptionText: "${device.displayName} move requested")
    parent?.moveAreaFromDevice(device.deviceNetworkId)
}

/**
 *  Called by the parent app with the outcome of a move request. result is the
 *  sentence to show — composed by the parent for a 200, the API's own message
 *  otherwise. isStateChange so a repeated refusal still lands in the event
 *  log and can trigger a rule. lastMoveAt moves only when the centre did.
 */
void moveResult(String result, Boolean moved) {
    sendEvent(name: "lastMoveResult", value: result, descriptionText: result, isStateChange: true)
    if (moved) {
        String at = new Date().format("yyyy-MM-dd'T'HH:mm:ss'Z'", TimeZone.getTimeZone("UTC"))
        sendEvent(name: "lastMoveAt", value: at, descriptionText: result)
    }
    if (txtEnable != false) log.info "${device.displayName}: ${result}"
}

private void ensureButtonCount() {
    if (device.currentValue("numberOfButtons") != 1) {
        sendEvent(name: "numberOfButtons", value: 1)
    }
}

private void setNumber(String attr, Integer value) {
    Integer cur = null
    try { cur = device.currentValue(attr) as Integer } catch (ignored) { }
    if (cur != value) {
        String desc = "${device.displayName} ${attr} is ${value}"
        sendEvent(name: attr, value: value, descriptionText: desc)
        if (txtEnable != false) log.info desc
    }
}
