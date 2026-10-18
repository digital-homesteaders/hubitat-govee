/**
 * Govee Fan: a Govee tower or pedestal fan, created by the Govee Connect app.
 *
 * Power goes over Govee's live connection. Speed, mode and oscillation need the
 * Govee API key, because the live connection only reports a fan's power.
 *
 * Copyright (c) 2026 Digital Homesteaders. MIT License.
 */

import groovy.transform.Field

@Field static final String DRIVER_VERSION = "0.2.0"

// Govee tower fans have 8 speed steps; the dropdowns can't vary per device.
@Field static final List SPEED_LEVELS = ["1", "2", "3", "4", "5", "6", "7", "8"]
@Field static final List FAN_MODES = ["FanSpeed", "Auto", "Sleep", "Nature", "Storm", "Custom"]

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
        attribute "transport", "enum", ["lan", "iot", "api"]

        command "setSpeedLevel", [[name: "Speed level*", type: "ENUM", description: "Fan step as in the Govee app (a fan with fewer steps uses its top one)", constraints: SPEED_LEVELS]]
        command "setMode", [[name: "Mode*", type: "ENUM", description: "Govee fan mode (supportedModes lists this fan's)", constraints: FAN_MODES]]
        command "setOscillation", [[name: "Oscillation*", type: "ENUM", constraints: ["on", "off"]]]
    }
    preferences {
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
    boolean same = device.currentValue(e.name)?.toString() == e.value?.toString()
    if (same && !e.isStateChange) return
    if (txtEnable && e.name in ["switch", "speed", "speedLevel", "mode", "oscillation", "healthStatus"]) {
        e.descriptionText = "${device.displayName} ${e.descriptionText ?: e.name + ' is ' + e.value + (e.unit ?: '')}"
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
