/**
 * A tiny stand-in for the Hubitat runtime, enough to compile the app and
 * drivers and to run them against each other without a hub.
 *
 * Each app or driver file is compiled as a script whose base class is
 * HubitatScript, which supplies definition/metadata/preferences, state,
 * settings, log, location, sendEvent, child devices, scheduling and so on.
 * Platform calls that only have side effects (runIn, asynchttpGet, the
 * WebSocket...) are recorded in `calls` for tests to inspect.
 *
 * Calls between objects (driver -> parent, app -> child device) go through
 * wrappers that refuse private methods, as Hubitat does.
 */

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.codehaus.groovy.control.CompilerConfiguration
import java.lang.reflect.Modifier

class Hub {
    File root
    Map<String, File> driverFiles = [:]   // driver name -> source file
    Map<String, Class> classes = [:]      // path -> compiled class
    String temperatureScale = "F"
    long clockOffset = 0                  // added to now(); see advance()
    long nextId = 100
    List<HubitatScript> scripts = []
    GroovyShell shell

    Hub(File root) {
        this.root = root
        CompilerConfiguration cc = new CompilerConfiguration()
        cc.scriptBaseClass = HubitatScript.name
        shell = new GroovyShell(Hub.classLoader, new Binding(), cc)
        new File(root, "drivers").listFiles().findAll { it.name.endsWith(".groovy") }.each { f ->
            def m = f.text =~ /definition\s*\(\s*name:\s*"([^"]+)"/
            if (m.find()) driverFiles[m.group(1)] = f
        }
    }

    /** Compile a source file (throws on compile errors). */
    Class compile(File f) {
        String key = f.canonicalPath
        if (!classes[key]) classes[key] = shell.parseClass(new GroovyCodeSource(f.text, f.name.replaceAll(/\W/, "_"), "/groovy/script"))
        return classes[key]
    }

    /** A new instance of an app or driver, with its top-level code run. */
    HubitatScript instance(File f) {
        HubitatScript s = (HubitatScript) compile(f).newInstance()
        s.binding = new Binding()
        s.hub = this
        s.run()
        scripts << s
        return s
    }

    /** Move the hub's clock forward, e.g. past an acknowledgement timeout. */
    void advance(long ms) { clockOffset += ms }

    /** End of an execution: every script's state goes through JSON, as on the hub. */
    void settle() { scripts.each { it.persistState() } }

    HubitatScript app(String path = "apps/govee-connect.groovy") {
        HubitatScript s = instance(new File(root, path))
        s.app = new FakeApp(id: nextId++, script: s)
        return s
    }

    /** A device running the named driver. */
    FakeDevice device(String driverName, String dni, Map props = [:], def parent = null) {
        File f = driverFiles[driverName]
        if (!f) throw new IllegalArgumentException("no driver named ${driverName}")
        HubitatScript s = instance(f)
        FakeDevice d = new FakeDevice(id: nextId++, deviceNetworkId: dni, name: props.name ?: driverName,
            label: props.label, driverName: driverName, driver: s)
        (props.data ?: [:]).each { k, v -> d.updateDataValue(k as String, v) }
        s.device = d
        s.parent = parent == null ? null : new Ref(target: parent)
        // Settings start at their declared defaults.
        s.declaredInputs.each { k, v -> if (v != null) s.settings[k] = v }
        return d
    }
}

/** Methods every script gets. */
abstract class HubitatScript extends Script {
    Hub hub
    Map state = [:]
    Map settings = [:]
    Map<String, Object> declaredInputs = [:]
    FakeLog log = new FakeLog()
    FakeApp app
    FakeDevice device
    Ref parent
    List<FakeDevice> children = []
    List<List> calls = []   // [name, args...]

    // Script.setProperty writes to the binding; set real properties instead.
    void setProperty(String name, Object v) {
        if (metaClass.hasProperty(this, name)) metaClass.setProperty(this, name, v)
        else super.setProperty(name, v)
    }

    Map getLocation() { [temperatureScale: hub?.temperatureScale ?: "F", timeZone: TimeZone.getDefault()] }

    // Settings are readable as bare names when declared with input.
    def propertyMissing(String name) {
        if (declaredInputs.containsKey(name) || settings.containsKey(name)) return settings[name]
        throw new MissingPropertyException(name, this.class)
    }

