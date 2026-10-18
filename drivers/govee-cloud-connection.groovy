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
 * It also holds the Govee LAN API: a multicast scan finds devices with LAN
 * Control on, their replies arrive on port 4002, and commands go to the
 * device on port 4003, each checked with a devStatus read straight after.
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

@Field static final String DRIVER_VERSION = "0.3.0"
@Field static final int CONNECT_WAIT_S = 5
@Field static final long CONFIRM_QUERY_MS = 1500
@Field static final int CONFIRM_TIMEOUT_S = 6
// A report this long after our confirm query is its answer: if it still shows
// the old value, the command failed and there's no point waiting longer.
@Field static final long QUERY_ANSWER_MS = 150
@Field static final int RECONNECT_MAX_S = 300
// Govee LAN API: scan on 4001 (multicast), replies arrive on 4002, commands go to 4003.
@Field static final String LAN_GROUP = "239.255.255.250"
@Field static final int LAN_SCAN_PORT = 4001
@Field static final int LAN_REPLY_PORT = 4002
@Field static final int LAN_CMD_PORT = 4003
@Field static final int LAN_SCAN_S = 300
// devStatus is read this long after a LAN command, to confirm it. A light that
// fades may not show the new value yet, so a mismatch is read again (up to
// LAN_READS times, LAN_REREAD_MS apart) before the command goes another way.
@Field static final long LAN_CONFIRM_MS = 300
@Field static final long LAN_REREAD_MS = 400
@Field static final int LAN_READS = 3
// A LAN command with no matching answer after this long goes another way.
@Field static final long LAN_TIMEOUT_MS = 2500

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
        MEM.putIfAbsent(id, [pending: [:], intent: [:]])
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
    ["periodicQuery", "watchdog", "connectMqtt", "afterConnect"].each { unschedule(it) }
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
    // LAN replies arrive as JSON with the sender's address; MQTT messages don't.
    if (event?.startsWith("{") && event.contains("fromIp")) {
        lanParse(event)
        return
    }
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
    parent?.iotMessage([device: id, sku: d.sku, cmd: d.cmd, state: st, via: "iot"])
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
    List done = list - open
    if (done.any { it.via == "lan" }) parent?.lanConfirmed(id)
    if (done.any { it.via != "lan" }) parent?.iotConfirmed(id)
    // The answer to our confirm query still shows the old value. Over MQTT, give
    // up now; over the LAN, read again a couple of times (the light may be fading).
    List answered = open.findAll { Map e ->
        long wait = e.via == "lan" ? 0L : QUERY_ANSWER_MS
        e.queriedAt && now() - (e.queriedAt as long) >= wait && (e.expect ?: [:]).keySet().every { st.containsKey(it) }
    }
    List failed = answered.findAll { Map e ->
        if (e.via != "lan") return true
        e.reads = ((e.reads ?: 0) as int) + 1
        e.queriedAt = null
        if ((e.reads as int) < LAN_READS) return false
        // The light answers but still disagrees: it's reachable, so send it once more over the LAN.
        if (!e.resent) {
            e.resent = true
            e.reads = 0
            e.at = now()
            lanSend(e.ip as String, [msg: [cmd: e.cmd, data: lanData(e.data as Map)]])
            logDebug "LAN ${e.ip}: resending ${e.cmd}"
            return false
        }
        return true
    }
    if (answered.size() > failed.size()) runInMillis(LAN_REREAD_MS, "lanConfirm", [overwrite: true])
    List waiting = open - failed
    if (waiting) p.put(id, waiting) else p.remove(id)
    giveUp(id, failed)
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
    noteIntent(id, expect)
    // A newer command for the same value replaces the older one (a slider being dragged).
    List list = ((p.get(id) ?: []) as List).findAll { !((it.expect ?: [:]).keySet().intersect((expect ?: [:]).keySet())) }
    list << [api: api, expect: expect ?: [:], at: now(), topic: topic, via: "iot"]
    p.put(id, list)
    runInMillis(CONFIRM_QUERY_MS, "confirmQuery", [overwrite: true])
    runIn(CONFIRM_TIMEOUT_S, "checkPending", [overwrite: true])
}

