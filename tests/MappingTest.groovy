/**
 * Unit tests for the Govee mapping functions in apps/govee-connect.groovy:
 * the client id, MQTT and API state, capability summaries, events for lights
 * and fans, command plans, fan speed names, and colour conversion.
 */

import groovy.json.JsonSlurper
import groovy.transform.Field
import static Check.check

@Field File root = new File(getClass().protectionDomain.codeSource.location.path).parentFile.parentFile
@Field Hub hub = new Hub(root)
@Field def app = hub.app()
@Field List API = new JsonSlurper().parseText(new File(root, "tests/fixtures/api-devices.json").text)

Map capsFor(String sku) { app.capabilitySummary(API.find { it.sku == sku }.capabilities) }

FakeDevice dev(String kind, Map caps = [:]) {
    new FakeDevice(id: 1, deviceNetworkId: "govee-X", name: kind,
        data: [id: "AA:BB", sku: "HX", kind: kind, caps: groovy.json.JsonOutput.toJson(caps)])
}

Map events(String kind, Map s, Map caps = [:]) {
    app.goveeEvents(dev(kind, caps), s).collectEntries { [(it.name): it.value] }
}

Map plan(String kind, Map s, String cmd, List args = [], Map caps = [:]) { app.planCommand(dev(kind, caps), s, cmd, args) }

check("clientId is uuid5(DNS, 'hubitat-govee:' + lowercase email), as Python computes it") {
    assert app.clientIdFor(" Owner@Example.com ") == "218a537e3b005148bcdccfad4f4cc94c"
}

check("MQTT state from a real H6052 report") {
    Map s = app.normalizeIot([brightness: 3, color: [b: 255, g: 8, r: 13], colorTemInKelvin: 0, connected: "true", mode: 4, onOff: 0, result: 1])
    assert s == [onOff: 0, brightness: 3, color: [r: 13, g: 8, b: 255], ct: 0, mode: 4, online: true]
    assert app.normalizeIot([onOff: 1, result: 1]) == [onOff: 1]
    assert app.normalizeIot([connected: "false"]).online == false
}

check("API state capabilities") {
    Map s = app.normalizeApi([
        [instance: "online", state: [value: true]], [instance: "powerSwitch", state: [value: 1]],
        [instance: "brightness", state: [value: 40]], [instance: "colorRgb", state: [value: 0xFF8000]],
        [instance: "colorTemperatureK", state: [value: ""]], [instance: "workMode", state: [value: [workMode: 1, modeValue: 3]]],
        [instance: "oscillationToggle", state: [value: 0]]])
    assert s == [online: true, onOff: 1, brightness: 40, color: [r: 255, g: 128, b: 0], workMode: 1, modeValue: 3, oscillation: 0]
}

check("capability summary: H7111 fan speeds and modes, H6052 CT range") {
    Map f = capsFor("H7111")
    assert f.speedMode == 1 && f.speeds == [1, 2, 3, 4, 5, 6, 7, 8]
    assert f.modes.collect { it[0] } == ["FanSpeed", "Custom", "Auto", "Sleep", "Nature", "Storm"]
    assert "oscillationToggle" in f.instances
    Map l = capsFor("H6052")
    assert l.ct == [2000, 9000] && l.brightness == [1, 100] && "segmentedColorRgb" in l.instances
}

check("light events: RGB") {
    Map e = events("light", [onOff: 1, brightness: 3, color: [r: 13, g: 8, b: 255], ct: 0, online: true])
    assert e.switch == "on" && e.level == 3 && e.colorMode == "RGB" && e.healthStatus == "online"
    assert e.hue == 67 && e.saturation == 97 && e.color == "#0D08FF" && e.colorName == "Blue"
}

check("light events: colour temperature wins over a stale colour") {
    Map e = events("light", [onOff: 0, brightness: 100, color: [r: 0, g: 0, b: 0], ct: 6500])
    assert e.switch == "off" && e.colorMode == "CT" && e.colorTemperature == 6500 && e.colorName == "Skylight"
    assert !e.containsKey("hue")
}

check("light events: offline") {
    assert events("light", [online: false]).healthStatus == "offline"
}

check("fan events: speed step, named speed, level, mode, oscillation") {
    Map caps = capsFor("H7111")
    Map e = events("fan", [onOff: 1, workMode: 1, modeValue: 8, oscillation: 1], caps)
    assert e.speed == "high" && e.speedLevel == 8 && e.level == 100 && e.maxSpeedLevel == 8
    assert e.mode == "FanSpeed" && e.oscillation == "on"
    assert new JsonSlurper().parseText(e.supportedFanSpeeds) == ["low", "medium-low", "medium", "medium-high", "high", "off", "on"]
    assert events("fan", [onOff: 1, workMode: 1, modeValue: 1], caps).speed == "low"
    assert events("fan", [onOff: 1, workMode: 1, modeValue: 4], caps).speed == "medium"
    assert events("fan", [onOff: 0, workMode: 1, modeValue: 4], caps).speed == "off"
    assert events("fan", [onOff: 1, workMode: 3, modeValue: 0], caps).speed == "auto"
    assert events("fan", [onOff: 1, workMode: 5, modeValue: 0], caps).mode == "Sleep"
}

check("fan events with only MQTT power (no API key)") {
    Map e = events("fan", [onOff: 1])
    assert e.switch == "on" && !e.containsKey("speed")
}

check("plan: on/off go over MQTT, with the API as fallback") {
    Map p = plan("light", [:], "on")
    assert p.send == [[via: "iot", cmd: "turn", data: [val: 1], api: [type: "devices.capabilities.on_off", instance: "powerSwitch", value: 1]]]
    assert p.optimistic == [onOff: 1]
    assert plan("light", [onOff: 1], "off").send[0].data == [val: 0]
}

