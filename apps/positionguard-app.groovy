/**
 *  PositionGuard — parent app for Hubitat Elevation
 *
 *  Polls the PositionGuard REST API and maintains one "PositionGuard Member"
 *  child presence device per member of the selected groups. Mirrors the
 *  official Home Assistant integration's API client behavior: same endpoints,
 *  same Bearer-token auth, same polling model, same data shapes, and the same
 *  last-known-state resilience on transient failures.
 *
 *  Endpoints used (a subset of the HA client — nothing new is invented):
 *    GET /groups                  — list groups; also the API-key validation call
 *    GET /groups/{id}/members     — members with area-level presence, every poll
 *    GET /groups/{id}/area-counts — per-area member counts, coordinate-free
 *
 *  PRIVACY INVARIANT — area-level presence only:
 *  This app must never request, store, log, or emit GPS coordinates.
 *  Member records from /groups/{id}/members carry area names only. Do not add
 *  a call to the area-geometry endpoint (/groups/{id}/areas): its payload
 *  contains area center coordinates, which must never reach the hub. For the
 *  same reason, raw response bodies are never logged — only paths, statuses,
 *  and item counts.
 *
 *  /groups/{id}/area-counts IS called and IS permitted: it is the deliberately
 *  coordinate-free counterpart to /areas — area_id, area_name, and member/stale
 *  counts only, no lat/lng/radius — added precisely so the count can reach the
 *  hub without the geometry. If that endpoint ever grows a coordinate field,
 *  this call must stop.
 *
 *  Version: 1.3.1 — keep in step with packageManifest.json. HPM update
 *  detection compares the manifest version only; this line is for humans.
 *
 *  MIT License — https://github.com/positionguard/positionguard-hubitat
 */

import groovy.transform.Field

@Field static final String BASE_URL = "https://api.positionguardai.com/api/v1"
@Field static final String KEY_PREFIX = "pg_live_"
@Field static final String DNI_PREFIX = "positionguard-"
@Field static final String CHILD_DRIVER = "PositionGuard Member"
@Field static final String CHILD_NAMESPACE = "positionguard"

// Per-(group, area) member-count devices are a second child type, keyed
// "positionguard-area-{groupId}-{areaId}". This prefix EXTENDS DNI_PREFIX, so
// removeStaleChildren (member cleanup) must skip them — see the guard there.
@Field static final String AREA_DNI_PREFIX = "positionguard-area-"
@Field static final String AREA_DRIVER = "PositionGuard Area"

// Sentinel for "sharing, but in no defined area". Hubitat users expect "away"
// on dashboards and in event logs, so this deliberately does not reuse the
// HA integration's HA-jargon state name.
@Field static final String NO_AREA = "away"

// Sentinel for "sharing is paused — area knowledge has genuinely lapsed".
// Distinct from NO_AREA: "away" is a known fact, "unknown" is the absence of one.
@Field static final String AREA_UNKNOWN = "unknown"

definition(
    name: "PositionGuard",
    namespace: "positionguard",
    author: "Christer Lundin",
    description: "Area-level family presence from PositionGuard — one presence child device per group member. Never exposes GPS coordinates.",
    category: "Integrations",
    singleInstance: true,
    iconUrl: "",
    iconX2Url: "",
    importUrl: "https://raw.githubusercontent.com/positionguard/positionguard-hubitat/main/apps/positionguard-app.groovy",
    documentationLink: "https://github.com/positionguard/positionguard-hubitat/blob/main/README.md"
)

preferences {
    page(name: "pageMain")
    page(name: "pageApiKey")
    page(name: "pageGroups")
}

// ------------------------------------------------------------------ UI pages