    /** Hubitat keeps state as JSON between executions; tests call this to mimic that. */
    void persistState() {
        state = (Map) new JsonSlurper().parseText(JsonOutput.toJson(state))
    }

    // ---- definitions
    void definition(Map m) { calls << ["definition", m] }
    void definition(Map m, Closure c) { calls << ["definition", m]; swallow(c, Closure.DELEGATE_FIRST) }
    void metadata(Closure c) { swallow(c, Closure.DELEGATE_FIRST) }
    void preferences(Closure c) { swallow(c, Closure.DELEGATE_FIRST) }
    private void swallow(Closure c, int strategy = Closure.OWNER_FIRST) {
        Swallow sw = new Swallow(script: this, strategy: strategy)
        c.delegate = sw
        c.resolveStrategy = strategy
        c.call()
    }

    // ---- pages (apps)
    def dynamicPage(Map m, Closure c) { swallow(c); return m }

    // ---- time and scheduling
    long now() { System.currentTimeMillis() + (hub?.clockOffset ?: 0) }
    void runIn(Number secs, String handler, Map opts = [:]) { calls << ["runIn", secs, handler, opts] }
    void runInMillis(Number ms, String handler, Map opts = [:]) { calls << ["runInMillis", ms, handler, opts] }
    void runEvery30Minutes(String handler) { calls << ["runEvery30Minutes", handler] }
    void runEvery1Hour(String handler) { calls << ["runEvery1Hour", handler] }
    void schedule(String cron, String handler) { calls << ["schedule", cron, handler] }
    void unschedule(String handler = null) { calls << ["unschedule", handler] }
    void unsubscribe() { calls << ["unsubscribe"] }
    void pauseExecution(Number ms) {}

    // ---- HTTP
    void asynchttpGet(String cb, Map params, Map data = null) { calls << ["asynchttpGet", cb, params, data] }
    void asynchttpPost(String cb, Map params, Map data = null) { calls << ["asynchttpPost", cb, params, data] }

    // ---- WebSocket and MQTT (drivers)
    FakeMqtt mqtt
    FakeInterfaces ifaces
    FakeInterfaces getInterfaces() {
        if (mqtt == null) mqtt = new FakeMqtt(calls: calls)
        if (ifaces == null) ifaces = new FakeInterfaces(calls: calls, mqtt: mqtt, webSocket: new FakeWebSocket(calls: calls))
        return ifaces
    }
    void sendHubCommand(def action) { calls << ["sendHubCommand", action] }

    // ---- events (drivers)
    void sendEvent(Map e) { device.applyEvent(e) }

    // ---- child devices
    FakeDevice getChildDevice(String dni) { children.find { it.deviceNetworkId == dni } }
    List<FakeDevice> getChildDevices() { new ArrayList(children) }
    FakeDevice addChildDevice(String namespace, String driverName, String dni, Map props = [:]) {
        if (getChildDevice(dni)) throw new IllegalArgumentException("duplicate dni ${dni}")
        FakeDevice d = hub.device(driverName, dni, props, this)
        children << d
        calls << ["addChildDevice", driverName, dni, props]
        if (d.driver.metaClass.respondsTo(d.driver, "installed")) d.driver.installed()
        return d
    }
    void deleteChildDevice(String dni) {
        children.removeAll { it.deviceNetworkId == dni }
        calls << ["deleteChildDevice", dni]
    }

    /** Names of calls made so far, e.g. ["runIn", ...]. */
    List callsNamed(String name) { calls.findAll { it[0] == name } }
}

/**
 * Delegate for definition/preferences/page blocks: inputs are recorded and
 * anything else is ignored. Pages see the script's own methods first
 * (OWNER_FIRST); metadata blocks don't. CompileCheck makes sure no script
 * defines a method that a metadata block could mistake for its DSL.
 */
class Swallow {
    HubitatScript script
    int strategy
    def input(Map m) { record(m.name, m.defaultValue) }
    def input(Map m, String name, String type) { record(name, m.defaultValue) }
    def input(String name, String type) { record(name, null) }
    def input(Map m, String name) { record(name, m.defaultValue) }
    private void record(def name, def dflt) { script.declaredInputs[name as String] = dflt }
    def methodMissing(String name, def args) {
        args.each { if (it instanceof Closure) { it.delegate = this; it.resolveStrategy = strategy; it.call() } }
        return null
    }
    def propertyMissing(String name) { script.getProperty(name) }
}

