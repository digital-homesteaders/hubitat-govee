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
 * wez/govee2mqtt (both MIT License; see NOTICE, also at
 * https://github.com/digital-homesteaders/hubitat-govee/blob/main/NOTICE).
 *
 * Copyright (c) 2026 Digital Homesteaders. MIT License.
 */

import groovy.json.JsonOutput
import groovy.transform.Field
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

definition(
    name: "Govee Connect",
    namespace: "govee",
    author: "Digital Homesteaders",
    description: "Govee lights and fans with live updates from Govee's cloud.",
    category: "Convenience",
    singleInstance: true,
    iconUrl: "",
    iconX2Url: "",
    importUrl: "https://raw.githubusercontent.com/digital-homesteaders/hubitat-govee/main/apps/govee-connect.groovy"
)

preferences {
    page(name: "mainPage")
}

// ---------------------------------------------------------------- constants

// The interface version: bump it in every file when the calls between the app
// and its drivers change, so a driver left at an older one is reported (it
// would fail quietly otherwise). Changes inside one file don't bump it.
@Field static final String VERSION = "0.4.6"
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
// When each device last reported over MQTT, by device id. Kept in memory, not
// in device data: a write per message is wasteful and races device creation.
// Lost on reboot, which only means one extra API read per device.
@Field static final ConcurrentHashMap<String, Long> LAST_IOT = new ConcurrentHashMap<>()
// Unconfirmed MQTT commands in a row before a device's commands go to the API
// instead (some firmware reports over MQTT but ignores commands there), and
// how long before MQTT is tried again for it.
@Field static final int IOT_MISSES_TO_API = 2
// A device that ignored MQTT commands is retried there weekly (one slow
// press; a single miss after that sends it straight back to the API).
@Field static final long IOT_RETRY_MS = 7 * 24 * 3600 * 1000L
// A LAN address not heard from in a scan for this long isn't used.
@Field static final long LAN_STALE_MS = 16 * 60 * 1000L
// LAN commands a device didn't confirm, in a row, before LAN is paused for it, and for how long.
@Field static final int LAN_MISSES_OFF = 2
@Field static final long LAN_OFF_MS = 3600 * 1000L
// Lights report a model-specific "mode" number over MQTT (H60B0: 5 = colour or white,
// 8 = scene; H6022 and others differ). The app learns each light's numbers: after
// Hubitat sets a colour (plain) or a scene, the next report's mode is that kind.
// MODE_INTENT: device id -> [kind: "plain" | "scene", at: ms].
@Field static final ConcurrentHashMap<String, Map> MODE_INTENT = new ConcurrentHashMap<>()
// A report this soon after the command may still show the old mode; later than the
// max, something else may have changed the light.
@Field static final long MODE_LEARN_MIN_MS = 1000L
@Field static final long MODE_LEARN_MAX_MS = 30 * 1000L
// Ask for a status this long after a colour or scene command, to learn from.
@Field static final int MODE_QUERY_S = 3
// Each light's last reported mode, to notice a light leaving a scene for a mode
// not learned yet (a colour picked in the Govee app, say).
@Field static final ConcurrentHashMap<String, String> LAST_MODE = new ConcurrentHashMap<>()
// The password while Govee's emailed code is awaited, by app id: [pw:, at:]. In
// memory only, never in state, and dropped after PASSWORD_KEEP_MS.
@Field static final ConcurrentHashMap<Long, Map> PENDING_PW = new ConcurrentHashMap<>()
@Field static final long PASSWORD_KEEP_MS = 15 * 60 * 1000L
// After an HTTP 429 the API's reads (polls, refreshes, scene lists) wait, starting
// at a minute and doubling to 30 minutes while it repeats. Commands still go.
// By app id: [until: ms, wait: ms].
@Field static final ConcurrentHashMap<Long, Map> API_BACKOFF = new ConcurrentHashMap<>()
@Field static final long API_BACKOFF_MIN_MS = 60 * 1000L
@Field static final long API_BACKOFF_MAX_MS = 30 * 60 * 1000L
// A light's scene lists that failed to load are tried again after this long.
@Field static final ConcurrentHashMap<String, Long> SCENES_TRIED = new ConcurrentHashMap<>()
@Field static final long SCENES_RETRY_MS = 30 * 60 * 1000L
// Govee's scene library per model (sku -> [p: paramId -> [code, param], s: sceneId -> ...]),
// for running scenes over the LAN. Kept in memory only: fetched again after a
// reboot or code save, the first time a lamp of that model is seen on the LAN.
@Field static final ConcurrentHashMap<String, Map> SCENE_LIB = new ConcurrentHashMap<>()
@Field static final ConcurrentHashMap<String, Long> LIB_FETCHING = new ConcurrentHashMap<>()
// How each model expects a scene's parameter data, from AlgoClaw's decoding of
// the Govee app (github.com/AlgoClaw/Govee, model_specific_parameters.json; facts
// only, re-implemented here). Each rule is [hex to strip from the start of the
// data, hex to put in its place, hex after the scene code]; the first rule whose
// prefix matches is used. Models not listed use the default rule, but only for
// scene types 0-4, the family it's verified on: newer families (e.g. the H60B0's
// type 6) need a rule nobody has published, so those scenes use the API.
@Field static final Map SCENE_RULES = [
    H6022: [["41", "585a", ""], ["00", "04", ""]],
    H6052: [["0111", "07", ""]],
    H6065: [["12000c000f", "04", "0247"], ["1200000000", "04", "0047"]],
    H6066: [["1200000000", "04", ""], ["1d", "", ""]],
    H6092: [["21", "560b", ""]],
    H610A: [],
    H6079: [],
]
@Field static final List DEFAULT_SCENE_RULE = ["", "02", ""]
@Field static final int DEFAULT_RULE_MAX_TYPE = 4
@Field static final Map POLL_CHOICES = ["2": "2 minutes", "5": "5 minutes", "10": "10 minutes", "15": "15 minutes", "30": "30 minutes"]

// Govee device types (Platform API `type`) and the kind of child each becomes.
@Field static final Map TYPE_KIND = ["devices.types.light": "light", "devices.types.fan": "fan"]
@Field static final Map KIND_DRIVER = [light: "Govee Light", fan: "Govee Fan"]

// Colour temperature range when the Platform API doesn't give one.
@Field static final List CT_RANGE = [2000, 9000]
// What to do about mismatched driver code, for HPM and hand-pasted installs alike.
@Field static final String VERSION_FIX = "Installed with Hubitat Package Manager: run its Update. Pasted by hand: paste the app and all three drivers from the same version. Also check each device's Type is the Govee driver it needs, not an old copy."
@Field static final String WRONG_FIX = "Set each device's Type to the driver it needs (Devices > the device > Type), and if a Drivers Code entry holds the other driver's code, paste the right code into it (or run Update in Hubitat Package Manager)."
// Govee fan mode names shown differently in Hubitat (either name is accepted). The
// Platform API calls workMode 7 "Storm"; the Govee Home app shows it as Turbo.
@Field static final Map MODE_LABELS = [FanSpeed: "Fixed speed", Storm: "Turbo"]

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
            input "apiKey", "password", title: "Govee API key", required: false
            input "pollMinutes", "enum", title: "Check devices that haven't sent a live update recently (using the API) every", options: POLL_CHOICES, defaultValue: "5", required: true
            if (state.rate) paragraph "API requests left today: ${state.rate.day ?: '?'}; this minute: ${state.rate.minute ?: '?'}"
        }
        section("Local control") {
            input "lanEnable", "bool", title: "Control lights directly over your home network when they allow it (turn on LAN Control in the Govee Home app: device > settings)", defaultValue: true
            if (settings.lanEnable != false) paragraph lanSummary()
        }
        section("Logging") {
            input "logEnable", "bool", title: "Debug logging for the app (turns itself off after 30 minutes)", defaultValue: false
            paragraph "Govee Connect version ${VERSION}${driverVersionNote()}"
        }
    }
}

