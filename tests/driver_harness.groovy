/**
 * Off-hub harness for drivers/positionguard-member.groovy — safety and
 * freshness rendering (position_fresh / the server's area hold).
 *
 * Run from the repo root (needs any Groovy 4 + a JDK; no hub, no network):
 *   groovy tests/driver_harness.groovy
 * Optionally pass an alternative driver file path as the first argument.
 * Loads the real driver source with a stubbed device (the metadata DSL in the
 * script body is never run; only the methods are exercised), calls
 * updateFromParent the way the parent app does, and asserts the events.
 */
import org.codehaus.groovy.control.CompilerConfiguration

class FakeDevice {
    String displayName = "Newman"
    Map<String, Object> values = [:]
    def currentValue(String a) { values[a] }
}

abstract class DriverBase extends Script {
    List<Map> events = []
    void sendEvent(Map e) {
        events << e
        binding.device.values[e.name] = e.value
    }
}

class FakeLog {
    List infos = [], warns = []
    void info(Object m) { infos << m.toString() }
    void warn(Object m) { warns << m.toString() }
    void error(Object m) { println "  [log.error] $m" }
    void debug(Object m) {}
}

def driverPath = args ? args[0] : "drivers/positionguard-member.groovy"

// A fresh driver instance per scenario: its own device and event list.
def load = {
    def binding = new Binding()
    binding.device = new FakeDevice()
    binding.settings = [:]
    binding.txtEnable = true
    binding.log = new FakeLog()
    binding.location = null
    def cc = new CompilerConfiguration(scriptBaseClass: DriverBase.name)
    def d = new GroovyShell(this.class.classLoader, binding, cc).parse(new File(driverPath))
    return d
}
def names = { d -> d.events.collect { it.name } }
def event = { d, String n -> d.events.find { it.name == n } }
def update = { d, Map safety, String sharing = "active" ->
    d.events.clear()
    d.binding.log.infos.clear()
    d.updateFromParent("Lake House", "2026-09-18T21:04:12Z", sharing, safety)
}

String HELD_DESC = "Newman was last confirmed at a saved place 600 min ago"

// ---- F1: older server (no fresh key) — today's derivation -----------------
def d = load()
update(d, [status: "at_area", area: "Lake House", ageSeconds: 600])
assert event(d, "safetyStatus").value == "at_area"
assert event(d, "positionFresh").value == "true" : "no fresh key: non-stale tier is fresh"
println "F1 PASS: field absent -> positionFresh derived from the status, as before"

// ---- F2: same device, the server now holds it — tier unchanged ------------
update(d, [status: "at_area", area: "Lake House", ageSeconds: 36000, fresh: false])
assert names(d) == ["positionFresh", "positionAgeSeconds"] : names(d)
assert event(d, "positionFresh").value == "false"
assert event(d, "positionFresh").descriptionText == HELD_DESC
assert event(d, "positionAgeSeconds").value == 36000
assert d.binding.log.infos == [HELD_DESC] : d.binding.log.infos
assert d.binding.device.values.safetyStatus == "at_area" : "no client-side downgrade on age"
println "F2 PASS: fresh -> held: only positionFresh/positionAgeSeconds move; the narrative says 'last confirmed'"

// ---- F3: new device whose first update is already held --------------------
d = load()
update(d, [status: "at_area", area: "Lake House", ageSeconds: 36000, fresh: false])
assert event(d, "safetyStatus").value == "at_area"
assert event(d, "safetyStatus").descriptionText == HELD_DESC
assert event(d, "positionFresh").value == "false"
assert d.binding.log.infos.count { it == HELD_DESC } == 1 : "narrated once per cycle"
println "F3 PASS: first update held -> safetyStatus at_area narrated as a last confirmation, logged once"

// ---- F4: held -> fresh again ----------------------------------------------
update(d, [status: "at_area", area: "Lake House", ageSeconds: 30, fresh: true])
assert names(d) == ["positionFresh", "positionAgeSeconds"] : names(d)
assert event(d, "positionFresh").value == "true"
println "F4 PASS: held -> fresh: positionFresh back to true"

// ---- F5: stale (the server does not hold outside an area) ------------------
update(d, [status: "stale", ageSeconds: 4200, fresh: false])
assert event(d, "safetyStatus").value == "unknown"
assert event(d, "positionFresh").value == "false"
println "F5 PASS: stale -> unknown, positionFresh false (unchanged)"

// ---- F6: paused wins over a fresh flag -------------------------------------
d = load()
update(d, [status: "at_area", ageSeconds: 30, fresh: true], "disabled")
assert event(d, "safetyStatus").value == "unknown"
assert event(d, "positionFresh").value == "false"
println "F6 PASS: sharing paused -> unknown / false regardless of the field"

// ---- F7: no safety fields at all (narrowed, muted, feature off) -----------
d = load()
update(d, null)
assert event(d, "safetyStatus").value == "unknown"
assert event(d, "positionFresh").value == "false"
println "F7 PASS: no safety fields -> unknown / false (unchanged)"

// ---- F8: presence and currentArea never follow freshness -------------------
d = load()
update(d, [status: "at_area", ageSeconds: 600, fresh: true])
def presence = d.binding.device.values.presence
def area = d.binding.device.values.currentArea
update(d, [status: "at_area", ageSeconds: 36000, fresh: false])
assert !names(d).contains("presence") && !names(d).contains("currentArea")
assert d.binding.device.values.presence == presence && d.binding.device.values.currentArea == area
println "F8 PASS: a hold moves no presence or currentArea event"

println "\nALL 8 FRESHNESS SCENARIOS PASS against the real driver source"
