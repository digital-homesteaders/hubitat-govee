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

@Field static final String DRIVER_VERSION = "0.2.0"

metadata {
    definition(name: "Govee Light", namespace: "govee", author: "Digital Homesteaders", importUrl: "") {
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
        attribute "goveeMode", "string"
        attribute "transport", "enum", ["lan", "iot", "api"]

        command "setScene", [[name: "Scene*", type: "STRING", description: "Scene name (part of it is enough) or its number from lightEffects"]]
    }
    preferences {
        input "favoriteScenes", "enum", title: "Favorite scenes (Next/Previous Effect cycle through these; none = all)",
            options: sceneOptions(), multiple: true, required: false
        input "txtEnable", "bool", title: "Description text logging", defaultValue: true
        input "logEnable", "bool", title: "Debug logging", defaultValue: false
    }
}

// ---------------------------------------------------------------- lifecycle

def installed() {
    checkVersion()
    refresh()
}

def updated() {
    checkVersion()
    applyState()
}

def refresh() {
    parent?.refreshFromDriver(device)
}

// ---------------------------------------------------------------- commands

def on() { sendGovee("on") }
def off() { sendGovee("off") }
def setLevel(level, duration = null) { sendGovee("setLevel", [level]) }
def setColor(Map color) { sendGovee("setColor", [color]) }
def setHue(hue) { sendGovee("setHue", [hue]) }
def setSaturation(saturation) { sendGovee("setSaturation", [saturation]) }
def setColorTemperature(temp, level = null, transitionTime = null) { sendGovee("setColorTemperature", [temp, level]) }

// Scenes, numbered as in the lightEffects attribute (Govee scenes first, then DIY).
def setEffect(effect) {
    int n = (effect ?: 0) as int
    List scenes = state.scenes ?: []
    if (n < 1 || n > scenes.size()) {
        log.warn "${device.displayName}: no scene ${effect} (there are ${scenes.size()}; see lightEffects)"
        return
    }
    state.effectIndex = n
    sendGovee("setEffect", [scenes[n - 1]])
}

def setNextEffect() { stepEffect(1) }
def setPreviousEffect() { stepEffect(-1) }

/** Step through the favorite scenes (or all of them), wrapping around. */
private void stepEffect(int delta) {
    int size = (state.scenes ?: []).size()
    if (!size) return
    Map byName = sceneNumbers()
    List order = ((settings.favoriteScenes ?: []) as List).collect { byName[it as String] }.findAll { it }.sort()
    if (!order) order = (1..size).toList()
    int cur = (state.effectIndex ?: 0) as int
    int pos = order.indexOf(cur)
    // Not on a favorite yet: Next starts at the first, Previous at the last.
    int next = pos < 0 ? (delta > 0 ? 0 : order.size() - 1) : ((pos + delta) % order.size() + order.size()) % order.size()
    setEffect(order[next])
}

/** A scene by name (exact, else the first containing it, ignoring case) or by number. */
def setScene(String scene) {
    String q = (scene ?: "").trim()
    if (q ==~ /\d+/) {
        setEffect(q as int)
        return
    }
    // The names as the favorites list shows them, so "Meditation (2)" works too.
    Map numbers = sceneNumbers()
    String exact = numbers.keySet().find { it.equalsIgnoreCase(q) }
    List names = (state.scenes ?: []).collect { (it[0] as String).toLowerCase() }
    int i = exact ? (numbers[exact] as int) - 1 : names.findIndexOf { it.contains(q.toLowerCase()) }
    if (i < 0) {
        log.warn "${device.displayName}: no scene called '${scene}' (see lightEffects)"
        return
    }
    setEffect(i + 1)
}

/** Choices for the favorite scenes preference: the scene names, alphabetically. */
List sceneOptions() {
    sceneNumbers().keySet().toList().sort { it.toLowerCase() }
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
    Map effects = [:]
    all.eachWithIndex { e, i -> effects[(i + 1).toString()] = e[0] }
    sendEvent(name: "lightEffects", value: groovy.json.JsonOutput.toJson(effects))
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
    if (device.currentValue("transport") != source) sendEvent(name: "transport", value: source)
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
    if (txtEnable && e.name in ["switch", "level", "colorName", "colorTemperature", "healthStatus", "effectName"]) {
        e.descriptionText = "${device.displayName} ${e.name} is ${e.value}${e.unit ?: ''}"
        log.info e.descriptionText
    }
    sendEvent(e)
}

private void logDebug(String msg) {
    if (logEnable) log.debug "${device.displayName}: ${msg}"
}

/** Called by the app on Done: record this driver's version and compare it with the app's. */
void goveeHello() { checkVersion() }

/** Warn when this driver and the app come from different versions (a file wasn't re-pasted). */
private void checkVersion() {
    if (device.getDataValue("driverVersion") != DRIVER_VERSION) device.updateDataValue("driverVersion", DRIVER_VERSION)
    String appVersion = null
    try { appVersion = parent?.goveeVersion() } catch (e) { }
    if (appVersion != DRIVER_VERSION) log.warn "Govee: ${device.displayName} driver is ${DRIVER_VERSION} but the app is ${appVersion ?: 'older'}: re-paste the app and all drivers from the same version"
}
