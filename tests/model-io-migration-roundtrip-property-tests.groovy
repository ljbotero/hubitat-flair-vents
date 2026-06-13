
import spock.lang.Specification
import spock.lang.Shared

/**
 * Property-based test for the PURE persisted-state model migration + export /
 * import (task 7.2).
 *
 * Implements Correctness Property 15 from design.md EXACTLY (one property per
 * feature method, tagged with the exact property heading):
 *
 *   Property 15: Model migration and round-trip safety
 *   "For any persisted payload — including v1 single-rate state and malformed
 *    input — migration preserves all learnable state, seeds leak from the
 *    regression intercept, and never throws or discards data on a parse
 *    failure; and for any valid exported model, importing then re-exporting
 *    yields an equivalent model (export(import(export(m))) == export(import(m)))."
 *
 * Validates: Requirements 12.3, 12.4, 12.5, 13.5.
 *
 * Grounded in design.md "Migration from existing single-rate state" and
 * "Export / import":
 *   - migration runs on load, is idempotent, and NEVER discards data on a parse
 *     failure (keeps existing + falls back to seeds/defaults per field);
 *   - room-efficiency baselines are seeded from the existing learned per-room
 *     rate; each vent curve is the near-linear seed from the existing
 *     rate-vs-aperture regression (leak from the INTERCEPT via
 *     {@link Dabv2Learning#deriveEffectiveness}), defaulting to LEAK_DEFAULT
 *     when no regression exists;
 *   - new metric/counter fields are back-filled with documented defaults
 *     (R13.5) and {@code v:2} is stamped; older payloads load with defaults for
 *     missing fields;
 *   - export serializes to schema-v2 JSON; import accepts v1 and v2; the
 *     round-trip export(import(...)) is a stable fixed point.
 *
 * The "round-trip" equation export(import(export(m))) == export(import(m)) is
 * verified as the idempotency / fixed-point of (export . import): once a payload
 * has been imported and re-exported, importing and re-exporting it again yields
 * the identical JSON. Both sides of the design equation pass through the same
 * import normalization, so they agree exactly.
 *
 * PURE-core contract (R18.2 / R18.7): NO Hubitat APIs, time, randomness, state.
 *
 * The class name contains "Property" so `./gradlew test --tests '*Property*'`
 * selects it; the `where:` block drives >= PropertyGen.ITERATIONS (100)
 * reproducible randomized payloads (v1 single-rate, v2, and malformed).
 */
class ModelIoMigrationRoundtripPropertySpec extends Specification {

  // The methods-only DAB v2 library, loaded as a script so its top-level
  // methods and @Field constants are callable off-device.
  @Shared
  Script lib = new GroovyShell().parse(new File('libraries/flair-vents-dabv2.groovy'))

  private static final double EPS = 1e-9d

  def 'Feature: hubitat-flair-vents-dab-v2, Property 15: Model migration and round-trip safety'() {
    expect: 'migration + export/import hold their invariants for every payload kind'
    checkMigrationAndRoundTrip(i)

    where:
    i << (0..<PropertyGen.ITERATIONS)
  }

  /**
   * All Property-15 assertions for one reproducible iteration. Each iteration
   * randomly exercises one of three payload kinds — v1 single-rate state, a
   * round-tripped v2 model, or malformed garbage — then checks the invariants
   * relevant to that kind plus the universal ones (never throws, stamps v2,
   * back-fills documented defaults, and export(import(...)) is a fixed point).
   */
  private boolean checkMigrationAndRoundTrip(int i) {
    PropertyGen g = PropertyGen.forIteration(i)
    int kind = g.nextInt(0, 2)
    if (kind == 0) {
      checkV1(g)
    } else if (kind == 1) {
      checkV2(g)
    } else {
      checkMalformed(g)
    }
    return true
  }

