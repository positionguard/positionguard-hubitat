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
    def currentValue(String a) { null }
}

// Fake async response for onAreaCounts (resp.status / hasError() / json).
class FakeResp {
    int status = 200
    boolean errored = false
    def json
    boolean hasError() { errored }
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

println "\nALL 7 MEMBER + 5 AREA SCENARIOS PASS against the real app source"
