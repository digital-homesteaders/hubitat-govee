/**
 * Compile every app and driver against the hub stubs and run its top-level
 * code (definition, metadata, preferences). Then check that each method a
 * driver calls on its parent exists there and is not private, since Hubitat
 * refuses calls to private methods across objects. Also check for Groovy
 * methods newer than the 2.4 line Hubitat runs.
 */

import java.lang.reflect.Modifier
import static Check.check

File root = new File(getClass().protectionDomain.codeSource.location.path).parentFile.parentFile
Hub hub = new Hub(root)
List<File> files = (new File(root, "apps").listFiles() + new File(root, "drivers").listFiles())
    .findAll { it.name.endsWith(".groovy") }.sort { it.path }

files.each { File f ->
    check("compiles and loads: ${root.toPath().relativize(f.toPath())}") {
        HubitatScript s = hub.instance(f)
        assert s.callsNamed("definition"), "no definition(...)"
    }
}

List<String> dslWords = ["definition", "capability", "attribute", "command", "fingerprint", "input",
    "section", "page", "paragraph", "href", "label", "mode", "app"]
files.each { File f ->
    check("no method shadows a definition word: ${f.name}") {
        Class c = hub.compile(f)
        List<String> clash = c.declaredMethods*.name.unique().findAll { it in dslWords }
        assert !clash
    }
}

// Groovy 2.5+ methods that compile here only because the JDK or a newer Groovy has them.
files.each { File f ->
    check("no post-2.4 Groovy methods: ${f.name}") {
        List bad = ["takeRight(", "dropRight(", ".tap {", "?.with(true)", "=> "].findAll { f.text.contains(it) }
        assert !bad
    }
}

boolean isPublic(Class c, String name) {
    c.methods.any { it.name == name && !Modifier.isPrivate(it.modifiers) }
}

new File(root, "drivers").listFiles().findAll { it.name.endsWith(".groovy") }.each { File f ->
    List<String> called = (f.text =~ /parent\??\.(\w+)\s*\(/).collect { it[1] }.unique()
    check("parent methods called by ${f.name} exist and are public") {
        Class c = hub.compile(new File(root, "apps/govee-connect.groovy"))
        List<String> missing = called.findAll { !isPublic(c, it) }
        assert !missing, "the app lacks public ${missing}"
    }
}

check("child device methods called by the app exist and are public") {
    String app = new File(root, "apps/govee-connect.groovy").text
    Map<String, List<String>> drivers = [
        cd: ["drivers/govee-light.groovy", "drivers/govee-fan.groovy"],
        conn: ["drivers/govee-cloud-connection.groovy"],
    ]
    List<String> builtIn = ["getDataValue", "updateDataValue", "currentValue", "getDeviceNetworkId", "getDisplayName"]
    drivers.each { String var, List<String> paths ->
        List<String> called = (app =~ /\b${var}\??\.(\w+)\s*\(/).collect { it[1] }.unique() - builtIn
        assert called, "no ${var}.x() calls found"
        paths.each { String p ->
            List<String> missing = called.findAll { !isPublic(hub.compile(new File(root, p)), it) }
            assert !missing, "${p} lacks public ${missing}"
        }
    }
    // Calls through getConnection()?.x() too.
    List<String> viaGet = (app =~ /getConnection\(\)\??\.(\w+)\s*\(/).collect { it[1] }.unique()
    List<String> missing = viaGet.findAll { !isPublic(hub.compile(new File(root, "drivers/govee-cloud-connection.groovy")), it) }
    assert !missing, "the connection driver lacks public ${missing}"
}

Check.finish()
