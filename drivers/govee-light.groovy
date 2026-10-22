/**
 * Govee Light: a Govee bulb, strip, lamp or light bar, created by the Govee
 * Connect app.
 *
 * The device keeps the light's merged Govee state; the app turns it into
 * events and turns commands into MQTT or Platform API calls (see
 * apps/govee-connect.groovy).
 *
 * Copyright (c) 2026 Digital Homesteaders. MIT License.
 */

import groovy.transform.Field
import java.util.concurrent.ConcurrentHashMap

@Field static final String DRIVER_VERSION = "0.4.4"
// Must equal definition(name:); tests/PackageCheck.groovy checks it.
@Field static final String DRIVER_NAME = "Govee Light"

// How the app last reached the device: the LAN, Govee's live (MQTT) connection, or the Govee API.
@Field static final Map TRANSPORTS = [lan: "local", iot: "cloud-live", api: "cloud-api"]

// goveeMode was Govee's raw mode number, which meant nothing in the UI (0.3.0 and earlier).
@Field static final List OLD_ATTRIBUTES = ["goveeMode"]

// Hold-to-dim (ChangeLevel): Govee has no fades, so brightness steps until the
// button is released. Over the API each step is a rate-limited cloud call, so
// steps are slower and bigger there. A ramp stops on its own after RAMP_MAX_MS.
@Field static final int RAMP_STEP = 10
@Field static final int RAMP_API_STEP = 25
@Field static final long RAMP_FAST_MS = 400L
@Field static final long RAMP_API_MS = 1500L
@Field static final long RAMP_MAX_MS = 20000L
// Flash: on/off this many times, at least this far apart (not over the API).
@Field static final int FLASH_TIMES = 5
@Field static final int FLASH_DEFAULT_MS = 750
@Field static final int FLASH_MIN_MS = 500
// Running ramps and flashes by device id. Shared across executions (state is read
// once per execution), so a release stops the next step at once.
@Field static final ConcurrentHashMap<Long, Map> RAMP = new ConcurrentHashMap<>()
@Field static final ConcurrentHashMap<Long, Map> FLASH = new ConcurrentHashMap<>()

metadata {
    // One thing at a time per device: a command, a live report and an API read
    // arriving together would otherwise each merge into state.gov and lose the others' changes.
    definition(name: "Govee Light", namespace: "govee", author: "Digital Homesteaders", singleThreaded: true, importUrl: "https://raw.githubusercontent.com/digital-homesteaders/hubitat-govee/main/drivers/govee-light.groovy") {
        capability "Actuator"
        capability "Light"
        capability "Switch"
        capability "SwitchLevel"
        capability "ColorControl"
        capability "ColorTemperature"
        capability "ColorMode"
        capability "ChangeLevel"
        capability "Flash"
        capability "LightEffects"
        capability "Refresh"

        attribute "healthStatus", "enum", ["online", "offline"]
        attribute "transport", "enum", ["local", "cloud-live", "cloud-api"]

        // Hubitat's own labels for these say "level" and "duration"; Govee has no fades.
        command "setLevel", [[name: "Brightness*", type: "NUMBER", description: "Percent, 1 to 100 (0 turns the light off)"]]
        command "setColorTemperature", [[name: "Color temperature*", type: "NUMBER", description: "Kelvin: about 2700 is warm white, 4000 neutral, 6500 daylight"],
                                        [name: "Brightness", type: "NUMBER", description: "Optional: percent, 1 to 100"]]
        command "setHue", [[name: "Hue*", type: "NUMBER", description: "Hubitat's 0 to 100 scale, not degrees: 0 red, 17 yellow, 33 green, 50 cyan, 67 blue, 83 magenta"]]
        command "setSaturation", [[name: "Saturation*", type: "NUMBER", description: "Color strength in percent: 0 is white, 100 is full color"]]
        command "setEffect", [[name: "Effect number*", type: "NUMBER", description: "The scene's number in lightEffects. To pick a scene by name, use Set Scene"]]
        command "setScene", [[name: "Scene name*", type: "STRING", description: "Part of the name is enough. Use this in rules: Set Effect numbers change when the favorites change"]]
    }
    preferences {
        input "favoriteScenes", "enum", title: "Favorite scenes (Set Effect numbers, Next/Previous Effect and lightEffects use only these; none picked = all scenes)",
            options: sceneOptions(), multiple: true, required: false
        input "colorKeepsLevel", "bool", title: "Keep the current brightness when setting a color (Hubitat's color picker otherwise sets 100%)", defaultValue: true
        input "txtEnable", "bool", title: "Log changes (on/off, brightness, color, scene)", defaultValue: true
        input "logEnable", "bool", title: "Debug logging (turns itself off after 30 minutes)", defaultValue: false
    }
}

