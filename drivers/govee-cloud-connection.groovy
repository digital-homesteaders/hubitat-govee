/**
 * Govee Cloud Connection: the MQTT session to Govee's AWS IoT endpoint,
 * created by the Govee Connect app (Hubitat lets only drivers open MQTT).
 *
 * It signs in with the account's client certificate (a PKCS#12 file from
 * Govee, turned into PEM here with the JVM's KeyStore), subscribes to the
 * account topic, and passes every message to the app. Govee devices report
 * changes on their own and answer "status" queries; a query goes to every
 * device every few minutes as a safety net.
 *
 * Commands are published to the device's own topic. A command counts as
 * confirmed when the device reports the value it set within a few seconds;
 * if it doesn't, the app sends it again over the Platform API (some firmware
 * reports over MQTT but ignores commands there).
 *
 * Protocol ported from lasswellt/govee-homeassistant and wez/govee2mqtt
 * (MIT License; see LICENSE).
 *
 * Copyright (c) 2026 Digital Homesteaders. MIT License.
 */

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.Field
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

metadata {
    definition(name: "Govee Cloud Connection", namespace: "govee", author: "Digital Homesteaders", importUrl: "") {
        capability "Initialize"
        attribute "connection", "enum", ["connecting", "connected", "disconnected"]
        command "reconnect"
        command "disconnectMqtt"
    }
    preferences {
        input "queryMinutes", "number", title: "Ask every device for its state every (minutes)", defaultValue: 5, range: "1..60"
        input "logEnable", "bool", title: "Debug logging", defaultValue: false
    }
}

@Field static final String DRIVER_VERSION = "0.1.6"
@Field static final int CONNECT_WAIT_S = 5
@Field static final long CONFIRM_QUERY_MS = 1500
@Field static final int CONFIRM_TIMEOUT_S = 6
// A report this long after our confirm query is its answer: if it still shows
// the old value, the command failed and there's no point waiting longer.
@Field static final long QUERY_ANSWER_MS = 150
@Field static final int RECONNECT_MAX_S = 300

// Unconfirmed commands per connection device. Lost on reboot, which is fine:
// they are seconds old at most.
@Field static final ConcurrentHashMap<Long, Map> MEM = new ConcurrentHashMap<>()

// Amazon Root CA 1 (https://www.amazontrust.com/repository/AmazonRootCA1.pem), which signs Govee's AWS IoT endpoint.
@Field static final String AMAZON_ROOT_CA1 = """-----BEGIN CERTIFICATE-----
MIIDQTCCAimgAwIBAgITBmyfz5m/jAo54vB4ikPmljZbyjANBgkqhkiG9w0BAQsF
ADA5MQswCQYDVQQGEwJVUzEPMA0GA1UEChMGQW1hem9uMRkwFwYDVQQDExBBbWF6
b24gUm9vdCBDQSAxMB4XDTE1MDUyNjAwMDAwMFoXDTM4MDExNzAwMDAwMFowOTEL
MAkGA1UEBhMCVVMxDzANBgNVBAoTBkFtYXpvbjEZMBcGA1UEAxMQQW1hem9uIFJv
b3QgQ0EgMTCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEBALJ4gHHKeNXj
ca9HgFB0fW7Y14h29Jlo91ghYPl0hAEvrAIthtOgQ3pOsqTQNroBvo3bSMgHFzZM
9O6II8c+6zf1tRn4SWiw3te5djgdYZ6k/oI2peVKVuRF4fn9tBb6dNqcmzU5L/qw
IFAGbHrQgLKm+a/sRxmPUDgH3KKHOVj4utWp+UhnMJbulHheb4mjUcAwhmahRWa6
VOujw5H5SNz/0egwLX0tdHA114gk957EWW67c4cX8jJGKLhD+rcdqsq08p8kDi1L
93FcXmn/6pUCyziKrlA4b9v7LWIbxcceVOF34GfID5yHI9Y/QCB/IIDEgEw+OyQm
jgSubJrIqg0CAwEAAaNCMEAwDwYDVR0TAQH/BAUwAwEB/zAOBgNVHQ8BAf8EBAMC
AYYwHQYDVR0OBBYEFIQYzIU07LwMlJQuCFmcx7IQTgoIMA0GCSqGSIb3DQEBCwUA
A4IBAQCY8jdaQZChGsV2USggNiMOruYou6r4lK5IpDB/G/wkjUu0yKGX9rbxenDI
U5PMCCjjmCXPI6T53iHTfIUJrU6adTrCC2qJeHZERxhlbI1Bjjt/msv0tadQ1wUs
N+gDS63pYaACbvXy8MWy7Vu33PqUXHeeE6V/Uq2V8viTO96LXFvKWlJbYK8U90vv
o/ufQJVtMVT8QtPHRh8jrdkPSHCa2XV4cdFyQzR1bldZwgJcJmApzyMZFo6IQ6XU
5MsI+yMRQ+hDKXJioaldXgjUkK642M4UwtBV8ob2xJNDd2ZhwLnoQdeXeGADbkpy
rqXRfboQnoZsG4q5WTP468SQvvG5
-----END CERTIFICATE-----"""

