
import me.biocomp.hubitat_ci.api.app_api.AppExecutor
import me.biocomp.hubitat_ci.app.HubitatAppSandbox
import me.biocomp.hubitat_ci.util.CapturingLog
import me.biocomp.hubitat_ci.validation.Flags
import spock.lang.Shared
import spock.lang.Specification

/**
 * Property-based test for per-room target/offset NEUTRALITY at offset 0 (task 7.1),
 * driving task 7.2 (thread an optional {@code perRoomTargetC} map into
 * {@code allocAllocate}) under strict TDD.
 *
 * Implements Correctness Property 22 from design.md EXACTLY (one property per
 * feature method, tagged with the exact property heading):
 *
 *   Property 22: Per-room offset 0 equals current behavior (R3.3)
 *   "With no per-room config (offset 0 for all rooms), dispatched positions
 *    exactly equal the shared-setpoint baseline for the same inputs, under both
 *    `balance` and `dab`."
 *   Validates: Requirements 3.3.
 *
 * <b>Offset-0 resolution is neutral.</b> Requirement 3.3 mandates that a room
 * with neither an absolute target nor an offset behaves identically to today's
 * shared-setpoint control. The pure resolver {@code dabv2ResolveRoomTargetC}
 * already returns the shared setpoint unchanged for {@code (abs=null, offset=0)};
 * this property asserts that injecting that resolved-to-setpoint map into each
 * control path reproduces the baseline dispatched positions byte-for-byte.
 *
 * <b>RED vs GREEN (strict TDD — this spec MUST be RED before task 7.2).</b>
 *   - <b>`balance` (RED, drives task 7.2):</b> the offset-0 leg calls
 *     {@code allocAllocate(rooms, setpointC, mode, settings, duct, perRoomTargetC)}
 *     with the NEW sixth {@code perRoomTargetC} argument. Today {@code allocAllocate}
 *     accepts only five parameters ({@code rooms, setpointC, mode, s, duct=null}),
 *     so the dynamic call throws {@code groovy.lang.MissingMethodException} at
 *     runtime — the feature method fails (RED). Task 7.2 adds the defaulted sixth
 *     parameter and, for an all-setpoint map, must reproduce the baseline targets
 *     exactly, turning this leg GREEN.
 *   - <b>`dab` (GREEN today, guards the resolver's neutrality):</b> the legacy
 *     sizing path {@code calculateVentOpenPercentage} takes a setpoint directly.
 *     The offset-0 leg sizes each room against
 *     {@code dabv2ResolveRoomTargetC(setpoint, null, 0)} which equals the shared
 *     setpoint, so the legacy percentage is unchanged. This leg is already GREEN
 *     and stays GREEN after task 7.2/7.6 wire the resolved target into the legacy
 *     path — it pins the neutrality the `balance` leg also depends on. (Because the
 *     `balance` leg throws first, the whole feature method is RED regardless.)
 *
 * The class name contains "Property" so `./gradlew test --tests '*Property*'`
 * selects it; the `where:` block drives >= PropertyGen.ITERATIONS reproducible
 * randomized scenarios, each exercising BOTH strategies.
 */
class PerRoomOffsetZeroParityPropertySpec extends Specification {

  // The methods-only DAB v2 library, loaded as a script so its top-level
  // methods and @Field constants are callable off-device.
  @Shared
  Script lib = new GroovyShell().parse(new File('libraries/flair-vents-dabv2.groovy'))

  // Lazily-built app sandbox script for the LEGACY `dab` sizing path
  // (calculateVentOpenPercentage). Rebuilt per feature execution in the `given:`
  // block — Spock's valid mock-creation region — so getLog()/getState() stubs are
  // honored (a Mock created/cached inside a then:/and: block returns a null log).

  private static final List VALIDATION_FLAGS = [
    Flags.DontValidateMetadata,
    Flags.DontValidatePreferences,
    Flags.DontValidateDefinition,
    Flags.DontRestrictGroovy,
    Flags.DontRequireParseMethodInDevice,
  ]
  private static final AbstractMap USER_SETTINGS = ['debugLevel': 1, 'thermostat1CloseInactiveRooms': true]

  private static final double EPS = 1e-9d
  private static final double HYST = 0.3d

