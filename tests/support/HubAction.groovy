package hubitat.device

/** Stand-ins for Hubitat's HubAction and Protocol, enough for UDP sends in tests. */
class HubAction {
    enum Type { LAN_TYPE_UDPCLIENT, LAN_TYPE_RAW }
    String action
    Protocol protocol
    Map options
    HubAction(String action, Protocol protocol, Map options) {
        this.action = action
        this.protocol = protocol
        this.options = options
    }
}

enum Protocol { LAN, ZIGBEE, TELNET }
