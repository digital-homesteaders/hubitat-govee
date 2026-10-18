/**
 * Govee Connect: Hubitat integration for Govee lights and fans.
 *
 * The app signs in to the Govee account the way the Govee phone app does (email,
 * password, and the emailed 2FA code), fetches the account's AWS IoT
 * certificate and device topics, and creates one child device per light or
 * fan. A "Govee Cloud Connection" child holds the MQTT session (only drivers
 * can open one): it carries pushed state for every device and the outgoing
 * power, brightness and colour commands.
 *
 * An optional Govee API key adds the official Platform API, used for what the
 * MQTT session can't do: fan speed, mode and oscillation, devices that never
 * answer over MQTT, and a fallback when an MQTT command isn't confirmed.
 *
 * All Govee-specific mapping lives in this file as plain functions of the
 * device's merged state, so the light and fan drivers stay thin.
 *
 * Protocol details are ported from lasswellt/govee-homeassistant and
 * wez/govee2mqtt (both MIT License; see LICENSE).
 *
 * Copyright (c) 2026 Digital Homesteaders. MIT License.
 */

import groovy.json.JsonOutput
import groovy.transform.Field
import java.security.MessageDigest

definition(
    name: "Govee Connect",
    namespace: "govee",
    author: "Digital Homesteaders",
    description: "Govee lights and fans with live updates from Govee's cloud.",
    category: "Convenience",
    singleInstance: true,
    iconUrl: "",
    iconX2Url: "",
    importUrl: ""
)

preferences {
    page(name: "mainPage")
}

// ---------------------------------------------------------------- constants

@Field static final String APP2 = "https://app2.govee.com"
@Field static final String OPENAPI = "https://openapi.api.govee.com/router/api/v1"
// The Govee app version the sign-in must claim (as govee-homeassistant does).
@Field static final String APP_VERSION = "7.4.10"
@Field static final String USER_AGENT = "GoveeHome/7.4.10 (com.ihoment.GoVeeSensor; build:2; iOS 18.4.0) Alamofire/5.10.2"
@Field static final String DEFAULT_ENDPOINT = "aqm3wd1qlc3dy-ats.iot.us-east-1.amazonaws.com"
@Field static final int TIMEOUT = 20
// A sign-in step with no answer after this long has failed.
@Field static final long LOGIN_STALE_MS = 60 * 1000L
// Rediscover the account's devices this often (needs a valid token).
@Field static final long DISCOVERY_MAX_AGE_MS = 24 * 3600 * 1000L
// A device with no MQTT message for this long is read over the Platform API instead.
@Field static final long QUIET_MS = 10 * 60 * 1000L
@Field static final Map POLL_CHOICES = ["2": "2 minutes", "5": "5 minutes", "10": "10 minutes", "15": "15 minutes", "30": "30 minutes"]

// Govee device types (Platform API `type`) and the kind of child each becomes.
@Field static final Map TYPE_KIND = ["devices.types.light": "light", "devices.types.fan": "fan"]
@Field static final Map KIND_DRIVER = [light: "Govee Light", fan: "Govee Fan"]

// Colour temperature range when the Platform API doesn't give one.
@Field static final List CT_RANGE = [2000, 9000]

// Hubitat's named fan speeds, slowest first, and the subsets used when a fan
// has fewer than five steps.
@Field static final List SPEED_NAMES = ["low", "medium-low", "medium", "medium-high", "high"]
@Field static final Map FEW_SPEEDS = [
    1: ["high"],
    2: ["low", "high"],
    3: ["low", "medium", "high"],
    4: ["low", "medium-low", "medium-high", "high"],
]

// ---------------------------------------------------------------- pages

def mainPage() {
    runPendingButton()
    expireLogin()
    Map lg = state.login ?: [:]
    boolean working = lg.step == "working"
    boolean finding = discovering()
    dynamicPage(name: "mainPage", title: "Govee Connect", install: true, uninstall: true, refreshInterval: (working || finding) ? 2 : 0) {
        if (!state.iot) {
            section("Sign in to Govee") {
                if (state.needsSignIn) paragraph "<b>Govee asked for a new sign-in.</b> Your devices keep working; sign in again to pick up new ones."
                if (working) {
                    paragraph lg.withCode ? "Checking your code with Govee..." : "Signing in to Govee..."
                } else if (lg.step == "code") {
                    paragraph "Govee emailed you a verification code. Enter it here within about 15 minutes."
                    input "verifyCode", "text", title: "Verification code", required: false
                    input "btnCode", "button", title: "Submit code"
                    input "btnCancel", "button", title: "Start over"
                } else {
                    paragraph "Use your Govee Home app email and password. Hubitat keeps a sign-in token and the account's device certificate, not your password."
                    input "email", "text", title: "Govee email", required: false
                    input "password", "password", title: "Govee password", required: false
                    input "btnSignIn", "button", title: "Sign in"
                }
                if (lg.error) paragraph "<b>${lg.error}</b>"
            }
        } else {
            section("Account") {
                paragraph "Signed in. ${connectionLine()}"
                if (state.needsSignIn) paragraph "<b>Govee's sign-in has expired.</b> Devices keep working; sign out and in again to pick up new devices."
                input "btnSignOut", "button", title: "Sign out"
            }
            section("Devices") {
                Map found = state.discovered ?: [:]
                if (found) {
                    input "selectedDevices", "enum", title: "Govee devices to add to Hubitat", options: pickerOptions(found), multiple: true, required: false
                    paragraph "Devices you untick are removed from Hubitat when you press Done."
                    if (state.unsupported) paragraph "Not supported yet: ${state.unsupported.join(', ')}"
                } else if (finding) {
                    paragraph "Looking for devices on your Govee account..."
                } else {
                    paragraph "No devices found.${state.lastError ? ' Last problem: ' + state.lastError : ''}"
                }
                input "btnFind", "button", title: "Look for new devices"
            }
        }
        section("Govee API key (recommended)") {
            paragraph "Needed for fan speed, mode and oscillation, for devices that don't report over Govee's live connection, " +
                "and as a fallback when a live command isn't confirmed. Request one in the Govee Home app: Profile > Settings > Apply for API Key."
            input "apiKey", "password", title: "API key", required: false
            input "pollMinutes", "enum", title: "Read quiet devices from the API every", options: POLL_CHOICES, defaultValue: "5", required: true
            if (state.rate) paragraph "API requests left today: ${state.rate.day ?: '?'}; this minute: ${state.rate.minute ?: '?'}"
        }
        section("Logging") {
            input "logEnable", "bool", title: "Debug logging (turns itself off after 30 minutes)", defaultValue: false
        }
    }
}