/** Drivers whose version differs from the app's (a file wasn't updated), or the wrong driver code. */
private String driverVersionNote() {
    List odd = getChildDevices().findAll { it.getDataValue("driverVersion") && it.getDataValue("driverVersion") != VERSION }
        .collect { "${it.displayName} (${it.getDataValue('driverVersion')})" } + (state.oldDrivers ?: []).collect { "${it} (older)" }
    List wrong = wrongDrivers().collect { "${it.device} runs ${it.runs} but needs ${it.needs}" }
    String note = odd ? ". <b>Drivers from another version:</b> ${odd.join(', ')}. ${VERSION_FIX} Then press Done." : ""
    if (wrong) note += ". <b>Wrong driver code:</b> ${wrong.join('; ')}. ${WRONG_FIX} Then press Done."
    return note
}

/** Devices whose driver code isn't the one their kind needs (a paste into the wrong Drivers Code entry). */
List wrongDrivers() {
    getChildDevices().collect { cd ->
        String runs = cd.getDataValue("driverName")
        String needs = cd.deviceNetworkId == connDni() ? "Govee Cloud Connection" : KIND_DRIVER[cd.getDataValue("kind")]
        (runs && needs && runs != needs) ? [device: cd.displayName, runs: runs, needs: needs] : null
    }.findAll { it }
}

private String lanSummary() {
    List names = getGoveeDevices().findAll { lanUsable(it.getDataValue("id")) }.collect { it.displayName }.sort()
    return names ? "Controlled over your home network: ${names.join(', ')}" : "No lights found on your home network yet (it's checked every 5 minutes)."
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

/** The app's code version; drivers check theirs against it. */
String goveeVersion() { VERSION }

void initialize() {
    unschedule()
    log.info "Govee Connect ${VERSION}"
    syncChildren()
    // Drivers too old to answer are from an earlier version as well.
    List old = []
    getChildDevices().each { cd ->
        try { cd.goveeHello() } catch (e) { old << cd.displayName }
    }
    state.oldDrivers = old
    wrongDrivers().each { log.warn "Govee: ${it.device} runs the ${it.runs} driver code but needs ${it.needs}: ${WRONG_FIX}" }
    if (logEnable) runIn(1800, "logsOff")
    if (!getGoveeDevices()) {
        getConnection()?.disconnectMqtt()
        getConnection()?.lanStop()
        return
    }
    getConnection()?.connectMqtt()
    if (settings.lanEnable != false) getConnection()?.lanStart()
    else getConnection()?.lanStop()
    loadScenes()
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
    // The password stays in memory only for the code step, then it's dropped.
    if (settings.password) {
        PENDING_PW[app.id as Long] = [pw: settings.password, at: now()]
        app.removeSetting("password")
    }
    state.remove("pendingPassword")
    String password = pendingPassword()
    if (!email || !password) {
        state.login = [error: code ? "The sign-in took too long, or the hub restarted. Enter your password again." : "Enter your Govee email and password."]
        return
    }
    state.clientId = clientIdFor(email)
    Map body = [email: email, password: password, client: state.clientId, clientType: "1"]
    if (code) body.code = code
    state.login = [step: "working", at: now(), withCode: code != null]
    asynchttpPost("loginResp", app2Request("/account/rest/account/v2/login", body), [withCode: code != null])
}

/** The password kept for the code step, or null once it has expired. */
private String pendingPassword() {
    Map p = PENDING_PW[app.id as Long]
    if (p && now() - (p.at as long) > PASSWORD_KEEP_MS) {
        dropPassword()
        p = null
    }
    return p?.pw as String
}

private void dropPassword() {
    PENDING_PW.remove(app.id as Long)
    state.remove("pendingPassword")
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
        dropPassword()
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
        dropPassword()
        state.login = [error: "Govee didn't accept that email and password."]
    } else {
        dropPassword()
        state.login = [error: "Sign-in failed (HTTP ${http}, status ${status}${json?.message ? ': ' + json.message : ''})."]
    }
}

