/**
 * Off-hub harness for apps/positionguard-app.groovy.
 *
 * Run from the repo root (needs any Groovy 4 + a JDK; no hub, no network):
 *   groovy -cp tests/stubs tests/hub_harness.groovy
 * Optionally pass an alternative app file path as the first argument.
 * Loads the real app source with a stubbed Hubitat platform (the script body
 * with definition/preferences is never run; only the methods are exercised)
 * and drives finalizePoll through the resilience scenarios.
 */
import org.codehaus.groovy.control.CompilerConfiguration

abstract class HubBase extends Script {
    Map<String, Object> childMap = [:]
    List<String> addAttempts = []
    Closure addBehavior = null

    def getChildDevice(String dni) { childMap[dni] }
    def getChildDevices() { childMap.values() as List }
    def addChildDevice(String ns, String driver, String dni, Map opts) {
        addAttempts << dni
        if (addBehavior) return addBehavior.call(ns, driver, dni, opts)
        def c = new FakeChild(dni: dni, name: opts.name)
        childMap[dni] = c
        return c
    }
    void deleteChildDevice(String dni) { childMap.remove(dni) }

    // Scheduler and HTTP, recorded so the move path can be pinned: it must never
    // unschedule polling, and it must POST with no body.
    List unscheduled = []
    List<Map> posts = []
    List<Map> gets = []
    void unschedule(String handler = null) { unscheduled << handler }
    void asynchttpPost(String handler, Map params, Map data) { posts << [handler: handler, params: params, data: data] }
    void asynchttpGet(String handler, Map params, Map data) { gets << [handler: handler, params: params, data: data] }
}

class FakeChild {
    String dni, name
    List updates = []
    boolean throwOnUpdate = false
    String getName() { name }
    void setName(String n) { name = n }
    String getDeviceNetworkId() { dni }
    String getDisplayName() { name }
    void updateFromParent(String area, String since, String sharing, Map safety = null) {
        if (throwOnUpdate) throw new RuntimeException("injected driver failure")
        updates << [area: area, since: since, sharing: sharing, safety: safety]
    }
    // Area-count device side (PositionGuard Area driver contract).
    List countUpdates = []
    boolean unavailable = false
    void updateCounts(Integer mc, Integer sc, Integer fc, String an, String gn) {
        unavailable = false
        countUpdates << [member: mc, stale: sc, fresh: fc, area: an, group: gn]
    }
    void markUnavailable() { unavailable = true }
    Map values = [:]
    def currentValue(String a) { values[a] }
    // Move-result side (PositionGuard Area driver, 1.5.0).
    List<Map> moveResults = []
    void moveResult(String result, Boolean moved) { moveResults << [result: result, moved: moved] }
}

// Fake async response for onAreaCounts (resp.status / hasError() / json).
class FakeResp {
    int status = 200
    boolean errored = false
    def json
    String errorData
    Map headers = [:]
    boolean hasError() { errored }
    String getErrorData() { errorData }
    Map getHeaders() { headers }
}

class FakeLog {
    List errors = [], warns = [], infos = [], debugs = []
    void error(Object m) { errors << m.toString(); println "  [log.error] $m" }
    void warn(Object m) { warns << m.toString() }
    void info(Object m) { infos << m.toString() }
    void debug(Object m) { debugs << m.toString() }
}

class FakeUnknownDeviceTypeException extends RuntimeException {
    FakeUnknownDeviceTypeException(String m) { super(m) }
}

def cc = new CompilerConfiguration(scriptBaseClass: HubBase.name)
def binding = new Binding()
def logger = new FakeLog()
binding.state = [:]
binding.settings = [groupIds: ["g1"]]
binding.log = logger
binding.logEnable = false

def shell = new GroovyShell(this.class.classLoader, binding, cc)
def appPath = args ? args[0] : "apps/positionguard-app.groovy"
def app = shell.parse(new File(appPath))
// NOTE: app.run() is deliberately never called — the script body only holds
// the definition/preferences DSL. Methods and @Field statics exist without it.

Map rec(String id, String nick, String area, Map extra = [:]) {
    [user_id: id, nickname: nick, inside: area != null, sharing_disabled: false,
     current_area: area ? [name: area] : null] + extra
}
def poll = { List members -> app.finalizePoll([membersByGroup: [g1: members]]) }
def dni = { String id -> "positionguard-${id}".toString() }