private Map pickerOptions(Map found) {
    found.collectEntries { id, d -> [(id): "${d.name} (${d.sku})".toString()] }.sort { it.value }
}

private String connectionLine() {
    def conn = getConnection()
    if (!conn) return "The live connection starts when you press Done."
    String c = conn.currentValue("connection") ?: "unknown"
    return "Live connection: ${c}."
}

void appButtonHandler(String btn) {
    // Buttons run before the page's typed text is saved, so the page acts on them.
    switch (btn) {
        case "btnSignIn":
        case "btnCode":
            state.pendingButton = btn
            break
        case "btnCancel":
            state.login = null
            app.removeSetting("verifyCode")
            break
        case "btnSignOut":
            signOut()
            break
        case "btnFind":
            state.pendingButton = btn
            break
    }
}

private void runPendingButton() {
    String btn = state.pendingButton
    if (!btn) return
    state.remove("pendingButton")
    if (btn == "btnSignIn") startSignIn(null)
    else if (btn == "btnCode") submitCode()
    else if (btn == "btnFind") discover()
}

private void expireLogin() {
    Map lg = state.login
    if (lg?.step == "working" && now() - ((lg.at ?: 0) as long) > LOGIN_STALE_MS) {
        lg.step = lg.withCode ? "code" : null
        lg.error = "Govee didn't answer. Try again."
        state.login = lg
    }
}

boolean discovering() {
    return state.iot && now() < ((state.discoverUntil ?: 0) as long)
}

// ---------------------------------------------------------------- lifecycle

def installed() {
    initialize()
}

def updated() {
    initialize()
}

def uninstalled() {
    unschedule()
    getChildDevices().each { deleteChildDevice(it.deviceNetworkId) }
}

void initialize() {
    unschedule()
    syncChildren()
    if (logEnable) runIn(1800, "logsOff")
    if (!getGoveeDevices()) {
        getConnection()?.disconnectMqtt()
        return
    }
    getConnection()?.connectMqtt()
    int minutes = ((settings.pollMinutes ?: "5") as int)
    schedule("0 */${minutes} * ? * *", "pollQuiet")
    runEvery1Hour("dailyDiscovery")
    runIn(5, "pollQuiet")
}

void logsOff() {
    app.updateSetting("logEnable", [value: "false", type: "bool"])
}

private void syncChildren() {
    List selected = settings.selectedDevices ?: []
    Map found = state.discovered ?: [:]
    selected.each { String id ->
        if (getChildDevice(deviceDni(id))) return
        Map d = found[id]
        if (!d) return
        String driver = KIND_DRIVER[d.kind]
        try {
            addChildDevice("govee", driver, deviceDni(id), [name: "Govee ${d.sku}", label: d.name, isComponent: false,
                data: [id: id, sku: d.sku, kind: d.kind] + (d.caps ? [caps: JsonOutput.toJson(d.caps)] : [:])])
            log.info "Govee: added ${d.name} (${d.sku})"
        } catch (e) {
            log.error "Govee: could not add ${d.name}: ${e.message} (is the '${driver}' driver installed?)"
        }
    }
    getGoveeDevices().each { cd ->
        if (!(cd.getDataValue("id") in selected)) {
            log.info "Govee: removing ${cd.displayName}"
            deleteChildDevice(cd.deviceNetworkId)
        }
    }
    if (getGoveeDevices() && !getConnection()) {
        try {
            addChildDevice("govee", "Govee Cloud Connection", connDni(), [name: "Govee Cloud Connection", isComponent: false])
        } catch (e) {
            log.error "Govee: could not add the cloud connection device: ${e.message} (is the 'Govee Cloud Connection' driver installed?)"
        }
    }
}

String deviceDni(String id) { "govee-" + id.replaceAll(/[^0-9A-Fa-f]/, "").toUpperCase() }
private String connDni() { "govee-connection-${app.id}" }
private def getConnection() { getChildDevice(connDni()) }
private List getGoveeDevices() { getChildDevices().findAll { it.deviceNetworkId != connDni() } }
private def deviceById(String id) { id ? getChildDevice(deviceDni(id)) : null }

// ---------------------------------------------------------------- sign-in

/** Stable per account, so Govee sees the same "phone" every time: uuid5(DNS, "hubitat-govee:" + email), as hex. */
String clientIdFor(String email) {
    String name = "hubitat-govee:" + (email ?: "").trim().toLowerCase()
    MessageDigest md = MessageDigest.getInstance("SHA-1")
    md.update("6ba7b8109dad11d180b400c04fd430c8".decodeHex())
    byte[] h = md.digest(name.getBytes("UTF-8"))
    h[6] = (byte) ((h[6] & 0x0f) | 0x50)
    h[8] = (byte) ((h[8] & 0x3f) | 0x80)
    byte[] u = new byte[16]
    for (int i = 0; i < 16; i++) u[i] = h[i]
    return u.encodeHex().toString()
}