class FakeWebSocket {
    List calls
    // connect("wss://...", pingInterval: 15) arrives as connect(Map, String).
    void connect(Map opts, String url) { calls << ["ws.connect", url, opts] }
    void connect(String url) { calls << ["ws.connect", url, [:]] }
    void close() { calls << ["ws.close"] }
    void sendMessage(String msg) { calls << ["ws.send", msg] }
}

class FakeInterfaces {
    List calls
    FakeMqtt mqtt
    FakeWebSocket webSocket
    Map<String, FakeMulticastSocket> sockets = [:]
    FakeMulticastSocket getMulticastSocket(String group, int port) {
        String k = "${group}:${port}"
        if (!sockets[k]) sockets[k] = new FakeMulticastSocket(calls: calls, group: group, port: port)
        return sockets[k]
    }
}

class FakeMulticastSocket {
    List calls
    String group
    int port
    boolean connected
    void connect() { connected = true; calls << ["socket.connect", port] }
    void close() { connected = false; calls << ["socket.close", port] }
    void sendMessage(String hex) { calls << ["socket.send", port, new String(hex.decodeHex(), "UTF-8")] }
    boolean isConnected() { connected }
}

class FakeMqtt {
    List calls
    boolean connected
    boolean refuse            // connect() leaves it disconnected
    Map connectOpts
    void connect(Map opts, String broker, String clientId, String user, String pass) {
        connectOpts = opts + [broker: broker, clientId: clientId]
        calls << ["mqtt.connect", broker, clientId]
        connected = !refuse
    }
    boolean isConnected() { connected }
    void disconnect() { connected = false; calls << ["mqtt.disconnect"] }
    void subscribe(String topic, int qos) { calls << ["mqtt.subscribe", topic, qos] }
    void publish(String topic, String payload, int qos, boolean retained) { calls << ["mqtt.publish", topic, payload, qos] }
    Map parseMessage(String e) { (Map) new JsonSlurper().parseText(e) }
}

class FakeLog {
    List<List<String>> lines = []
    void debug(def m) { lines << ["debug", m.toString()] }
    void info(def m) { lines << ["info", m.toString()] }
    void warn(def m) { lines << ["warn", m.toString()] }
    void error(def m) { lines << ["error", m.toString()] }
    List<String> at(String level) { lines.findAll { it[0] == level }.collect { it[1] } }
}

class FakeApp {
    long id
    HubitatScript script
    String getLabel() { "Govee Connect" }
    String installationState = "COMPLETE"
    String getInstallationState() { installationState }
    void removeSetting(String name) { script.settings.remove(name) }
    void updateSetting(String name, def v) { script.settings[name] = v instanceof Map ? v.value : v }
}

/** Calls into another script, allowing only its public methods. */
class Ref {
    def target
    def methodMissing(String name, def args) {
        Object[] a = args as Object[]
        if (!target.class.methods.any { it.name == name && !Modifier.isPrivate(it.modifiers) }) {
            throw new MissingMethodException(name, target.class, a)
        }
        return target.invokeMethod(name, a)
    }
}

/** A device: data values, current attribute values, events, and its driver. */
class FakeDevice {
    long id
    String deviceNetworkId
    String name
    String label
    String driverName
    HubitatScript driver
    Map<String, String> data = [:]
    Map<String, Object> current = [:]
    List<Map> events = []

    long getIdAsLong() { id }
    String getDisplayName() { label ?: name }
    String getDataValue(String k) { data[k] }
    void updateDataValue(String k, def v) { data[k] = v?.toString() }
    def currentValue(String attr, boolean skipCache = false) { current[attr] }
    void deleteCurrentState(String attr) { current.remove(attr) }

    void applyEvent(Map e) {
        events << new LinkedHashMap(e)
        current[e.name as String] = e.value
    }

    /** The latest event for an attribute. */
    Map lastEvent(String attr) { events.reverse().find { it.name == attr } }

    // Device methods are the driver's public methods.
    def methodMissing(String name, def args) {
        return new Ref(target: driver).methodMissing(name, args)
    }
}