// ---------------------------------------------------------------- lifecycle

def installed() {
    checkVersion()
    refresh()
}

def updated() {
    checkVersion()
    if (logEnable) runIn(1800, "logsOff")
    dropOldAttributes()
    publishEffects()
    applyState()
}

def refresh() {
    parent?.refreshFromDriver(device)
}

// ---------------------------------------------------------------- commands

def on() { sendGovee("on") }
def off() { sendGovee("off") }
def setLevel(level, duration = null) { sendGovee("setLevel", [level]) }
def setColor(Map color) {
    // The colour picker sends a level with every colour; by default, leave brightness alone.
    Map c = color ?: [:]
    if (settings.colorKeepsLevel != false) c = c.findAll { it.key != "level" }
    sendGovee("setColor", [c])
}
def setHue(hue) { sendGovee("setHue", [hue]) }
def setSaturation(saturation) { sendGovee("setSaturation", [saturation]) }
def setColorTemperature(temp, level = null, transitionTime = null) { sendGovee("setColorTemperature", [temp, level]) }

// ---------------------------------------------------------------- hold to dim, flash

/** A button held: step brightness up or down until stopLevelChange (release). */
def startLevelChange(direction) {
    stopRunning()
    boolean up = direction?.toString() != "down"
    boolean on = device.currentValue("switch") == "on"
    // Dimming down a light that's off does nothing; brightening it starts from the bottom.
    if (!on && !up) return
    int level = on ? ((device.currentValue("level") ?: 50) as int) : 0
    boolean api = parent?.commandRoute(device) == "api"
    RAMP[device.idAsLong] = [up: up, level: level, step: api ? RAMP_API_STEP : RAMP_STEP,
                             every: api ? RAMP_API_MS : RAMP_FAST_MS, until: now() + RAMP_MAX_MS]
    rampStep()
}

/** The button released. */
def stopLevelChange() {
    RAMP.remove(device.idAsLong)
    unschedule("rampStep")
}

void rampStep() {
    Map r = RAMP[device.idAsLong]
    if (!r) return
    int next = Math.max(1, Math.min(100, (r.level as int) + (r.up ? 1 : -1) * (r.step as int)))
    if (next != r.level) {
        r.level = next
        sendGovee("setLevel", [next], true)
    }
    if (next <= 1 || next >= 100 || now() > (r.until as long)) {
        RAMP.remove(device.idAsLong)
        return
    }
    runInMillis(r.every as long, "rampStep")
}

/** Blink FLASH_TIMES times, ending as it started. Any other command stops it. */
def flash(rateToFlash = null) {
    stopRunning()
    if (parent?.commandRoute(device) == "api") {
        log.warn "${device.displayName}: can't flash: its commands go through the Govee API, which is too slow and rate-limited for that"
        return
    }
    int rate = Math.max(FLASH_MIN_MS, (rateToFlash ?: FLASH_DEFAULT_MS) as int)
    FLASH[device.idAsLong] = [left: FLASH_TIMES * 2, rate: rate, on: device.currentValue("switch") == "on"]
    flashStep()
}

