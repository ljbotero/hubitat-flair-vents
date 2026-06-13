
import groovy.transform.CompileStatic

/**
 * In-memory form of a portable {@code *.scenario.json} parity fixture (task 8.1,
 * R17.1). Holds exactly the deserialized PURE allocator inputs — the same
 * {@link RoomAllocInput} / {@link AllocSettings} / {@link DuctSignals} the
 * Groovy {@code Dabv2Allocator.allocate} consumes — so a loaded scenario can be
 * fed straight into the pure modules with no further mapping.
 *
 * Test-support data holder; see {@link ParityFixtures} for the loader.
 */
@CompileStatic
class ParityScenario {
  /** Stable scenario id (matches the fixture file name stem). */
  String id
  /** Conditioning mode: {@code "cooling"} or {@code "heating"}. */
  String mode
  /** Shared thermostat setpoint (deg C). */
  double setpointC
  /** Allocation tunables (safety floor, granularity, thresholds, ...). */
  Map settings
  /** Per-room allocator inputs (active and inactive). */
  List rooms
  /** Optional duct signals; {@code null} when the fixture's {@code duct} is null. */
  Map duct
}
