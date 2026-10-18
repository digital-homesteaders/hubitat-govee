/**
 * End-to-end flows through the app and the real drivers, with the hub and
 * Govee's cloud faked: sign-in with the emailed code, the certificate, device
 * discovery, child creation, the MQTT session, pushed reports, commands and
 * their confirmation or API fallback, fan control over the API, and polling.
 * State goes through JSON after each step (hub.settle()), as it does on a hub.
 */

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.Field
import static Check.check

@Field File root = new File(getClass().protectionDomain.codeSource.location.path).parentFile.parentFile
@Field Hub hub = new Hub(root)
@Field def app = hub.app()
@Field List API = new JsonSlurper().parseText(new File(root, "tests/fixtures/api-devices.json").text)
@Field String LAMP = API.find { it.sku == "H6052" }.device
@Field String FAN = API.find { it.sku == "H7111" }.device
@Field String PASSWORD = "s3cret-Pa55"
@Field String TOKEN = "tok-ABCDEFGH123456"
@Field String ACCOUNT_TOPIC = "GA/acct-0123456789abcdef"

FakeDevice child(String dni) { app.getChildDevice(dni) }
FakeDevice conn() { child("govee-connection-${app.app.id}") }
FakeDevice lamp() { child(app.deviceDni(LAMP)) }
FakeDevice fan() { child(app.deviceDni(FAN)) }
String topic(String id) { "GD/topic-" + id.replace(":", "") }

def resp(int status, def body, Map headers = [:]) {
    new Expando(status: status, data: body instanceof String ? body : JsonOutput.toJson(body), errorData: null, headers: headers)
}

/** The last asynchttp call of either kind. */
List lastHttp() { (app.callsNamed("asynchttpPost") + app.callsNamed("asynchttpGet")).sort { app.calls.indexOf(it) }.last() }
Map bodyOf(List call) { new JsonSlurper().parseText(call[2].body) }
List published(int from = 0) { hub.scripts.collectMany { it.callsNamed("mqtt.publish") }.drop(from) }
int pubCount() { published().size() }

void mqtt(Map msg) {
    conn().parse(JsonOutput.toJson([topic: ACCOUNT_TOPIC, payload: JsonOutput.toJson(msg)]))
    hub.settle()
}

check("sign-in: Govee asks for a code, the app has it emailed") {
    app.settings.email = " Owner@Example.com "
    app.settings.password = PASSWORD
    app.appButtonHandler("btnSignIn")
    app.mainPage()
    hub.settle()
    List c = lastHttp()
    assert c[0] == "asynchttpPost" && c[1] == "loginResp" && c[2].uri == "https://app2.govee.com/account/rest/account/v2/login"
    assert bodyOf(c) == [email: "Owner@Example.com", password: PASSWORD, client: "218a537e3b005148bcdccfad4f4cc94c", clientType: "1"]
    assert c[2].headers.clientId == "218a537e3b005148bcdccfad4f4cc94c" && c[2].headers.appVersion == "7.4.10"
    assert !app.settings.password, "the password setting is dropped straight away"
    app.loginResp(resp(200, [status: 454, message: ""]), c[3])
    hub.settle()
    c = lastHttp()
    assert c[1] == "codeResp" && bodyOf(c) == [type: 8, email: "Owner@Example.com"]
    app.codeResp(resp(200, [status: 200]), [:])
    hub.settle()
    assert app.state.login.step == "code"
    app.mainPage()
}

check("a malformed code is refused without asking Govee") {
    int n = app.callsNamed("asynchttpPost").size()
    app.settings.verifyCode = "12ab"
    app.appButtonHandler("btnCode")
    app.mainPage()
    assert app.callsNamed("asynchttpPost").size() == n && app.state.login.error
}

check("a wrong code says so and keeps the code step") {
    app.settings.verifyCode = "9999"
    app.appButtonHandler("btnCode")
    app.mainPage()
    List c = lastHttp()
    assert bodyOf(c).code == "9999" && bodyOf(c).password == PASSWORD
    app.loginResp(resp(200, [status: 454, message: "bad"]), c[3])
    hub.settle()
    assert app.state.login.step == "code" && app.state.login.error.contains("didn't accept that code")
}

