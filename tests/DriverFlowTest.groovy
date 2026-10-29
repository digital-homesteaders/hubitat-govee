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

// The fake hub never answers HTTP itself: requests a check leaves open mustn't hold up the next.
Check.before = { app.class.API_OPEN.clear(); app.class.API_QUEUE.clear() }

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

check("the password waiting for the code is kept in memory only, and not for long") {
    assert !app.state.pendingPassword && !JsonOutput.toJson(app.state).contains(PASSWORD), "never in state"
    int posts = app.callsNamed("asynchttpPost").size()
    hub.advance(16 * 60 * 1000L)
    app.settings.verifyCode = "1234"
    app.appButtonHandler("btnCode")
    app.mainPage()
    assert app.callsNamed("asynchttpPost").size() == posts && app.state.login.error.contains("Enter your password again")
    // Starting over works as before.
    app.settings.password = PASSWORD
    app.appButtonHandler("btnSignIn")
    app.mainPage()
    hub.settle()
    List c = lastHttp()
    assert bodyOf(c).password == PASSWORD && !bodyOf(c).code
    app.loginResp(resp(200, [status: 454, message: ""]), c[3])
    app.codeResp(resp(200, [status: 200]), [:])
    hub.settle()
    assert app.state.login.step == "code"
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
    assert conn().driver.callsNamed("runIn").any { it[2] == "afterConnect" }
    assert conn().driver.callsNamed("socket.connect")*.getAt(1) as Set == [4001, 4002] as Set, "LAN sockets open"
}

check("Done records each driver's version; the app page names any mismatch") {
    assert lamp().getDataValue("driverVersion") == conn().getDataValue("driverVersion")
    assert app.mainPage() != null
    lamp().updateDataValue("driverVersion", "0.0.1")
    assert app.driverVersionNote().contains("Aura Table Lamp (0.0.1)")
    app.updated()
    hub.settle()
    assert app.driverVersionNote() == ""
}

check("each device records its driver's name; a light on fan code is named on the page and warned") {
    assert lamp().getDataValue("driverName") == "Govee Light" && conn().getDataValue("driverName") == "Govee Cloud Connection"
    assert app.wrongDrivers() == []
    // As if the fan driver had been pasted into the light's Drivers Code entry.
    lamp().updateDataValue("driverName", "Govee Fan")
    assert app.wrongDrivers() == [[device: "Aura Table Lamp", runs: "Govee Fan", needs: "Govee Light"]]
    assert app.driverVersionNote().contains("Aura Table Lamp runs Govee Fan but needs Govee Light")
    lamp().updateDataValue("kind", "fan")
    lamp().goveeHello()
    assert lamp().driver.log.lines.any { it[1].contains("is a fan but runs the Govee Light driver code") }
    lamp().updateDataValue("kind", "light")
    lamp().goveeHello()
    assert app.wrongDrivers() == [] && app.driverVersionNote() == ""
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
    assert l.currentValue("hue") == 67 && l.currentValue("healthStatus") == "online" && l.currentValue("transport") == "cloud-live"
    assert !l.getDataValue("lastIot"), "no device-data write per message"
    assert l.currentValue("colorTemperature") != null, "the RGBW tile needs a colour temperature even in RGB mode"
    assert l.lastEvent("level").descriptionText == "${l.displayName} brightness is 3%"
    l.current.goveeMode = "4"
    l.goveeHello()
    assert l.currentValue("goveeMode") == null, "the old raw mode attribute is cleared"
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
    assert lamp().currentValue("transport") == "cloud-api"
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
    assert lamp().currentValue("switch") == "on" && lamp().currentValue("transport") == "cloud-live"
    hub.advance(6 * 3600 * 1000L)
    assert app.apiOnly(LAMP), "still API-only after 6 hours"
    hub.advance(7 * 24 * 3600 * 1000L)
    assert !app.apiOnly(LAMP), "MQTT is tried again after a week"
    // One miss on the retry sends it straight back to the API.
    lamp().off()
    hub.settle()
    hub.advance(7000)
    conn().checkPending()
    hub.settle()
    assert app.apiOnly(LAMP)
}