private Map mem() {
    Long id = device.idAsLong
    Map m = MEM.get(id)
    if (m == null) {
        MEM.putIfAbsent(id, [pending: [:]])
        m = MEM.get(id)
    }
    return m
}

// ---------------------------------------------------------------- lifecycle

def installed() {
    sendEvent(name: "connection", value: "disconnected")
}

def updated() {
    checkVersion()
    if (state.wanted) scheduleQueries()
}

/** Runs at hub start-up (Initialize capability). */
def initialize() {
    checkVersion()
    state.failures = 0
    connectMqtt()
}

def reconnect() {
    state.failures = 0
    connectMqtt()
}

def disconnectMqtt() {
    state.wanted = false
    unschedule()
    try { if (interfaces.mqtt.isConnected()) interfaces.mqtt.disconnect() } catch (e) { }
    mem().pending.clear()
    setConnection("disconnected")
}

// ---------------------------------------------------------------- connecting

def connectMqtt() {
    checkVersion()
    Map creds = parent?.iotCredentials()
    if (!creds) {
        logDebug "No certificate yet; waiting for the app to sign in"
        setConnection("disconnected")
        return
    }
    Map pem
    try {
        pem = pemFor(creds)
    } catch (e) {
        log.error "Govee: couldn't read the device certificate: ${e.message}"
        setConnection("disconnected")
        return
    }
    state.wanted = true
    state.accountTopic = creds.accountTopic
    setConnection("connecting")
    try {
        if (interfaces.mqtt.isConnected()) interfaces.mqtt.disconnect()
        interfaces.mqtt.connect("ssl://${creds.endpoint}:8883", creds.clientId as String, null, null,
            tlsVersion: "1.2", caCertificate: AMAZON_ROOT_CA1, clientCertificate: pem.cert, privateKey: pem.key, cleanSession: true)
    } catch (e) {
        log.warn "Govee: MQTT connect failed: ${e.message}"
        scheduleReconnect()
        return
    }
    runIn(CONNECT_WAIT_S, "afterConnect")
}

/** PEM certificate and key, converted once per certificate and cached in state. */
private Map pemFor(Map creds) {
    if (creds.certPem && creds.keyPem) return [cert: creds.certPem, key: creds.keyPem]
    String b64 = (creds.p12 as String).replaceAll(/\s/, "")
    String tag = sha256(b64.getBytes("UTF-8")).substring(0, 16)
    if (state.pemTag == tag && state.pemCert && state.pemKey) return [cert: state.pemCert, key: state.pemKey]
    String pass = (creds.p12Pass ?: "") as String
    KeyStore ks = KeyStore.getInstance("PKCS12")
    ks.load(new ByteArrayInputStream(b64.decodeBase64()), pass.toCharArray())
    String alias = ks.aliases().toList().find { ks.isKeyEntry(it) }
    if (!alias) throw new IllegalArgumentException("no private key in Govee's certificate")
    state.pemCert = pem("CERTIFICATE", ks.getCertificate(alias).encoded as byte[])
    state.pemKey = pem("PRIVATE KEY", ks.getKey(alias, pass.toCharArray()).encoded as byte[])
    state.pemTag = tag
    return [cert: state.pemCert, key: state.pemKey]
}

