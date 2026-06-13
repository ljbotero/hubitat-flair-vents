
import spock.lang.Specification

/**
 * Harness property-suite smoke test (task 1.3).
 *
 * Proves the property-based testing wiring end-to-end against the PURE
 * libraries/ source set:
 *   - the spec class name contains "Property" so `--tests '*Property*'` runs it;
 *   - the feature method demonstrates the exact tagging convention used by the
 *     real Correctness-Property tests (1-17):
 *         Feature: hubitat-flair-vents-dab-v2, Property N: <text>
 *   - the `where:` block drives >= PropertyGen.ITERATIONS (100) randomized,
 *     reproducible examples through a pure library function.
 *
 * Real properties replace "Property smoke" with the numbered property text from
 * design.md (e.g. "Property 1: Safety floor is inviolable").
 */
class HarnessPropertySpec extends Specification {

  def 'Feature: hubitat-flair-vents-dab-v2, Property smoke: pure clamp stays within bounds for any input'() {
    given:
    def g = PropertyGen.forIteration(i)
    double lo = g.nextDouble(-50.0d, 50.0d)
    double hi = lo + g.nextDouble(0.0d, 100.0d)
    double value = g.nextDouble(-200.0d, 200.0d)

    when:
    double clamped = Dabv2Placeholder.clamp(value, lo, hi)

    then:
    clamped >= lo
    clamped <= hi

    where:
    i << (0..<PropertyGen.ITERATIONS)
  }
}