// ---- T0: healthy first poll — 3 members created and updated -------------
poll([rec("A", "Alice", "Home"), rec("B", "Bob", null), rec("C", "Cleo", "Gym")])
assert app.childMap.size() == 3
assert app.childMap[dni("A")].updates.size() == 1
assert app.childMap[dni("B")].updates.size() == 1
assert app.childMap[dni("C")].updates.size() == 1
assert binding.state.lastKnown.keySet() == ["A", "B", "C"] as Set
assert binding.state.lastPoll != null
println "T0 PASS: healthy poll creates and updates all 3 members"

// ---- T1: B's device throws — A and C still update, B holds prev ---------
binding.state.lastPoll = null
app.childMap[dni("B")].throwOnUpdate = true
def prevB = binding.state.lastKnown["B"]
poll([rec("A", "Alice", "Work"), rec("B", "Bob", "Cafe"), rec("C", "Cleo", "Home")])
assert app.childMap[dni("A")].updates.size() == 2 : "A (before thrower) must update"
assert app.childMap[dni("C")].updates.size() == 2 : "C (AFTER thrower) must update — the old code froze it"
assert app.childMap[dni("B")].updates.size() == 1 : "B got no new update"
assert logger.errors.any { it.contains("Bob") && it.contains("other members are unaffected") }
assert binding.state.lastKnown["B"] == prevB : "B's lastKnown must hold prev, not record the undelivered cur"
assert binding.state.lastKnown["A"].area == "Work"
assert binding.state.lastPoll != null : "state.lastPoll must keep advancing"
println "T1 PASS: one throwing member is isolated; loop, siblings, and lastPoll continue"

// ---- T2: B recovers — held prev makes the diff re-fire; A/C suppressed --
app.childMap[dni("B")].throwOnUpdate = false
poll([rec("A", "Alice", "Work"), rec("B", "Bob", "Cafe"), rec("C", "Cleo", "Home")])
assert app.childMap[dni("B")].updates.size() == 2 : "B must receive the retried update after recovery"
assert app.childMap[dni("B")].updates[-1].area == "Cafe"
assert app.childMap[dni("A")].updates.size() == 2 : "unchanged A stays diff-suppressed"
assert app.childMap[dni("C")].updates.size() == 2 : "unchanged C stays diff-suppressed"
println "T2 PASS: held prev re-fires the diff on recovery; suppression intact for the others"

// ---- T3: DNI collision on creation — classified message, poll continues -
def collisionErrCount = logger.errors.size()
app.addBehavior = { ns, drv, d, o ->
    throw new IllegalArgumentException("A device with the same device network ID exists, Please use a different DNI")
}
poll([rec("A", "Alice", "Work"), rec("D", "Dana", "Home")])
def collisionMsg = logger.errors[collisionErrCount]
assert collisionMsg.contains("already in use") && collisionMsg.contains(dni("D"))
assert collisionMsg.contains("orphaned") && !collisionMsg.contains("driver code is not installed")
assert binding.state.lastPoll != null && binding.state.lastKnown.containsKey("A")
println "T3 PASS: DNI collision names the collision and the orphan fix, not the driver"

// ---- T4: missing driver — classified by exception class name ------------
def missErrCount = logger.errors.size()
app.addBehavior = { ns, drv, d, o -> throw new FakeUnknownDeviceTypeException("Device type 'PositionGuard Member' in namespace 'positionguard' not found") }
poll([rec("E", "Erik", "Home")])
def missMsg = logger.errors[missErrCount]
assert missMsg.contains("driver code is not installed") && missMsg.contains("HPM Repair")
println "T4 PASS: missing driver classified by class name"

// ---- T5: unrecognized creation failure — raw fallback message -----------
def genErrCount = logger.errors.size()
app.addBehavior = { ns, drv, d, o -> throw new IllegalStateException("hub database is busy") }
poll([rec("F", "Fia", "Home")])
def genMsg = logger.errors[genErrCount]
assert genMsg.contains("hub database is busy") && !genMsg.contains("orphaned")
println "T5 PASS: unknown creation failure falls back to the raw error"