def afterConnect() {
    boolean up = false
    try { up = interfaces.mqtt.isConnected() } catch (e) { }
    if (!up) {
        log.warn "Govee: MQTT didn't connect"
        scheduleReconnect()
        return
    }
    interfaces.mqtt.subscribe(state.accountTopic as String, 1)
    state.failures = 0
    setConnection("connected")
    log.info "Govee: live connection up"
    queryStatus(null)
    scheduleQueries()
}

private void scheduleQueries() {
    int minutes = Math.max(1, ((settings.queryMinutes ?: 5) as int))
    runIn(minutes * 60, "periodicQuery")
    runIn(60, "watchdog")
}

def periodicQuery() {
    if (!state.wanted) return
    if (isUp()) queryStatus(null)
    int minutes = Math.max(1, ((settings.queryMinutes ?: 5) as int))
    runIn(minutes * 60, "periodicQuery")
}

/** Hubitat's MQTT client doesn't reconnect by itself. */
def watchdog() {
    if (!state.wanted) return
    if (!isUp() && device.currentValue("connection") != "connecting") {
        log.info "Govee: live connection lost; reconnecting"
        connectMqtt()
    }
    runIn(60, "watchdog")
}

private void scheduleReconnect() {
    setConnection("disconnected")
    int failures = ((state.failures ?: 0) as int) + 1
    state.failures = failures
    int wait = Math.min(RECONNECT_MAX_S, 5 * (1 << Math.min(failures - 1, 6)))
    logDebug "Reconnecting in ${wait} s"
    runIn(wait, "connectMqtt")
}

def mqttClientStatus(String s) {
    logDebug "MQTT status: ${s}"
    if (s?.startsWith("Error") || s?.toLowerCase()?.contains("lost")) {
        if (state.wanted) scheduleReconnect()
        else setConnection("disconnected")
    }
}

private boolean isUp() {
    try { return interfaces.mqtt.isConnected() } catch (e) { return false }
}

// ---------------------------------------------------------------- messages

def parse(String event) {
    Map m = interfaces.mqtt.parseMessage(event)
    def json = null
    try { json = new JsonSlurper().parseText(m.payload as String) } catch (e) { }
    if (!(json instanceof Map)) return
    Map d = json.msg instanceof Map ? json.msg : json
    String id = d.device
    if (!id) return
    Map st = d.state instanceof Map ? d.state : null
    logDebug "${d.sku} ...${id.length() > 5 ? id.substring(id.length() - 5) : id} ${d.cmd}: ${st ?: d.data ?: ''}"
    if (st) st = settlePending(id, st)
    parent?.iotMessage([device: id, sku: d.sku, cmd: d.cmd, state: st])
}

/**
 * Check a report against the device's unconfirmed commands. A command is
 * confirmed when the report shows the value it set. Values that contradict a
 * command still waiting are left out of what the app sees, so the switch
 * doesn't flick back while the command is retried. Returns the state to pass on.
 */
private Map settlePending(String id, Map st) {
    Map p = mem().pending
    List list = (p.get(id) ?: []) as List
    if (!list) return st
    List open = list.findAll { !reportMatches(it.expect as Map, st) }
    if (open.size() < list.size()) parent?.iotConfirmed(id)
    // The answer to our confirm query still shows the old value: give up now.
    List failed = open.findAll { Map e ->
        e.queriedAt && now() - (e.queriedAt as long) >= QUERY_ANSWER_MS && (e.expect ?: [:]).keySet().every { st.containsKey(it) }
    }
    List waiting = open - failed
    if (waiting) p.put(id, waiting) else p.remove(id)
    List apis = failed.collect { it.api }.findAll { it }
    if (apis) parent?.iotUnconfirmed(id, apis)
    Map out = new LinkedHashMap(st)
    open.each { Map e ->
        (e.expect ?: [:]).keySet().each { String k ->
            out.remove(k)
            // Colour and colour temperature move together.
            if (k in ["color", "colorTemInKelvin"]) { out.remove("color"); out.remove("colorTemInKelvin") }
        }
    }
    return out
}

