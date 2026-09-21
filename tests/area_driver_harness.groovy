/**
 * Off-hub harness for drivers/positionguard-area.groovy — the 1.5.0 "move
 * here" button and its result attributes.
 *
 * Run from the repo root (needs any Groovy 4 + a JDK; no hub, no network):
 *   groovy tests/area_driver_harness.groovy
 * Optionally pass an alternative driver file path as the first argument.
 * Loads the real driver source with a stubbed device and parent (the metadata
 * DSL in the script body is never run; only the methods are exercised).
 */
import org.codehaus.groovy.control.CompilerConfiguration

class FakeDevice {
    String displayName = "Family: Hotel count"
    String deviceNetworkId = "positionguard-area-g1-a1"
    Map<String, Object> values = [:]
    def currentValue(String a) { values[a] }
}

class FakeParent {
    List<String> moveCalls = []
    void moveAreaFromDevice(String dni) { moveCalls << dni }
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

def driverPath = args ? args[0] : "drivers/positionguard-area.groovy"

def load = {
    def binding = new Binding()
    binding.device = new FakeDevice()
    binding.parent = new FakeParent()
    binding.settings = [:]
    binding.txtEnable = true
    binding.log = new FakeLog()
    def cc = new CompilerConfiguration(scriptBaseClass: DriverBase.name)
    return new GroovyShell(this.class.classLoader, binding, cc).parse(new File(driverPath))
}
def named = { d, String n -> d.events.findAll { it.name == n } }

// ---- AD1: Momentary push() and PushableButton push(1) reach the same path --
def d = load()
d.push()
d.push(1)
assert d.binding.parent.moveCalls == ["positionguard-area-g1-a1", "positionguard-area-g1-a1"]
assert named(d, "pushed").size() == 2 && named(d, "pushed").every { it.value == 1 && it.isStateChange }
println "AD1 PASS: push() and push(1) both ask the parent to move this area and emit pushed=1"

// ---- AD2: moved -> lastMoveResult and lastMoveAt, readable descriptionText -
d = load()
String moved = "Hotel moved 341 km (212 mi) to your phone's location"
d.moveResult(moved, true)
def r = named(d, "lastMoveResult")[-1]
assert r.value == moved && r.descriptionText == moved && r.isStateChange
def at = named(d, "lastMoveAt")[-1]
assert at.value ==~ /\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ/ && at.descriptionText == moved
assert d.binding.log.infos == ["Family: Hotel count: ${moved}".toString()]
println "AD2 PASS: a move sets lastMoveResult and lastMoveAt (ISO-8601 UTC)"

// ---- AD3: refusal / moved:false -> lastMoveResult only, repeats still land -
d = load()
String refusal = "Only the person who created this area can move it."
d.moveResult(refusal, false)
d.moveResult(refusal, false)
assert named(d, "lastMoveAt").isEmpty() : "lastMoveAt tracks real moves only"
assert named(d, "lastMoveResult").size() == 2 && named(d, "lastMoveResult").every { it.value == refusal && it.isStateChange }
println "AD3 PASS: a refusal sets lastMoveResult only; a repeated one is still an event"

// ---- AD4: a pre-1.5.0 device gets numberOfButtons on its next count update -
d = load()
d.updateCounts(2, 0, 2, "Hotel", "Family")
d.updateCounts(2, 0, 2, "Hotel", "Family")
assert named(d, "numberOfButtons").size() == 1 && named(d, "numberOfButtons")[0].value == 1
println "AD4 PASS: numberOfButtons=1 set once by the first count update"

println "\nALL 4 AREA DRIVER SCENARIOS PASS against the real driver source"