// ==== Area member counts (onAreaCounts) ==================================
app.addBehavior = null // clear T5's injected failure so devices create again
def ac = { List entries, int status = 200, boolean err = false ->
    def r = new FakeResp(status: status, errored: err, json: entries)
    app.onAreaCounts(r, [areaGroupId: "g1", areaGroupName: "Group One"])
}
def areaDev = { String areaId -> app.childMap["positionguard-area-g1-${areaId}".toString()] }

// ---- TA1: real counts create devices; member/stale/fresh; 0 is a real 0 --
ac([[area_id: "a1", area_name: "Home", member_count: 3, stale_count: 1],
    [area_id: "a2", area_name: "Gym", member_count: 0, stale_count: 0]])
assert areaDev("a1")?.countUpdates?.last() == [member: 3, stale: 1, fresh: 2, area: "Home", group: "Group One"]
assert areaDev("a2") != null && areaDev("a2").countUpdates.last().member == 0 : "empty area is a real 0 device, not absent"
println "TA1 PASS: area-count devices created; member/stale/fresh correct; 0 is real"

// ---- TA2: withheld count -> existing device unavailable, new one not made -
ac([[area_id: "a1", area_name: "Home"],                                   // absent -> unavailable
    [area_id: "a2", area_name: "Gym", member_count: 2, stale_count: 0],   // still present
    [area_id: "a3", area_name: "Shed"]])                                  // absent + new -> no device
assert areaDev("a1").unavailable : "withheld count marks the existing device unavailable"
assert areaDev("a3") == null : "a public/archived area (absent) never spawns a permanently-unavailable device"
assert areaDev("a2").countUpdates.last().member == 2 : "still-present area keeps updating"
println "TA2 PASS: withheld count -> unavailable on existing, no device for never-counted areas"

// ---- TA3: non-200 (older backend 404) degrades, never deletes -----------
def errsBefore = logger.errors.size()
ac(null, 404)
assert areaDev("a1") != null && areaDev("a2") != null : "a 404 must not delete area devices"
assert areaDev("a1").unavailable && areaDev("a2").unavailable : "a 404 marks the group's area devices unavailable"
assert logger.errors.size() == errsBefore : "degradation logs at debug, never error (no log spam)"
println "TA3 PASS: 404 degrades to unavailable, no deletion, no error spam"

// ---- TA4: area removed from the response -> device deleted ---------------
ac([[area_id: "a1", area_name: "Home", member_count: 1, stale_count: 0]]) // a2 gone
assert areaDev("a1") != null && areaDev("a2") == null : "an area dropped from the 200 response is removed"
println "TA4 PASS: stale area device removed on a successful response"

// ---- TA5: member cleanup must never delete area devices -----------------
assert app.childMap.keySet().any { it.startsWith("positionguard-area-") }
app.removeStaleChildren([] as Set) // no active members -> would delete every member device
assert areaDev("a1") != null : "removeStaleChildren must skip area-count devices (shared DNI root)"
assert !app.childMap.keySet().any { it.startsWith("positionguard-") && !it.startsWith("positionguard-area-") } : "member devices were cleaned"
println "TA5 PASS: member cleanup leaves area-count devices untouched"

// ---- TF1: position_fresh reaches the driver when sent, and only then ------
binding.state.lastKnown = [:]
app.addBehavior = null
Map held = [safety_status: "at_area", safety_area: "Lake House", position_age_seconds: 36000, position_fresh: false]
Map old = [safety_status: "at_area", safety_area: "Lake House", position_age_seconds: 600]
poll([rec("H", "Hana", "Lake House", held), rec("O", "Otto", "Lake House", old)])
def hs = app.childMap[dni("H")].updates[-1].safety
def os = app.childMap[dni("O")].updates[-1].safety
assert hs == [status: "at_area", area: "Lake House", ageSeconds: 36000, fresh: false] : hs
assert os == [status: "at_area", area: "Lake House", ageSeconds: 600] && !os.containsKey("fresh") : os
println "TF1 PASS: position_fresh passed as safety.fresh; absent on an older server, not false"