check("the right code signs in, fetches the certificate and the device list") {
    app.settings.verifyCode = "1234"
    app.appButtonHandler("btnCode")
    app.mainPage()
    List c = lastHttp()
    app.loginResp(resp(200, [status: 200, message: "Login successful", client: [token: TOKEN, accountId: 4242, topic: ACCOUNT_TOPIC, refreshToken: "r"]]), c[3])
    hub.settle()
    assert app.state.token == TOKEN && !app.state.pendingPassword && !app.state.login
    assert app.state.iot.accountTopic == ACCOUNT_TOPIC && app.state.iot.accountId == "4242"
    List key = app.callsNamed("asynchttpGet").last()
    assert key[1] == "iotKeyResp" && key[2].headers.Authorization == "Bearer ${TOKEN}"
    List list = app.callsNamed("asynchttpPost").last()
    assert list[1] == "accountDevicesResp" && list[2].uri.endsWith("/device/rest/devices/v1/list")
    String p12 = new File(root, "tests/fixtures/test.p12").bytes.encodeBase64().toString()
    app.iotKeyResp(resp(200, [status: 200, data: [endpoint: "x-ats.iot.us-east-1.amazonaws.com", log: "", p12: p12, p12Pass: "test-pass-1"]]), [:])
    hub.settle()
    assert app.state.iot.p12 == p12
}

check("discovery: account list plus API capabilities; unknown types are listed as unsupported") {
    app.settings.apiKey = "api-key-1"
    Map settingsJson = [topic: topic(LAMP)]
    app.accountDevicesResp(resp(200, [status: 200, devices: [
        [sku: "H6052", deviceName: "Aura Table Lamp", device: LAMP, deviceExt: JsonOutput.toJson([deviceSettings: JsonOutput.toJson(settingsJson)])],
        [sku: "H7111", deviceName: "Guest Room Fan", device: FAN, deviceExt: [deviceSettings: [topic: topic(FAN)]]],
        [sku: "H5179", deviceName: "Thermometer", device: "11:22", deviceExt: [deviceSettings: [topic: "GD/t"]]],
    ]]), [:])
    hub.settle()
    List c = app.callsNamed("asynchttpGet").last()
    assert c[1] == "apiDevicesResp" && c[2].uri == "https://openapi.api.govee.com/router/api/v1/user/devices" && c[2].headers["Govee-API-Key"] == "api-key-1"
    List apiList = API + [[sku: "H5179", device: "11:22", type: "devices.types.thermometer", capabilities: []]]
    app.apiDevicesResp(resp(200, [code: 200, data: apiList], ["X-RateLimit-Remaining": "9990", "API-RateLimit-Remaining": "99"]), [:])
    hub.settle()
    assert app.state.discovered.keySet() == [LAMP, FAN] as Set
    assert app.state.discovered[LAMP].topic == topic(LAMP) && app.state.discovered[FAN].kind == "fan"
    assert app.state.discovered[FAN].caps.speeds.size() == 8
    assert app.state.unsupported == ["Thermometer (H5179)"]
    assert app.state.rate == [day: "9990", minute: "99"]
    app.mainPage()
}

check("Done creates the devices and the connection, which connects with the converted certificate") {
    app.settings.selectedDevices = [LAMP, FAN]
    app.updated()
    hub.settle()
    assert lamp().driverName == "Govee Light" && lamp().displayName == "Aura Table Lamp" && lamp().getDataValue("sku") == "H6052"
    assert fan().driverName == "Govee Fan"
    assert conn().driverName == "Govee Cloud Connection"
    assert app.callsNamed("schedule").last()[1] == "0 */5 * ? * *"
    FakeMqtt m = conn().driver.mqtt
    assert m.connectOpts.broker == "ssl://x-ats.iot.us-east-1.amazonaws.com:8883"
    assert m.connectOpts.clientId == "AP/4242/218a537e3b005148bcdccfad4f4cc94c"
    assert m.connectOpts.clientCertificate.trim() == new File(root, "tests/fixtures/cert.pem").text.trim()
    assert m.connectOpts.privateKey.trim() == new File(root, "tests/fixtures/key8.pem").text.trim()
    assert m.connectOpts.caCertificate.contains("BEGIN CERTIFICATE") && m.connectOpts.tlsVersion == "1.2"
    assert conn().currentValue("connection") == "connecting"
    assert conn().driver.callsNamed("runIn").last()[2] == "afterConnect"
}

