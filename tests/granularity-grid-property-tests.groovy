
// DAB v2 / legacy vent-GRANULARITY grid property tests (Task 3.1; R5).
//
// Implements Correctness Properties 19 and 20 from design.md EXACTLY (one
// property per feature method, tagged with the exact property heading), plus
// the G=5 / G=100 boundary EXAMPLE specs called for by Task 3.1.
//
//   Property 19: Granularity multiples with no group split  (R5.1/R5.9)
//     For every G in {5,10,25,50,100} and any computed target, each dispatched
//     position is an exact multiple of G in 0-100, every vent in a room-group
//     shares one rounded value, and intermediate multiples are reachable from
//     intermediate inputs.
//     Validates: Requirements 5.1, 5.9.
//
//   Property 20: Granularity tie-break determinism  (R5.3)
//     A target exactly halfway between two multiples rounds to the HIGHER
//     multiple, identically across repeated evaluations -- on BOTH the legacy
//     path (`roundToNearestMultiple`) and the DAB v2 path that snaps a computed
//     intermediate aperture onto the granularity grid (`allocRoundToGranularity`,
//     re-confirmed by `dabV2GroupNormalize`).
//     Validates: Requirements 5.3.
//
// STRICT TDD: these specs encode the REQUIRED behavior for Task 3.2 and are
// expected to be RED against the current code. The DAB v2 grid snap currently
// rounds half-to-even (Math.rint), so an exactly-halfway computed target rounds
// to the LOWER multiple (e.g. 25 -> 20 at G=10), violating R5.3's round-half-up
// tie-break. The class name contains "Property" so `--tests '*Property*'`
// selects it; each property `where:` block drives >= PropertyGen.ITERATIONS
// (100) reproducible randomized scenarios.
//
// Run `./gradlew test --tests '*GranularityGrid*'`.

import me.biocomp.hubitat_ci.util.CapturingLog
import me.biocomp.hubitat_ci.api.app_api.AppExecutor
import me.biocomp.hubitat_ci.app.HubitatAppSandbox
import me.biocomp.hubitat_ci.validation.Flags
import spock.lang.Shared
import spock.lang.Specification

class GranularityGridPropertySpec extends Specification {

  private static final String APP_FILE = Dabv2AppHarness.combinedAppText()
  private static final List VALIDATION_FLAGS = [
            Flags.DontValidateMetadata,
            Flags.DontValidatePreferences,
            Flags.DontValidateDefinition,
            Flags.DontRestrictGroovy,
            Flags.DontRequireParseMethodInDevice,
            Flags.AllowWritingToSettings,
            Flags.AllowReadingNonInputSettings
          ]

  // The five supported granularity grids (R5.1).
  private static final List GRID = [5, 10, 25, 50, 100]

  // One sandbox script per granularity (settings.ventGranularity drives both
  // the legacy `roundToNearestMultiple` and the DAB v2 alloc-settings grid).
  @Shared Map<Integer, Object> scriptByG = [:]

  // Cross-iteration accumulator proving intermediate multiples are reachable.
  @Shared Set observedIntermediate = [] as Set

  def setupSpec() {
    GRID.each { int g -> scriptByG[g] = buildScript(g) }
  }

  private Object buildScript(int g) {
    final log = new CapturingLog()
    AppExecutor executorApi = Mock {
      _ * getState() >> [:]
      _ * getAtomicState() >> [:]
      _ * getLog() >> log
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi,
      'validationFlags': VALIDATION_FLAGS,
      'userSettingValues': [safetyFloorPct: 30, thermostat1AdditionalStandardVents: 0,
                            ventGranularity: g.toString()])
    script.atomicState = [:]
    return script
  }

  // ---------------------------------------------------------------------------
  // Property 19 — multiples of G in 0-100, no group split, intermediate reachable
  // ---------------------------------------------------------------------------
  def 'Feature: hubitat-flair-vents-dab-v2, Property 19: granularity multiples with no group split'() {
    given: 'a randomized zone evaluated under one of the five configured grids'
    def gen = PropertyGen.forIteration(i)
    int g = GRID[gen.nextInt(0, GRID.size() - 1)]
    def script = scriptByG[g]
    String mode = gen.nextBoolean() ? 'cooling' : 'heating'
    boolean heating = mode == 'heating'
    double setpointC = gen.nextDouble(19.0d, 23.0d)
    int nRooms = gen.nextInt(1, 5)

    List roomData = []
    (0..<nRooms).each { int k ->
      // Force room 0 to be multi-vent so the no-group-split guarantee is exercised.
      int nVents = (k == 0) ? gen.nextInt(2, 4) : gen.nextInt(1, 3)
      List ventIds = (0..<nVents).collect { "r${k}#v${it}".toString() }
      double tempC = heating ? setpointC - gen.nextDouble(-3.0d, 6.0d)
                             : setpointC + gen.nextDouble(-3.0d, 6.0d)
      roomData << [
        roomId      : "r${k}".toString(),
        tempC       : tempC,
        active      : true,
        coolingRate : gen.nextDouble(0.02d, 0.3d),
        heatingRate : gen.nextDouble(0.02d, 0.3d),
        currentOpen : (double) gen.nextInt(0, 20) * 5.0d,
        ventIds     : ventIds,
      ]
    }

    when: 'the zone is evaluated, group-dispatched, and snapped for dispatch'
    def result = script.evaluateDabV2Zone(roomData, setpointC, mode, null, [hour: 12], null)
    List rooms = script.dabV2DispatchRoomView(result.targets, roomData)
    def plan = script.dabV2PlanGroupDispatch(
        result.targets, result.floorRequiredRooms, rooms, [:], 1_000_000L, [:])
    Map perVent = script.dabV2ExpandGroupTargets(plan)
    // Final commanded position is the grid-snapped value the dispatcher sends.
    Map dispatched = perVent.collectEntries { vid, t ->
      [vid, script.roundToNearestMultiple(t as BigDecimal)]
    }

    then: 'every dispatched position is an exact multiple of G within 0-100 (R5.1)'
    dispatched.values().every { int p ->
      p >= 0 && p <= 100 && (p % g == 0)
    }

    and: 'every vent in a room-group shares one rounded value (R5.9, no split)'
    roomData.every { rd ->
      def vals = (rd.ventIds ?: []).collect { dispatched[it] }.findAll { it != null }
      vals.unique().size() <= 1
    }

    and: 'intermediate multiples are reachable across the input space (R5.1)'
    dispatched.values().each { int p -> if (p > 0 && p < 100) { observedIntermediate << p } }
    (i < PropertyGen.ITERATIONS - 1) || !observedIntermediate.isEmpty()

    where:
    i << (0..<PropertyGen.ITERATIONS)
  }