// ==== Move an area (1.5.0: moveAreaFromDevice / onAreaMove) ===============
// Messages below are the API's own strings (internal/rest/area_move.go and
// middleware.RequireScope). The app must show them unchanged.
binding.settings.apiKey = "pg_live_harness"
binding.setVariable("app", new Expando(getInstallationState: { "COMPLETE" }))
def hotelDni = "positionguard-area-g1-a1"
def hotel = app.childMap[hotelDni]
assert hotel != null : "TA4 left the a1 area device in place"
hotel.values.areaName = "Hotel"
def lastResult = { hotel.moveResults[-1] }
def moveResp = { int status, Map body, Map headers = [:] ->
    def r = new FakeResp(status: status, errored: status >= 400 || status == 0, headers: headers)
    if (status >= 200 && status < 300) r.json = body
    else r.errorData = body == null ? null : groovy.json.JsonOutput.toJson(body)
    app.onAreaMove(r, [dni: hotelDni, areaId: "a1", path: "/areas/a1/move"])
}
// What must not change on the move path: polling, the auth state, presence devices.
def memberDnis = app.childMap.keySet().findAll { !it.startsWith("positionguard-area-") }
assert !memberDnis.isEmpty() : "TF1 left presence devices to guard"
def presenceSnapshot = { memberDnis.collectEntries { [(it): app.childMap[it].updates.size()] } }
def lastKnownBefore = new LinkedHashMap(binding.state.lastKnown)
def presenceBefore = presenceSnapshot()
def assertPollingIntact = { String why ->
    assert app.unscheduled.isEmpty() : "${why}: the move path unscheduled polling"
    assert binding.state.authError == null : "${why}: the move path set an auth error"
    assert presenceSnapshot() == presenceBefore : "${why}: a presence device was touched"
    assert binding.state.lastKnown == lastKnownBefore : "${why}: lastKnown changed"
    int getsBefore = app.gets.size()
    app.poll()
    assert app.gets.size() == getsBefore + 1 && app.gets[-1].data.path == "/groups" : "${why}: the next poll did not run"
}

// ---- TM1: preference off — no request, a clear reason on the device -------
binding.settings.allowAreaMove = false
app.moveAreaFromDevice(hotelDni)
assert app.posts.isEmpty() : "preference off must not call the API"
assert lastResult() == [result: app.MOVE_DISABLED, moved: false]
assert lastResult().result.contains("Allow this hub to move areas")
println "TM1 PASS: preference off -> no request; lastMoveResult says how to turn it on"

// ---- TM2: preference on — POST /areas/{id}/move with NO body ---------------
binding.settings.allowAreaMove = true
app.moveAreaFromDevice(hotelDni)
assert app.posts.size() == 1
def post = app.posts[-1]
assert post.handler == "onAreaMove"
assert post.params.uri == "https://api.positionguardai.com/api/v1/areas/a1/move"
assert post.params.keySet() == ["uri", "headers", "timeout"] as Set : "no body, no content type: ${post.params.keySet()}"
assert post.params.headers.Authorization == "Bearer pg_live_harness"
println "TM2 PASS: preference on -> one POST to /areas/a1/move, no body"

// ---- TM2b: area id recovered on a known group id, hyphens and all ---------
binding.settings.groupIds = ["grp", "grp-1", "g1"]
assert app.areaIdFromDni("positionguard-area-grp-1-a-2") == "a-2" : "longest matching group id wins"
assert app.areaIdFromDni("positionguard-area-grp-a-3") == "a-3"
assert app.areaIdFromDni("positionguard-area-other-a-4") == null
binding.settings.groupIds = ["g1"]
println "TM2b PASS: area id split on the longest known group id"

// ---- TM3: moved — both units, metres/feet under 1 km ----------------------
moveResp(200, [area_id: "a1", moved: true, distance_m: 341234.5, position_age_seconds: 37])
assert lastResult() == [result: "Hotel moved 341 km (212 mi) to your phone's location", moved: true]
moveResp(200, [area_id: "a1", moved: true, distance_m: 1462.8, position_age_seconds: 12])
assert lastResult().result == "Hotel moved 1.5 km (0.9 mi) to your phone's location"
moveResp(200, [area_id: "a1", moved: true, distance_m: 412.3, position_age_seconds: 5])
assert lastResult().result == "Hotel moved 412 m (1353 ft) to your phone's location"
println "TM3 PASS: moved -> '341 km (212 mi)', '1.5 km (0.9 mi)', '412 m (1353 ft)'"