  // ---------------------------------------------------------------------------
  // Universal invariants: never throws, stamps v2, back-fills defaults, and the
  // export(import(...)) round-trip is a stable fixed point.
  // ---------------------------------------------------------------------------
  private void assertUniversal(Object payload) {
    // Import NEVER throws on any payload (parse-failure-safe).
    Map m = lib.mioImportModel(payload)
    assert m != null
    // Migration stamps schema v2 (R12.5).
    assert m.version == 2
    // Learnable-state containers are never discarded (null) — kept as maps.
    assert m.roomEff != null
    assert m.ventEff != null
    // Documented defaults back-filled (R13.5).
    assert m.metrics != null
    assert m.counters != null
    assert m.counters.containsKey('recalc24h')
    assert m.counters.containsKey('hold24h')

    // export(import(export(m))) == export(import(m)): the round-trip is a
    // fixed point — re-importing and re-exporting yields identical JSON.
    String once = lib.mioExportModel(m)
    Map reimported = lib.mioImportModel(once)
    String twice = lib.mioExportModel(reimported)
    assert once == twice
  }

  // ---------------------------------------------------------------------------
  // (kind 0) v1 single-rate state — learnable state preserved + leak from intercept
  // ---------------------------------------------------------------------------
  private void checkV1(PropertyGen g) {
    String roomId = 'room-' + g.nextInt(0, 999)
    String ventId = 'vent-' + g.nextInt(0, 999)
    double coolRate = g.nextDouble(0.01d, 1.4d)   // positive, inside the rate band
    double heatRate = g.nextDouble(0.01d, 1.4d)
    boolean hasRegression = g.nextBoolean()

    Map<String, Object> rec = new LinkedHashMap<String, Object>()
    rec.put('roomId', roomId)
    rec.put('roomName', 'Room ' + roomId)
    rec.put('ventId', ventId)
    rec.put('coolingRate', coolRate)
    rec.put('heatingRate', heatRate)

    double slope = 0.0d
    double intercept = 0.0d
    int regN = 0
    if (hasRegression) {
      slope = g.nextDouble(0.001d, 0.02d)      // positive rising shape
      intercept = g.nextDouble(0.0d, 0.2d)     // non-negative leak source
      regN = g.nextInt(lib.LRN_MODEL_MIN_N, 50)  // trusted fit
      rec.put('slope', slope)
      rec.put('intercept', intercept)
      rec.put('n', regN)
    }

    // v1 export-wrapper shape (as the existing Integration serializes it).
    Map<String, Object> payload = [
      exportMetadata: [version: '0.22', exportDate: '2025-01-01T00:00:00Z'],
      efficiencyData: [
        globalRates: [maxCoolingRate: 1.5d, maxHeatingRate: 1.2d],
        roomEfficiencies: [rec]
      ]
    ] as Map<String, Object>

    assertUniversal(payload)

    Map m = lib.mioImportModel(payload)

    // Learnable state preserved: room baselines seeded from the learned rates.
    Map rm = m.roomEff.get(roomId)
    assert rm != null
    assert rm.cooling.baseline != null
    assert Math.abs(rm.cooling.baseline.doubleValue() - coolRate) <= EPS
    assert rm.heating.baseline != null
    assert Math.abs(rm.heating.baseline.doubleValue() - heatRate) <= EPS

    // Vent leak seeded from the regression INTERCEPT (else LEAK_DEFAULT).
    Map ve = m.ventEff.get(ventId)
    assert ve != null
    double expectedLeak = hasRegression ?
      lib.lrnDeriveEffectiveness(slope, intercept, regN).leak :
      lib.LRN_LEAK_DEFAULT
    assert Math.abs(ve.cooling.leak - expectedLeak) <= EPS
    assert Math.abs(ve.heating.leak - expectedLeak) <= EPS
    // The seeded curve's flow(0) equals the seeded leak (clamped into range).
    assert Math.abs(lib.lrnVentCurveFlow(ve.cooling.curve, 0.0d)
      - lib.dabv2Clamp(expectedLeak, 0.0d, lib.LRN_LEAK_MAX)) <= 1e-6d
  }

  // ---------------------------------------------------------------------------
  // (kind 1) round-tripped v2 model — import accepts v2 losslessly + idempotent
  // ---------------------------------------------------------------------------
  private void checkV2(PropertyGen g) {
    Map built = sampleV2Model(g)
    String exported = lib.mioExportModel(built)

    // The serialized v2 payload is what universal invariants run against.
    assertUniversal(exported)

    // v2 import is lossless: encode(import(export(m))) == encode(m).
    Map imported = lib.mioImportModel(exported)
    assert lib.mioEncode(imported) == lib.mioEncode(built)
  }

