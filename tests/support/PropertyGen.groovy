
/**
 * Property-based test generator library for the off-device harness (task 1.3).
 *
 * The harness standardizes on **Spock data-driven generators + this small
 * deterministic random-input helper** (design.md: "Spock's data-driven
 * generators with a small random-input helper; the harness standardizes on one
 * and documents it"). jqwik was NOT chosen because Spock 1.2-groovy-2.5 runs on
 * the JUnit 4 platform and the existing 35-spec suite depends on it; adding a
 * JUnit 5 property engine would fork the test platform. This helper gives the
 * same essentials — randomized inputs, a fixed iteration count, and a printable
 * seed for reproducibility — on the existing Spock/JUnit 4 stack.
 *
 * Convention (per design.md "Property-based tests"):
 *   - Each Correctness Property 1-17 is implemented by exactly ONE Spock
 *     feature method that runs >= ITERATIONS (100) randomized examples.
 *   - The spec class name contains "Property" so `--tests '*Property*'` selects
 *     the property suite.
 *   - The feature method name is the EXACT tag:
 *         Feature: hubitat-flair-vents-dab-v2, Property N: <text>
 *   - Drive iterations with a `where:` block, e.g.:
 *
 *         def "Feature: hubitat-flair-vents-dab-v2, Property 1: floor inviolable"() {
 *           given:
 *           def g = PropertyGen.forIteration(i)   // reproducible per-iteration RNG
 *           ...
 *           expect:
 *           combinedOpenPct(result) >= clampedFloor
 *           where:
 *           i << (0..<PropertyGen.ITERATIONS)
 *         }
 *
 * Pure and deterministic: seeding off the iteration index makes any failing
 * iteration reproducible (re-run the same `i`).
 */
class PropertyGen {

  /** Minimum randomized examples per property test (R18 / design: >= 100). */
  static final int ITERATIONS = 100

  /** Base seed mixed with the iteration index for reproducibility. */
  static final long BASE_SEED = 0x5EEDL

  private final Random rng

  PropertyGen(long seed) {
    this.rng = new Random(seed)
  }

  /** Reproducible per-iteration generator: same `i` always yields same stream. */
  static PropertyGen forIteration(int i) {
    return new PropertyGen(BASE_SEED + (i as long))
  }

  /** Uniform double in [lo, hi). */
  double nextDouble(double lo, double hi) {
    return lo + (rng.nextDouble() * (hi - lo))
  }

  /** Uniform int in [lo, hi] inclusive. */
  int nextInt(int lo, int hi) {
    return lo + rng.nextInt((hi - lo) + 1)
  }

  boolean nextBoolean() {
    return rng.nextBoolean()
  }

  /** Random element from a non-empty list. */
  def pick(List options) {
    return options[rng.nextInt(options.size())]
  }

  /** List of `n` doubles in [lo, hi). */
  List<Double> doubles(int n, double lo, double hi) {
    return (0..<n).collect { nextDouble(lo, hi) }
  }
}