def pageMain() {
    boolean configured = isConfigured()
    dynamicPage(name: "pageMain", title: "PositionGuard", install: configured, uninstall: true) {
        if (!configured) {
            section("Setup") {
                paragraph "PositionGuard exposes your family group's presence at " +
                    "<b>area level</b> — home, school, grandma's house — never GPS coordinates."
                paragraph "You need a (free) API key from " +
                    "<a href='https://dev.positionguardai.com' target='_blank'>dev.positionguardai.com</a>."
                href page: "pageApiKey",
                    title: "1. Enter and validate your API key",
                    description: apiKey() ? "API key entered — open to re-validate" : "Tap to enter your API key",
                    state: apiKey() ? "complete" : null
                if (apiKey()) {
                    href page: "pageGroups",
                        title: "2. Select groups",
                        description: selectedGroupIds() ? "${selectedGroupIds().size()} group(s) selected" : "Tap to select groups",
                        state: selectedGroupIds() ? "complete" : null
                }
            }
        } else {
            section("Status") {
                if (state.authError) {
                    paragraph "<span style='color:#b00020'><b>${state.authError}</b></span><br>" +
                        "Polling is stopped. Re-enter and validate your API key below, then press <b>Done</b>."
                } else {
                    String lastPoll = state.lastPoll ? "${state.lastPoll} (UTC)" : "none yet — press Done to start polling"
                    String txt = "Last successful poll: ${lastPoll}<br>" +
                        "Member presence devices: ${getChildDevices()?.size() ?: 0}"
                    int failures = (state.consecutiveFailures ?: 0) as int
                    if (failures > 0) {
                        txt += "<br><span style='color:#b26b00'>${failures} consecutive failed poll(s) — " +
                            "child devices are holding last-known state.</span>"
                    }
                    paragraph txt
                }
            }
            section("Settings") {
                href page: "pageGroups",
                    title: "Groups",
                    description: "${selectedGroupIds().size()} group(s) selected — tap to change"
                input name: "pollMinutes", type: "enum", title: "Polling interval",
                    options: ["1": "Every minute", "5": "Every 5 minutes", "10": "Every 10 minutes"],
                    defaultValue: "1", required: true
                input name: "logEnable", type: "bool",
                    title: "Enable debug logging (auto-disables after 30 minutes)",
                    defaultValue: false
                href page: "pageApiKey",
                    title: "Re-enter API key",
                    description: "Validate a new or rotated API key"
            }
            section {
                input name: "btnPollNow", type: "button", title: "Poll now"
            }
        }
    }
}

def pageApiKey() {
    Map validation = apiKey() ? validateApiKey(apiKey()) : null
    // No nextPage until the key validates — an invalid key cannot advance.
    String next = validation?.ok ? "pageGroups" : null
    dynamicPage(name: "pageApiKey", title: "PositionGuard API key", nextPage: next) {
        section {
            paragraph "Create an API key at " +
                "<a href='https://dev.positionguardai.com' target='_blank'>dev.positionguardai.com</a> " +
                "(sign in with the same phone number you use in the PositionGuard app)."
            input name: "apiKey", type: "password", title: "API key",
                required: true, submitOnChange: true
            if (validation != null) {
                if (validation.ok) {
                    paragraph "&#9989; API key validated — found ${validation.groups.size()} group(s). " +
                        "Press <b>Next</b> to select groups."
                } else {
                    paragraph "<span style='color:#b00020'>&#10060; ${validation.error}</span>"
                }
            }
        }
    }
}

def pageGroups() {
    // Re-fetch groups on every visit so new/renamed groups show up,
    // matching the HA options flow.
    Map fetch = apiGetSync("/groups")
    Map<String, String> options = [:]
    String fetchError = null
    if (fetch.ok && fetch.json instanceof List) {
        fetch.json.each { g -> options[g.id as String] = (g.name ?: g.id) as String }
    } else if (fetch.status in [401, 403]) {
        fetchError = "PositionGuard rejected the API key (HTTP ${fetch.status}). Re-enter it from the previous page."
    } else {
        fetchError = "Could not reach PositionGuard (${fetch.error ?: 'HTTP ' + fetch.status}). Try again shortly."
    }
    dynamicPage(name: "pageGroups", title: "Select groups", nextPage: "pageMain") {
        section {
            if (fetchError) {
                paragraph "<span style='color:#b00020'>&#10060; ${fetchError}</span>"
            } else {
                input name: "groupIds", type: "enum", title: "Groups to integrate",
                    options: options, multiple: true, required: true, submitOnChange: true
                if (selectedGroupIds()) {
                    paragraph "Each member of the selected group(s) gets one presence child device. " +
                        "Members removed from all selected groups have their device deleted."
                } else {
                    paragraph "Select at least one group."
                }
            }
        }
    }
}

def appButtonHandler(String btn) {
    if (btn == "btnPollNow") {
        pollNow()
    }
}

// --------------------------------------------------------------- validation

