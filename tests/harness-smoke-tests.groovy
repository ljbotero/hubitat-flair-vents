
import spock.lang.Specification

/**
 * Off-device harness smoke test (task 1.3).
 *
 * This trivial spec imports a PURE library from libraries/*.groovy (no Hubitat
 * platform APIs) and asserts a known value. It proves the JVM/Spock harness can
 * compile and exercise the pure-core modules directly, without a physical hub or
 * the hubitat_ci sandbox.
 *
 * RED: fails before libraries/dabv2-placeholder.groovy exists and before the
 *      build's main sourceSet includes the libraries/ directory.
 * GREEN: passes once the placeholder pure library is created and compiled.
 */
class HarnessSmokeTest extends Specification {

  def 'pure placeholder library compiles on the JVM and returns its known value'() {
    given:
    def placeholder = new Dabv2Placeholder()

    expect:
    placeholder.answer() == 42
  }
}