private void startSignIn(String code) {
    String email = (settings.email ?: "").trim()
    if (!email || (!settings.password && !state.pendingPassword)) {
        state.login = [error: "Enter your Govee email and password."]
        return
    }
    // The password stays only for the code step, then it's dropped.
    if (settings.password) {
        state.pendingPassword = settings.password
        app.removeSetting("password")
    }
    state.clientId = clientIdFor(email)
    Map body = [email: email, password: state.pendingPassword, client: state.clientId, clientType: "1"]
    if (code) body.code = code
    state.login = [step: "working", at: now(), withCode: code != null]
    asynchttpPost("loginResp", app2Request("/account/rest/account/v2/login", body), [withCode: code != null])
}

private void submitCode() {
    String code = (settings.verifyCode ?: "").trim()
    app.removeSetting("verifyCode")
    if (!(code ==~ /\d{4,8}/)) {
        state.login = [step: "code", error: "Enter the digits from Govee's email."]
        return
    }
    startSignIn(code)
}

void loginResp(resp, Map data) {
    Map lg = state.login ?: [:]
    int http = statusOf(resp)
    Map json = parseJson(bodyOf(resp))
    def status = json?.status
    logDebug "Sign-in: HTTP ${http}, status ${status}, ${json?.message}"
    if (http == 200 && status == 200 && json?.client?.token) {
        Map c = json.client
        state.remove("pendingPassword")
        state.token = c.token
        state.login = null
        state.remove("needsSignIn")
        state.iot = [accountId: c.accountId?.toString(), accountTopic: c.topic, clientId: state.clientId]
        log.info "Govee: signed in"
        asynchttpGet("iotKeyResp", app2Request("/app/v1/account/iot/key", null, c.token as String))
        discover()
    } else if (status == 454) {
        if (data?.withCode) {
            state.login = [step: "code", error: "Govee didn't accept that code. Check it, or start over for a new one."]
        } else {
            state.login = [step: "working", at: now(), withCode: false]
            asynchttpPost("codeResp", app2Request("/account/rest/account/v1/verification", [type: 8, email: settings.email.trim()]))
        }
    } else if (http == 401 || status == 401) {
        state.remove("pendingPassword")
        state.login = [error: "Govee didn't accept that email and password."]
    } else {
        state.remove("pendingPassword")
        state.login = [error: "Sign-in failed (HTTP ${http}, status ${status}${json?.message ? ': ' + json.message : ''})."]
    }
}

void codeResp(resp, Map data) {
    int http = statusOf(resp)
    if (http == 200) {
        state.login = [step: "code"]
    } else {
        state.remove("pendingPassword")
        state.login = [error: "Govee couldn't send a verification code (HTTP ${http}). Try again later."]
    }
}

void iotKeyResp(resp, Map data) {
    int http = statusOf(resp)
    Map json = parseJson(bodyOf(resp))
    Map d = (json?.data instanceof Map) ? json.data : [:]
    if (http != 200 || !(d.p12 || d.certificatePem)) {
        log.error "Govee: fetching the device certificate failed (HTTP ${http}, ${json?.message})"
        state.lastError = "Govee didn't send the device certificate (HTTP ${http})"
        return
    }
    Map iot = state.iot ?: [:]
    iot.endpoint = d.endpoint ?: DEFAULT_ENDPOINT
    iot.p12 = d.p12
    iot.p12Pass = d.p12Pass ?: d.p12_pass ?: ""
    iot.certPem = d.certificatePem
    iot.keyPem = d.privateKey
    state.iot = iot
    logDebug "Certificate received for ${iot.endpoint}"
    getConnection()?.connectMqtt()
}

private void signOut() {
    getConnection()?.disconnectMqtt()
    ["token", "iot", "login", "clientId", "pendingPassword", "needsSignIn", "discovered", "unsupported", "discoverUntil"].each { state.remove(it) }
    log.info "Govee: signed out"
}

/** What the connection driver needs to open the MQTT session. */
Map iotCredentials() {
    Map iot = state.iot
    if (!iot?.accountTopic || !(iot.p12 || iot.certPem)) return null
    return [endpoint: iot.endpoint ?: DEFAULT_ENDPOINT, clientId: iot.accountId ? "AP/${iot.accountId}/${iot.clientId}" : iot.clientId,
            accountTopic: iot.accountTopic, p12: iot.p12, p12Pass: iot.p12Pass, certPem: iot.certPem, keyPem: iot.keyPem]
}

/** Device topics for the devices in Hubitat, for status queries. */
Map iotTopics() {
    Map found = state.discovered ?: [:]
    Map out = [:]
    getGoveeDevices().each { cd ->
        String id = cd.getDataValue("id")
        String topic = found[id]?.topic
        if (topic) out[id] = topic
    }
    return out
}

// ---------------------------------------------------------------- discovery

void dailyDiscovery() {
    if (state.token && now() - ((state.discoveredAt ?: 0) as long) > DISCOVERY_MAX_AGE_MS) discover()
}

private void discover() {
    if (!state.token) return
    state.discoverUntil = now() + 30000
    state.remove("lastError")
    asynchttpPost("accountDevicesResp", app2Request("/device/rest/devices/v1/list", [:], state.token as String))
}

void accountDevicesResp(resp, Map data) {
    try {
        accountDevices(resp)
    } catch (e) {
        discoveryFailed("reading Govee's device list failed: ${e}")
    }
}