/**
 *  Validate an API key the same way the HA config flow does: a format
 *  pre-check on the key prefix, then GET /groups. Returns
 *  [ok: true, groups: [...]] or [ok: false, error: "..."].
 */
private Map validateApiKey(String key) {
    if (!key.startsWith(KEY_PREFIX)) {
        return [ok: false, error: "That does not look like a PositionGuard API key (it should start with \"${KEY_PREFIX}\")."]
    }
    Map result = apiGetSync("/groups", key)
    if (result.ok) {
        if (!(result.json instanceof List) || result.json.isEmpty()) {
            return [ok: false, error: "The key is valid, but no groups were found. Create a group in the PositionGuard app first."]
        }
        // A freshly validated key clears any standing auth error so polling
        // can resume after the user presses Done.
        state.remove("authError")
        state.consecutiveFailures = 0
        return [ok: true, groups: result.json]
    }
    if (result.status in [401, 403]) {
        return [ok: false, error: "PositionGuard rejected this API key (HTTP ${result.status}). Check for typos, or create a new key at dev.positionguardai.com."]
    }
    return [ok: false, error: "Could not reach PositionGuard (${result.error ?: 'HTTP ' + result.status}). Check the hub's internet connection and try again."]
}

// ---------------------------------------------------------------- lifecycle

def installed() {
    logDebug "installed"
    initialize()
}

def updated() {
    logDebug "updated"
    unschedule()
    initialize()
}

def uninstalled() {
    // The platform removes child devices of an uninstalled app; this is belt
    // and braces for older hub firmware.
    getChildDevices()?.each { deleteChildDevice(it.deviceNetworkId) }
}

def initialize() {
    if (logEnable) runIn(1800, "logsOff")
    if (!isConfigured()) {
        log.warn "PositionGuard is not fully configured yet — polling not scheduled"
        return
    }
    if (state.authError) {
        log.warn "PositionGuard has a standing auth error — polling stays disabled until the API key is re-validated in the app"
        return
    }
    schedulePolling()
    pollNow()
}

private void schedulePolling() {
    switch (settings.pollMinutes ?: "1") {
        case "5":  runEvery5Minutes("poll");  break
        case "10": runEvery10Minutes("poll"); break
        default:   runEvery1Minute("poll")
    }
    logDebug "polling scheduled every ${settings.pollMinutes ?: '1'} minute(s)"
}

def logsOff() {
    log.warn "Debug logging disabled"
    app.updateSetting("logEnable", [value: "false", type: "bool"])
}

// ------------------------------------------------------------------ polling

/** Public entry point for the scheduler, the app's Poll now button, and child refresh(). */
def pollNow() {
    poll()
}

def poll() {
    if (!isConfigured()) return
    // Children cannot be created before the app is installed (Done pressed).
    if (app.getInstallationState() != "COMPLETE") return
    if (state.authError) {
        logDebug "skipping poll — standing auth error"
        return
    }
    logDebug "GET /groups"
    asyncGet("onGroups", "/groups", [:])
}

def onGroups(resp, Map data) {
    List groups = parseListResponse(resp, data.path)
    if (groups == null) return

    Map<String, String> groupNames = [:]
    groups.each { g -> groupNames[g.id as String] = (g.name ?: g.id) as String }

    List<String> active = selectedGroupIds().findAll { groupNames.containsKey(it) }
    if (!active) {
        // Same warning the HA coordinator logs; the poll still completes, so
        // members of inaccessible groups are reconciled (removed) below.
        log.warn "None of the selected groups are accessible with this API key; " +
            "you may have left them, or they were deleted"
    }
    fetchNextGroupMembers([pending: active, membersByGroup: [:]])

    // Area member counts run alongside the member chain and update a separate
    // set of per-(group, area) devices. Best-effort and independent: a failure
    // here (e.g. an older backend that 404s /area-counts) degrades those devices
    // to unavailable without touching member presence.
    active.each { gid ->
        logDebug "GET /groups/${gid}/area-counts"
        asyncGet("onAreaCounts", "/groups/${gid}/area-counts",
            [areaGroupId: gid, areaGroupName: groupNames[gid]])
    }
}