check("plan: setLevel turns an off light on; 0 turns it off") {
    Map p = plan("light", [onOff: 0], "setLevel", [40])
    assert p.send*.cmd == ["brightness", "turn"] && p.send[0].data == [val: 40] && p.optimistic == [brightness: 40, onOff: 1]
    assert plan("light", [onOff: 1], "setLevel", [40]).send*.cmd == ["brightness"]
    assert plan("light", [onOff: 1], "setLevel", [0]).send*.cmd == ["turn"]
}

check("plan: setColor sends colorwc RGB, level and power") {
    Map p = plan("light", [onOff: 0], "setColor", [[hue: 0, saturation: 100, level: 50]])
    assert p.send*.cmd == ["colorwc", "brightness", "turn"]
    assert p.send[0].data == [color: [r: 255, g: 0, b: 0], colorTemInKelvin: 0]
    assert p.send[0].api == [type: "devices.capabilities.color_setting", instance: "colorRgb", value: 0xFF0000]
    assert p.optimistic == [color: [r: 255, g: 0, b: 0], ct: 0, brightness: 50, onOff: 1]
}

check("plan: setHue keeps the current saturation") {
    Map p = plan("light", [onOff: 1, color: [r: 255, g: 128, b: 128], ct: 0], "setHue", [33.3])
    Map hsv = app.rgbToHsv(p.optimistic.color.r, p.optimistic.color.g, p.optimistic.color.b)
    assert Math.abs(hsv.hue - 33) <= 1 && Math.abs(hsv.saturation - 50) <= 1
}

check("plan: setColorTemperature is clamped to the device's range") {
    Map p = plan("light", [onOff: 1], "setColorTemperature", [12000], [ct: [2700, 6500]])
    assert p.send[0].data == [color: [r: 0, g: 0, b: 0], colorTemInKelvin: 6500] && p.optimistic == [ct: 6500]
    assert plan("light", [onOff: 1], "setColorTemperature", [1000]).send[0].data.colorTemInKelvin == 2000
}

check("plan: fan speeds and modes go over the API") {
    Map caps = capsFor("H7111")
    Map p = plan("fan", [onOff: 1], "setSpeed", ["high"], caps)
    assert p.send == [[via: "api", api: [type: "devices.capabilities.work_mode", instance: "workMode", value: [workMode: 1, modeValue: 8]]]]
    assert plan("fan", [:], "setSpeed", ["low"], caps).send[0].api.value.modeValue == 1
    assert plan("fan", [:], "setSpeed", ["medium"], caps).send[0].api.value.modeValue in [4, 5]
    // Every named speed lands on a step that reads back as the same name.
    [3, 4, 5, 6, 8, 12].each { n -> app.speedNamesFor(n).each { name -> assert app.speedNameFor(app.stepForSpeedName(name, n), n) == name } }
    assert plan("fan", [:], "setLevel", [50], caps).send[0].api.value.modeValue == 4
    assert plan("fan", [:], "setSpeedLevel", [20], caps).send[0].api.value.modeValue == 8
    assert plan("fan", [:], "setSpeed", ["off"], caps).send[0].cmd == "turn"
    assert plan("fan", [:], "setSpeed", ["auto"], caps).send[0].api.value == [workMode: 3, modeValue: 0]
    assert plan("fan", [:], "setMode", ["nature"], caps).send[0].api.value == [workMode: 6, modeValue: 0]
    assert plan("fan", [onOff: 1, workMode: 1, modeValue: 8], "cycleSpeed", [], caps).send[0].api.value.modeValue == 1
    assert plan("fan", [:], "setOscillation", ["on"], caps).send[0].api.instance == "oscillationToggle"
}

check("plan: fan speed without the API's capabilities is an error, power still works") {
    assert plan("fan", [:], "setSpeed", ["high"]).error && !plan("fan", [:], "setSpeed", ["high"]).send
    assert plan("fan", [onOff: 1], "setLevel", [50]).error
    assert plan("fan", [:], "setSpeed", ["bogus"], capsFor("H7111")).error
    assert plan("fan", [:], "setMode", ["Auto"]).error
    assert plan("fan", [:], "on").send[0].cmd == "turn"
}

check("speed names") {
    assert app.speedNamesFor(3) == ["low", "medium", "high"]
    assert (1..8).collect { app.speedNameFor(it, 8) } == ["low", "medium-low", "medium-low", "medium", "medium", "medium-high", "medium-high", "high"]
    assert (1..4).collect { app.speedNameFor(it, 4) } == ["low", "medium-low", "medium-high", "high"]
    assert app.stepForLevel(1, 8) == 1 && app.stepForLevel(100, 8) == 8 && app.levelForStep(4, 8) == 50
}

check("colour conversion round-trips") {
    [[255, 0, 0], [0, 255, 0], [0, 0, 255], [255, 255, 0], [13, 8, 255], [255, 128, 0]].each { r, g, b ->
        Map hsv = app.rgbToHsv(r, g, b)
        Map rgb = app.hsvToRgb(hsv.hue as double, hsv.saturation as double)
        // Only hue and saturation survive: compare at full value.
        double scale = 255d / Math.max(r, Math.max(g, b))
        [[rgb.r, r], [rgb.g, g], [rgb.b, b]].each { got, want -> assert Math.abs(got - want * scale) <= 6 }
    }
}

check("kind from SKU when there's no API key") {
    assert app.guessKind("H6052") == "light" && app.guessKind("H7111") == "fan" && app.guessKind("H7101") == "fan"
    assert app.guessKind("H7131") == null && app.guessKind("H5179") == null && app.guessKind("H605B") == "light"
}

Check.finish()