private void accountDevices(resp) {
    int http = statusOf(resp)
    Map json = parseJson(bodyOf(resp))
    List devices = (json?.devices instanceof List) ? json.devices : null
    if (http == 401 || json?.status == 401 || (http == 200 && devices == null)) {
        // An expired token comes back as HTTP 200 with no list. The certificate still works.
        state.needsSignIn = true
        state.remove("discoverUntil")
        state.lastError = "Govee's sign-in has expired"
        log.warn "Govee: the sign-in token has expired; devices keep working, sign in again to pick up new ones"
        return
    }
    if (http != 200) {
        discoveryFailed("Govee's device list failed (HTTP ${http})")
        return
    }
    Map found = [:]
    devices.each { Map dv ->
        Map ext = jsonish(dv.deviceExt)
        Map ds = jsonish(ext.deviceSettings)
        if (!dv.device) return
        found[dv.device] = [sku: dv.sku, name: dv.deviceName ?: dv.sku, topic: ds.topic, kind: guessKind(dv.sku as String)]
    }
    log.info "Govee: the account lists ${found.size()} device(s)"
    state.accountDevices = found
    state.discoveredAt = now()
    if (settings.apiKey) {
        apiRequest("GET", "/user/devices", null, "apiDevicesResp")
    } else {
        finishDiscovery([:])
    }
}

void apiDevicesResp(resp, Map data) {
    try {
        apiDevices(resp)
    } catch (e) {
        discoveryFailed("reading the API device list failed: ${e}")
    }
}

private void apiDevices(resp) {
    Map json = apiJson(resp)
    Map caps = [:]
    if (json?.data instanceof List) {
        json.data.each { Map d -> if (d.device) caps[d.device] = d }
        log.info "Govee: the API lists ${caps.size()} device(s)"
    } else {
        log.warn "Govee: the API device list failed (HTTP ${statusOf(resp)}${json?.message ? ', ' + json.message : ''}); fan controls need it"
    }
    finishDiscovery(caps)
}

private void discoveryFailed(String why) {
    log.error "Govee: ${why}"
    state.lastError = why
    state.remove("discoverUntil")
    state.remove("accountDevices")
}

private void finishDiscovery(Map apiDevices) {
    Map found = [:]
    List unsupported = []
    (state.accountDevices ?: [:]).each { String id, Map d ->
        Map api = apiDevices[id]
        String kind = api ? (TYPE_KIND[api.type] ?: null) : d.kind
        if (!kind) {
            unsupported << "${d.name} (${d.sku})"
            return
        }
        Map entry = d + [kind: kind]
        if (api) entry.caps = capabilitySummary(api.capabilities ?: [])
        found[id] = entry
    }
    state.discovered = found
    state.unsupported = unsupported
    state.remove("accountDevices")
    state.remove("discoverUntil")
    log.info "Govee: found ${found.size()} device(s)${unsupported ? ', ' + unsupported.size() + ' not supported yet' : ''}"
    // Keep the children's data in step with what Govee reports now.
    getGoveeDevices().each { cd ->
        Map d = found[cd.getDataValue("id")]
        if (d?.caps) cd.updateDataValue("caps", JsonOutput.toJson(d.caps))
    }
}

/** Light or fan from the SKU, when there's no API key to ask: fans are H710x/H711x, other H7xxx are appliances, H5xxx sensors. */
String guessKind(String sku) {
    if (!sku) return null
    if (sku ==~ /(?i)H71[0-1]\w*/) return "fan"
    if (sku ==~ /(?i)H[57]\w*/) return null
    return "light"
}

/**
 * The parts of a Platform API capability list the drivers need: ranges,
 * fan speeds and modes, and which instances exist.
 */
Map capabilitySummary(List capabilities) {
    Map out = [instances: capabilities.collect { it.instance }.findAll { it }]
    capabilities.each { Map c ->
        Map p = c.parameters ?: [:]
        switch (c.instance) {
            case "brightness":
                if (p.range) out.brightness = [p.range.min ?: 0, p.range.max ?: 100]
                break
            case "colorTemperatureK":
                if (p.range) out.ct = [p.range.min ?: CT_RANGE[0], p.range.max ?: CT_RANGE[1]]
                break
            case "workMode":
                // Hubitat's sandbox refuses a property called "fields" (p.fields, p["fields"]), even on a Map.
                Map fields = ((p.get("fields") ?: []) as List).collectEntries { [(it.fieldName): it] }
                List modes = (fields.workMode?.options ?: []).collect { [it.name, it.value] }
                out.modes = modes
                // The speed mode is the one whose modeValue lists its own options.
                def speedOpt = (fields.modeValue?.options ?: []).find { it.options }
                if (speedOpt) {
                    def m = modes.find { it[0] == speedOpt.name }
                    if (m) {
                        out.speedMode = m[1]
                        out.speeds = speedOpt.options.collect { it.value }.findAll { it != null }.sort()
                    }
                }
                break
        }
    }
    return out
}

private Map jsonish(def v) {
    if (v instanceof Map) return v
    if (v instanceof String && v) {
        try {
            def o = new groovy.json.JsonSlurper().parseText(v)
            return o instanceof Map ? o : [:]
        } catch (e) { }
    }
    return [:]
}

// ---------------------------------------------------------------- MQTT messages