private void fetchNextGroupMembers(Map ctx) {
    if (!ctx.pending) {
        finalizePoll(ctx)
        return
    }
    String gid = ctx.pending[0]
    Map next = [pending: ctx.pending.drop(1), membersByGroup: ctx.membersByGroup, currentGroup: gid]
    logDebug "GET /groups/${gid}/members"
    asyncGet("onMembers", "/groups/${gid}/members", next)
}

def onMembers(resp, Map data) {
    List members = parseListResponse(resp, data.path)
    if (members == null) return
    data.membersByGroup[data.currentGroup as String] = members
    fetchNextGroupMembers(data)
}

/**
 *  End of a fully successful poll cycle: merge member records across groups,
 *  reconcile child devices, and push updates only for members whose state
 *  actually changed since the last successful poll.
 */
private void finalizePoll(Map ctx) {
    Map merged = mergeMembers(ctx.membersByGroup)
    Map previous = (state.lastKnown ?: [:]) as Map
    Map next = [:]
    String now = nowIso()

    merged.each { String id, Map m ->
        Map prev = previous[id] as Map
        // A sharing pause is a meaningful unknown: the child driver freezes
        // presence (a pause must never look like a departure or arrival),
        // while currentArea honestly reports that area knowledge lapsed
        // instead of showing a stale area.
        String area = (m.sharing == "disabled") ? AREA_UNKNOWN : m.area
        String since = (prev != null && prev.area == area && prev.since) ? prev.since : now
        // Paused members carry no safety state: the driver renders "unknown",
        // matching how currentArea lapses to "unknown" during a pause.
        Map cur = [nickname: m.nickname, area: area, since: since, sharing: m.sharing,
                   safety: (m.sharing == "disabled") ? null : m.safety]
        try {
            syncChild(id, cur, prev)
            next[id] = cur
        } catch (e) {
            // One member's device throwing must not take down the poll for
            // every member iterated after them. Hold THIS member's previous
            // entry, not cur: recording an update the device never received
            // would suppress the diff and freeze the member permanently, so
            // keeping prev is what makes the retry fire on the next poll.
            if (prev != null) next[id] = prev
            log.error "Failed to update member device for '${cur.nickname ?: id}' — " +
                "holding this member's last-known state; other members are unaffected. ${e}"
        }
    }

    removeStaleChildren(merged.keySet())

    state.lastKnown = next
    state.consecutiveFailures = 0
    state.lastPoll = now
    logDebug "poll complete: ${merged.size()} member(s) across ${ctx.membersByGroup.size()} group(s)"
}

/**
 *  Merge per-group member records into one record per member (children are
 *  per member, not per group-member pair). Rules:
 *    - nickname: first one seen, iterating groups in the configured order
 *    - sharing:  "disabled" only if sharing is paused in every group record
 *    - area:     first group record (configured order) where the member is
 *                inside an area; null when in no area anywhere
 */
private Map mergeMembers(Map membersByGroup) {
    Map collected = [:]
    selectedGroupIds().each { gid ->
        (membersByGroup[gid] ?: []).each { rec ->
            String id = rec.user_id as String
            if (!id) return
            Map m = collected[id] as Map
            if (m == null) {
                m = [nickname: null, records: []]
                collected[id] = m
            }
            if (!m.nickname && rec.nickname) m.nickname = rec.nickname as String
            m.records << rec
        }
    }

    Map out = [:]
    collected.each { String id, Map m ->
        List activeRecords = m.records.findAll { !it.sharing_disabled }
        String area = null
        for (rec in activeRecords) {
            if (rec.inside && rec.current_area?.name) {
                area = rec.current_area.name as String
                break
            }
        }
        // Safety status describes the MEMBER's own usual area, so records
        // agree wherever the server included it — take the first active
        // record that carries it (the API omits the fields for public groups,
        // muted members, or when the feature is off server-side). Names and
        // an age in seconds only; the coordinate invariant is untouched.
        Map safety = null
        for (rec in activeRecords) {
            if (rec.safety_status) {
                safety = [status: rec.safety_status as String]
                if (rec.safety_area) safety.area = rec.safety_area as String
                if (rec.position_age_seconds != null) safety.ageSeconds = rec.position_age_seconds as Integer
                // position_fresh: false marks the server's area hold (at_area kept
                // through a phone's silence). Older servers never send it; absent,
                // the driver derives freshness from the status as before.
                if (rec.position_fresh != null) safety.fresh = (rec.position_fresh as Boolean)
                break
            }
        }
        out[id] = [nickname: m.nickname, area: area,
                   sharing: activeRecords ? "active" : "disabled", safety: safety]
    }
    return out
}

