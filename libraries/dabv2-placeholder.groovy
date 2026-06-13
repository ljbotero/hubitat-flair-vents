
/**
 * PURE placeholder module for the off-device harness (task 1.3).
 *
 * This file demonstrates the pure-core isolation contract that every real
 * dabv2-*.groovy library (Allocator, Safety_Floor, Learning_Model,
 * Context_Mapper, model-io) must honor (design.md "Sandbox-safe Groovy
 * contract", R18.2 / R18.7):
 *
 *   - NO Hubitat platform APIs (no log, state, atomicState, sendEvent,
 *     subscribe, runIn/schedule, asynchttp*, device/app singletons).
 *   - NO wall-clock time and NO randomness (time is always passed in as a
 *     primitive; allocation must be deterministic — Property 6).
 *   - Only language features available BOTH in the Hubitat sandbox and on a
 *     plain JVM, so the identical source runs under Spock off-device and is
 *     #include-d by the app on-device.
 *
 * It exists so the JVM/Spock harness has something pure to compile and assert
 * against before the real modules land (tasks 3-7). It can be deleted once a
 * real pure module is present and referenced by the smoke test.
 */
class Dabv2Placeholder {

  /**
   * A pure, deterministic helper used by the harness property smoke test to
   * prove the property-generator wiring exercises the libraries/ source set.
   * Clamps {@code value} to the inclusive [lo, hi] range.
   */
  static double clamp(double value, double lo, double hi) {
    if (value < lo) { return lo }
    if (value > hi) { return hi }
    return value
  }

  /** A known, deterministic value the harness smoke test asserts against. */
  int answer() {
    return 42
  }

}