/** Hand unconfirmed commands back to the app: LAN ones and MQTT ones go different ways. */
private void giveUp(String id, List entries) {
    // A newer command for the same value has been sent since: don't resend the old one.
    entries = entries.findAll { !superseded(id, it as Map) }
    List lan = entries.findAll { it.via == "lan" }.collect { [cmd: it.cmd, data: it.data, api: it.api] }
    List apis = entries.findAll { it.via != "lan" }.collect { it.api }.findAll { it }
    if (lan) parent?.lanUnconfirmed(id, lan)
    if (apis) parent?.iotUnconfirmed(id, apis)
}

/** The latest value sent for each key (onOff, brightness, color...), per device. */
private void noteIntent(String id, Map expect) {
    Map intent = mem().intent
    Map mine = (intent.get(id) ?: [:]) as Map
    (expect ?: [:]).each { k, v -> mine[k] = v }
    intent.put(id, mine)
}

/** True when a newer command has set a different value for any of this entry's keys. */
private boolean superseded(String id, Map e) {
    Map mine = (mem().intent.get(id) ?: [:]) as Map
    Map expect = (e.expect ?: [:]) as Map
    boolean newer = expect.any { k, v -> mine.containsKey(k) && !reportMatches([(k): v], [(k): mine[k]]) }
    if (newer) logDebug "Dropping a superseded ${e.cmd ?: e.api?.instance} for ...${id.length() > 5 ? id.substring(id.length() - 5) : id}"
    return newer
}

/** Devices often report a change on their own; a status query makes sure. */
def confirmQuery() {
    Map topics = [:]
    long t = now()
    mem().pending.each { String id, List list ->
        List iot = (list ?: []).findAll { it.via != "lan" }
        if (!iot) return
        iot.each { Map e -> if (!e.queriedAt) e.queriedAt = t }
        topics[id] = iot[-1].topic
    }
    if (topics) queryStatus(topics)
}

def checkPending() {
    long cutoff = now() - CONFIRM_TIMEOUT_S * 1000L + 500
    long lanCutoff = now() - LAN_TIMEOUT_MS + 200
    Map p = mem().pending
    List ids = p.keySet().toList()
    ids.each { String id ->
        List list = p.get(id) ?: []
        List expired = list.findAll { (it.at as long) <= (it.via == "lan" ? lanCutoff : cutoff) }
        if (!expired) return
        p.put(id, list - expired)
        if (!p.get(id)) p.remove(id)
        giveUp(id, expired)
    }
    if (p) runInMillis(p.values().flatten().any { it.via == "lan" } ? LAN_TIMEOUT_MS : CONFIRM_TIMEOUT_S * 1000L, "checkPending", [overwrite: true])
}

// ---------------------------------------------------------------- LAN

/** Listen for LAN replies and scan for devices now and every few minutes. */
def lanStart() {
    try {
        def listener = interfaces.getMulticastSocket(LAN_GROUP, LAN_REPLY_PORT)
        if (!listener.connected) listener.connect()
        def scanner = interfaces.getMulticastSocket(LAN_GROUP, LAN_SCAN_PORT)
        if (!scanner.connected) scanner.connect()
        state.lan = true
    } catch (e) {
        log.warn "Govee: couldn't open the LAN sockets: ${e.message}"
        return
    }
    runIn(2, "scanLan")
}

def lanStop() {
    state.lan = false
    unschedule("scanLan")
    [LAN_REPLY_PORT, LAN_SCAN_PORT].each { int port ->
        try {
            def s = interfaces.getMulticastSocket(LAN_GROUP, port)
            if (s.connected) s.close()
        } catch (e) { }
    }
}

/** Ask every Govee device on the LAN to announce itself. */
def scanLan() {
    if (!state.lan) return
    try {
        def scanner = interfaces.getMulticastSocket(LAN_GROUP, LAN_SCAN_PORT)
        if (!scanner.connected) scanner.connect()
        scanner.sendMessage(hexOf(JsonOutput.toJson([msg: [cmd: "scan", data: [account_topic: "reserve"]]])))
        logDebug "LAN scan sent"
    } catch (e) {
        log.warn "Govee: LAN scan failed: ${e.message}"
    }
    runIn(LAN_SCAN_S, "scanLan")
}