// ---------------------------------------------------------- child lifecycle

private void syncChild(String memberId, Map cur, Map prev) {
    String dni = "${DNI_PREFIX}${memberId}"
    def child = getChildDevice(dni)
    boolean isNew = false

    if (!child) {
        // The device NAME carries the PositionGuard nickname; the LABEL is
        // never set here. Hubitat convention: the integration owns the name,
        // the user owns the label — displayName shows the name until the
        // user chooses a label of their own, and a manual rename must stick.
        String deviceName = cur.nickname ?: "PositionGuard member"
        try {
            child = addChildDevice(CHILD_NAMESPACE, CHILD_DRIVER, dni,
                [name: deviceName, isComponent: false])
        } catch (e) {
            log.error childCreationError(deviceName, dni, CHILD_DRIVER, e)
            return
        }
        log.info "Created presence device '${deviceName}' (${dni})"
        isNew = true
    } else if (cur.nickname && child.getName() != cur.nickname) {
        // Nickname change in PositionGuard: update the device name, never
        // recreate — and never touch the label, which belongs to the user.
        log.info "Updating device name '${child.getName()}' to '${cur.nickname}' (${dni}) — " +
            "PositionGuard nickname changed; any user-set label is untouched"
        child.setName(cur.nickname as String)
    }

    boolean changed = prev == null ||
        prev.area != cur.area ||
        prev.since != cur.since ||
        prev.sharing != cur.sharing ||
        prev.safety != cur.safety
    if (isNew || changed) {
        try {
            child.updateFromParent((cur.area ?: NO_AREA) as String, cur.since as String,
                cur.sharing as String, cur.safety as Map)
        } catch (groovy.lang.MissingMethodException ignored) {
            // Driver code predates the safety attributes (manual-install skew;
            // HPM updates both files together). Deliver the presence update
            // the old signature carries rather than dropping it.
            child.updateFromParent((cur.area ?: NO_AREA) as String, cur.since as String,
                cur.sharing as String)
        }
    }
}

/**
 *  One accurate sentence for a failed addChildDevice. The two real-world
 *  causes need opposite fixes, so the message must not guess:
 *    - UnknownDeviceTypeException — the named driver's code is not on the hub
 *    - a "device network id" IllegalArgumentException — the DNI is already
 *      taken, usually by an orphaned member device left behind by a previous
 *      install: it still shows on dashboards but no app owns it, so it never
 *      updates, and this app cannot create its own until it is removed.
 *  Matched by class name and message text, not class literals, so the check
 *  survives platform-version differences in the exact exception classes.
 */
private String childCreationError(String deviceName, String dni, String driverName, Throwable e) {
    String msg = e.message ?: ""
    if (e.class.name.endsWith("UnknownDeviceTypeException")) {
        return "Cannot create '${deviceName}' (${dni}): the '${driverName}' driver code is not installed " +
            "on this hub. Install the driver (HPM Repair, or import it from GitHub), then press Poll now."
    }
    if (msg.toLowerCase().contains("network id") || msg.toLowerCase().contains("dni")) {
        return "Cannot create '${deviceName}': device network id ${dni} is already in use by a device " +
            "this app does not own — usually an orphaned PositionGuard device from a previous install, " +
            "which never updates. Find it under Devices (search for ${dni}), remove it or change its " +
            "device network id, then press Poll now. (${msg})"
    }
    return "Failed to create child device for '${deviceName}' (${dni}): ${msg ?: e.class.simpleName}"
}

private void removeStaleChildren(Set activeMemberIds) {
    getChildDevices()?.each { child ->
        String dni = child.deviceNetworkId
        if (!dni?.startsWith(DNI_PREFIX)) return
        // Area-count devices share the DNI_PREFIX root but are not members and
        // are reconciled separately (removeStaleAreaChildren). Never let member
        // cleanup delete them.
        if (dni.startsWith(AREA_DNI_PREFIX)) return
        String memberId = dni.substring(DNI_PREFIX.length())
        if (!activeMemberIds.contains(memberId)) {
            log.info "Removing presence device '${child.displayName}' (${dni}) — " +
                "member is no longer in any selected group"
            deleteChildDevice(dni)
        }
    }
}

