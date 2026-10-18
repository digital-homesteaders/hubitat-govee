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

@Field static final String DRIVER_VERSION = "0.4.0"

// How the app last reached the device: the LAN, Govee's live (MQTT) connection, or the Govee API.
@Field static final Map TRANSPORTS = [lan: "local", iot: "cloud-live", api: "cloud-api"]

// goveeMode was Govee's raw mode number, which meant nothing in the UI (0.3.0 and earlier).
@Field static final List OLD_ATTRIBUTES = ["goveeMode"]

metadata {
    definition(name: "Govee Light", namespace: "govee", author: "Digital Homesteaders", importUrl: "https://raw.githubusercontent.com/GITHUB_OWNER/hubitat-govee/main/drivers/govee-light.groovy") {
        capability "Actuator"
        capability "Light"
        capability "Switch"
        capability "SwitchLevel"
        capability "ColorControl"
        capability "ColorTemperature"
        capability "ColorMode"
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

private void sendGovee(String cmd, List args = []) {
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
    String appVersion = null
    try { appVersion = parent?.goveeVersion() } catch (e) { }
    if (appVersion != DRIVER_VERSION) log.warn "Govee: ${device.displayName} driver is ${DRIVER_VERSION} but the app is ${appVersion ?: 'older'}: re-paste the app and all drivers from the same version"
}