/** A message from the account topic, passed on by the connection driver. */
void iotMessage(Map msg) {
    String id = msg.device
    def cd = deviceById(id)
    if (!cd) return
    Map s = normalizeIot(msg.state instanceof Map ? msg.state : [:])
    if (!s) {
        cd.goveeSeen("iot")
        return
    }
    cd.goveeUpdate(s, "iot")
    // Fans report only power over MQTT: read speed and mode once power changes.
    if (cd.getDataValue("kind") == "fan" && s.containsKey("onOff") && settings.apiKey) runIn(2, "refreshDevice", [data: [id: id], overwrite: false])
}

/** MQTT state -> the app's state keys. */
Map normalizeIot(Map st) {
    Map out = [:]
    if (st.onOff != null) out.onOff = (st.onOff as int)
    if (st.brightness != null) out.brightness = (st.brightness as int)
    if (st.color instanceof Map) out.color = [r: (st.color.r ?: 0) as int, g: (st.color.g ?: 0) as int, b: (st.color.b ?: 0) as int]
    if (st.colorTemInKelvin != null) out.ct = (st.colorTemInKelvin as int)
    if (st.mode != null) out.mode = st.mode
    if (st.connected != null) out.online = st.connected.toString() == "true"
    return out
}

/** Platform API state capabilities -> the app's state keys. */
Map normalizeApi(List caps) {
    Map out = [:]
    caps.each { Map c ->
        def v = c.state?.value
        if (v == null || v == "") return
        switch (c.instance) {
            case "online": out.online = v.toString() == "true"; break
            case "powerSwitch": out.onOff = v as int; break
            case "brightness": out.brightness = v as int; break
            case "colorRgb":
                int rgb = v as int
                out.color = [r: (rgb >> 16) & 0xff, g: (rgb >> 8) & 0xff, b: rgb & 0xff]
                break
            case "colorTemperatureK": out.ct = v as int; break
            case "oscillationToggle": out.oscillation = v as int; break
            case "workMode":
                if (v instanceof Map) {
                    out.workMode = v.workMode
                    out.modeValue = v.modeValue
                }
                break
        }
    }
    return out
}

// ---------------------------------------------------------------- mapping: state -> events

/** Events for a device's merged state. */
List goveeEvents(def cd, Map s, Map opts = [:]) {
    String kind = cd.getDataValue("kind")
    Map caps = capsOf(cd)
    List ev = []
    if (s.onOff != null) ev << [name: "switch", value: s.onOff ? "on" : "off"]
    if (s.online != null) ev << [name: "healthStatus", value: s.online ? "online" : "offline"]
    if (kind == "light") ev.addAll(lightEvents(s, caps))
    if (kind == "fan") ev.addAll(fanEvents(s, caps))
    return ev
}

private List lightEvents(Map s, Map caps) {
    List ev = []
    if (s.brightness != null) ev << [name: "level", value: Math.max(0, Math.min(100, s.brightness as int)), unit: "%"]
    int ct = (s.ct ?: 0) as int
    Map c = s.color
    if (ct > 0) {
        ev << [name: "colorMode", value: "CT"]
        ev << [name: "colorTemperature", value: ct, unit: "K"]
        ev << [name: "colorName", value: ctName(ct)]
    } else if (c && (c.r || c.g || c.b)) {
        Map hsv = rgbToHsv(c.r as int, c.g as int, c.b as int)
        ev << [name: "colorMode", value: "RGB"]
        ev << [name: "hue", value: hsv.hue]
        ev << [name: "saturation", value: hsv.saturation, unit: "%"]
        ev << [name: "color", value: rgbHex(c.r as int, c.g as int, c.b as int)]
        ev << [name: "colorName", value: hueName(hsv.hue as int, hsv.saturation as int)]
    }
    if (s.mode != null) ev << [name: "goveeMode", value: s.mode.toString()]
    return ev
}

private List fanEvents(Map s, Map caps) {
    List ev = []
    List speeds = caps.speeds ?: []
    if (speeds) {
        ev << [name: "maxSpeedLevel", value: speeds.size()]
        ev << [name: "supportedFanSpeeds", value: JsonOutput.toJson(speedNamesFor(speeds.size()) + ["off", "on"])]
    }
    List modes = caps.modes ?: []
    if (modes) ev << [name: "supportedModes", value: JsonOutput.toJson(modes.collect { it[0] })]
    if (s.workMode != null) {
        def m = modes.find { it[1] == s.workMode }
        ev << [name: "mode", value: m ? m[0] : s.workMode.toString()]
    }
    if (s.onOff == 0) {
        ev << [name: "speed", value: "off"]
    } else if (s.workMode != null && caps.speedMode != null && s.workMode == caps.speedMode && speeds) {
        int idx = speeds.indexOf(s.modeValue)
        if (idx >= 0) {
            ev << [name: "speedLevel", value: idx + 1]
            ev << [name: "level", value: levelForStep(idx + 1, speeds.size()), unit: "%"]
            ev << [name: "speed", value: speedNameFor(idx + 1, speeds.size())]
        }
    } else if (s.workMode != null && s.onOff == 1) {
        def m = modes.find { it[1] == s.workMode }
        if (m && m[0].toString().toLowerCase() == "auto") ev << [name: "speed", value: "auto"]
    }
    if (s.oscillation != null) ev << [name: "oscillation", value: s.oscillation ? "on" : "off"]
    return ev
}

// ---------------------------------------------------------------- mapping: commands

/**
 * A driver command -> what to send. Returns [send: [...], optimistic: Map].
 * Each send item is [via: "iot", cmd:, data:] or [via: "api", type:, instance:, value:],
 * listed in the order they should go out.
 */