// ------------------------------------------------------ area member counts

/**
 *  Per-group area-count response handler, independent of the member chain.
 *  Updates the per-(group, area) count devices for one group and degrades
 *  gracefully: a 404 (a backend predating /area-counts) or any non-200 marks
 *  this group's count devices unavailable with a single DEBUG line — never a
 *  repeated error, never a device deletion. Auth failures stop polling like
 *  everywhere else.
 *
 *  PRIVACY: /area-counts is coordinate-free (area_id, area_name, counts only).
 *  Nothing here reads or stores a coordinate.
 */
def onAreaCounts(resp, Map data) {
    String gid = data.areaGroupId as String
    String gname = (data.areaGroupName ?: gid) as String

    int status = 0
    try { status = (resp.status ?: 0) as int } catch (ignored) { }

    if (status == 401 || status == 403) {
        handleAuthFailure(status)
        return
    }
    if (resp.hasError() || status != 200) {
        logDebug "area-counts unavailable for ${gid} (HTTP ${status ?: '?'}); " +
            "marking its count devices unavailable"
        markGroupAreaChildrenUnavailable(gid)
        return
    }
    def json = null
    try { json = resp.json } catch (e) { json = null }
    if (!(json instanceof List)) {
        logDebug "area-counts: unexpected payload shape for ${gid}; marking unavailable"
        markGroupAreaChildrenUnavailable(gid)
        return
    }

    Set<String> liveKeys = [] as Set
    json.each { entry ->
        String areaId = entry.area_id as String
        if (!areaId) return
        String areaName = (entry.area_name ?: areaId) as String
        // Tri-state: member_count present => a real count (0 included); absent =>
        // withheld (public group, archived area, or a compute failure). Absent
        // must read as unavailable, never 0.
        Integer memberCount = (entry.member_count != null) ? (entry.member_count as Integer) : null
        Integer staleCount  = (entry.stale_count  != null) ? (entry.stale_count  as Integer) : null
        String key = "${gid}-${areaId}".toString()

        if (memberCount == null) {
            // Withheld. Only reflect it on a device that already exists; never
            // create a permanently-unavailable device for a public/archived area.
            def existing = getChildDevice("${AREA_DNI_PREFIX}${key}")
            if (existing) {
                liveKeys << key
                markAreaChildUnavailable(existing)
            }
            return
        }
        liveKeys << key
        try {
            syncAreaChild(gid, gname, areaId, areaName, memberCount, staleCount)
        } catch (e) {
            log.error "Failed to update area-count device for '${areaName}' in ${gid} — " +
                "other areas are unaffected. ${e}"
        }
    }
    removeStaleAreaChildren(gid, liveKeys)
}

private void syncAreaChild(String gid, String gname, String areaId, String areaName,
                           Integer memberCount, Integer staleCount) {
    String dni = "${AREA_DNI_PREFIX}${gid}-${areaId}"
    def child = getChildDevice(dni)
    if (!child) {
        String label = "${gname}: ${areaName} count"
        try {
            child = addChildDevice(CHILD_NAMESPACE, AREA_DRIVER, dni,
                [name: label, isComponent: false])
        } catch (e) {
            log.error childCreationError(label, dni, AREA_DRIVER, e)
            return
        }
        log.info "Created area-count device '${label}' (${dni})"
    }
    Integer fresh = (memberCount != null && staleCount != null) ? (memberCount - staleCount) : null
    child.updateCounts(memberCount, staleCount, fresh, areaName, gname)
}

private void markAreaChildUnavailable(child) {
    try { child.markUnavailable() } catch (groovy.lang.MissingMethodException ignored) { }
}

/** Mark every existing area-count device of one group unavailable (degradation). */
private void markGroupAreaChildrenUnavailable(String gid) {
    String prefix = "${AREA_DNI_PREFIX}${gid}-"
    getChildDevices()?.each { child ->
        if (child.deviceNetworkId?.startsWith(prefix)) markAreaChildUnavailable(child)
    }
}

/**
 *  Remove area-count devices for a group whose (group, area) no longer appears
 *  in that group's SUCCESSFUL area-counts response. Scoped per group and only
 *  called after a 200, so a 404/degraded group never deletes its devices — they
 *  are marked unavailable instead.
 */
