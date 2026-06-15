
// Cross-cutting invariants guard spec (Task 20.2, Requirement 8 / R8).
//
// R8 captures the inviolable, cross-cutting non-functional constraints that every
// v0.236 behavior must respect (derived from AGENTS.md and the Hubitat sandbox).
// Most are exercised behaviorally elsewhere; this spec is the STATIC backstop: it
// scans the shipped source (`src/*.groovy` + `libraries/flair-vents-dabv2.groovy`)
// for forbidden patterns so a future edit that reintroduces a banned construct
// fails the build here rather than on-device.
//
// What it pins (acceptance-criterion references in each feature method):
//   - Library purity:   no Hubitat API / wall-clock / RNG / state / atomicState,
//                       no `import`/`package`, `library(` is the first code line
//                       (R8.17-R8.21).
//   - No new blocking I/O: zero blocking `httpPost`/`httpPut`/`httpDelete`; the
//                       single blocking `httpGet` is the tracked `getStructureData`
//                       carve-out (R8.7-R8.9).
//   - Credential hygiene: no log statement interpolates `flairAccessToken` /
//                       `client_id` / `client_secret` / `access_token` (R8.4-R8.6).
//   - State discipline: the concurrent counter `activeRequests` lives in
//                       `atomicState`, never `state` (R8.10-R8.12).
//   - Sandbox limits:   no `java.time`, reflection, external JARs/`@Grab`;
//                       libraries pulled in via `#include` (R8.22-R8.25).
//
// Comments and string-literal `//` are stripped before scanning so the carve-out
// comment (which deliberately says "BLOCKING synchronous httpGet") and other
// descriptive prose never trip a guard.
//
// Validates: Requirements 8.4, 8.5, 8.6, 8.7, 8.8, 8.9, 8.10, 8.11, 8.12, 8.17,
//            8.18, 8.19, 8.20, 8.21, 8.22, 8.23, 8.24, 8.25
//
// Run `./gradlew test --tests '*CrossCuttingInvariantsGuard*'`.

import spock.lang.Specification

class CrossCuttingInvariantsGuardSpec extends Specification {

  static final String APP_PATH    = 'src/hubitat-flair-vents-app.groovy'
  static final String VENT_PATH   = 'src/hubitat-flair-vents-driver.groovy'
  static final String PUCK_PATH   = 'src/hubitat-flair-vents-pucks-driver.groovy'
  static final String LIB_PATH    = 'libraries/flair-vents-dabv2.groovy'

  static final List<String> SRC_PATHS = [APP_PATH, VENT_PATH, PUCK_PATH].asImmutable()

  // --- source helpers --------------------------------------------------------

  private static String rawText(String path) {
    File f = new File(path)
    assert f.isFile(), "expected source file is missing: ${path}"
    return f.text
  }