Map planCommand(def cd, Map s, String cmd, List args) {
    String kind = cd.getDataValue("kind")
    Map caps = capsOf(cd)
    List send = []
    Map opt = [:]
    switch (cmd) {
        case "on":
        case "off":
            int v = cmd == "on" ? 1 : 0
            send << power(v)
            opt.onOff = v
            break
        case "setLevel":
            int level = clamp(args[0] as int, 0, 100)
            if (kind == "fan") {
                int n = (caps.speeds ?: []).size()
                if (level > 0 && !n) return [send: [], optimistic: [:], error: "fan speeds need the Govee API key"]
                return planFanStep(caps, s, level == 0 ? 0 : stepForLevel(level, n))
            }
            if (level == 0) {
                send << power(0)
                opt.onOff = 0
            } else {
                send << [via: "iot", cmd: "brightness", data: [val: level], api: [type: "devices.capabilities.range", instance: "brightness", value: level]]
                opt.brightness = level
                if (s.onOff != 1) {
                    send << power(1)
                    opt.onOff = 1
                }
            }
            break
        case "setColor":
            Map hsl = args[0] as Map
            Map rgb = hsvToRgb((hsl.hue ?: 0) as double, (hsl.saturation ?: 0) as double)
            send << colorSend(rgb)
            opt.color = rgb
            opt.ct = 0
            if (hsl.level != null) {
                int level = clamp(hsl.level as int, 1, 100)
                send << [via: "iot", cmd: "brightness", data: [val: level], api: [type: "devices.capabilities.range", instance: "brightness", value: level]]
                opt.brightness = level
            }
            if (s.onOff != 1) { send << power(1); opt.onOff = 1 }
            break
        case "setHue":
        case "setSaturation":
            Map hsv = s.color && !(s.ct ?: 0) ? rgbToHsv(s.color.r as int, s.color.g as int, s.color.b as int) : [hue: 0, saturation: 100]
            double hue = cmd == "setHue" ? (args[0] as double) : (hsv.hue as double)
            double sat = cmd == "setSaturation" ? (args[0] as double) : (hsv.saturation as double)
            Map rgb = hsvToRgb(hue, sat)
            send << colorSend(rgb)
            opt.color = rgb
            opt.ct = 0
            if (s.onOff != 1) { send << power(1); opt.onOff = 1 }
            break
        case "setColorTemperature":
            List range = caps.ct ?: CT_RANGE
            int k = clamp(args[0] as int, range[0] as int, range[1] as int)
            send << [via: "iot", cmd: "colorwc", data: [color: [r: 0, g: 0, b: 0], colorTemInKelvin: k],
                     api: [type: "devices.capabilities.color_setting", instance: "colorTemperatureK", value: k]]
            opt.ct = k
            if (args.size() > 1 && args[1] != null) {
                int level = clamp(args[1] as int, 1, 100)
                send << [via: "iot", cmd: "brightness", data: [val: level], api: [type: "devices.capabilities.range", instance: "brightness", value: level]]
                opt.brightness = level
            }
            if (s.onOff != 1) { send << power(1); opt.onOff = 1 }
            break
        case "setSpeed":
            String name = args[0] as String
            if (name == "off") return planCommand(cd, s, "off", [])
            if (name == "on") return planCommand(cd, s, "on", [])
            if (name == "auto") return planFanMode(caps, "auto")
            int n = (caps.speeds ?: []).size()
            if (!n || caps.speedMode == null) return [send: [], optimistic: [:], error: "fan speeds need the Govee API key"]
            int step = stepForSpeedName(name, n)
            if (step < 1) return [send: [], optimistic: [:], error: "unknown fan speed '${name}'"]
            return planFanStep(caps, s, step)
        case "cycleSpeed":
            int n = (caps.speeds ?: []).size()
            int cur = s.workMode == caps.speedMode ? ((caps.speeds ?: []).indexOf(s.modeValue) + 1) : 0
            return planFanStep(caps, s, n ? (cur % n) + 1 : 0)
        case "setSpeedLevel":
            return planFanStep(caps, s, args[0] as int)
        case "setMode":
            return planFanMode(caps, args[0] as String)
        case "setOscillation":
            int v = args[0].toString() == "on" ? 1 : 0
            send << [via: "api", api: [type: "devices.capabilities.toggle", instance: "oscillationToggle", value: v]]
            opt.oscillation = v
            break
        default:
            return [send: [], optimistic: [:], error: "unknown command ${cmd}"]
    }
    return [send: send, optimistic: opt]
}

private Map power(int v) {
    [via: "iot", cmd: "turn", data: [val: v], api: [type: "devices.capabilities.on_off", instance: "powerSwitch", value: v]]
}

private Map colorSend(Map rgb) {
    [via: "iot", cmd: "colorwc", data: [color: rgb, colorTemInKelvin: 0],
     api: [type: "devices.capabilities.color_setting", instance: "colorRgb", value: ((rgb.r as int) << 16) + ((rgb.g as int) << 8) + (rgb.b as int)]]
}

private Map planFanStep(Map caps, Map s, int step) {
    List speeds = caps.speeds ?: []
    if (step <= 0) return [send: [power(0)], optimistic: [onOff: 0]]
    if (!speeds || caps.speedMode == null) return [send: [], optimistic: [:], error: "fan speeds need the Govee API key"]
    step = clamp(step, 1, speeds.size())
    Map send = [via: "api", api: [type: "devices.capabilities.work_mode", instance: "workMode", value: [workMode: caps.speedMode, modeValue: speeds[step - 1]]]]
    return [send: [send], optimistic: [onOff: 1, workMode: caps.speedMode, modeValue: speeds[step - 1]]]
}