private void removeStaleAreaChildren(String gid, Set<String> liveKeys) {
    String prefix = "${AREA_DNI_PREFIX}${gid}-"
    getChildDevices()?.each { child ->
        String dni = child.deviceNetworkId
        if (!dni?.startsWith(prefix)) return
        // Suffix after "positionguard-area-" is "{gid}-{areaId}", the liveKeys form.
        String key = dni.substring(AREA_DNI_PREFIX.length())
        if (!liveKeys.contains(key)) {
            log.info "Removing area-count device '${child.displayName}' (${dni}) — " +
                "area no longer present in ${gid}"
            deleteChildDevice(dni)
        }
    }
}

// -------------------------------------------------------------- API helpers

private String apiKey() {
    settings.apiKey ? (settings.apiKey as String).trim() : null
}

private List<String> selectedGroupIds() {
    def v = settings.groupIds
    if (v == null) return []
    return (v instanceof List ? v : [v]).collect { it as String }
}

private boolean isConfigured() {
    apiKey() && selectedGroupIds()
}

private Map<String, String> authHeaders(String overrideKey = null) {
    // Same header format as the HA client. Never log these.
    return [
        "Authorization": "Bearer " + (overrideKey ?: apiKey()),
        "Accept"       : "application/json"
    ]
}

private void asyncGet(String handler, String path, Map data) {
    asynchttpGet(handler, [uri: BASE_URL + path, headers: authHeaders(), timeout: 10],
        data + [path: path])
}

/** Synchronous GET, used only by the setup/settings pages. */
private Map apiGetSync(String path, String overrideKey = null) {
    Map params = [
        uri        : BASE_URL + path,
        headers    : authHeaders(overrideKey),
        contentType: "application/json",
        timeout    : 10
    ]
    logDebug "GET ${path} (sync)"
    try {
        Map out = [ok: false, status: 0]
        httpGet(params) { resp ->
            out = [ok: resp.status == 200, status: resp.status, json: resp.data]
        }
        return out
    } catch (groovyx.net.http.HttpResponseException e) {
        return [ok: false, status: e.response?.status ?: 0, error: "HTTP ${e.response?.status}"]
    } catch (e) {
        return [ok: false, status: 0, error: e.message ?: e.class.simpleName]
    }
}

/**
 *  Common handling for async poll responses. Returns the parsed JSON list, or
 *  null after routing the failure:
 *    - 401/403: stop polling, surface an app-level error, log error ONCE
 *    - anything else (timeout, 429, 5xx, bad payload): keep children on
 *      last-known state and log a warning — presence must not flap
 */
private List parseListResponse(resp, String path) {
    int status = 0
    try { status = (resp.status ?: 0) as int } catch (ignored) { }

    if (status == 401 || status == 403) {
        handleAuthFailure(status)
        return null
    }
    if (resp.hasError() || status != 200) {
        handleTransientFailure("HTTP ${status ?: '?'}${resp.hasError() ? ' — ' + resp.getErrorMessage() : ''} for ${path}")
        return null
    }
    def json = null
    try { json = resp.json } catch (e) {
        handleTransientFailure("unparseable response for ${path}")
        return null
    }
    if (!(json instanceof List)) {
        handleTransientFailure("unexpected payload shape for ${path}")
        return null
    }
    logDebug "HTTP 200 for ${path} (${json.size()} item(s))"
    return json
}

private void handleAuthFailure(int status) {
    unschedule("poll")
    if (!state.authError) {
        log.error "PositionGuard rejected the API key (HTTP ${status}). Polling stopped — " +
            "open the PositionGuard app, re-enter a valid key, and press Done."
    }
    state.authError = "API key rejected (HTTP ${status}) on ${nowIso()} (UTC)"
}

private void handleTransientFailure(String reason) {
    int failures = ((state.consecutiveFailures ?: 0) as int) + 1
    state.consecutiveFailures = failures
    log.warn "Poll failed (${reason}). Child devices keep last-known presence. " +
        "Consecutive failures: ${failures}"
}

// ------------------------------------------------------------------- misc

private String nowIso() {
    new Date().format("yyyy-MM-dd'T'HH:mm:ss'Z'", TimeZone.getTimeZone("UTC"))
}

private void logDebug(String msg) {
    if (logEnable) log.debug msg
}