void flashStep() {
    Map f = FLASH[device.idAsLong]
    if (!f) return
    f.on = !f.on
    f.left = (f.left as int) - 1
    sendGovee(f.on ? "on" : "off", [], true)
    if ((f.left as int) <= 0) {
        FLASH.remove(device.idAsLong)
        return
    }
    runInMillis(f.rate as long, "flashStep")
}

/** Stop a ramp or flash in progress (another command takes over). */
private void stopRunning() {
    if (RAMP.remove(device.idAsLong) != null) unschedule("rampStep")
    if (FLASH.remove(device.idAsLong) != null) unschedule("flashStep")
}

// Scenes, numbered as in the lightEffects attribute (Govee scenes first, then DIY).
// With favorites picked, the effects are just the favorites, numbered 1, 2, 3...
def setEffect(effect) {
    int n = (effect ?: 0) as int
    List order = effectOrder()
    if (n < 1 || n > order.size()) {
        log.warn "${device.displayName}: no effect ${effect}: effects are numbered 1 to ${order.size()} (see lightEffects), or use Set Scene by name"
        return
    }
    applyScene(order[n - 1] as int)
}

/** Run scene number `i` of the full list (1-based). */
private void applyScene(int i) {
    List scenes = state.scenes ?: []
    if (i < 1 || i > scenes.size()) return
    state.effectIndex = i
    sendGovee("setEffect", [scenes[i - 1]])
}

/** Full-list scene numbers that make up the effects: the favorites, else every scene. */
private List effectOrder() {
    int size = (state.scenes ?: []).size()
    Map byName = sceneNumbers()
    List order = ((settings.favoriteScenes ?: []) as List).collect { byName[it as String] }.findAll { it }.unique().sort()
    return order ?: (size ? (1..size).toList() : [])
}

/** lightEffects: the effects by number, as Set Effect takes them. */
private void publishEffects() {
    List scenes = state.scenes ?: []
    Map effects = [:]
    effectOrder().eachWithIndex { int i, int n -> effects[(n + 1).toString()] = scenes[i - 1][0] }
    String v = groovy.json.JsonOutput.toJson(effects)
    if (device.currentValue("lightEffects") != v) sendEvent(name: "lightEffects", value: v)
}

def setNextEffect() { stepEffect(1) }
def setPreviousEffect() { stepEffect(-1) }

/** Step through the favorite scenes (or all of them), wrapping around. */
private void stepEffect(int delta) {
    List order = effectOrder()
    if (!order) return
    int cur = (state.effectIndex ?: 0) as int
    int pos = order.indexOf(cur)
    // Not on a favorite yet: Next starts at the first, Previous at the last.
    int next = pos < 0 ? (delta > 0 ? 0 : order.size() - 1) : ((pos + delta) % order.size() + order.size()) % order.size()
    applyScene(order[next] as int)
}

/** A scene by name (exact, else the first containing it, ignoring case) or by number. */
def setScene(String scene) {
    String q = (scene ?: "").trim()
    if (q ==~ /\d+/) {
        setEffect(q as int)
        return
    }
    // A number still works, as the effect number (kept for older rules).
    // The names as the favorites list shows them, so "Meditation (2)" works too.
    Map numbers = sceneNumbers()
    String exact = numbers.keySet().find { it.equalsIgnoreCase(q) }
    List names = (state.scenes ?: []).collect { (it[0] as String).toLowerCase() }
    int i = exact ? (numbers[exact] as int) - 1 : names.findIndexOf { it.contains(q.toLowerCase()) }
    if (i < 0) {
        log.warn "${device.displayName}: no scene called '${scene}' (see lightEffects)"
        return
    }
    applyScene(i + 1)
}

/** Choices for the favorite scenes preference: the scene names, alphabetically. */
List sceneOptions() {
    // The hub also reads the preferences without a device (the home screen tiles),
    // where there's no state; failing there breaks the tile.
    try {
        return sceneNumbers().keySet().toList().sort { it.toLowerCase() }
    } catch (e) {
        return []
    }
}