  def 'Feature: hubitat-flair-vents-dab-v2, Property 22: Per-room offset 0 equals current behavior'() {
    given: 'a randomized active-room scenario and a no-per-room-config (offset 0) target map'
    def g = PropertyGen.forIteration(i)
    Map scenario = makeScenario(g)
    List rooms = scenario.rooms as List
    double setpointC = scenario.setpointC as double
    String mode = scenario.mode as String
    Map s = scenario.settings as Map

    // Offset-0 resolved target for every active room == the shared setpoint
    // (dabv2ResolveRoomTargetC(setpoint, abs=null, offset=0) -> setpoint). This is
    // the "no per-room config" map Requirement 3.3 says must be a no-op.
    Map perRoomTargetC = [:]
    rooms.each { Map r ->
      if (r.active) {
        perRoomTargetC[r.roomId] = lib.dabv2ResolveRoomTargetC(setpointC as BigDecimal, null, 0.0G)
      }
    }
    // Build the LEGACY sizing app here in `given:` (a valid Spock mock-creation
    // region) so its getLog()/getState() interaction stubs are honored.
    def app = legacySizingApp()

    expect: 'the offset-0 map resolves each room to exactly the shared setpoint (neutrality precondition)'
    perRoomTargetC.values().every { (it as BigDecimal) == (setpointC as BigDecimal) }

    when: 'BALANCE: the allocator runs at the shared setpoint (baseline) and with the offset-0 per-room map'
    Map baseBalance = lib.allocAllocate(rooms, setpointC, mode, s, null)
    // RED until task 7.2: allocAllocate has no sixth `perRoomTargetC` parameter yet,
    // so this dynamic 6-arg call throws MissingMethodException today.
    Map offsetZeroBalance = lib.allocAllocate(rooms, setpointC, mode, s, null, perRoomTargetC)

    then: 'every dispatched balance position is byte-for-byte equal to the shared-setpoint baseline'
    Map<String, Double> baseTargets = baseBalance.targets as Map<String, Double>
    Map<String, Double> offTargets = offsetZeroBalance.targets as Map<String, Double>
    offTargets.keySet() == baseTargets.keySet()
    baseTargets.every { String roomId, Double v ->
      Math.abs((offTargets[roomId] as double) - (v as double)) <= EPS
    }

    and: 'DAB: the legacy per-room sizing at the offset-0 resolved target equals sizing at the shared setpoint'
    rooms.findAll { it.active }.every { Map r ->
      BigDecimal resolved = perRoomTargetC[r.roomId] as BigDecimal
      def baseLegacy = app.calculateVentOpenPercentage(
        r.roomId as String, r.tempC as BigDecimal, setpointC as BigDecimal,
        mode, r.legacyRate as BigDecimal, r.legacyTime as BigDecimal)
      def offsetZeroLegacy = app.calculateVentOpenPercentage(
        r.roomId as String, r.tempC as BigDecimal, resolved,
        mode, r.legacyRate as BigDecimal, r.legacyTime as BigDecimal)
      Math.abs((offsetZeroLegacy as double) - (baseLegacy as double)) <= EPS
    }

    where:
    i << (0..<PropertyGen.ITERATIONS)
  }

  // ---------------------------------------------------------------------------
  // Scenario generation: 2-5 active rooms (a mix of satisfied/unsatisfied) plus
  // 0-2 inactive filler rooms, sharing one randomized setpoint/mode/granularity.
  // Each room also carries legacy-sizing inputs (rate/time) so the `dab` leg can
  // exercise calculateVentOpenPercentage on the same population.
  // ---------------------------------------------------------------------------

  private Map makeScenario(PropertyGen g) {
    String mode = g.nextBoolean() ? 'cooling' : 'heating'
    double setpointC = g.nextDouble(18.0d, 26.0d)   // within the 10-32 C absolute band
    boolean heating = mode == 'heating'

    List rooms = []
    int nActive = g.nextInt(2, 5)
    (0..<nActive).each { int k -> rooms << makeRoom(g, "a${k}", true, setpointC, heating) }
    int nInactive = g.nextInt(0, 2)
    (0..<nInactive).each { int k -> rooms << makeRoom(g, "i${k}", false, setpointC, heating) }

    Map s = lib.dabv2NewAllocSettings()
    s.hysteresisC = HYST
    s.granularity = g.nextInt(1, 10)
    s.safetyFloorPct = g.nextDouble(20.0d, 90.0d)
    s.conventionalVents = g.nextInt(0, 3)
    s.conventionalOpenPct = g.nextDouble(0.0d, 100.0d)

    return [rooms: rooms, setpointC: setpointC, mode: mode, settings: s]
  }

  private Map makeRoom(PropertyGen g, String roomId, boolean active,
      double setpointC, boolean heating) {
    // Spread temperatures across both satisfied and unsatisfied regions so the
    // allocator and the legacy sizer both see real work on at least some rooms.
    double tempC = setpointC + g.nextDouble(-4.0d, 4.0d)

    Map r = [:]
    r.roomId = roomId
    r.active = active
    r.tempC = tempC
    r.efficiency = g.nextDouble(0.05d, 0.5d)
    double leak = g.nextDouble(0.0d, 0.2d)
    r.leak = leak
    r.currentOpen = g.nextDouble(0.0d, 100.0d)
    int nVents = g.nextInt(1, 2)
    r.ventIds = (0..<nVents).collect { "${roomId}#v${it}".toString() }
    r.signedErrorC = heating ? (setpointC - tempC) : (tempC - setpointC)
    r.curve = g.nextBoolean() ? lib.lrnSeedLinear(leak) : null
    // Legacy `dab` sizing inputs (calculateVentOpenPercentage): a positive rate
    // and time so the formula returns a meaningful intermediate percentage.
    r.legacyRate = g.nextDouble(0.05d, 3.0d)
    r.legacyTime = g.nextDouble(1.0d, 60.0d)
    return r
  }

  // Build the app sandbox for the legacy sizing path. Created fresh per feature
  // execution (called from the `given:` block) so the Mock's interaction stubs are
  // active — mirrors the proven OvershootCloseTest.buildScript() pattern.
  private Object legacySizingApp() {
    def log = new CapturingLog()
    AppExecutor executorApi = Mock {
      _ * getState() >> [:]
      _ * getLog() >> log
    }
    return new HubitatAppSandbox(Dabv2AppHarness.combinedAppText()).run(
      'api': executorApi,
      'validationFlags': VALIDATION_FLAGS,
      'userSettingValues': USER_SETTINGS)
  }
}