check("connected: subscribe to the account topic and ask every device for its state") {
    int from = pubCount()
    conn().afterConnect()
    hub.settle()
    assert conn().driver.callsNamed("mqtt.subscribe").last() == ["mqtt.subscribe", ACCOUNT_TOPIC, 1]
    assert conn().currentValue("connection") == "connected"
    List q = published(from)
    assert q*.getAt(1) as Set == [topic(LAMP), topic(FAN)] as Set
    Map msg = new JsonSlurper().parseText(q[0][2]).msg
    assert msg.cmd == "status" && msg.type == 0 && msg.cmdVersion == 2 && !msg.containsKey("data") && q[0][3] == 0
}

check("the certificate is converted once, then reused") {
    String tag = conn().driver.state.pemTag
    conn().reconnect()
    hub.settle()
    assert conn().driver.state.pemTag == tag
    conn().afterConnect()
    hub.settle()
}

check("a pushed report updates the light") {
    mqtt([sku: "H6052", device: LAMP, cmd: "status", state: [brightness: 3, color: [b: 255, g: 8, r: 13], colorTemInKelvin: 0, connected: "true", mode: 4, onOff: 1, result: 1]])
    FakeDevice l = lamp()
    assert l.currentValue("switch") == "on" && l.currentValue("level") == 3 && l.currentValue("colorMode") == "RGB"
    assert l.currentValue("hue") == 67 && l.currentValue("healthStatus") == "online" && l.currentValue("transport") == "iot"
    assert !l.getDataValue("lastIot"), "no device-data write per message"
    mqtt([msg: [sku: "H6052", device: LAMP, cmd: "status", state: [connected: "false", onOff: 0]]])
    assert l.currentValue("healthStatus") == "offline" && l.currentValue("switch") == "off"
}

check("on(): MQTT turn to the lamp's own topic, shown straight away, confirmed by the next report") {
    int from = pubCount()
    lamp().on()
    hub.settle()
    List p = published(from)
    assert p.size() == 1 && p[0][1] == topic(LAMP) && p[0][3] == 1
    Map msg = new JsonSlurper().parseText(p[0][2]).msg
    assert msg.cmd == "turn" && msg.data == [val: 1] && msg.type == 1 && msg.cmdVersion == 0 && msg.transaction.startsWith("v_")
    assert lamp().currentValue("switch") == "on"
    assert conn().driver.callsNamed("runInMillis").last()[2] == "confirmQuery"
    conn().confirmQuery()
    assert new JsonSlurper().parseText(published(from).last()[2]).msg.cmd == "status"
    mqtt([sku: "H6052", device: LAMP, cmd: "status", state: [onOff: 1]])
    int posts = app.callsNamed("asynchttpPost").size()
    hub.advance(10000)
    conn().checkPending()
    assert app.callsNamed("asynchttpPost").size() == posts, "a confirmed command isn't resent"
}

check("an unconfirmed MQTT command is resent over the API") {
    lamp().setColorTemperature(3000)
    hub.settle()
    int posts = app.callsNamed("asynchttpPost").size()
    hub.advance(7000)
    conn().checkPending()
    hub.settle()
    List c = app.callsNamed("asynchttpPost").drop(posts)
    assert c.size() == 1 && c[0][1] == "apiControlResp" && c[0][2].uri.endsWith("/device/control")
    assert bodyOf(c[0]).payload == [sku: "H6052", device: LAMP, capability: [type: "devices.capabilities.color_setting", instance: "colorTemperatureK", value: 3000]]
    assert lamp().currentValue("colorMode") == "CT" && lamp().currentValue("colorTemperature") == 3000
    app.apiControlResp(resp(200, [code: 200, msg: "success"]), c[0][3])
    hub.settle()
    assert lamp().currentValue("transport") == "api"
}

check("several unconfirmed commands: only the newest value per capability is resent") {
    lamp().setLevel(20)
    lamp().setLevel(30)
    lamp().setLevel(40)
    hub.settle()
    int posts = app.callsNamed("asynchttpPost").size()
    hub.advance(7000)
    conn().checkPending()
    hub.settle()
    List c = app.callsNamed("asynchttpPost").drop(posts)
    assert c.size() == 1 && bodyOf(c[0]).payload.capability.value == 40
}

