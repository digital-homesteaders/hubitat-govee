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

@Field static final String DRIVER_VERSION = "0.1.6"

metadata {
    definition(name: "Govee Light", namespace: "govee", author: "Digital Homesteaders", importUrl: "") {
        capability "Actuator"
        capability "Light"
        capability "Switch"
        capability "SwitchLevel"
        capability "ColorControl"
        capability "ColorTemperature"
        capability "ColorMode"
        capability "Refresh"

        attribute "healthStatus", "enum", ["online", "offline"]
        attribute "goveeMode", "string"
        attribute "transport", "enum", ["iot", "api"]
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
def setLevel(level, duration = null) { sendGovee("setLevel", [level]) }
def setColor(Map color) { sendGovee("setColor", [color]) }
def setHue(hue) { sendGovee("setHue", [hue]) }
def setSaturation(saturation) { sendGovee("setSaturation", [saturation]) }
def setColorTemperature(temp, level = null, transitionTime = null) { sendGovee("setColorTemperature", [temp, level]) }

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
    if (txtEnable && e.name in ["switch", "level", "colorName", "colorTemperature", "healthStatus"]) {
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