private Map planFanMode(Map caps, String name) {
    def m = (caps.modes ?: []).find { it[0].toString().equalsIgnoreCase(name) }
    if (!m) return [send: [], optimistic: [:], error: "this fan has no mode '${name}'"]
    Map send = [via: "api", api: [type: "devices.capabilities.work_mode", instance: "workMode", value: [workMode: m[1], modeValue: 0]]]
    return [send: [send], optimistic: [onOff: 1, workMode: m[1], modeValue: 0]]
}

/** Called by a light or fan driver. Sends the command and returns the optimistic state. */
Map goveeCommand(def cd, Map s, String cmd, List args) {
    Map plan = planCommand(cd, s ?: [:], cmd, args ?: [])
    if (plan.error) {
        log.warn "Govee: ${cd.displayName}: ${plan.error}"
        return [:]
    }
    String id = cd.getDataValue("id")
    String sku = cd.getDataValue("sku")
    def conn = getConnection()
    String topic = (state.discovered ?: [:])[id]?.topic
    boolean live = conn && topic && conn.currentValue("connection") == "connected"
    plan.send.each { Map item ->
        if (item.via == "iot" && live) {
            conn.publishCommand(id, topic, item.cmd as String, item.data as Map, item.api as Map)
        } else if (item.api) {
            if (item.via == "iot") logDebug "${cd.displayName}: no live connection, sending ${item.cmd} over the API"
            sendApi(id, sku, item.api as Map)
        }
    }
    return plan.optimistic
}

/** The connection driver gives up on an MQTT command with no reply: send it over the API. */
void iotUnconfirmed(String id, Map api) {
    def cd = deviceById(id)
    if (!cd || !api) return
    if (!settings.apiKey) {
        log.warn "Govee: ${cd.displayName} didn't confirm a command, and there's no API key to retry it with"
        return
    }
    log.info "Govee: ${cd.displayName} didn't confirm ${api.instance} over the live connection; sending it over the API"
    sendApi(id, cd.getDataValue("sku"), api)
}

// ---------------------------------------------------------------- Platform API

private void sendApi(String id, String sku, Map cap) {
    if (!settings.apiKey) {
        log.warn "Govee: ${cap.instance} needs the Govee API key (or the live connection)"
        return
    }
    Map body = [requestId: "hubitat-${now()}", payload: [sku: sku, device: id, capability: cap]]
    apiRequest("POST", "/device/control", body, "apiControlResp", [id: id, instance: cap.instance])
}

void apiControlResp(resp, Map data) {
    Map json = apiJson(resp)
    def cd = deviceById(data?.id as String)
    if (statusOf(resp) != 200 || (json?.code && json.code != 200)) {
        log.warn "Govee: ${cd?.displayName ?: data?.id}: ${data?.instance} failed (HTTP ${statusOf(resp)}${json?.msg ? ', ' + json.msg : ''})"
        return
    }
    cd?.goveeSeen("api")
}

/** Read the state of quiet devices: those with no MQTT message lately, and fans (MQTT gives only their power). */
void pollQuiet() {
    if (!settings.apiKey) return
    long cutoff = now() - QUIET_MS
    getGoveeDevices().each { cd ->
        String lastText = cd.getDataValue("lastIot")
        Long last = lastText ? (lastText as Long) : null
        boolean quiet = !last || last < cutoff
        if (quiet || cd.getDataValue("kind") == "fan") refreshDevice([id: cd.getDataValue("id")])
    }
}

void refreshDevice(Map data) {
    def cd = deviceById(data?.id as String)
    if (!cd || !settings.apiKey) return
    Map body = [requestId: "hubitat-${now()}", payload: [sku: cd.getDataValue("sku"), device: data.id]]
    apiRequest("POST", "/device/state", body, "apiStateResp", [id: data.id])
}

/** Called by a driver's refresh(): ask over MQTT and, with an API key, read it from the API too. */
void refreshFromDriver(def cd) {
    String id = cd.getDataValue("id")
    String topic = (state.discovered ?: [:])[id]?.topic
    getConnection()?.queryStatus(topic ? [(id): topic] : [:])
    refreshDevice([id: id])
}

void apiStateResp(resp, Map data) {
    Map json = apiJson(resp)
    def cd = deviceById(data?.id as String)
    if (!cd) return
    List caps = json?.payload?.capabilities
    if (statusOf(resp) != 200 || !(caps instanceof List)) {
        logDebug "${cd.displayName}: state read failed (HTTP ${statusOf(resp)})"
        return
    }
    cd.goveeUpdate(normalizeApi(caps), "api")
}

private void apiRequest(String method, String path, Map body, String callback, Map cbData = [:]) {
    Map params = [uri: OPENAPI + path, headers: ["Govee-API-Key": settings.apiKey, "Accept": "application/json"],
                  timeout: TIMEOUT, contentType: "application/json", textParser: true]
    if (body != null) {
        params.requestContentType = "application/json"
        params.body = JsonOutput.toJson(body)
    }
    logDebug "${method} ${path}"
    if (method == "POST") asynchttpPost(callback, params, cbData)
    else asynchttpGet(callback, params, cbData)
}

private Map apiJson(resp) {
    noteRateLimits(resp)
    Map json = parseJson(bodyOf(resp))
    if (statusOf(resp) == 429) log.warn "Govee: the API rate limit was reached; requests resume shortly"
    if (statusOf(resp) == 401) log.warn "Govee: the API key was rejected"
    return json
}

private void noteRateLimits(resp) {
    Map h = [:]
    try { h = resp.headers ?: [:] } catch (e) { }
    def day = h.find { k, v -> k?.toString()?.equalsIgnoreCase("X-RateLimit-Remaining") }?.value
    def minute = h.find { k, v -> k?.toString()?.equalsIgnoreCase("API-RateLimit-Remaining") }?.value
    if (day != null || minute != null) state.rate = [day: day, minute: minute]
}

