/**
 * Check packageManifest.json against the code, so an HPM install or update gets
 * every file at one version: each app and driver is listed once, its name and
 * namespace match the code's definition, its importUrl is the manifest location,
 * and the manifest version is the version stamped in every file
 * (scripts/release.sh sets them all).
 */

import groovy.json.JsonSlurper
import static Check.check

File root = new File(getClass().protectionDomain.codeSource.location.path).parentFile.parentFile
Map m = new JsonSlurper().parse(new File(root, "packageManifest.json")) as Map
List<Map> entries = ((m.apps ?: []) + (m.drivers ?: [])) as List<Map>
List<File> files = (new File(root, "apps").listFiles() + new File(root, "drivers").listFiles())
    .findAll { it.name.endsWith(".groovy") }.sort { it.path }
// After scripts/set-github-owner.sh the placeholder is a real name: accept any owner.
String basePattern = /https:\/\/raw\.githubusercontent\.com\/[A-Za-z0-9_-]+\/hubitat-govee\/main\//

String relPath(File f) { f.parentFile.name + "/" + f.name }

check("the manifest has a release version") {
    assert m.version ==~ /\d+\.\d+\.\d+/ && m.dateReleased ==~ /\d{4}-\d{2}-\d{2}/ && m.packageName == "Govee Connect"
}

check("every file is stamped with the manifest version") {
    Map stamped = files.collectEntries { f ->
        def v = (f.text =~ /static final String (?:DRIVER_)?VERSION = "([^"]+)"/)
        [(f.name): v.find() ? v.group(1) : "none"]
    }
    assert stamped.values().every { it == m.version }, "run scripts/release.sh: ${stamped} vs ${m.version}"
}

check("every app and driver is in the manifest once, and nothing else is") {
    List listed = entries.collect { (it.location as String).replaceFirst(basePattern, "") }
    assert listed.sort() == files.collect { relPath(it) }.sort()
    assert entries.every { (it.location as String) ==~ (basePattern + /.+/) }
    assert m.apps.size() == 1 && m.apps[0].primary == true
}

check("manifest names and namespaces match each file's definition, and importUrl is its location") {
    files.each { File f ->
        Map e = entries.find { (it.location as String).endsWith("/" + relPath(f)) }
        String t = f.text
        def name = (t =~ /definition\(\s*name:\s*"([^"]+)"/) ; boolean isDriver = name.find()
        String defName = isDriver ? name.group(1) : (t =~ /(?m)^\s*name:\s*"([^"]+)"/).with { it.find() ? it.group(1) : null }
        String ns = (t =~ /namespace:\s*"([^"]+)"/).with { it.find() ? it.group(1) : null }
        String imp = (t =~ /importUrl:\s*"([^"]*)"/).with { it.find() ? it.group(1) : null }
        assert e.name == defName && e.namespace == ns, f.name
        assert imp == e.location, "${f.name}: importUrl ${imp} vs manifest ${e.location}"
    }
}

check("manifest ids are unique UUIDs and the licence exists") {
    List ids = entries*.id
    assert ids.every { it ==~ /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/ } && ids.unique(false).size() == ids.size()
    assert new File(root, (m.licenseFile as String).replaceFirst(basePattern, "")).exists()
}

Check.finish()