void codeResp(resp, Map data) {
    int http = statusOf(resp)
    if (http == 200) {
        state.login = [step: "code"]
    } else {
        dropPassword()
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
    PENDING_PW.remove(app.id as Long)
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
    loadScenes(true)
}

// ---------------------------------------------------------------- scenes

/**
 * Fetch each light's scene and DIY scene lists from the API, for lights
 * that have them. Without `force`, only lights with no list from the last day.
 * The lists live on the light devices, not in the app's state.
 */
void loadScenes(boolean force = false) {
    if (!settings.apiKey || apiPaused()) return
    Map at = state.scenesAt ?: [:]
    getGoveeDevices().findAll { it.getDataValue("kind") == "light" }.each { cd ->
        String id = cd.getDataValue("id")
        List instances = capsOf(cd).instances ?: []
        if (!("lightScene" in instances) && !("diyScene" in instances)) return
        if (!force && at[id] && now() - (at[id] as long) < DISCOVERY_MAX_AGE_MS) return
        // A list that failed isn't asked for again on every poll.
        Long tried = SCENES_TRIED[id]
        if (!force && tried && now() - tried < SCENES_RETRY_MS) return
        SCENES_TRIED[id] = now()
        Map body = [requestId: "hubitat-${now()}", payload: [sku: cd.getDataValue("sku"), device: id]]
        if ("lightScene" in instances) apiRequest("POST", "/device/scenes", body, "scenesResp", [id: id, kind: "scene"])
        if ("diyScene" in instances) apiRequest("POST", "/device/diy-scenes", body, "scenesResp", [id: id, kind: "diy"])
    }
}

void scenesResp(resp, Map data) {
    Map json = apiJson(resp)
    def cd = deviceById(data?.id as String)
    if (!cd) return
    List caps = json?.payload?.capabilities
    if (statusOf(resp) != 200 || !(caps instanceof List)) {
        log.warn "Govee: ${cd.displayName}: reading its ${data.kind == 'diy' ? 'DIY scenes' : 'scenes'} failed (HTTP ${statusOf(resp)})"
        return
    }
    List list = []
    caps.findAll { it.type == "devices.capabilities.dynamic_scene" }.each { Map c ->
        ((c.parameters?.options ?: []) as List).each { Map o -> if (o.name && o.value != null) list << [o.name, data.kind, o.value] }
    }
    logDebug "${cd.displayName}: ${list.size()} ${data.kind} scene(s)"
    cd.goveeScenes(data.kind as String, list)
    // Fresh only once a list has arrived: a failure is tried again later.
    Map at = state.scenesAt ?: [:]
    at[data.id] = now()
    state.scenesAt = at
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

/** A report from the account topic (via "iot") or a LAN devStatus (via "lan"), passed on by the connection driver. */
void iotMessage(Map msg) {
    String id = msg.device
    def cd = deviceById(id)
    if (!cd) return
    String via = msg.via ?: "iot"
    noteIot(id, now())
    Map s = normalizeIot(msg.state instanceof Map ? msg.state : [:])
    if (!s) {
        cd.goveeSeen(via)
        return
    }
    if (s.mode != null && cd.getDataValue("kind") == "light") s.putAll(sceneFromMode(id, s.mode, LAST_MODE.put(id, s.mode.toString())))
    cd.goveeUpdate(s, via)
    // Fans report only power over MQTT: read speed and mode once power changes.
    if (cd.getDataValue("kind") == "fan" && s.containsKey("onOff") && settings.apiKey) runIn(2, "refreshDevice", [data: [id: id], overwrite: false])
}

void noteIot(String id, long at) { if (id) LAST_IOT.put(id, at) }

/**
 * What a light's reported mode says about scenes: [scene: true] while one runs,
 * [scene: false, effect: null] once it's back to a plain colour or white, or
 * nothing while the number isn't known yet. Learns the number first when Hubitat
 * has just set a colour or scene.
 */
Map sceneFromMode(String id, def mode, String before = null) {
    String m = mode.toString()
    Map intent = MODE_INTENT[id]
    long age = intent ? now() - (intent.at as long) : Long.MAX_VALUE
    if (intent && age >= MODE_LEARN_MIN_MS && age <= MODE_LEARN_MAX_MS) {
        MODE_INTENT.remove(id)
        learnMode(id, intent.kind as String, m)
    }
    // Hubitat's own recent command already says what the light is doing.
    if (age < MODE_LEARN_MIN_MS) return [:]
    Map known = (state.lightModes ?: [:])[id] ?: [:]
    if (m in (known.scene ?: [])) return [scene: true]
    if (m in (known.plain ?: [])) return [scene: false, effect: null]
    // Left a scene for a mode not learned yet: whatever it is, the scene is over.
    if (before != null && before != m && before in (known.scene ?: [])) return [scene: false, effect: null]
    return [:]
}

/** Record that `mode` means `kind` for this light; a number seen as both is dropped. */
void learnMode(String id, String kind, String mode) {
    Map all = state.lightModes ?: [:]
    Map known = all[id] ?: [plain: [], scene: []]
    String other = kind == "scene" ? "plain" : "scene"
    if (mode in (known[kind] ?: [])) return
    if (mode in (known[other] ?: [])) {
        known[other] = (known[other] as List) - mode
        logDebug "${deviceById(id)?.displayName}: mode ${mode} seen for both plain and scene; forgetting it"
    } else {
        known[kind] = ((known[kind] ?: []) as List) + mode
        logDebug "${deviceById(id)?.displayName}: mode ${mode} means ${kind}"
    }
    all[id] = known
    state.lightModes = all
}

/** Ask a light for its state over MQTT, to learn its mode after a colour or scene command. */
void modeQuery(Map data) {
    String topic = (state.discovered ?: [:])[data?.id]?.topic
    if (topic) getConnection()?.queryStatus([(data.id): topic])
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
    if (s.onOff != null) ev << say([name: "switch", value: s.onOff ? "on" : "off"], "is")
    if (s.online != null) ev << say([name: "healthStatus", value: s.online ? "online" : "offline"], "is")
    if (kind == "light") {
        ev.addAll(lightEvents(s, caps))
        ev.addAll(tileDefaults(cd, ev, caps))
    }
    if (kind == "fan") ev.addAll(fanEvents(s, caps))
    return ev
}

private List lightEvents(Map s, Map caps) {
    List ev = []
    // Hubitat calls brightness "level" (SwitchLevel); the logs say brightness.
    if (s.brightness != null) ev << say([name: "level", value: Math.max(0, Math.min(100, s.brightness as int)), unit: "%"], "brightness is")
    int ct = (s.ct ?: 0) as int
    Map c = s.color
    if (s.scene == true) {
        // The colour a light reports during a scene is stale: show the scene instead.
        ev << say([name: "colorMode", value: "EFFECTS"], "color mode is")
        ev << say([name: "effectName", value: s.effect ?: "unknown"], "scene is")
        return ev
    }
    if (ct > 0) {
        ev << [name: "colorMode", value: "CT"]
        ev << say([name: "colorTemperature", value: ct, unit: "K"], "color temperature is")
        ev << say([name: "colorName", value: ctName(ct)], "color is")
    } else if (c && (c.r || c.g || c.b)) {
        Map hsv = rgbToHsv(c.r as int, c.g as int, c.b as int)
        ev << [name: "colorMode", value: "RGB"]
        ev << [name: "hue", value: hsv.hue]
        ev << [name: "saturation", value: hsv.saturation, unit: "%"]
        ev << [name: "color", value: rgbHex(c.r as int, c.g as int, c.b as int)]
        ev << say([name: "colorName", value: hueName(hsv.hue as int, hsv.saturation as int)], "color is")
    }
    if (s.containsKey("effect")) ev << say([name: "effectName", value: s.effect ?: "none"], "scene is")
    return ev
}

/**
 * Hubitat's RGBW tile needs colorTemperature, hue and saturation. A light only
 * reports the ones for its current mode, so give any never set a starting value.
 */
private List tileDefaults(def cd, List ev, Map caps) {
    List names = ev.collect { it.name }
    List out = []
    if (!("colorTemperature" in names) && cd.currentValue("colorTemperature") == null) {
        out << [name: "colorTemperature", value: ((caps.ct ?: CT_RANGE)[0]) as int, unit: "K"]
    }
    if (!("hue" in names) && cd.currentValue("hue") == null) out << [name: "hue", value: 0]
    if (!("saturation" in names) && cd.currentValue("saturation") == null) out << [name: "saturation", value: 0, unit: "%"]
    return out
}

private List fanEvents(Map s, Map caps) {
    List ev = []
    List speeds = caps.speeds ?: []
    List modes = caps.modes ?: []
    // The speed attribute shows "auto" in Auto mode, so the speed list offers it too.
    boolean hasAuto = modes.any { it[0].toString().equalsIgnoreCase("auto") }
    if (speeds) {
        ev << [name: "maxSpeedLevel", value: speeds.size()]
        ev << [name: "supportedFanSpeeds", value: JsonOutput.toJson(speedNamesFor(speeds.size()) + (hasAuto ? ["auto"] : []) + ["off", "on"])]
    }
    if (modes) ev << [name: "supportedModes", value: JsonOutput.toJson(modes.collect { modeLabel(it[0]) })]
    if (s.workMode != null) {
        def m = modes.find { it[1] == s.workMode }
        ev << say([name: "mode", value: m ? modeLabel(m[0]) : s.workMode.toString()], "mode is")
    }
    if (s.onOff == 0) {
        ev << say([name: "speed", value: "off"], "speed is")
    } else if (s.workMode != null && caps.speedMode != null && s.workMode == caps.speedMode && speeds) {
        int idx = speeds.indexOf(s.modeValue)
        if (idx >= 0) {
            ev << [name: "speedLevel", value: idx + 1, descriptionText: "speed level is ${idx + 1} of ${speeds.size()}"]
            ev << say([name: "speed", value: speedNameFor(idx + 1, speeds.size())], "speed is")
        }
    } else if (s.workMode != null && s.onOff == 1) {
        // Other modes run their own speed: Hubitat's closest names are auto and on.
        def m = modes.find { it[1] == s.workMode }
        ev << say([name: "speed", value: m && m[0].toString().toLowerCase() == "auto" ? "auto" : "on"], "speed is")
    }
    if (s.oscillation != null) ev << say([name: "oscillation", value: s.oscillation ? "on" : "off"], "oscillation is")
    return ev
}

/** Add the log wording for an event: "<words> <value><unit>", e.g. "brightness is 50%". */
private Map say(Map e, String words) {
    String unit = e.unit == "K" ? " K" : (e.unit ?: "")
    e.descriptionText = "${words} ${e.value}${unit}".toString()
    return e
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
                // On first: some lights ignore brightness and colour while off.
                powerOnFirst(s, send, opt)
                send << [via: "iot", cmd: "brightness", data: [val: level], api: [type: "devices.capabilities.range", instance: "brightness", value: level]]
                opt.brightness = level
            }
            break
        case "setColor":
            Map hsl = args[0] as Map
            Map rgb = hsvToRgb((hsl.hue ?: 0) as double, (hsl.saturation ?: 0) as double)
            powerOnFirst(s, send, opt)
            send << colorSend(rgb)
            opt.color = rgb
            opt.ct = 0
            opt.effect = null
            opt.scene = false
            if (hsl.level != null) {
                int level = clamp(hsl.level as int, 1, 100)
                send << [via: "iot", cmd: "brightness", data: [val: level], api: [type: "devices.capabilities.range", instance: "brightness", value: level]]
                opt.brightness = level
            }
            break
        case "setHue":
        case "setSaturation":
            Map hsv = s.color && !(s.ct ?: 0) ? rgbToHsv(s.color.r as int, s.color.g as int, s.color.b as int) : [hue: 0, saturation: 100]
            double hue = cmd == "setHue" ? (args[0] as double) : (hsv.hue as double)
            double sat = cmd == "setSaturation" ? (args[0] as double) : (hsv.saturation as double)
            Map rgb = hsvToRgb(hue, sat)
            powerOnFirst(s, send, opt)
            send << colorSend(rgb)
            opt.color = rgb
            opt.ct = 0
            opt.effect = null
            opt.scene = false
            break
        case "setColorTemperature":
            List range = caps.ct ?: CT_RANGE
            int k = clamp(args[0] as int, range[0] as int, range[1] as int)
            powerOnFirst(s, send, opt)
            send << [via: "iot", cmd: "colorwc", data: [color: [r: 0, g: 0, b: 0], colorTemInKelvin: k],
                     api: [type: "devices.capabilities.color_setting", instance: "colorTemperatureK", value: k]]
            opt.ct = k
            opt.effect = null
            opt.scene = false
            if (args.size() > 1 && args[1] != null) {
                int level = clamp(args[1] as int, 1, 100)
                send << [via: "iot", cmd: "brightness", data: [val: level], api: [type: "devices.capabilities.range", instance: "brightness", value: level]]
                opt.brightness = level
            }
            break
        case "setEffect":
            // args[0] is the driver's scene entry: [name, kind ("scene" or "diy"), API value].
            List entry = args[0] as List
            if (!entry) return [send: [], optimistic: [:], error: "no such scene"]
            send << [via: "api", api: [type: "devices.capabilities.dynamic_scene", instance: entry[1] == "diy" ? "diyScene" : "lightScene", value: entry[2]]]
            opt.effect = entry[0]
            opt.scene = true
            opt.onOff = 1
            break
        case "setSpeed":
            String name = args[0] as String
            if (name == "off") return planCommand(cd, s, "off", [])
            if (name == "on") return planCommand(cd, s, "on", [])
            if (name == "auto") return planFanMode(caps, s, "auto")
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
            return planFanMode(caps, s, args[0] as String)
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

/** Turn the light on before what depends on it, when it's off. */
private void powerOnFirst(Map s, List send, Map opt) {
    if (s.onOff == 1 || opt.onOff == 1) return
    send << power(1)
    opt.onOff = 1
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
    return [send: [send], optimistic: [onOff: 1, workMode: caps.speedMode, modeValue: speeds[step - 1], lastSpeed: speeds[step - 1]]]
}

/** A Govee fan mode's name as Hubitat shows it. */
String modeLabel(def name) { (MODE_LABELS[name?.toString()] ?: name?.toString()) as String }

private Map planFanMode(Map caps, Map s, String name) {
    if (!caps.modes) return [send: [], optimistic: [:], error: "fan modes need the Govee API key (if it's set, press Look for new devices in the app)"]
    def m = (caps.modes ?: []).find { it[0].toString().equalsIgnoreCase(name) || modeLabel(it[0]).equalsIgnoreCase(name) }
    // The Set Mode dropdown is the same for every fan, so say which modes this one has.
    if (!m) return [send: [], optimistic: [:], error: "this fan has no mode '${name}'; it has ${(caps.modes as List).collect { modeLabel(it[0]) }.join(', ')}"]
    // The speed mode needs a step: go back to the last one used.
    if (m[1] == caps.speedMode) {
        List speeds = caps.speeds ?: []
        int step = speeds.indexOf(s.lastSpeed) + 1
        if (step < 1) step = speeds.indexOf(s.modeValue) + 1
        if (step < 1) step = Math.max(1, (int) Math.ceil(speeds.size() / 2d))
        return planFanStep(caps, s, step)
    }
    Map send = [via: "api", api: [type: "devices.capabilities.work_mode", instance: "workMode", value: [workMode: m[1], modeValue: 0]]]
    return [send: [send], optimistic: [onOff: 1, workMode: m[1], modeValue: 0]]
}

/** How a device's commands go now: the LAN (ip), MQTT (live), else the API. */
private Map routeFor(String id) {
    def conn = getConnection()
    String topic = (state.discovered ?: [:])[id]?.topic
    boolean live = conn && topic && conn.currentValue("connection") == "connected" && !(settings.apiKey && apiOnly(id))
    String ip = conn && lanUsable(id) ? (state.lan ?: [:])[id]?.ip : null
    return [conn: conn, topic: topic, live: live, ip: ip]
}

/** For drivers pacing repeated commands: "lan", "iot" or "api" (slow and rate-limited). */
String commandRoute(def cd) {
    Map r = routeFor(cd.getDataValue("id"))
    return r.ip ? "lan" : (r.live ? "iot" : "api")
}

/**
 * Called by a light or fan driver. Sends the command and returns the optimistic
 * state, or [refused: why] for a command it can't send (the driver logs and shows it).
 */
Map goveeCommand(def cd, Map s, String cmd, List args) {
    Map plan = planCommand(cd, s ?: [:], cmd, args ?: [])
    if (plan.error) return [refused: plan.error]
    String id = cd.getDataValue("id")
    String sku = cd.getDataValue("sku")
    Map r = routeFor(id)
    def conn = r.conn
    String topic = r.topic
    boolean live = r.live
    String ip = r.ip
    plan.send.each { Map item ->
        List lib = (item.api?.instance == "lightScene" && ip) ? libraryScene(sku, item.api.value) : null
        if (item.api?.instance == "lightScene" && ip && !lib) ensureLibrary(sku)
        List frames = lib ? lanSceneFrames(sku, lib) : null
        if (frames) {
            // A scene over the LAN: turn the light on first if it's off.
            if (s?.onOff != 1) conn.publishLan(id, ip, "turn", [val: 1], power(1).api as Map, [onOff: 1])
            conn.publishLanFrames(id, ip, frames)
            logDebug "${cd.displayName}: scene over the LAN (code ${lib[0]})"
        } else if (item.via == "iot" && ip) {
            conn.publishLan(id, ip, item.cmd as String, item.data as Map, item.api as Map, expectFor(item.cmd as String, item.data as Map))
        } else if (item.via == "iot" && live) {
            conn.publishCommand(id, topic, item.cmd as String, item.data as Map, item.api as Map, expectFor(item.cmd as String, item.data as Map))
        } else if (item.api) {
            if (item.via == "iot") {
                String why = settings.apiKey && apiOnly(id) ? "ignores live commands" : "no live connection"
                logDebug "${cd.displayName}: ${why}, sending ${item.cmd} over the API"
            }
            sendApi(id, sku, item.api as Map)
        }
    }
    if (plan.optimistic.containsKey("scene") && topic) {
        MODE_INTENT[id] = [kind: plan.optimistic.scene ? "scene" : "plain", at: now()]
        runIn(MODE_QUERY_S, "modeQuery", [data: [id: id], overwrite: false])
    }
    return plan.optimistic
}

/** What a device should report once an MQTT command has worked (MQTT state keys). */
Map expectFor(String cmd, Map data) {
    switch (cmd) {
        case "turn": return [onOff: data.val]
        case "brightness": return [brightness: data.val]
        case "colorwc":
            int k = (data.colorTemInKelvin ?: 0) as int
            return k > 0 ? [colorTemInKelvin: k] : [color: data.color]
    }
    return [:]
}

/** True while a device's commands skip MQTT because it ignored them there. */
boolean apiOnly(String id) {
    Long since = (state.apiOnly ?: [:])[id] as Long
    return since != null && now() - since < IOT_RETRY_MS
}

/**
 * The connection driver gave up on MQTT commands the device didn't carry out:
 * send them over the API, and after a few misses in a row stop using MQTT
 * for this device's commands for a while.
 */
void iotUnconfirmed(String id, List apis) {
    def cd = deviceById(id)
    if (!cd || !apis) return
    if (!settings.apiKey) {
        log.warn "Govee: ${cd.displayName} didn't carry out a command sent over the live connection, and there's no API key to retry it with"
        return
    }
    Map misses = state.iotMisses ?: [:]
    int n = ((misses[id] ?: 0) as int) + 1
    misses[id] = n
    state.iotMisses = misses
    if (n >= IOT_MISSES_TO_API && !apiOnly(id)) {
        Map ao = state.apiOnly ?: [:]
        ao[id] = now()
        state.apiOnly = ao
        log.info "Govee: ${cd.displayName} reports over the live connection but ignores commands there; using the API for its commands"
    } else {
        log.info "Govee: ${cd.displayName} didn't carry out ${apis*.instance.join(', ')} over the live connection; sending it over the API"
    }
    apis.each { sendApi(id, cd.getDataValue("sku"), it as Map) }
}

/** An MQTT command was carried out: the device obeys MQTT. */
void iotConfirmed(String id) {
    if ((state.iotMisses ?: [:])[id]) {
        Map misses = state.iotMisses
        misses.remove(id)
        state.iotMisses = misses
    }
    if ((state.apiOnly ?: [:])[id]) {
        Map ao = state.apiOnly
        ao.remove(id)
        state.apiOnly = ao
    }
}

// ---------------------------------------------------------------- LAN

/** A device answered a LAN scan. */
void lanFound(String id, String ip, String sku) {
    if (!id || !ip) return
    Map lan = state.lan ?: [:]
    Map old = lan[id]
    lan[id] = [ip: ip, at: now()]
    state.lan = lan
    def cd = deviceById(id)
    if (!cd) return
    if (old?.ip != ip) {
        log.info "Govee: ${cd.displayName} found on the LAN at ${ip}"
        cd.updateDataValue("lanIp", ip)
    }
    if (cd.getDataValue("kind") == "light" && "lightScene" in (capsOf(cd).instances ?: [])) ensureLibrary(cd.getDataValue("sku"))
}

// ---------------------------------------------------------------- scenes over the LAN

/** Fetch Govee's scene library for a model, once (it needs no sign-in). */
void ensureLibrary(String sku) {
    if (!sku || SCENE_LIB.containsKey(sku)) return
    Long started = LIB_FETCHING.get(sku)
    if (started && now() - started < 60000) return
    LIB_FETCHING.put(sku, now())
    Map p = app2Request("/appsku/v1/light-effect-libraries", null)
    p.query = [sku: sku]
    asynchttpGet("libraryResp", p, [sku: sku])
}

void libraryResp(resp, Map data) {
    String sku = data?.sku
    LIB_FETCHING.remove(sku)
    Map json = parseJson(bodyOf(resp))
    List cats = json?.data?.categories instanceof List ? json.data.categories : null
    if (statusOf(resp) != 200 || cats == null) {
        log.warn "Govee: couldn't fetch the scene library for ${sku} (HTTP ${statusOf(resp)}); its scenes use the cloud"
        return
    }
    SCENE_LIB.put(sku, sceneLibrary(cats))
    log.info "Govee: scene library for ${sku}: ${SCENE_LIB.get(sku).p.size()} scenes can run over the LAN"
}

/** Library categories -> lookups by the API's scene value: paramId, and sceneId as a fallback. */
Map sceneLibrary(List categories) {
    Map byParam = [:], byScene = [:]
    categories.each { Map c ->
        (c.scenes ?: []).each { Map sc ->
            (sc.lightEffects ?: []).each { Map e ->
                int code = (e.sceneCode ?: 0) as int
                if (!code) return
                List entry = [code, (e.scenceParam ?: "") as String, (e.sceneType ?: sc.sceneType ?: 0) as int]
                if (e.scenceParamId != null) byParam[e.scenceParamId.toString()] = entry
                if (sc.sceneId != null && !byScene.containsKey(sc.sceneId.toString())) byScene[sc.sceneId.toString()] = entry
            }
        }
    }
    return [p: byParam, s: byScene]
}

/** [code, param] for an API scene value {id, paramId}, if the model's library has it. */
List libraryScene(String sku, def value) {
    Map lib = SCENE_LIB.get(sku)
    if (!lib || !(value instanceof Map)) return null
    return (lib.p[value.paramId?.toString()] ?: lib.s[value.id?.toString()]) as List
}

/**
 * The ptReal frames for a library scene [code, param, sceneType] on this
 * model, or null when the scene should go through the API instead.
 */
List lanSceneFrames(String sku, List scene) {
    int code = scene[0] as int
    String param = (scene[1] ?: "") as String
    // No parameter data: the scene code alone switches the scene.
    if (!param) return sceneFrames(code, "", null)
    String hex = param.decodeBase64().encodeHex().toString()
    List rules = SCENE_RULES[sku] as List
    if (rules == null) {
        int type = ((scene.size() > 2 ? scene[2] : 0) ?: 0) as int
        if (type > DEFAULT_RULE_MAX_TYPE) return null
        rules = [DEFAULT_SCENE_RULE]
    }
    List rule = rules.find { hex.startsWith(it[0] as String) } as List
    // A model with rules but none matching: the data goes as it is.
    return sceneFrames(code, param, rule ?: ["", "", ""])
}

/**
 * The ptReal frames that start a scene (base64, 20 bytes each): the scene's
 * parameter data in numbered a3 lines, then 33 05 04 <code>. Each line is
 * padded to 19 bytes and ends with an XOR checksum. `rule` is [hex stripped
 * from the start of the data, hex put before it, hex after the code]; null
 * sends only the code line. Layout from govee2mqtt (SetSceneCode) and AlgoClaw.
 */
List sceneFrames(int code, String paramB64, List rule = ["", "02", ""]) {
    List frames = []
    if (rule != null && paramB64) {
        String hex = (paramB64 as String).decodeBase64().encodeHex().toString()
        String strip = (rule[0] ?: "") as String
        if (strip && hex.startsWith(strip)) hex = hex.substring(strip.length())
        frames.addAll(dataFrames(hexBytes((rule[1] ?: "") as String) + hexBytes(hex)))
    }
    frames << finishFrame([0x33, 0x05, 0x04, code & 0xff, (code >> 8) & 0xff] + hexBytes(rule ? (rule[2] ?: "") as String : ""))
    return frames
}

private List<Integer> hexBytes(String hex) {
    List<Integer> out = []
    for (int i = 0; i + 1 < hex.length(); i += 2) out << Integer.parseInt(hex.substring(i, i + 2), 16)
    return out
}

/** The numbered a3 lines carrying a scene's data (the header line holds the line count). */
private List dataFrames(List<Integer> payload) {
    List<Integer> data = [0xa3, 0x00, 0x01, 0x00]
    int lines = 0
    int lastMarker = 1
    for (int i = 0; i < payload.size(); i++) {
        if (data.size() % 19 == 0) {
            lines++
            data << 0xa3
            lastMarker = data.size()
            data << lines
        }
        data << payload[i]
    }
    // The last line is marked 0xff instead of its number; the header carries the line count.
    data[lastMarker] = 0xff
    data[3] = lines + 1
    List frames = []
    for (int i = 0; i < data.size(); i += 19) frames << finishFrame(data.subList(i, Math.min(i + 19, data.size())))
    return frames
}

private String finishFrame(List<Integer> bytes) {
    byte[] out = new byte[20]
    int x = 0
    for (int i = 0; i < bytes.size(); i++) {
        out[i] = (byte) (bytes[i] & 0xff)
        x ^= bytes[i] & 0xff
    }
    out[19] = (byte) x
    return out.encodeBase64().toString()
}

/** LAN address -> device id, for the devices in Hubitat. */
Map lanTargets() {
    Map out = [:]
    (state.lan ?: [:]).each { String id, Map l -> if (deviceById(id)) out[l.ip] = id }
    return out
}

/** True when commands for this device should go over the LAN. */
boolean lanUsable(String id) {
    if (settings.lanEnable == false || !id) return false
    Map l = (state.lan ?: [:])[id]
    if (!l?.ip || now() - ((l.at ?: 0) as long) > LAN_STALE_MS) return false
    Long off = (state.lanOff ?: [:])[id] as Long
    return off == null || now() - off > LAN_OFF_MS
}

/**
 * LAN commands the device didn't confirm: send them over MQTT or the API,
 * and after a few misses in a row pause LAN for this device for an hour.
 */
void lanUnconfirmed(String id, List items) {
    def cd = deviceById(id)
    if (!cd || !items) return
    Map misses = state.lanMisses ?: [:]
    int n = ((misses[id] ?: 0) as int) + 1
    misses[id] = n
    state.lanMisses = misses
    if (n >= LAN_MISSES_OFF) {
        Map off = state.lanOff ?: [:]
        off[id] = now()
        state.lanOff = off
        misses.remove(id)
        state.lanMisses = misses
        log.info "Govee: ${cd.displayName} isn't answering on the LAN; using the cloud for it for an hour"
    } else {
        log.info "Govee: ${cd.displayName} didn't confirm ${items*.cmd.join(', ')} on the LAN; sending it through the cloud"
    }
    def conn = getConnection()
    String topic = (state.discovered ?: [:])[id]?.topic
    boolean live = conn && topic && conn.currentValue("connection") == "connected" && !(settings.apiKey && apiOnly(id))
    items.each { Map item ->
        if (live) conn.publishCommand(id, topic, item.cmd as String, item.data as Map, item.api as Map, expectFor(item.cmd as String, item.data as Map))
        else if (item.api) sendApi(id, cd.getDataValue("sku"), item.api as Map)
    }
}

/** A LAN command was carried out. */
void lanConfirmed(String id) {
    if ((state.lanMisses ?: [:])[id]) {
        Map misses = state.lanMisses
        misses.remove(id)
        state.lanMisses = misses
    }
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
        // Hubitat already shows the new value: read what the device really has.
        if (cd) {
            long wait = apiPauseLeft()
            if (wait > 0) runIn((int) (wait / 1000) + 5, "refreshDevice", [data: [id: data.id], overwrite: false])
            else refreshDevice([id: data.id])
        }
        return
    }
    cd?.goveeSeen("api")
}

/** Read the state of quiet devices: those with no MQTT message lately, and fans (MQTT gives only their power). */
void pollQuiet() {
    if (!settings.apiKey || apiPaused()) return
    loadScenes()
    long cutoff = now() - QUIET_MS
    getGoveeDevices().each { cd ->
        Long last = LAST_IOT.get(cd.getDataValue("id"))
        boolean quiet = !last || last < cutoff
        if (quiet || cd.getDataValue("kind") == "fan") refreshDevice([id: cd.getDataValue("id")])
    }
}

void refreshDevice(Map data) {
    def cd = deviceById(data?.id as String)
    if (!cd || !settings.apiKey) return
    if (apiPaused()) {
        logDebug "${cd.displayName}: not read, the API asked us to slow down"
        return
    }
    Map body = [requestId: "hubitat-${now()}", payload: [sku: cd.getDataValue("sku"), device: data.id]]
    apiRequest("POST", "/device/state", body, "apiStateResp", [id: data.id])
}

/** Called by a driver's refresh(): ask over MQTT and, with an API key, read it from the API too. */
void refreshFromDriver(def cd) {
    String id = cd.getDataValue("id")
    String topic = (state.discovered ?: [:])[id]?.topic
    // No topic: nothing to ask (an empty map would mean every device).
    if (topic) getConnection()?.queryStatus([(id): topic])
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
    if (statusOf(resp) == 429) noteRateLimited()
    else if (statusOf(resp) == 200) API_BACKOFF.remove(app.id as Long)
    if (statusOf(resp) == 401) log.warn "Govee: the API key was rejected"
    return json
}

/** An HTTP 429: hold the API's reads back, longer each time it repeats. */
private void noteRateLimited() {
    Long key = app.id as Long
    Map b = API_BACKOFF[key]
    // Already waiting: requests sent before the first 429 are still answering.
    if (b && now() < (b.until as long)) return
    // Any success clears this, so an earlier entry means it's still limited.
    long wait = b ? Math.min((b.wait as long) * 2, API_BACKOFF_MAX_MS) : API_BACKOFF_MIN_MS
    API_BACKOFF[key] = [until: now() + wait, wait: wait]
    log.warn "Govee: the API rate limit was reached; polls and refreshes wait ${(int) (wait / 60000)} minute(s), commands still go"
}

/** How long the API's reads still wait after a 429, in ms (0 when they don't). */
long apiPauseLeft() {
    Map b = API_BACKOFF[app.id as Long]
    return b ? Math.max(0L, (b.until as long) - now()) : 0L
}

boolean apiPaused() { apiPauseLeft() > 0 }

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