// ---------------------------------------------------------------- app2 requests

private Map app2Request(String path, Map body, String token = null) {
    Map headers = ["Accept": "application/json", "appVersion": APP_VERSION, "clientId": state.clientId ?: clientIdFor(settings.email as String),
                   "clientType": "1", "iotVersion": "0", "timestamp": now().toString(), "User-Agent": USER_AGENT]
    if (token) headers["Authorization"] = "Bearer ${token}"
    Map p = [uri: APP2 + path, headers: headers, timeout: TIMEOUT, contentType: "application/json", textParser: true]
    if (body != null) {
        p.requestContentType = "application/json"
        p.body = JsonOutput.toJson(body)
    }
    return p
}

// ---------------------------------------------------------------- pure helpers

Map capsOf(def cd) {
    String s = cd.getDataValue("caps")
    if (!s) return [:]
    try {
        def o = new groovy.json.JsonSlurper().parseText(s)
        return o instanceof Map ? o : [:]
    } catch (e) {
        return [:]
    }
}

List speedNamesFor(int n) {
    if (n <= 0) return []
    if (n < 5) return FEW_SPEEDS[n]
    return SPEED_NAMES
}

/** Hubitat's speed name for fan step `step` of `n`. */
String speedNameFor(int step, int n) {
    List names = speedNamesFor(n)
    if (!names) return "on"
    if (n <= 5) return names[step - 1]
    return names[(int) Math.round((step - 1) * 4d / (n - 1))]
}

/** Fan step (1..n) for a Hubitat speed name, or 0 if the name isn't one. */
int stepForSpeedName(String name, int n) {
    if (n <= 5) return speedNamesFor(n).indexOf(name) + 1
    int idx = SPEED_NAMES.indexOf(name)
    return idx < 0 ? 0 : (int) Math.round(idx * (n - 1) / 4d) + 1
}

int levelForStep(int step, int n) { n ? (int) Math.round(step * 100d / n) : 0 }

int stepForLevel(int level, int n) { n ? clamp((int) Math.ceil(level * n / 100d), 1, n) : 0 }

int clamp(int v, int lo, int hi) { Math.max(lo, Math.min(hi, v)) }

/** Hubitat hue/saturation (0-100) from RGB. */
Map rgbToHsv(int r, int g, int b) {
    double rf = r / 255d, gf = g / 255d, bf = b / 255d
    double max = Math.max(rf, Math.max(gf, bf)), min = Math.min(rf, Math.min(gf, bf))
    double d = max - min
    double h = 0
    if (d > 0) {
        if (max == rf) h = ((gf - bf) / d) % 6
        else if (max == gf) h = (bf - rf) / d + 2
        else h = (rf - gf) / d + 4
        h *= 60
        if (h < 0) h += 360
    }
    double s = max == 0 ? 0 : d / max
    return [hue: (int) Math.round(h / 3.6d), saturation: (int) Math.round(s * 100d), level: (int) Math.round(max * 100d)]
}

/** RGB at full value from Hubitat hue/saturation (0-100). */
Map hsvToRgb(double hue, double sat) {
    double h = ((hue % 100) + 100) % 100 * 3.6d
    double s = Math.max(0d, Math.min(100d, sat)) / 100d
    double c = s
    double x = c * (1 - Math.abs((h / 60d) % 2 - 1))
    double m = 1 - c
    double r = 0, g = 0, b = 0
    if (h < 60) { r = c; g = x } else if (h < 120) { r = x; g = c } else if (h < 180) { g = c; b = x }
    else if (h < 240) { g = x; b = c } else if (h < 300) { r = x; b = c } else { r = c; b = x }
    return [r: (int) Math.round((r + m) * 255d), g: (int) Math.round((g + m) * 255d), b: (int) Math.round((b + m) * 255d)]
}

String rgbHex(int r, int g, int b) {
    String hexd = "0123456789ABCDEF"
    StringBuilder sb = new StringBuilder("#")
    [r, g, b].each { int v -> sb.append(hexd.charAt((v >> 4) & 0xf)).append(hexd.charAt(v & 0xf)) }
    return sb.toString()
}

String hueName(int hue, int sat) {
    if (sat < 15) return "White"
    int deg = (int) Math.round(hue * 3.6d)
    List names = [[15, "Red"], [45, "Orange"], [75, "Yellow"], [105, "Chartreuse"], [135, "Green"], [165, "Spring"], [195, "Cyan"],
                  [225, "Azure"], [255, "Blue"], [285, "Violet"], [315, "Magenta"], [345, "Rose"], [361, "Red"]]
    return names.find { deg < (it[0] as int) }[1]
}

String ctName(int k) {
    if (k < 2600) return "Incandescent"
    if (k < 3500) return "Warm White"
    if (k < 4500) return "Neutral White"
    if (k < 5500) return "Cool White"
    if (k < 6500) return "Daylight"
    return "Skylight"
}

// ---------------------------------------------------------------- misc

private int statusOf(resp) {
    try { return (resp.status ?: 0) as int } catch (e) { return 0 }
}

private String bodyOf(resp) {
    String b = null
    try { b = resp.data?.toString() } catch (e) { }
    if (b == null) {
        try { b = resp.errorData?.toString() } catch (e) { }
    }
    return b ?: ""
}

private Map parseJson(String s) {
    if (!s) return null
    try {
        def o = new groovy.json.JsonSlurper().parseText(s)
        return o instanceof Map ? o : null
    } catch (e) {
        return null
    }
}

private void logDebug(String msg) {
    if (logEnable) log.debug "Govee: ${msg}"
}
