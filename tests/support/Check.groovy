/** Minimal test runner: named checks with Groovy power asserts. */
class Check {
    static int passed = 0
    static List<String> failed = []

    static void check(String name, Closure body) {
        try {
            body.call()
            passed++
        } catch (Throwable e) {
            failed << name
            println "FAIL ${name}\n    ${e.toString().readLines().join('\n    ')}"
        }
    }

    /** Asserts that body throws IllegalArgumentException. */
    static void throwsIAE(Closure body) {
        try {
            body.call()
        } catch (IllegalArgumentException e) {
            return
        }
        throw new AssertionError("expected IllegalArgumentException")
    }

    /** Print a summary; exit 1 if anything failed. */
    static void finish() {
        println "${passed} passed, ${failed.size()} failed"
        if (failed) System.exit(1)
    }
}