// ---- TM4: moved:false (within 1 m) is a calm 200, not a move ---------------
moveResp(200, [area_id: "a1", moved: false, distance_m: 0.4, position_age_seconds: 20])
assert lastResult() == [result: "Hotel is already at your phone's location (within 1 m), so it wasn't moved.", moved: false]
println "TM4 PASS: moved:false -> already there, lastMoveAt untouched (moved=false)"

// ---- TM5: the six 409 refusals — the API's message, word for word ----------
[
    area_archived            : "Unarchive the area before moving it.",
    not_sharing              : "Location sharing is off for this account, so the area wasn't moved.",
    no_position              : "PositionGuard has no recent position for this account yet.",
    position_too_old         : "Your last position is 34 minutes old, so the area wasn't moved. Open PositionGuard to send a fresh one.",
    position_accuracy_unknown: "PositionGuard can't tell how accurate your last position was, so the area wasn't moved.",
    position_too_inaccurate  : "Your last position is less precise than this area is wide, so the area wasn't moved.",
].each { reason, msg ->
    moveResp(409, [error: msg, reason: reason, position_age_seconds: 2065])
    assert lastResult() == [result: msg, moved: false] : "${reason}: ${lastResult()}"
}
assertPollingIntact("409 refusals")
println "TM5 PASS: all six refusals shown verbatim; polling intact"

// ---- TM6: 403 not the owner — verbatim, and polling survives it -----------
moveResp(403, [error: "Only the person who created this area can move it."])
assert lastResult() == [result: "Only the person who created this area can move it.", moved: false]
assertPollingIntact("403 not-owner")
println "TM6 PASS: 403 not-owner shown verbatim; polling scheduled, presence untouched"

// ---- TM7: 403 MISSING_SCOPE — the scope half of the opt-in ----------------
String scopeMsg = "this API key does not have the areas:move scope required by this endpoint"
moveResp(403, [error: scopeMsg, code: "MISSING_SCOPE", missing_scope: "areas:move"])
assert lastResult() == [result: scopeMsg, moved: false]
assertPollingIntact("403 MISSING_SCOPE")
println "TM7 PASS: 403 MISSING_SCOPE shown as the API words it; polling scheduled, presence untouched"

// ---- TM8: 401 on the move path — still not the move path's call to make ---
moveResp(401, [error: "authentication required"])
assert lastResult() == [result: "authentication required", moved: false]
assertPollingIntact("401")
moveResp(401, null)
assert lastResult().result == "PositionGuard answered HTTP 401 without a message, so Hotel may not have moved."
assertPollingIntact("401 without a body")
println "TM8 PASS: 401 never calls handleAuthFailure; polling scheduled, presence untouched"

// ---- TM9: 404 ------------------------------------------------------------
moveResp(404, [error: "area not found"])
assert lastResult() == [result: "area not found", moved: false]
println "TM9 PASS: 404 shown verbatim"

// ---- TM10: 429 — verbatim message, Retry-After in the log -----------------
int infosBefore = logger.infos.size()
moveResp(429, [error: "This area was moved less than 30 seconds ago. Try again shortly.", reason: "rate_limited", retry_after_seconds: 12],
    ["Retry-After": "12"])
assert lastResult() == [result: "This area was moved less than 30 seconds ago. Try again shortly.", moved: false]
assert logger.infos.drop(infosBefore).any { it.contains("HTTP 429") && it.contains("Retry-After 12 s") }
assertPollingIntact("429")
println "TM10 PASS: 429 shown verbatim; Retry-After logged; polling intact"

// ---- TM11: no answer — honest about not knowing ---------------------------
moveResp(408, null)
assert lastResult().result == "No answer from PositionGuard, so it isn't known whether Hotel moved. Check the app before trying again."
assertPollingIntact("timeout")
println "TM11 PASS: timeout says the outcome is unknown; polling intact"

// ---- TM12: nothing coordinate-shaped anywhere the move path writes --------
def written = (hotel.moveResults.collect { it.result } + logger.infos + logger.warns + logger.debugs +
    [binding.state.toString(), app.posts.toString()]).join("\n").toLowerCase()
["latitude", "longitude", "\"lat\"", "\"lng\"", "lat:", "lng:"].each { assert !written.contains(it) : "found ${it}" }
println "TM12 PASS: no coordinate field in results, logs, state, or the request"

println "\nALL 7 MEMBER + 5 AREA + 13 MOVE SCENARIOS PASS against the real app source"