/** Unique scene name -> its number; a repeated name gets " (2)", " (3)"... */
Map sceneNumbers() {
    Map out = [:]
    (state.scenes ?: []).eachWithIndex { e, i ->
        String name = e[0] as String
        String key = name
        int n = 2
        while (out.containsKey(key)) key = "${name} (${n++})".toString()
        out[key] = i + 1
    }
    return out
}

/** The app hands over a scene list ("scene" or "diy"); both lists make up the numbered effects. */
void goveeScenes(String kind, List list) {
    Map lists = state.sceneLists ?: [:]
    lists[kind] = list ?: []
    state.sceneLists = lists
    List all = ((lists.scene ?: []) as List) + ((lists.diy ?: []) as List).collect { ["DIY: ${it[0]}".toString(), it[1], it[2]] }
    state.scenes = all
    publishEffects()
}

/** `stepping` is true for a ramp or flash's own steps; any other command stops those. */
private void sendGovee(String cmd, List args = [], boolean stepping = false) {
    if (!stepping) stopRunning()
    Map optimistic = parent?.goveeCommand(device, state.gov ?: [:], cmd, args)
    // Show the change straight away; the light's own report follows.
    if (optimistic) merge(optimistic)
}

// ---------------------------------------------------------------- state from the app

/** Merge reported state; `source` is "iot" or "api". */
void goveeUpdate(Map reported, String source) {
    goveeSeen(source)
    merge(reported)
}

/** Any sign of life from the device, even a message without state. */
void goveeSeen(String source) {
    String t = TRANSPORTS[source] ?: source
    if (device.currentValue("transport") != t) sendEvent(name: "transport", value: t)
}

private void merge(Map changes) {
    state.gov = (state.gov ?: [:]) + (changes ?: [:])
    applyState()
}

private void applyState() {
    if (!state.gov || !parent) return
    parent.goveeEvents(device, state.gov).each { Map e -> emit(e) }
}

private void emit(Map e) {
    // Most of each report is unchanged: skip those values rather than send them.
    boolean same = device.currentValue(e.name)?.toString() == e.value?.toString()
    if (same && !e.isStateChange) return
    // The app words the events worth logging ("brightness is 50%"); the rest stay quiet.
    if (e.descriptionText) {
        e.descriptionText = "${device.displayName} ${e.descriptionText}".toString()
        if (txtEnable) log.info e.descriptionText
    }
    sendEvent(e)
}

/** Debug logging switches itself off 30 minutes after it's turned on. */
void logsOff() {
    device.updateSetting("logEnable", [value: "false", type: "bool"])
}

private void logDebug(String msg) {
    if (logEnable) log.debug "${device.displayName}: ${msg}"
}

/** Called by the app on Done: record this driver's version and compare it with the app's. */
void goveeHello() {
    checkVersion()
    dropOldAttributes()
}

/** Clear attributes earlier versions set and this one no longer has. */
private void dropOldAttributes() {
    OLD_ATTRIBUTES.each { if (device.currentValue(it) != null) device.deleteCurrentState(it) }
}

/** Warn when this driver and the app come from different versions (a file wasn't re-pasted). */
private void checkVersion() {
    if (device.getDataValue("driverVersion") != DRIVER_VERSION) device.updateDataValue("driverVersion", DRIVER_VERSION)
    // The device page's Data shows which driver code and version this device runs.
    if (device.getDataValue("driverName") != DRIVER_NAME) device.updateDataValue("driverName", DRIVER_NAME)
    // Code pasted into the wrong Drivers Code entry: say so rather than act oddly.
    String kind = device.getDataValue("kind")
    if (kind && kind != "light") log.warn "Govee: ${device.displayName} is a ${kind} but runs the ${DRIVER_NAME} driver code: re-paste each driver into its own Drivers Code entry (or set this device's Type to Govee Fan)"
    String appVersion = null
    try { appVersion = parent?.goveeVersion() } catch (e) { }
    if (appVersion != DRIVER_VERSION) log.warn "Govee: ${device.displayName} driver is ${DRIVER_VERSION} but the app is ${appVersion ?: 'older'}: re-paste the app and all drivers from the same version"
}