/** True when a report shows every value in `expect` (small differences allowed). */
boolean reportMatches(Map expect, Map st) {
    if (!expect) return false
    return expect.every { String k, v ->
        def got = st[k]
        if (got == null) return false
        switch (k) {
            case "brightness": return Math.abs((got as int) - (v as int)) <= 1
            case "colorTemInKelvin": return Math.abs((got as int) - (v as int)) <= 50
            case "color":
                if (!(got instanceof Map) || !(v instanceof Map)) return false
                return ["r", "g", "b"].every { Math.abs(((got[it] ?: 0) as int) - ((v[it] ?: 0) as int)) <= 3 }
            default: return got.toString() == v.toString()
        }
    }
}

// ---------------------------------------------------------------- publishing

/** Ask devices for their state. `topics` maps device id -> topic; null means every device in Hubitat. */
def queryStatus(Map topics) {
    if (!isUp()) return
    Map t = topics ?: (parent?.iotTopics() ?: [:])
    t.each { String id, String topic ->
        publish(topic, [msg: [cmd: "status", cmdVersion: 2, transaction: "v_${now()}", type: 0]], 0)
    }
    logDebug "Status query sent to ${t.size()} device(s)"
}

/**
 * Send a command. If the device doesn't report `expect` within a few
 * seconds, the app sends `api` over the Platform API instead.
 */
def publishCommand(String id, String topic, String cmd, Map data, Map api, Map expect) {
    if (!publish(topic, [msg: [cmd: cmd, data: data, cmdVersion: 0, transaction: "v_${now()}", type: 1]], 1)) {
        if (api) parent?.iotUnconfirmed(id, [api])
        return
    }
    Map p = mem().pending
    // A newer command for the same value replaces the older one (a slider being dragged).
    List list = ((p.get(id) ?: []) as List).findAll { !((it.expect ?: [:]).keySet().intersect((expect ?: [:]).keySet())) }
    list << [api: api, expect: expect ?: [:], at: now(), topic: topic]
    p.put(id, list)
    runInMillis(CONFIRM_QUERY_MS, "confirmQuery", [overwrite: true])
    runIn(CONFIRM_TIMEOUT_S, "checkPending", [overwrite: true])
}

/** Devices often report a change on their own; a status query makes sure. */
def confirmQuery() {
    Map topics = [:]
    long t = now()
    mem().pending.each { String id, List list ->
        if (!list) return
        list.each { Map e -> if (!e.queriedAt) e.queriedAt = t }
        topics[id] = list[-1].topic
    }
    if (topics) queryStatus(topics)
}

def checkPending() {
    long cutoff = now() - CONFIRM_TIMEOUT_S * 1000L + 500
    Map p = mem().pending
    List ids = p.keySet().toList()
    ids.each { String id ->
        List list = p.get(id) ?: []
        List expired = list.findAll { (it.at as long) <= cutoff }
        if (!expired) return
        p.put(id, list - expired)
        if (!p.get(id)) p.remove(id)
        List apis = expired.collect { it.api }.findAll { it }
        if (apis) parent?.iotUnconfirmed(id, apis)
    }
    if (p) runIn(CONFIRM_TIMEOUT_S, "checkPending", [overwrite: true])
}

private boolean publish(String topic, Map payload, int qos) {
    if (!topic || !isUp()) return false
    try {
        interfaces.mqtt.publish(topic, JsonOutput.toJson(payload), qos, false)
        return true
    } catch (e) {
        log.warn "Govee: MQTT publish failed: ${e.message}"
        return false
    }
}

// ---------------------------------------------------------------- helpers

private void setConnection(String v) {
    if (device.currentValue("connection") != v) sendEvent(name: "connection", value: v)
}

private String sha256(byte[] b) { MessageDigest.getInstance("SHA-256").digest(b).encodeHex().toString() }

private String pem(String label, byte[] der) {
    String b64 = der.encodeBase64().toString()
    StringBuilder sb = new StringBuilder("-----BEGIN ${label}-----\n")
    for (int i = 0; i < b64.length(); i += 64) sb.append(b64.substring(i, Math.min(i + 64, b64.length()))).append("\n")
    return sb.append("-----END ${label}-----\n").toString()
}

private void logDebug(String msg) {
    if (logEnable) log.debug "Govee connection: ${msg}"
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