  // ---------------------------------------------------------------------------
  // (kind 2) malformed input — never throws, never discards into an error
  // ---------------------------------------------------------------------------
  private void checkMalformed(PropertyGen g) {
    List<Object> garbage = [
      null,
      'not a json object at all',
      '{ broken json',
      '[1, 2, 3]',
      42,
      [1, 2, 3],
      'true',
      // a map with wrong-typed sections everywhere
      [v: 'two', roomEff: 'nope', ventEff: 12345, metrics: [1, 2], counters: 'x'],
      // a v2-ish map with corrupt entries
      [v: 2, roomEff: [bad: 'x', worse: 99], ventEff: [v1: [c: 'oops']]],
      // an empty map
      [:]
    ] as List<Object>
    Object payload = g.pick(garbage)

    // The core guarantee: import never throws and never discards into an error.
    assertUniversal(payload)

    Map m = lib.mioImportModel(payload)
    assert m.roomEff != null
    assert m.ventEff != null
  }

  // ---------------------------------------------------------------------------
  // Builders
  // ---------------------------------------------------------------------------
  /** A random but well-formed in-memory v2 model with all sections populated. */
  private Map sampleV2Model(PropertyGen g) {
    Map m = lib.mioNewModel()
    int rooms = g.nextInt(1, 4)
    for (int r = 0; r < rooms; r++) {
      String roomId = 'room-' + r
      Map rm = lib.lrnNewRoomModel()
      rm.cooling.baseline = g.nextDouble(0.005d, 0.05d)
      rm.cooling.n = g.nextInt(0, 40)
      rm.heating.baseline = g.nextDouble(0.005d, 0.05d)
      rm.heating.n = g.nextInt(0, 40)
      for (int k = 0; k < lib.LRN_EFF_REGIME_COUNT; k++) {
        rm.cooling.regimes[k].rate = g.nextDouble(0.0d, 0.05d)
        rm.cooling.regimes[k].n = g.nextInt(0, 10)
        rm.heating.regimes[k].rate = g.nextDouble(0.0d, 0.05d)
        rm.heating.regimes[k].n = g.nextInt(0, 10)
      }
      m.roomEff.put(roomId, rm)

      Map ve = [cooling: null, heating: null]
      ve.cooling = sampleVentMode(g)
      ve.heating = sampleVentMode(g)
      m.ventEff.put(roomId + '#v1', ve)
    }
    m.metrics.put('balance', [
      adjPerCycle: g.nextDouble(0.0d, 5.0d), movePerCycle: g.nextDouble(0.0d, 5.0d),
      avgSpread: g.nextDouble(0.0d, 2.0d), maxSpread: g.nextDouble(0.0d, 4.0d)
    ])
    // counters populated with ALL documented keys so back-fill is a no-op and
    // the v2 round-trip is exactly lossless.
    m.counters = [recalc24h: g.nextInt(0, 20), hold24h: g.nextInt(0, 40),
                  windowStartMs: 1700000000000L] as Map<String, Object>
    m.preAdjustFlags = [('room-0'): true] as Map<String, Object>
    return m
  }

  private Map sampleVentMode(PropertyGen g) {
    double leak = g.nextDouble(0.0d, lib.LRN_LEAK_MAX)
    Map curve = lib.lrnSeedLinear(leak)
    lib.lrnVentCurveUpdate(curve, 35.0d, g.nextDouble(0.5d, 0.9d))
    Map vm = lib.mioNewVentMode()
    vm.leak = leak
    vm.n = g.nextInt(0, 60)
    vm.knee = g.pick([20, 35, 50, 75, 100]) as int
    vm.curve = curve
    vm.sx = g.nextDouble(0.0d, 1000.0d)
    vm.sy = g.nextDouble(0.0d, 100.0d)
    vm.sxx = g.nextDouble(0.0d, 10000.0d)
    vm.sxy = g.nextDouble(0.0d, 5000.0d)
    return vm
  }
}