check("a report contradicting a waiting command doesn't flip the switch back or confirm it (H6052)") {
    app.state.remove("iotMisses"); app.state.remove("apiOnly")
    mqtt([sku: "H6052", device: LAMP, cmd: "status", state: [onOff: 0]])
    lamp().on()
    hub.settle()
    assert lamp().currentValue("switch") == "on"
    mqtt([sku: "H6052", device: LAMP, cmd: "status", state: [onOff: 0, brightness: 40, result: 1]])
    assert lamp().currentValue("switch") == "on", "the stale off is held back"
    assert lamp().currentValue("level") == 40, "other values still come through"
    int posts = app.callsNamed("asynchttpPost").size()
    hub.advance(7000)
    conn().checkPending()
    hub.settle()
    List c = app.callsNamed("asynchttpPost").drop(posts)
    assert c.size() == 1 && bodyOf(c[0]).payload.capability.instance == "powerSwitch"
    assert app.state.iotMisses[LAMP] == 1 && !app.apiOnly(LAMP)
}

check("after two misses in a row, the device's commands go straight to the API") {
    lamp().off()
    hub.settle()
    hub.advance(7000)
    conn().checkPending()
    hub.settle()
    assert app.apiOnly(LAMP)
    assert app.log.lines.any { it[1].contains("ignores commands there") }
    int from = pubCount()
    int posts = app.callsNamed("asynchttpPost").size()
    lamp().on()
    hub.settle()
    assert pubCount() == from, "no MQTT publish"
    assert bodyOf(app.callsNamed("asynchttpPost").drop(posts)[0]).payload.capability == [type: "devices.capabilities.on_off", instance: "powerSwitch", value: 1]
    // Status still arrives over MQTT.
    mqtt([sku: "H6052", device: LAMP, cmd: "status", state: [onOff: 1]])
    assert lamp().currentValue("switch") == "on" && lamp().currentValue("transport") == "iot"
    hub.advance(6 * 3600 * 1000L + 1000)
    assert !app.apiOnly(LAMP), "MQTT is tried again after 6 hours"
}

check("a matching report confirms and clears the misses; a dragged slider confirms on its last value") {
    app.state.iotMisses = [(LAMP): 1]
    int from = pubCount()
    lamp().setLevel(20)
    lamp().setLevel(30)
    lamp().setLevel(40)
    hub.settle()
    assert published(from).size() == 3
    mqtt([sku: "H6052", device: LAMP, cmd: "status", state: [onOff: 1, brightness: 39]])
    int posts = app.callsNamed("asynchttpPost").size()
    hub.advance(7000)
    conn().checkPending()
    hub.settle()
    assert app.callsNamed("asynchttpPost").size() == posts, "nothing resent"
    assert !app.state.iotMisses[LAMP]
}

check("expected values per MQTT command") {
    assert app.expectFor("turn", [val: 1]) == [onOff: 1]
    assert app.expectFor("brightness", [val: 30]) == [brightness: 30]
    assert app.expectFor("colorwc", [color: [r: 0, g: 0, b: 0], colorTemInKelvin: 3000]) == [colorTemInKelvin: 3000]
    assert app.expectFor("colorwc", [color: [r: 1, g: 2, b: 3], colorTemInKelvin: 0]) == [color: [r: 1, g: 2, b: 3]]
    def d = conn().driver
    assert d.reportMatches([color: [r: 255, g: 0, b: 0]], [color: [r: 253, g: 1, b: 0]])
    assert !d.reportMatches([color: [r: 255, g: 0, b: 0]], [color: [r: 0, g: 0, b: 255]])
    assert d.reportMatches([colorTemInKelvin: 3000], [colorTemInKelvin: 3030]) && !d.reportMatches([onOff: 1], [brightness: 3])
}

check("fan: power over MQTT; speed, mode and oscillation over the API") {
    int from = pubCount()
    fan().on()
    hub.settle()
    assert new JsonSlurper().parseText(published(from).last()[2]).msg.cmd == "turn"
    int posts = app.callsNamed("asynchttpPost").size()
    fan().setSpeed("high")
    fan().setMode("Sleep")
    fan().setOscillation("on")
    hub.settle()
    List caps = app.callsNamed("asynchttpPost").drop(posts).collect { bodyOf(it).payload.capability }
    assert caps == [[type: "devices.capabilities.work_mode", instance: "workMode", value: [workMode: 1, modeValue: 8]],
                    [type: "devices.capabilities.work_mode", instance: "workMode", value: [workMode: 5, modeValue: 0]],
                    [type: "devices.capabilities.toggle", instance: "oscillationToggle", value: 1]]
    assert fan().currentValue("mode") == "Sleep" && fan().currentValue("oscillation") == "on"
}