  // ---------------------------------------------------------------------------
  // Property 20 — exactly-halfway rounds to the HIGHER multiple, deterministically
  // ---------------------------------------------------------------------------
  def 'Feature: hubitat-flair-vents-dab-v2, Property 20: granularity tie-break determinism'() {
    given: 'a value exactly halfway between two multiples of the configured grid'
    def gen = PropertyGen.forIteration(i)
    int g = GRID[gen.nextInt(0, GRID.size() - 1)]
    def script = scriptByG[g]
    int m = gen.nextInt(0, (100 / g as int) - 1)   // lower multiple index
    double lower = (double) (m * g)
    double higher = (double) ((m + 1) * g)
    double halfway = lower + (g / 2.0d)             // exactly between lower and higher

    expect: 'the legacy path rounds the tie up to the higher multiple (R5.3)'
    script.roundToNearestMultiple(halfway as BigDecimal) == (int) higher

    and: 'the legacy path is deterministic across repeated evaluations'
    script.roundToNearestMultiple(halfway as BigDecimal) ==
        script.roundToNearestMultiple(halfway as BigDecimal)

    and: 'the DAB v2 computed-target grid snap also rounds the tie up to the higher multiple (R5.3/R5.7/R5.8)'
    script.allocRoundToGranularity(halfway, g) == higher

    and: 'the DAB v2 group-normalize rounds the tie up to the higher multiple (R5.8/R5.9)'
    (script.dabV2GroupNormalize([only: halfway], g).only as double) == higher

    and: 'the DAB v2 grid snap is deterministic across repeated evaluations'
    script.allocRoundToGranularity(halfway, g) == script.allocRoundToGranularity(halfway, g)

    where:
    i << (0..<PropertyGen.ITERATIONS)
  }

  // ---------------------------------------------------------------------------
  // Example specs — G=5 and G=100 boundaries honored on BOTH paths.
  // ---------------------------------------------------------------------------
  def 'G=5 boundary honored on the legacy roundToNearestMultiple path'() {
    given:
    def script = scriptByG[5]

    expect: 'positions snap to integer multiples of 5 across 0-100 (R5.5)'
    script.roundToNearestMultiple(0 as BigDecimal) == 0
    script.roundToNearestMultiple(12 as BigDecimal) == 10
    script.roundToNearestMultiple(13 as BigDecimal) == 15
    script.roundToNearestMultiple(37 as BigDecimal) == 35
    script.roundToNearestMultiple(63 as BigDecimal) == 65
    script.roundToNearestMultiple(100 as BigDecimal) == 100
  }

  def 'G=5 boundary honored on the DAB v2 dabV2GroupNormalize path'() {
    given:
    def script = scriptByG[5]

    when:
    Map out = script.dabV2GroupNormalize([a: 12, b: 13, c: 37, d: 63], 5)

    then: 'every room snaps to a multiple of 5 in 0-100, intermediate multiples reachable (R5.5/R5.8)'
    out.a == 10.0d
    out.b == 15.0d
    out.c == 35.0d
    out.d == 65.0d
  }

  def 'G=100 boundary commands only 0 or 100 on the legacy roundToNearestMultiple path'() {
    given:
    def script = scriptByG[100]

    expect: 'only the open/closed extremes are commanded (R5.6)'
    script.roundToNearestMultiple(0 as BigDecimal) == 0
    script.roundToNearestMultiple(37 as BigDecimal) == 0
    script.roundToNearestMultiple(49 as BigDecimal) == 0
    script.roundToNearestMultiple(63 as BigDecimal) == 100
    script.roundToNearestMultiple(100 as BigDecimal) == 100
  }

  def 'G=100 boundary commands only 0 or 100 on the DAB v2 dabV2GroupNormalize path'() {
    given:
    def script = scriptByG[100]

    when:
    Map out = script.dabV2GroupNormalize([a: 37, b: 49, c: 63, d: 100], 100)

    then: 'every room is either fully closed or fully open (R5.6/R5.8)'
    out.values().every { (it as double) == 0.0d || (it as double) == 100.0d }
    out.a == 0.0d
    out.c == 100.0d
  }
}
