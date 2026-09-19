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
 *  PRIVACY INVARIANT — area-level only: this driver never receives, stores, logs,
 *  or emits GPS coordinates. It handles names and counts.
 *
 *  Version: 1.4.1 — keep in step with packageManifest.json. HPM update detection
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

        attribute "memberCount", "number"    // members inside now (stale included)
        attribute "staleCount", "number"     // subset with a stale last position
        attribute "freshCount", "number"     // memberCount - staleCount
        attribute "areaName", "string"
        attribute "groupName", "string"
        attribute "countAvailable", "enum", ["true", "false"] // is memberCount current?
    }

    preferences {
        input name: "txtEnable", type: "bool", title: "Enable descriptionText logging",
            defaultValue: true
    }
}

def installed() {
    log.info "${device.displayName} installed — waiting for counts from the PositionGuard app"
    sendEvent(name: "countAvailable", value: "false")
}

/**
 *  Called by the parent app when this area's counts change. A null memberCount
 *  means the server withheld the count (public group, archived area) or it could
 *  not be fetched — the device is marked unavailable rather than shown a
 *  misleading number. staleCount / freshCount may be null independently.
 */
void updateCounts(Integer memberCount, Integer staleCount, Integer freshCount,
                  String areaName, String groupName) {
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

private void setNumber(String attr, Integer value) {
    Integer cur = null
    try { cur = device.currentValue(attr) as Integer } catch (ignored) { }
    if (cur != value) {
        String desc = "${device.displayName} ${attr} is ${value}"
        sendEvent(name: attr, value: value, descriptionText: desc)
        if (txtEnable != false) log.info desc
    }
}