check("a matching report confirms and clears the misses; a dragged slider confirms on its last value") {
    app.state.remove("apiOnly")
    mqtt([sku: "H6052", device: LAMP, cmd: "status", state: [onOff: 1]])
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

check("a contradicting answer to the confirm query fails the command at once") {
    app.state.remove("iotMisses"); app.state.remove("apiOnly")
    lamp().on()
    hub.settle()
    // A report before the confirm query is not its answer: keep waiting.
    int posts = app.callsNamed("asynchttpPost").size()
    mqtt([sku: "H6052", device: LAMP, cmd: "status", state: [onOff: 0]])
    assert app.callsNamed("asynchttpPost").size() == posts
    conn().confirmQuery()
    hub.advance(400)
    mqtt([sku: "H6052", device: LAMP, cmd: "status", state: [onOff: 0, brightness: 40]])
    List c = app.callsNamed("asynchttpPost").drop(posts)
    assert c.size() == 1 && bodyOf(c[0]).payload.capability == [type: "devices.capabilities.on_off", instance: "powerSwitch", value: 1]
    assert lamp().currentValue("switch") == "on", "the stale off is still held back"
    hub.advance(7000)
    conn().checkPending()
    assert app.callsNamed("asynchttpPost").size() == posts + 1, "not sent twice"
    app.state.remove("iotMisses"); app.state.remove("apiOnly")
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

check("scenes: fetched per light, numbered as light effects, set over the API") {
    app.state.remove("apiOnly")
    int posts = app.callsNamed("asynchttpPost").size()
    app.loadScenes(true)
    List c = app.callsNamed("asynchttpPost").drop(posts)
    assert c.collect { it[2].uri.tokenize("/").last() } as Set == ["scenes", "diy-scenes"] as Set
    assert c.every { bodyOf(it).payload.device == LAMP }, "only the light has scenes"
    List sceneCall = c.find { it[2].uri.endsWith("/device/scenes") }
    app.scenesResp(resp(200, [code: 200, payload: [capabilities: [[type: "devices.capabilities.dynamic_scene", instance: "lightScene",
        parameters: [options: [[name: "Sunrise", value: [paramId: 1681, id: 1606]], [name: "Ocean", value: [paramId: 1683, id: 1608]]]]]]]]), sceneCall[3])
    List diyCall = c.find { it[2].uri.endsWith("/diy-scenes") }
    app.scenesResp(resp(200, [code: 200, payload: [capabilities: [[type: "devices.capabilities.dynamic_scene", instance: "diyScene",
        parameters: [options: [[name: "Mine", value: 8455]]]]]]]), diyCall[3])
    hub.settle()
    assert new JsonSlurper().parseText(lamp().currentValue("lightEffects")) == ["1": "Sunrise", "2": "Ocean", "3": "DIY: Mine"]
    posts = app.callsNamed("asynchttpPost").size()
    lamp().setEffect(2)
    hub.settle()
    assert bodyOf(app.callsNamed("asynchttpPost").drop(posts)[0]).payload.capability == [type: "devices.capabilities.dynamic_scene", instance: "lightScene", value: [paramId: 1683, id: 1608]]
    assert lamp().currentValue("effectName") == "Ocean"
    lamp().setNextEffect()
    hub.settle()
    assert lamp().currentValue("effectName") == "DIY: Mine"
    assert bodyOf(app.callsNamed("asynchttpPost").last()).payload.capability == [type: "devices.capabilities.dynamic_scene", instance: "diyScene", value: 8455]
    lamp().setNextEffect()
    hub.settle()
    assert lamp().currentValue("effectName") == "Sunrise", "wraps around"
    lamp().setScene("ocean")
    hub.settle()
    assert lamp().currentValue("effectName") == "Ocean"
    lamp().setScene("min")
    hub.settle()
    assert lamp().currentValue("effectName") == "DIY: Mine", "part of a name is enough"
    lamp().setScene("1")
    hub.settle()
    assert lamp().currentValue("effectName") == "Sunrise"
    assert lamp().driver.sceneOptions() == ["DIY: Mine", "Ocean", "Sunrise"]
    // Favorites (by name): Next and Previous cycle through these only, in list order.
    lamp().driver.settings.favoriteScenes = ["DIY: Mine", "Sunrise"]
    lamp().updated()
    assert new JsonSlurper().parseText(lamp().currentValue("lightEffects")) == ["1": "Sunrise", "2": "DIY: Mine"], "just the favorites"
    lamp().setEffect(2)
    hub.settle()
    assert lamp().currentValue("effectName") == "DIY: Mine"
    lamp().setScene("ocean")
    hub.settle()
    assert lamp().currentValue("effectName") == "Ocean", "Set Scene still reaches every scene"
    // Ocean isn't a favorite: Next starts at the first favorite.
    lamp().setNextEffect()
    hub.settle()
    assert lamp().currentValue("effectName") == "Sunrise"
    lamp().setNextEffect()
    hub.settle()
    assert lamp().currentValue("effectName") == "DIY: Mine"
    lamp().setNextEffect()
    hub.settle()
    assert lamp().currentValue("effectName") == "Sunrise", "wraps around"
    lamp().setPreviousEffect()
    hub.settle()
    assert lamp().currentValue("effectName") == "DIY: Mine"
    lamp().driver.settings.remove("favoriteScenes")
    lamp().updated()
    assert new JsonSlurper().parseText(lamp().currentValue("lightEffects")).size() == 3
    lamp().setColorTemperature(4000)
    hub.settle()
    assert lamp().currentValue("effectName") == "none"
    posts = app.callsNamed("asynchttpPost").size()
    lamp().setEffect(9)
    assert app.callsNamed("asynchttpPost").size() == posts
}

String lanEvent(String ip, Map msg) {
    JsonOutput.toJson([fromIp: ip, payload: JsonOutput.toJson([msg: msg]).getBytes("UTF-8").encodeHex().toString()])
}
List hubActions(int from = 0) { conn().driver.callsNamed("sendHubCommand").drop(from).collect { [it[1].options.destinationAddress, new JsonSlurper().parseText(it[1].action).msg] } }

check("LAN: a scan finds the lamp; our own scan echo is ignored") {
    app.state.remove("iotMisses"); app.state.remove("apiOnly")
    conn().scanLan()
    List sent = conn().driver.callsNamed("socket.send").last()
    assert sent[1] == 4001 && new JsonSlurper().parseText(sent[2]) == [msg: [cmd: "scan", data: [account_topic: "reserve"]]]
    conn().parse(lanEvent("192.168.1.9", [cmd: "scan", data: [account_topic: "reserve"]]))
    assert !app.state.lan
    conn().parse(lanEvent("192.168.1.50", [cmd: "scan", data: [ip: "192.168.1.50", device: LAMP, sku: "H6052", bleVersionHard: "3.01.01"]]))
    hub.settle()
    assert app.state.lan[LAMP].ip == "192.168.1.50" && lamp().getDataValue("lanIp") == "192.168.1.50"
    assert app.lanUsable(LAMP) && app.log.lines.any { it[1].contains("found on the LAN at 192.168.1.50") }
}

check("LAN: commands go to the lamp directly and a devStatus read confirms them") {
    int from = pubCount()
    int acts = conn().driver.callsNamed("sendHubCommand").size()
    lamp().on()
    lamp().setColorTemperature(3000)
    hub.settle()
    assert pubCount() == from, "nothing over MQTT"
    List a = hubActions(acts)
    assert a[0] == ["192.168.1.50:4003", [cmd: "turn", data: [value: 1]]]
    assert a[1] == ["192.168.1.50:4003", [cmd: "colorwc", data: [color: [r: 0, g: 0, b: 0], colorTemInKelvin: 3000]]]
    assert conn().driver.callsNamed("runInMillis").takeRight(2)*.getAt(2) == ["lanConfirm", "checkPending"]
    conn().lanConfirm()
    assert hubActions(acts).last() == ["192.168.1.50:4003", [cmd: "devStatus", data: [:]]]
    conn().parse(lanEvent("192.168.1.50", [cmd: "devStatus", data: [onOff: 1, brightness: 40, color: [r: 0, g: 0, b: 0], colorTemInKelvin: 3000]]))
    hub.settle()
    assert lamp().currentValue("transport") == "local" && lamp().currentValue("colorTemperature") == 3000
    int posts = app.callsNamed("asynchttpPost").size()
    hub.advance(7000)
    conn().checkPending()
    assert app.callsNamed("asynchttpPost").size() == posts && pubCount() == from, "confirmed: nothing resent"
}

check("LAN: a fading light is read again; it confirms on a later read") {
    int from = pubCount()
    lamp().setLevel(80)
    hub.settle()
    conn().lanConfirm()
    conn().parse(lanEvent("192.168.1.50", [cmd: "devStatus", data: [onOff: 1, brightness: 55]]))
    hub.settle()
    assert conn().driver.callsNamed("runInMillis").last()[2] == "lanConfirm", "read again"
    assert lamp().currentValue("level") == 80, "the mid-fade value is held back"
    conn().lanConfirm()
    conn().parse(lanEvent("192.168.1.50", [cmd: "devStatus", data: [onOff: 1, brightness: 80]]))
    hub.settle()
    assert pubCount() == from && !app.state.lanMisses?.get(LAMP), "confirmed, nothing sent through the cloud"
}

check("LAN: a light that still disagrees is sent the command again over the LAN, then through the cloud") {
    int from = pubCount()
    lamp().off()
    hub.settle()
    int acts = conn().driver.callsNamed("sendHubCommand").size()
    3.times {
        conn().lanConfirm()
        conn().parse(lanEvent("192.168.1.50", [cmd: "devStatus", data: [onOff: 1, brightness: 40]]))
        hub.settle()
    }
    assert published(from).isEmpty() && hubActions(acts).findAll { it[1].cmd == "turn" } == [["192.168.1.50:4003", [cmd: "turn", data: [value: 0]]]], "resent over the LAN"
    3.times {
        assert published(from).isEmpty()
        conn().lanConfirm()
        conn().parse(lanEvent("192.168.1.50", [cmd: "devStatus", data: [onOff: 1, brightness: 40]]))
        hub.settle()
    }
    List p = published(from)
    assert p.size() == 1 && new JsonSlurper().parseText(p[0][2]).msg.cmd == "turn", "fell back to MQTT"
    assert lamp().currentValue("switch") == "off", "the stale on is held back"
    assert app.state.lanMisses[LAMP] == 1 && app.lanUsable(LAMP)
}

check("LAN: a command overtaken by a newer one is never resent through the cloud") {
    app.state.remove("lanMisses")
    int from = pubCount()
    int posts = app.callsNamed("asynchttpPost").size()
    lamp().off()
    hub.settle()
    // The off is still waiting when the user turns it back on, then the off's timer runs out.
    conn().driver.mem().pending.get(LAMP)?.each { it.at = 0L }
    List keep = conn().driver.mem().pending.get(LAMP)
    lamp().on()
    hub.settle()
    conn().driver.mem().pending.put(LAMP, keep + conn().driver.mem().pending.get(LAMP))
    hub.advance(3000)
    conn().checkPending()
    hub.settle()
    List cloud = published(from).collect { new JsonSlurper().parseText(it[2]).msg.data } +
        app.callsNamed("asynchttpPost").drop(posts).collect { bodyOf(it).payload.capability.value }
    assert !cloud.any { it == [val: 0] || it == 0 }, "the stale off isn't resent: ${cloud}"
    // Leave things as the next test expects: one miss so far, nothing waiting.
    conn().driver.mem().pending.clear()
    app.state.lanMisses = [(LAMP): 1]
    app.state.remove("lanOff")
}

check("LAN: two misses in a row pause LAN for the lamp for an hour") {
    lamp().on()
    hub.settle()
    hub.advance(1000)
    conn().checkPending()
    assert app.lanUsable(LAMP), "not given up after 1 s"
    hub.advance(2000)
    conn().checkPending()
    hub.settle()
    assert !app.lanUsable(LAMP) && app.log.lines.any { it[1].contains("isn't answering on the LAN") }
    int acts = conn().driver.callsNamed("sendHubCommand").size()
    int from = pubCount()
    lamp().off()
    hub.settle()
    assert conn().driver.callsNamed("sendHubCommand").size() == acts && pubCount() == from + 1
    hub.advance(3600 * 1000L + 1000)
    conn().parse(lanEvent("192.168.1.50", [cmd: "scan", data: [ip: "192.168.1.50", device: LAMP, sku: "H6052"]]))
    hub.settle()
    assert app.lanUsable(LAMP), "LAN is used again after the hour, once a scan sees it"
    app.state.remove("lan")
    app.state.remove("lanOff")
}

check("repeated scene names get a number so each can be picked") {
    lamp().goveeScenes("scene", [["Meditation", "scene", [id: 1]], ["Calm", "scene", [id: 2]], ["Meditation", "scene", [id: 3]]])
    lamp().goveeScenes("diy", [])
    assert lamp().driver.sceneOptions() == ["Calm", "Meditation", "Meditation (2)"]
    assert lamp().driver.sceneNumbers()["Meditation (2)"] == 3
    lamp().setScene("meditation (2)")
    hub.settle()
    assert bodyOf(app.callsNamed("asynchttpPost").last()).payload.capability.value == [id: 3]
}

check("LAN scenes: the library is fetched once per model when a lamp turns up on the LAN") {
    app.state.remove("lanOff"); app.state.remove("lanMisses")
    app.class.SCENE_LIB.clear(); app.class.LIB_FETCHING.clear()
    int gets = app.callsNamed("asynchttpGet").size()
    conn().parse(lanEvent("192.168.1.50", [cmd: "scan", data: [ip: "192.168.1.50", device: LAMP, sku: "H6052"]]))
    conn().parse(lanEvent("192.168.1.50", [cmd: "scan", data: [ip: "192.168.1.50", device: LAMP, sku: "H6052"]]))
    hub.settle()
    List g = app.callsNamed("asynchttpGet").drop(gets)
    assert g.size() == 1 && g[0][1] == "libraryResp" && g[0][2].uri.endsWith("/appsku/v1/light-effect-libraries") && g[0][2].query == [sku: "H6052"]
    assert !g[0][2].headers.Authorization, "no sign-in needed"
    app.libraryResp(resp(200, new File(root, "tests/fixtures/scene-library-h6072.json").text), g[0][3])
    assert app.log.lines.any { it[1].contains("scene library for H6052") }
}

check("LAN scenes: a library scene goes to the lamp as ptReal frames; others use the API") {
    lamp().goveeScenes("scene", [["Sunrise", "scene", [paramId: 1681, id: 1606]], ["Cloud Only", "scene", [paramId: 1, id: 1]]])
    lamp().goveeScenes("diy", [["Mine", "diy", 8455]])
    mqtt([sku: "H6052", device: LAMP, cmd: "status", state: [onOff: 0]])
    int acts = conn().driver.callsNamed("sendHubCommand").size()
    int posts = app.callsNamed("asynchttpPost").size()
    lamp().setScene("Sunrise")
    hub.settle()
    List a = hubActions(acts)
    assert a[0] == ["192.168.1.50:4003", [cmd: "turn", data: [value: 1]]], "turned on first"
    assert a[1][0] == "192.168.1.50:4003" && a[1][1].cmd == "ptReal"
    List frames = a[1][1].data.command
    assert frames.last().decodeBase64().encodeHex().toString().startsWith("3305043308"), "code 2099 = 0x0833"
    assert frames == app.lanSceneFrames("H6052", app.libraryScene("H6052", [paramId: 1681, id: 1606]))
    assert app.callsNamed("asynchttpPost").size() == posts, "no API call"
    assert lamp().currentValue("effectName") == "Sunrise"
    mqtt([sku: "H6052", device: LAMP, cmd: "status", state: [onOff: 1]])
    acts = conn().driver.callsNamed("sendHubCommand").size()
    lamp().setScene("Cloud Only")
    lamp().setScene("Mine")
    hub.settle()
    assert hubActions(acts).findAll { it[1].cmd == "ptReal" }.isEmpty()
    List caps = app.callsNamed("asynchttpPost").drop(posts).collect { bodyOf(it).payload.capability.instance }
    assert caps == ["lightScene", "diyScene"]
    app.state.remove("lan")
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

check("light: an unknown scene is refused on the device page") {
    lamp().setScene("No Such Scene Anywhere")
    hub.settle()
    assert lamp().currentValue("lastError")
    lamp().on()
    hub.settle()
    assert lamp().currentValue("lastError") == null
    // An empty name would otherwise match the first scene.
    int posts = app.callsNamed("asynchttpPost").size()
    lamp().setScene("")
    hub.settle()
    assert lamp().currentValue("lastError").startsWith("Set Scene needs a scene name")
    assert app.callsNamed("asynchttpPost").size() == posts
    // Commands that don't send anything still clear it.
    lamp().refresh()
    assert lamp().currentValue("lastError") == null
    // A name given to Set Effect is looked up as a scene.
    lamp().setEffect("No Such Scene Anywhere")
    assert lamp().currentValue("lastError") == "no scene called 'No Such Scene Anywhere' (see lightEffects)"
    lamp().stopLevelChange()
    assert lamp().currentValue("lastError") == null
}

check("fan: a mode it lacks is refused on the device page, and the next command clears it") {
    int posts = app.callsNamed("asynchttpPost").size()
    fan().setMode("Party")
    hub.settle()
    assert app.callsNamed("asynchttpPost").size() == posts
    assert fan().currentValue("lastError").startsWith("this fan has no mode 'Party'; it has Fixed speed")
    fan().setMode("Sleep")
    hub.settle()
    assert fan().currentValue("lastError") == null
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
    assert fan().currentValue("mode") == "Fixed speed"
}

check("polling reads fans and quiet devices, not lights that report over MQTT") {
    app.noteIot(LAMP, hub.scripts[0].now())
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

check("API requests wait their turn, commands first, so Hubitat isn't left with a pile pending") {
    app.class.API_OPEN.clear(); app.class.API_QUEUE.clear()
    int posts = app.callsNamed("asynchttpPost").size()
    List ids = (1..6).collect { "AA:BB:CC:00:00:0${it}".toString() }
    ids.each { app.apiRequest("POST", "/device/state", [payload: [device: it]], "apiStateResp", [id: it]) }
    app.apiRequest("POST", "/device/state", [payload: [device: ids[5]]], "apiStateResp", [id: ids[5]])
    List sent = app.callsNamed("asynchttpPost").drop(posts)
    assert sent.size() == 4, "no more than four at once"
    assert app.apiQueue().reads.size() == 2, "a read already waiting isn't queued twice"
    app.apiRequest("POST", "/device/control", [payload: [device: LAMP]], "apiControlResp", [id: LAMP, instance: "powerSwitch"])
    // An answer frees a slot: the command goes ahead of the reads.
    app.apiStateResp(resp(200, [code: 200, payload: [capabilities: []]]), sent[0][3])
    assert app.callsNamed("asynchttpPost").last()[1] == "apiControlResp"
    app.apiStateResp(resp(200, [code: 200, payload: [capabilities: []]]), sent[1][3])
    assert bodyOf(app.callsNamed("asynchttpPost").last()).payload.device == ids[4]
    // An answer that never comes stops holding a slot.
    hub.advance(60 * 1000L)
    int before = app.callsNamed("asynchttpPost").size()
    app.apiRequest("POST", "/device/state", [payload: [device: LAMP]], "apiStateResp", [id: LAMP])
    assert app.callsNamed("asynchttpPost").size() == before + 1
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

check("reconnect backs off; the watchdog leaves the backoff alone, and the timer reconnects") {
    conn().driver.mqtt.refuse = true
    conn().connectMqtt()
    conn().afterConnect()
    hub.settle()
    assert conn().driver.state.failures >= 2
    List waits = conn().driver.callsNamed("runIn").findAll { it[2] == "connectMqtt" }.collect { it[1] }
    assert waits[-1] > waits[-2]
    conn().driver.mqtt.refuse = false
    int connects = conn().driver.callsNamed("mqtt.connect").size()
    conn().watchdog()
    assert conn().driver.callsNamed("mqtt.connect").size() == connects, "no connect while backing off"
    conn().connectMqtt()                            // the backoff timer firing
    conn().afterConnect()
    hub.settle()
    assert conn().currentValue("connection") == "connected" && conn().driver.state.failures == 0
}

// ---- client hygiene (the hub's memory-leak hunt, 2026-10-23): never leave an old client holding resources

/** Calls made by the connection driver since `from`, by name. */
List connCalls(int from) { conn().driver.calls.drop(from).collect { it[0] } }

check("connect always releases the old client first, even one that says it isn't connected, and after new code") {
    conn().driver.getClass().MEM.clear()            // new code or a restart: phase unknown
    conn().driver.mqtt.connected = false            // a half-dead client reports not connected
    int from = conn().driver.calls.size()
    conn().connectMqtt()
    List c = connCalls(from)
    assert c.indexOf("mqtt.disconnect") >= 0 && c.indexOf("mqtt.disconnect") < c.indexOf("mqtt.connect")
}

check("an error notice while connecting is ignored; afterConnect decides") {
    int failures = (conn().driver.state.failures ?: 0) as int
    conn().mqttClientStatus("Error: Connection lost: old client")
    hub.settle()
    assert conn().currentValue("connection") == "connecting" && ((conn().driver.state.failures ?: 0) as int) == failures
    int from = conn().driver.calls.size()
    conn().connectMqtt()                            // a second connect on top: skipped
    assert !connCalls(from).contains("mqtt.connect")
    conn().afterConnect()
    hub.settle()
    assert conn().currentValue("connection") == "connected"
}

check("several notices for one drop count as one failure") {
    conn().driver.mqtt.connected = false
    conn().mqttClientStatus("Error: Connection lost: connection reset")
    conn().mqttClientStatus("Error: Connection lost: connection reset")
    conn().mqttClientStatus("Connection lost")
    hub.settle()
    assert conn().driver.state.failures == 1
    conn().driver.mqtt.connected = true
    conn().connectMqtt()
    conn().afterConnect()
    hub.settle()
}

check("a silent drop (no notice) is the watchdog's: it reconnects") {
    assert conn().currentValue("connection") == "connected"
    conn().driver.mqtt.connected = false            // died without a status callback
    int connects = conn().driver.callsNamed("mqtt.connect").size()
    conn().watchdog()
    assert conn().driver.callsNamed("mqtt.connect").size() == connects + 1
    conn().afterConnect()
    hub.settle()
    assert conn().currentValue("connection") == "connected"
}

check("a connection that comes up cancels the backoff timer, so it can't tear it down later") {
    int from = conn().driver.calls.size()
    conn().driver.mqtt.connected = false
    conn().mqttClientStatus("Error: Connection lost: connection reset")
    conn().driver.mqtt.connected = true
    conn().connectMqtt()                            // Reconnect pressed while the backoff waits
    conn().afterConnect()
    hub.settle()
    assert conn().driver.calls.drop(from).any { it == ["unschedule", "connectMqtt"] }
}

check("a late notice from the old client, after the new connection is up, is ignored") {
    assert conn().currentValue("connection") == "connected"
    int failures = (conn().driver.state.failures ?: 0) as int
    conn().mqttClientStatus("Error: Connection lost: old client")
    hub.settle()
    assert conn().currentValue("connection") == "connected" && ((conn().driver.state.failures ?: 0) as int) == failures
}

check("the watchdog is scheduled from the first attempt, so a dropped timer is caught before any connection came up") {
    conn().driver.getClass().MEM.clear()
    conn().driver.mqtt.refuse = true
    int from = conn().driver.calls.size()
    conn().connectMqtt()
    conn().afterConnect()
    assert conn().driver.calls.drop(from).any { it[0] == "runIn" && it[2] == "watchdog" }
    conn().driver.mqtt.refuse = false
    conn().connectMqtt()
    conn().afterConnect()
    hub.settle()
    assert conn().currentValue("connection") == "connected"
}

check("with no certificate mid re-sign-in, the driver keeps retrying instead of stopping") {
    Map iot = app.state.iot
    app.state.remove("iot")
    try {
        conn().driver.mqtt.connected = false
        conn().mqttClientStatus("Error: Connection lost: connection reset")
        hub.settle()
        int from = conn().driver.calls.size()
        conn().connectMqtt()                        // the backoff timer firing
        assert conn().driver.calls.drop(from).any { it[0] == "runIn" && it[2] == "connectMqtt" }, "another try scheduled"
    } finally {
        app.state.iot = iot
    }
    conn().driver.mqtt.connected = true
    conn().connectMqtt()
    conn().afterConnect()
    hub.settle()
    assert conn().currentValue("connection") == "connected"
}

check("the watchdog restarts a reconnect whose timer the hub dropped") {
    conn().driver.mqtt.connected = false
    conn().mqttClientStatus("Error: Connection lost: connection reset")
    hub.settle()
    int connects = conn().driver.callsNamed("mqtt.connect").size()
    conn().watchdog()
    assert conn().driver.callsNamed("mqtt.connect").size() == connects, "the backoff timer is still due"
    hub.advance(10 * 60 * 1000L)                    // well past the longest wait: the timer was lost
    conn().watchdog()
    assert conn().driver.callsNamed("mqtt.connect").size() == connects + 1
    conn().driver.mqtt.connected = false            // that attempt went nowhere,
    hub.advance(2 * 60 * 1000L)                     // and its afterConnect was lost too
    conn().watchdog()
    assert conn().driver.callsNamed("mqtt.connect").size() == connects + 2, "a stalled connect is retried"
    conn().driver.mqtt.connected = true
    conn().afterConnect()
    hub.settle()
    assert conn().currentValue("connection") == "connected"
}

check("a light's mode number: learned after a colour command, then shows scenes started elsewhere") {
    lamp().setColorTemperature(2700)
    hub.settle()
    assert app.callsNamed("runIn").last()[2] == "modeQuery"
    mqtt([sku: "H6052", device: LAMP, cmd: "status", state: [mode: 5, onOff: 1]])
    assert !(app.state.lightModes ?: [:])[LAMP], "too soon after the command to learn from"
    hub.advance(3000)
    mqtt([sku: "H6052", device: LAMP, cmd: "status", state: [colorTemInKelvin: 2700, mode: 5, onOff: 1]])
    assert app.state.lightModes[LAMP].plain == ["5"]
    // A scene's number, learned the same way; a scene started in the Govee app then shows.
    app.learnMode(LAMP, "scene", "8")
    hub.advance(60000)
    mqtt([sku: "H6052", device: LAMP, cmd: "status", state: [colorTemInKelvin: 2700, mode: 8, onOff: 1]])
    assert lamp().currentValue("colorMode") == "EFFECTS" && lamp().currentValue("effectName") == "unknown"
    mqtt([sku: "H6052", device: LAMP, cmd: "status", state: [colorTemInKelvin: 2700, mode: 5, onOff: 1]])
    assert lamp().currentValue("colorMode") == "CT" && lamp().currentValue("effectName") == "none"
    // A number seen as both kinds is ambiguous and forgotten.
    app.learnMode(LAMP, "scene", "5")
    assert app.state.lightModes[LAMP].plain == [] && app.state.lightModes[LAMP].scene == ["8"]
}

check("a light that leaves a learned scene mode for one not learned yet is out of the scene") {
    mqtt([sku: "H6052", device: LAMP, cmd: "status", state: [mode: 8, onOff: 1]])
    assert lamp().currentValue("colorMode") == "EFFECTS"
    // A colour picked in the Govee app: a mode number Hubitat hasn't seen.
    mqtt([sku: "H6052", device: LAMP, cmd: "status", state: [color: [r: 0, g: 0, b: 255], colorTemInKelvin: 0, mode: 11, onOff: 1]])
    assert lamp().currentValue("colorMode") == "RGB" && lamp().currentValue("effectName") == "none"
    mqtt([sku: "H6052", device: LAMP, cmd: "status", state: [color: [r: 0, g: 0, b: 255], colorTemInKelvin: 0, mode: 11, onOff: 1]])
    assert lamp().currentValue("colorMode") == "RGB"
}

check("MQTT: a payload with unreadable bytes (H6117) is cleaned and read; garbage is skipped") {
    String bad = JsonOutput.toJson([sku: "H6117", device: LAMP, cmd: "status", state: [brightness: 61, onOff: 1]]).replace(',"cmd"', ',\u0001\uFFFD"cmd"')
    conn().parse(JsonOutput.toJson([topic: ACCOUNT_TOPIC, payload: bad]))
    hub.settle()
    assert lamp().currentValue("level") == 61
    conn().parse(JsonOutput.toJson([topic: ACCOUNT_TOPIC, payload: "\u0002\u0003not json"]))
    conn().parse("not even an MQTT message")
    hub.settle()
    assert lamp().currentValue("level") == 61
}

check("a failed API command reads the device's real state back") {
    int posts = app.callsNamed("asynchttpPost").size()
    app.apiControlResp(resp(400, [code: 400, msg: "device offline"]), [id: LAMP, instance: "powerSwitch"])
    List c = app.callsNamed("asynchttpPost").drop(posts)
    assert c.size() == 1 && c[0][1] == "apiStateResp" && bodyOf(c[0]).payload.device == LAMP
}

check("an HTTP 429 holds back polls, refreshes and scene lists, longer each time; a success ends it") {
    int posts = app.callsNamed("asynchttpPost").size()
    app.apiControlResp(resp(429, [code: 429, msg: "Too Many Requests"]), [id: LAMP, instance: "powerSwitch"])
    assert app.callsNamed("asynchttpPost").size() == posts, "the read-back waits too"
    List later = app.callsNamed("runIn").last()
    assert later[2] == "refreshDevice" && later[1] >= 60
    app.pollQuiet()
    app.refreshDevice([id: FAN])
    app.loadScenes(true)
    assert app.callsNamed("asynchttpPost").size() == posts
    hub.advance(61 * 1000L)
    assert !app.apiPaused()
    app.apiStateResp(resp(429, [code: 429]), [id: FAN])
    assert app.apiPauseLeft() > 60 * 1000L, "a second 429 in a row waits two minutes"
    hub.advance(121 * 1000L)
    app.apiStateResp(resp(200, [code: 200, payload: [capabilities: []]]), [id: FAN])
    app.apiStateResp(resp(429, [code: 429]), [id: FAN])
    assert app.apiPauseLeft() <= 60 * 1000L, "after a success it starts at a minute again"
    hub.advance(61 * 1000L)
    app.apiStateResp(resp(200, [code: 200, payload: [capabilities: []]]), [id: FAN])
    app.pollQuiet()
    assert app.callsNamed("asynchttpPost").size() > posts
}

check("the API key is sent without spaces or line breaks copied around it") {
    String key = app.settings.apiKey
    app.settings.apiKey = "  key-from-email\n"
    try {
        app.refreshDevice([id: FAN])
        assert lastHttp()[2].headers["Govee-API-Key"] == "key-from-email"
    } finally {
        app.settings.apiKey = key
    }
}

check("a rejected API key warns once, holds the API back an hour, and a new key or a success ends it") {
    String key = app.settings.apiKey
    app.settings.apiKey = "bad-key"
    // Callback data as apiRequest sends it: tagged with the key the request went with.
    Closure sent = { Map d -> d + [keyHash: app.settings.apiKey.trim().hashCode()] }
    try {
        int posts = app.callsNamed("asynchttpPost").size()
        int warns = app.log.lines.count { it[1].contains("API key was rejected") }
        3.times { app.apiStateResp(resp(401, [code: 401, message: "Invalid API Key"]), sent([id: FAN])) }
        assert app.log.lines.count { it[1].contains("API key was rejected") } == warns + 1, "one warning for a burst"
        assert app.apiKeyRejected() && app.apiPauseLeft() > 59 * 60 * 1000L
        app.pollQuiet()
        app.refreshDevice([id: FAN])
        app.apiControlResp(resp(401, [code: 401]), sent([id: LAMP, instance: "powerSwitch"]))
        app.sendApi(LAMP, "H6117", [type: "devices.capabilities.on_off", instance: "powerSwitch", value: 1])
        assert app.callsNamed("asynchttpPost").size() == posts
        assert app.log.lines.any { it[1].contains("powerSwitch not sent") }
        hub.advance(61 * 60 * 1000L)
        assert !app.apiPaused() && !app.apiKeyRejected(), "reads and commands try again after an hour"
        app.sendApi(LAMP, "H6117", [type: "devices.capabilities.on_off", instance: "powerSwitch", value: 1])
        assert app.callsNamed("asynchttpPost").size() == posts + 1, "a command goes after the hour"
        app.apiStateResp(resp(401, [code: 401]), sent([id: FAN]))
        assert app.log.lines.count { it[1].contains("API key was rejected") } == warns + 2, "warned again an hour later"
        Map oldKeyRequest = sent([id: FAN])
        app.settings.apiKey = "new-key"
        assert !app.apiKeyRejected() && !app.apiPaused(), "a new key goes straight away"
        app.apiStateResp(resp(401, [code: 401]), oldKeyRequest)
        assert !app.apiKeyRejected(), "a late answer for the old key doesn't blame the new one"
        app.settings.apiKey = "bad-key"
        app.apiStateResp(resp(401, [code: 401]), sent([id: FAN]))
        app.apiStateResp(resp(200, [code: 200, payload: [capabilities: []]]), sent([id: FAN]))
        assert !app.apiKeyRejected(), "a request that works clears it"
        warns = app.log.lines.count { it[1].contains("API key was rejected") }
        3.times { app.apiStateResp(resp(200, [code: 401, message: "Invalid API Key"]), sent([id: FAN])) }
        assert app.apiKeyRejected(), "a rejection carried in an HTTP 200 body still pauses the API"
        assert app.log.lines.count { it[1].contains("API key was rejected") } == warns + 1, "and warns once, not per answer"
        app.settings.apiKey = "   "
        app.sendApi(LAMP, "H6117", [type: "devices.capabilities.on_off", instance: "powerSwitch", value: 1])
        assert app.log.lines.any { it[1].contains("needs the Govee API key") }, "a key of only spaces is no key"
    } finally {
        app.settings.apiKey = key
        hub.settle()
    }
}

check("scene lists that fail to load are tried again in half an hour, not a day") {
    app.state.remove("scenesAt")
    hub.advance(31 * 60 * 1000L)
    int posts = app.callsNamed("asynchttpPost").size()
    app.loadScenes()
    List c = app.callsNamed("asynchttpPost").drop(posts)
    assert c.size() == 2
    c.each { app.scenesResp(resp(500, [code: 500]), it[3]) }
    hub.settle()
    assert !(app.state.scenesAt ?: [:])[LAMP]
    posts = app.callsNamed("asynchttpPost").size()
    app.loadScenes()
    assert app.callsNamed("asynchttpPost").size() == posts, "not on every poll"
    hub.advance(31 * 60 * 1000L)
    app.loadScenes()
    c = app.callsNamed("asynchttpPost").drop(posts)
    assert c.size() == 2
    app.scenesResp(resp(200, [code: 200, payload: [capabilities: []]]), c[0][3])
    hub.settle()
    assert app.state.scenesAt[LAMP]
    hub.advance(31 * 60 * 1000L)
    posts = app.callsNamed("asynchttpPost").size()
    app.loadScenes()
    assert app.callsNamed("asynchttpPost").size() == posts, "loaded: fresh for a day"
}

check("Set Color keeps the brightness unless the preference is off") {
    mqtt([sku: "H6052", device: LAMP, cmd: "status", state: [brightness: 20, onOff: 1]])
    lamp().setColor([hue: 0, saturation: 100, level: 100])
    hub.settle()
    assert lamp().currentValue("level") == 20 && lamp().currentValue("colorMode") == "RGB"
    lamp().driver.settings.colorKeepsLevel = false
    lamp().setColor([hue: 0, saturation: 100, level: 100])
    hub.settle()
    assert lamp().currentValue("level") == 100
    lamp().driver.settings.colorKeepsLevel = true
}

check("hold to dim: steps while held, stops on release, at the ends, or on another command") {
    lamp().goveeUpdate([brightness: 20, onOff: 1], "iot")
    assert app.commandRoute(lamp()) == "iot"
    lamp().startLevelChange("up")
    hub.settle()
    assert lamp().currentValue("level") == 30
    assert lamp().driver.callsNamed("runInMillis").last()[1] == 400L && lamp().driver.callsNamed("runInMillis").last()[2] == "rampStep"
    lamp().rampStep()
    assert lamp().currentValue("level") == 40
    lamp().stopLevelChange()
    lamp().rampStep()
    assert lamp().currentValue("level") == 40, "released: no more steps"
    lamp().startLevelChange("down")
    lamp().on()
    lamp().rampStep()
    assert lamp().currentValue("level") == 30, "another command stops the ramp"
    lamp().goveeUpdate([brightness: 95, onOff: 1], "iot")
    lamp().startLevelChange("up")
    assert lamp().currentValue("level") == 100
    lamp().rampStep()
    assert lamp().currentValue("level") == 100 && lamp().driver.RAMP.isEmpty(), "stops at the top"
    lamp().goveeUpdate([onOff: 0], "iot")
    lamp().startLevelChange("down")
    assert lamp().currentValue("switch") == "off" && lamp().driver.RAMP.isEmpty(), "dimming down an off light does nothing"
    lamp().startLevelChange("up")
    assert lamp().currentValue("switch") == "on" && lamp().currentValue("level") == 10, "brightening an off light starts low"
    lamp().stopLevelChange()
}

check("hold to dim over the API: bigger, slower steps; flash refused") {
    String key = app.settings.apiKey
    app.settings.apiKey = key ?: "test-key"
    app.state.apiOnly = [(LAMP): app.now()]
    hub.settle()
    try {
        assert app.commandRoute(lamp()) == "api"
        lamp().goveeUpdate([brightness: 20, onOff: 1], "iot")
        lamp().startLevelChange("up")
        assert lamp().currentValue("level") == 45 && lamp().driver.callsNamed("runInMillis").last()[1] == 1500L
        lamp().stopLevelChange()
        lamp().flash()
        assert lamp().driver.log.lines.any { it[1].contains("can't flash") } && lamp().driver.FLASH.isEmpty()
    } finally {
        app.state.apiOnly = [:]
        app.settings.apiKey = key
        hub.settle()
    }
}

check("flash: blinks and ends as it started; another command stops it") {
    assert app.commandRoute(lamp()) == "iot"
    lamp().goveeUpdate([onOff: 1], "iot")
    lamp().flash(100)
    assert lamp().currentValue("switch") == "off"
    assert lamp().driver.callsNamed("runInMillis").last()[1] == 500L, "no faster than 500 ms"
    9.times { lamp().flashStep() }
    assert lamp().currentValue("switch") == "on" && lamp().driver.FLASH.isEmpty()
    lamp().flash()
    lamp().setLevel(50)
    lamp().flashStep()
    assert lamp().currentValue("switch") == "on", "Set Level stopped the flash"
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

check("removing the last device stops the live connection and the LAN listener") {
    assert conn().driver.state.lan == true
    app.settings.selectedDevices = []
    app.updated()
    hub.settle()
    assert conn().currentValue("connection") == "disconnected" && conn().driver.state.lan == false
}

check("sign out forgets the token and certificate and stops the connection") {
    app.appButtonHandler("btnSignOut")
    hub.settle()
    assert !app.state.token && !app.state.iot && conn().currentValue("connection") == "disconnected"
    app.mainPage()
}

Check.finish()