check("fan: an MQTT power report triggers an API read, which fills in speed") {
    int runIns = app.callsNamed("runIn").size()
    mqtt([sku: "H7111", device: FAN, cmd: "status", state: [onOff: 1, result: 1]])
    assert app.callsNamed("runIn").drop(runIns).any { it[2] == "refreshDevice" }
    app.refreshDevice([id: FAN])
    List c = app.callsNamed("asynchttpPost").last()
    assert c[1] == "apiStateResp" && bodyOf(c).payload == [sku: "H7111", device: FAN]
    app.apiStateResp(resp(200, [code: 200, payload: [sku: "H7111", device: FAN, capabilities: [
        [instance: "online", state: [value: true]], [instance: "powerSwitch", state: [value: 1]],
        [instance: "workMode", state: [value: [workMode: 1, modeValue: 3]]], [instance: "oscillationToggle", state: [value: 0]]]]]), c[3])
    hub.settle()
    assert fan().currentValue("speedLevel") == 3 && fan().currentValue("speed") == "medium-low" && fan().currentValue("oscillation") == "off"
    assert fan().currentValue("mode") == "FanSpeed"
}

check("polling reads fans and quiet devices, not lights that report over MQTT") {
    int posts = app.callsNamed("asynchttpPost").size()
    app.pollQuiet()
    List ids = app.callsNamed("asynchttpPost").drop(posts).collect { bodyOf(it).payload.device }
    assert ids == [FAN]
    app.noteIot(LAMP, hub.scripts[0].now() - 11 * 60 * 1000L)
    posts = app.callsNamed("asynchttpPost").size()
    app.pollQuiet()
    ids = app.callsNamed("asynchttpPost").drop(posts).collect { bodyOf(it).payload.device }
    assert ids as Set == [FAN, LAMP] as Set
}

check("with the live connection down, commands go straight to the API") {
    conn().driver.mqtt.connected = false
    conn().mqttClientStatus("Error: Connection lost: connection reset")
    hub.settle()
    assert conn().currentValue("connection") == "disconnected"
    assert conn().driver.callsNamed("runIn").last()[2] == "connectMqtt"
    int from = pubCount()
    int posts = app.callsNamed("asynchttpPost").size()
    lamp().off()
    hub.settle()
    assert pubCount() == from
    assert bodyOf(app.callsNamed("asynchttpPost").drop(posts)[0]).payload.capability == [type: "devices.capabilities.on_off", instance: "powerSwitch", value: 0]
}

check("reconnect backs off and the watchdog reconnects") {
    conn().driver.mqtt.refuse = true
    conn().connectMqtt()
    conn().afterConnect()
    hub.settle()
    assert conn().driver.state.failures >= 2
    List waits = conn().driver.callsNamed("runIn").findAll { it[2] == "connectMqtt" }.collect { it[1] }
    assert waits[-1] > waits[-2]
    conn().driver.mqtt.refuse = false
    conn().watchdog()
    conn().afterConnect()
    hub.settle()
    assert conn().currentValue("connection") == "connected" && conn().driver.state.failures == 0
}

check("an expired token: discovery says so, the live connection keeps going") {
    app.accountDevicesResp(resp(200, [status: 200, message: "token expired"]), [:])
    hub.settle()
    assert app.state.needsSignIn && conn().currentValue("connection") == "connected"
    app.mainPage()
}

check("no secret reaches a log, event or child data") {
    List text = hub.scripts.collectMany { it.log.lines*.getAt(1) }
    text += hub.scripts.findAll { it.device }.collectMany { it.device.events*.value*.toString() + it.device.data.values() }
    String all = text.join("\n")
    [PASSWORD, TOKEN, "owner@example.com", "Owner@Example.com", ACCOUNT_TOPIC, topic(LAMP), "BEGIN", "test-pass-1"].each {
        assert !all.contains(it), "leaked ${it}"
    }
}

check("sign out forgets the token and certificate and stops the connection") {
    app.appButtonHandler("btnSignOut")
    hub.settle()
    assert !app.state.token && !app.state.iot && conn().currentValue("connection") == "disconnected"
    app.mainPage()
}

Check.finish()
