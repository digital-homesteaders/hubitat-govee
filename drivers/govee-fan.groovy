/**
 * Govee Fan: a Govee tower or pedestal fan, created by the Govee Connect app.
 *
 * Power goes over Govee's live connection. Speed, mode and oscillation need the
 * Govee API key, because the live connection only reports a fan's power.
 *
 * Copyright (c) 2026 Digital Homesteaders. MIT License.
 */

import groovy.transform.Field

@Field static final String DRIVER_VERSION = "0.3.0"

// How the app last reached the device: the LAN, Govee's live (MQTT) connection, or the Govee API.
@Field static final Map TRANSPORTS = [lan: "local", iot: "cloud-live", api: "cloud-api"]

// Govee tower fans have 8 speed steps; the dropdowns can't vary per device.
@Field static final List SPEED_LEVELS = ["1", "2", "3", "4", "5", "6", "7", "8"]
// "Fixed speed" is Govee's FanSpeed mode (the app accepts either name).
@Field static final List FAN_MODES = ["Fixed speed", "Auto", "Sleep", "Nature", "Storm", "Custom"]

metadata {
    definition(name: "Govee Fan", namespace: "govee", author: "Digital Homesteaders", importUrl: "") {
        capability "Actuator"
        capability "Switch"
        capability "FanControl"
        capability "Refresh"

        attribute "speedLevel", "number"
        attribute "maxSpeedLevel", "number"
        attribute "mode", "string"
        attribute "supportedModes", "JSON_OBJECT"
        attribute "oscillation", "enum", ["on", "off"]
        attribute "healthStatus", "enum", ["online", "offline"]
        attribute "transport", "enum", ["local", "cloud-live", "cloud-api"]

        command "setSpeedLevel", [[name: "Speed (1 to 8)*", type: "ENUM", description: "The speed number as in the Govee app; a fan with fewer speeds uses its top one", constraints: SPEED_LEVELS]]
        command "setMode", [[name: "Mode*", type: "ENUM", description: "Fixed speed uses the last speed number; the others are the Govee app's modes (supportedModes lists this fan's)", constraints: FAN_MODES]]
        command "setOscillation", [[name: "Oscillation (swing side to side)*", type: "ENUM", constraints: ["on", "off"]]]
    }
    preferences {
        input "txtEnable", "bool", title: "Log changes (on/off, speed, mode, oscillation)", defaultValue: true
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
    applyState()
}

def refresh() {
    parent?.refreshFromDriver(device)
}

// ---------------------------------------------------------------- commands

def on() { sendGovee("on") }
def off() { sendGovee("off") }
def setSpeed(speed) { sendGovee("setSpeed", [speed]) }
def cycleSpeed() { sendGovee("cycleSpeed") }
def setSpeedLevel(level) { sendGovee("setSpeedLevel", [level as int]) }
def setMode(mode) { sendGovee("setMode", [mode]) }
def setOscillation(value) { sendGovee("setOscillation", [value]) }

private void sendGovee(String cmd, List args = []) {
    Map optimistic = parent?.goveeCommand(device, state.gov ?: [:], cmd, args)
    if (optimistic) merge(optimistic)
}

// ---------------------------------------------------------------- state from the app

void goveeUpdate(Map reported, String source) {
    goveeSeen(source)
    merge(reported)
}

/** Fans have no scenes; the app may still offer a list. */
void goveeScenes(String kind, List list) {}

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
    boolean same = device.currentValue(e.name)?.toString() == e.value?.toString()
    if (same && !e.isStateChange) return
    // The app words the events worth logging ("speed is medium"); the rest stay quiet.
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
void goveeHello() { checkVersion() }

/** Warn when this driver and the app come from different versions (a file wasn't re-pasted). */
private void checkVersion() {
    if (device.getDataValue("driverVersion") != DRIVER_VERSION) device.updateDataValue("driverVersion", DRIVER_VERSION)
    String appVersion = null
    try { appVersion = parent?.goveeVersion() } catch (e) { }
    if (appVersion != DRIVER_VERSION) log.warn "Govee: ${device.displayName} driver is ${DRIVER_VERSION} but the app is ${appVersion ?: 'older'}: re-paste the app and all drivers from the same version"
}