  /**
   * Remove block comments and line comments so pattern scans see CODE only.
   * Tracks single/double-quoted string state so a `//` inside a string literal
   * (or a credential token mentioned in a comment) is handled correctly.
   */
  private static String stripComments(String text) {
    // Strip /* ... */ block comments first (DOTALL).
    String noBlock = text.replaceAll(/(?s)\/\*.*?\*\//, ' ')
    StringBuilder out = new StringBuilder()
    for (String line : noBlock.split('\n', -1)) {
      out.append(stripLineComment(line)).append('\n')
    }
    return out.toString()
  }

  private static String stripLineComment(String line) {
    boolean inSingle = false
    boolean inDouble = false
    char[] cs = line.toCharArray()
    for (int i = 0; i < cs.length; i++) {
      char c = cs[i]
      char prev = i > 0 ? cs[i - 1] : (char) 0
      if (c == ('\'' as char) && !inDouble && prev != ('\\' as char)) { inSingle = !inSingle }
      else if (c == ('"' as char) && !inSingle && prev != ('\\' as char)) { inDouble = !inDouble }
      else if (!inSingle && !inDouble && c == ('/' as char) && i + 1 < cs.length && cs[i + 1] == ('/' as char)) {
        return line.substring(0, i)
      }
    }
    return line
  }

  private static int countMatches(String text, String regex) {
    java.util.regex.Matcher m = (text =~ regex)
    int n = 0
    while (m.find()) { n++ }
    return n
  }

  // ===========================================================================
  // Library purity (R8.17-R8.21)
  // ===========================================================================

  def 'R8: the app defines no methodMissing/propertyMissing MOP hook (forces pathological sandbox compile)'() {
    when:
    String code = stripComments(rawText(APP_PATH))

    then: 'no metaclass missing-method/property hooks — they route every dynamic call in the class and make the Hubitat sandbox compile the whole app pathologically slowly (it could not be saved on-hub)'
    countMatches(code, /\bdef\s+methodMissing\s*\(/) == 0
    countMatches(code, /\bdef\s+propertyMissing\s*\(/) == 0
  }

  def 'R8.21: the DAB v2 library has no import or package lines'() {
    when:
    String raw = rawText(LIB_PATH)

    then: 'a pure #include-able library declares no import/package (types qualified inline)'
    countMatches(raw, /(?m)^\s*import\s/) == 0
    countMatches(raw, /(?m)^\s*package\s/) == 0
  }

  def 'R8.25: the first code line of the DAB v2 library is the library() metadata call'() {
    when: 'the first non-blank, non-comment line'
    String firstCode = rawText(LIB_PATH).split('\n', -1)
      .collect { it.trim() }
      .find { it && !it.startsWith('//') && !it.startsWith('*') && !it.startsWith('/*') }

    then: 'library(...) comes first so the body is #include-appended cleanly on-device'
    firstCode.startsWith('library(')
  }

  def 'R8.17/R8.20: the DAB v2 library calls no Hubitat API and touches no state/atomicState'() {
    when:
    String code = stripComments(rawText(LIB_PATH))

    then: 'no state / atomicState reads or writes in pure math'
    countMatches(code, /\bstate\b/) == 0
    countMatches(code, /\batomicState\b/) == 0

    and: 'no Hubitat platform calls (HTTP, scheduling, devices, events, subscriptions)'
    countMatches(code, /\basynchttp\w*\s*\(/) == 0
    countMatches(code, /(?<![A-Za-z.])httpGet\s*\(/) == 0
    countMatches(code, /(?<![A-Za-z.])httpPost\s*\(/) == 0
    countMatches(code, /\bsendEvent\s*\(/) == 0
    countMatches(code, /\bgetChildDevice\s*\(/) == 0
    countMatches(code, /\brunIn(Millis)?\s*\(/) == 0
    countMatches(code, /\b(subscribe|unsubscribe|unschedule)\s*\(/) == 0
  }

  def 'R8.18/R8.19: the DAB v2 library uses no wall-clock time and no randomness'() {
    when:
    String code = stripComments(rawText(LIB_PATH))

    then: 'no wall-clock sources'
    countMatches(code, /System\.(currentTimeMillis|nanoTime)/) == 0
    countMatches(code, /new\s+Date\s*\(/) == 0
    countMatches(code, /Calendar\.getInstance/) == 0
    countMatches(code, /(?<![A-Za-z.])now\s*\(\s*\)/) == 0

    and: 'no randomness'
    countMatches(code, /new\s+Random\b/) == 0
    countMatches(code, /Math\.random\b/) == 0
    countMatches(code, /UUID\.randomUUID/) == 0
  }

  // ===========================================================================
  // Sandbox limits across library + app + drivers (R8.22-R8.25)
  // ===========================================================================

  def 'R8.22/R8.23/R8.24: no java.time, reflection, external JARs, or @Grab anywhere'() {
    expect:
    ([LIB_PATH] + SRC_PATHS).every { String path ->
      String code = stripComments(rawText(path))
      assert countMatches(code, /\bjava\.time\b/) == 0, "java.time found in ${path}"
      assert countMatches(code, /@Grab\b/) == 0, "@Grab found in ${path}"
      assert countMatches(code, /\bjava\.lang\.reflect\b/) == 0, "reflection import found in ${path}"
      assert countMatches(code, /\bClass\.forName\b/) == 0, "Class.forName found in ${path}"
      assert countMatches(code, /\.getDeclared(Field|Method|Constructor)/) == 0, "reflection found in ${path}"
      // The Hubitat sandbox rejects getClass() (and the equivalent `this.class`)
      // as a disallowed MethodCallExpression — guard against reintroduction.
      assert countMatches(code, /\bgetClass\s*\(/) == 0, "getClass() found in ${path}"
      assert countMatches(code, /\bthis\.class\b/) == 0, "this.class reflection found in ${path}"
      true
    }
  }

  def 'R8.25: the app pulls the DAB v2 library in via #include (not a copy)'() {
    expect:
    countMatches(rawText(APP_PATH), /(?m)^\s*#include\s+bot\.flair\.FlairVentsDabv2\b/) == 1
  }

  // ===========================================================================
  // No new blocking HTTP (R8.7-R8.9)
  // ===========================================================================

  def 'R8.7/R8.8: the app introduces no blocking httpPost/httpPut/httpDelete'() {
    when:
    String code = stripComments(rawText(APP_PATH))

    then: 'all writes go through asynchttp*; no blocking write verbs exist'
    countMatches(code, /(?<![A-Za-z.])httpPost\s*\(/) == 0
    countMatches(code, /(?<![A-Za-z.])httpPut\s*\(/) == 0
    countMatches(code, /(?<![A-Za-z.])httpDelete\s*\(/) == 0
  }

  def 'R8.9: the only blocking httpGet is the tracked getStructureData carve-out'() {
    when:
    String code = stripComments(rawText(APP_PATH))

    then: 'exactly one blocking httpGet remains (no NEW blocking I/O was added)'
    countMatches(code, /(?<![A-Za-z.])httpGet\s*\(/) == 1

    and: 'and it lives inside getStructureData (the documented carve-out)'
    // Locate the BLOCKING httpGet via the same negative-lookbehind matcher so we
    // do not match the `httpGet(` substring inside `asynchttpGet(`.
    java.util.regex.Matcher gm = (code =~ /(?<![A-Za-z.])httpGet\s*\(/)
    gm.find()
    int callIdx = gm.start()
    int defIdx = code.indexOf('def getStructureData(')
    defIdx >= 0 && callIdx > defIdx
  }

  // ===========================================================================
  // Credential hygiene (R8.4-R8.6)
  // ===========================================================================

  def 'R8.4/R8.6: no log statement interpolates a credential or token value'() {
    expect:
    SRC_PATHS.every { String path ->
      String code = stripComments(rawText(path))
      code.split('\n', -1).each { String line ->
        boolean isLogCall = (line =~ /\b(log|logError|logDebug|logWarn|logInfo|logDetails)\s*[\("']/)
        if (isLogCall) {
          // Leaks are credential VALUES reaching the log: the token variable
          // `flairAccessToken` itself, or a `${...}` interpolation of a secret
          // setting. Plain descriptive words like "access_token" in a static
          // message (e.g. "Getting access_token from Flair") are NOT leaks.
          assert !(line =~ /flairAccessToken/),
            "OAuth token variable referenced in a log statement in ${path}: ${line.trim()}"
          assert !(line =~ /\$\{[^}]*(client_secret|clientSecret|client_id|clientId|access_token|flairAccessToken)[^}]*\}/),
            "credential value interpolated into a log statement in ${path}: ${line.trim()}"
        }
      }
      true
    }
  }

  def 'R8.5: credentials are not rendered into the config UI'() {
    expect:
    String code = stripComments(rawText(APP_PATH))
    // A paragraph/href that echoes a secret value would leak it in the UI.
    countMatches(code, /paragraph[^\n]*(flairAccessToken|client_secret|clientSecret|access_token)/) == 0
  }

  // ===========================================================================
  // State discipline (R8.10-R8.12)
  // ===========================================================================

  def 'R8.11: the concurrent activeRequests counter lives in atomicState, never state'() {
    when:
    String code = stripComments(rawText(APP_PATH))

    then: 'the shared/concurrent counter is read/written only through atomicState'
    countMatches(code, /\bstate\.activeRequests\b/) == 0
    countMatches(code, /\bstate\s*\[\s*['"]activeRequests['"]\s*\]/) == 0
    countMatches(code, /\batomicState\.activeRequests\b/) > 0
  }
}
