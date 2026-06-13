/**
 * Off-device #include emulator for the Flair Vents app.
 *
 * On a Hubitat hub the app pulls in the pure DAB v2 methods with
 * `#include bot.flair.FlairVentsDabv2`, which the hub resolves by appending the
 * library's body to the app at save time. Neither `groovyc` nor `hubitat_ci`'s
 * parser understands the `#include` directive, so this helper reproduces what
 * the hub does: it returns the app source with the `#include` line removed and
 * the methods-only library body appended (the library's `library(...)`
 * metadata call stripped, since running it off-device would call an undefined
 * method). The combined text is therefore exactly what runs on the hub, so the
 * `hubitat_ci` specs exercise the shipped artifact (test-what-we-ship).
 *
 * Specs load it by defining `APP_FILE = Dabv2AppHarness.combinedAppText()` (a
 * String) and passing it to `new HubitatAppSandbox(APP_FILE)` (the String ctor).
 */
class Dabv2AppHarness {

  static final String APP_PATH = 'src/hubitat-flair-vents-app.groovy'
  static final String LIB_PATH = 'libraries/flair-vents-dabv2.groovy'

  private static String cached = null

  /** App source + inlined methods-only library, with #include and library() stripped. Cached. */
  static synchronized String combinedAppText() {
    if (cached != null) { return cached }
    String app = stripIncludes(new File(APP_PATH).text)
    String lib = stripLibraryCall(new File(LIB_PATH).text)
    cached = app + '\n\n' + lib + '\n'
    return cached
  }

  /** Drop any line whose first non-space token is the Hubitat #include directive. */
  private static String stripIncludes(String text) {
    StringBuilder sb = new StringBuilder()
    for (String line : text.split('\n', -1)) {
      if (line.trim().startsWith('#include')) { continue }
      sb.append(line).append('\n')
    }
    return sb.toString()
  }

  /**
   * Remove the top-level `library( ... )` metadata call so the inlined body is
   * pure method/field definitions. Drops lines from the one beginning with
   * `library(` through the first subsequent line that is just `)`.
   */
  private static String stripLibraryCall(String text) {
    StringBuilder sb = new StringBuilder()
    boolean inCall = false
    for (String line : text.split('\n', -1)) {
      if (!inCall && line.trim().startsWith('library(')) { inCall = true; continue }
      if (inCall) {
        if (line.trim() == ')') { inCall = false }
        continue
      }
      sb.append(line).append('\n')
    }
    return sb.toString()
  }
}