def socketStatus(String s) {
    logDebug "LAN socket: ${s}"
}

private void lanParse(String event) {
    def e = null
    def msg = null
    try {
        e = new JsonSlurper().parseText(event)
        msg = new JsonSlurper().parseText(new String((e.payload as String).decodeHex(), "UTF-8"))?.msg
    } catch (ex) {
        logDebug "LAN message not understood: ${ex.message}"
        return
    }
    if (!(msg instanceof Map) || !(msg.data instanceof Map)) return
    String ip = e.fromIp
    if (msg.cmd == "scan") {
        // Our own scan request comes back too; it carries account_topic.
        if (msg.data.account_topic || !msg.data.device) return
        parent?.lanFound(msg.data.device as String, (msg.data.ip ?: ip) as String, msg.data.sku as String)
    } else if (msg.cmd == "devStatus") {
        String id = (state.lanIps ?: [:])[ip]
        if (!id) {
            state.lanIps = parent?.lanTargets() ?: [:]
            id = state.lanIps[ip]
        }
        if (!id) return
        logDebug "LAN ${ip} devStatus: ${msg.data}"
        Map st = settlePending(id, msg.data as Map)
        parent?.iotMessage([device: id, state: st, via: "lan"])
    }
}

/**
 * Send a command over the LAN. If the device's devStatus doesn't show
 * `expect` right after, the app sends it another way.
 */
def publishLan(String id, String ip, String cmd, Map data, Map api, Map expect) {
    noteIntent(id, expect)
    if (!lanSend(ip, [msg: [cmd: cmd, data: lanData(data)]])) {
        parent?.lanUnconfirmed(id, [[cmd: cmd, data: data, api: api]])
        return
    }
    Map ips = state.lanIps ?: [:]
    if (ips[ip] != id) {
        ips[ip] = id
        state.lanIps = ips
    }
    Map p = mem().pending
    List list = ((p.get(id) ?: []) as List).findAll { !((it.expect ?: [:]).keySet().intersect((expect ?: [:]).keySet())) }
    list << [api: api, cmd: cmd, data: data, expect: expect ?: [:], at: now(), ip: ip, via: "lan"]
    p.put(id, list)
    runInMillis(LAN_CONFIRM_MS, "lanConfirm", [overwrite: true])
    runInMillis(LAN_TIMEOUT_MS, "checkPending", [overwrite: true])
}

/** The LAN API spells the value "value" where MQTT uses "val". */
private Map lanData(Map data) { data.containsKey("val") ? [value: data.val] : data }

/** Send raw ptReal frames (base64) over the LAN, e.g. a scene. Nothing reports a scene back, so this isn't confirmed. */
def publishLanFrames(String id, String ip, List frames) {
    if (!lanSend(ip, [msg: [cmd: "ptReal", data: [command: frames]]])) log.warn "Govee: couldn't send a scene to ${ip}"
}

/** Read devStatus from every device with an unconfirmed LAN command. */
def lanConfirm() {
    long t = now()
    Set ips = [] as Set
    mem().pending.each { String id, List list ->
        (list ?: []).findAll { it.via == "lan" }.each { Map e ->
            if (!e.queriedAt) e.queriedAt = t
            ips << e.ip
        }
    }
    ips.each { lanSend(it as String, [msg: [cmd: "devStatus", data: [:]]]) }
}

/** Ask LAN devices for their state; `ips` is a list of addresses. */
def lanQuery(List ips) {
    (ips ?: []).each { lanSend(it as String, [msg: [cmd: "devStatus", data: [:]]]) }
}

private boolean lanSend(String ip, Map payload) {
    if (!ip) return false
    try {
        sendHubCommand(new hubitat.device.HubAction(JsonOutput.toJson(payload), hubitat.device.Protocol.LAN,
            [type: hubitat.device.HubAction.Type.LAN_TYPE_UDPCLIENT, destinationAddress: "${ip}:${LAN_CMD_PORT}", ignoreResponse: true]))
        return true
    } catch (e) {
        log.warn "Govee: LAN send to ${ip} failed: ${e.message}"
        return false
    }
}

private String hexOf(String s) { s.getBytes("UTF-8").encodeHex().toString() }

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
