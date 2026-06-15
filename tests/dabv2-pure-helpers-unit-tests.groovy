
import spock.lang.Specification
import spock.lang.Shared
import spock.lang.Unroll

/**
 * Unit specs for the six NEW pure DAB v2 helpers (task 1.3, strict-TDD red).
 *
 * These helpers do NOT exist yet in `libraries/flair-vents-dabv2.groovy`
 * (`FlairVentsDabv2`) — they are implemented in task 1.4. This spec pins each
 * helper's contract from the design document so the implementation has an
 * executable target. Until 1.4 lands, every feature below fails (the methods
 * are absent -> MissingMethodException), which is the intended red state.
 *
 * Loaded exactly like the other pure-module specs (context-mapper,
 * safety-floor-combined): the methods-only library is parsed as a Groovy
 * script so its top-level `dabv2*` methods + `@Field` constants are callable
 * off-device. PURE contract (R8.17–R8.21): no Hubitat APIs, time, randomness,
 * or state.
 *
 * Contracts mirror design.md:
 *   - §R3  dabv2ResolveRoomTargetC : absolute wins over offset; default
 *          offset 0 == shared setpoint; abs clamps 10.0–32.0 °C,
 *          offset clamps −5.0..+5.0 °C.                     (R3.6, R3.2–R3.5)
 *   - §R4 §6.2 dabv2BackoffIntervalMs : Retry-After honored & clamped to 60 s;
 *          else exponential base 1 s ×2, capped at 60 s.    (R4.4, R4.5, R4.6, R4.7)
 *   - §R6  dabv2DetectCirculation : true on 'fan only'; true on
 *          fanMode 'on' && operatingState 'idle'; else false.(R6.1, R6.2, R6.4)
 *   - §R6  dabv2CirculationTargets : active vents (or all vents when not
 *          closing inactive) -> circulation %.               (R6.3, R6.10–R6.13)
 *   - §5.1 dabv2SelectStructureId : configured id wins; single structure
 *          adopted; >1 with none configured -> requireSelection.(R2.17, R2.18)
 *   - §R1  dabv2PuckRevision : 'PUCK2' for ep_puck2 / device-type 'PUCK2';
 *          'PUCK' for device-type 'PUCK'; else 'UNKNOWN'.    (R1.2, R1.3)
 *
 * The class name contains "Spec" so `./gradlew test` selects it.
 *
 * Validates: Requirements 1.2, 1.3, 2.17, 2.18, 3.6, 4.4, 4.5, 4.6, 4.7,
 * 6.1, 6.2, 6.4, 8.17, 8.18, 8.19, 8.20, 8.21.
 */
class Dabv2PureHelpersUnitSpec extends Specification {

  // The methods-only DAB v2 library, loaded as a script so its top-level
  // methods and @Field constants are callable off-device.
  @Shared
  Script lib = new GroovyShell().parse(new File('libraries/flair-vents-dabv2.groovy'))

  private static final double EPS = 1e-9d

  private static boolean approx(Object actual, Object expected) {
    return Math.abs((actual as double) - (expected as double)) < EPS
  }

  // =========================================================================
  // dabv2ResolveRoomTargetC — §R3 (R3.6, R3.2–R3.5)
  //   absTargetC != null -> clamp(absTargetC, 10.0, 32.0)
  //   else               -> sharedSetpointC + clamp(offsetC, -5.0, +5.0)
  // =========================================================================

  def 'default offset 0 resolves exactly to the shared setpoint (R3.3)'() {
    expect: 'no per-room config (offset 0, no absolute) == today'
    approx(lib.dabv2ResolveRoomTargetC(21.0G, null, 0.0G), 21.0G)
  }

  @Unroll
  def 'signed offset is added to the shared setpoint (shared=#shared offset=#offset -> #expected)'() {
    expect:
    approx(lib.dabv2ResolveRoomTargetC(shared as BigDecimal, null, offset as BigDecimal), expected)

    where:
    shared | offset || expected
    21.0G  | 2.0G   || 23.0G
    21.0G  | -3.0G  || 18.0G
    20.0G  | 1.5G   || 21.5G
  }

  def 'an absolute target wins over a non-zero offset (R3.1/R3.2)'() {
    expect: 'absolute is authoritative; the offset is ignored entirely'
    approx(lib.dabv2ResolveRoomTargetC(21.0G, 24.0G, 3.0G), 24.0G)
  }

  @Unroll
  def 'absolute target is clamped to 10.0–32.0 °C (abs=#abs -> #expected)'() {
    expect:
    approx(lib.dabv2ResolveRoomTargetC(21.0G, abs as BigDecimal, 0.0G), expected)

    where:
    abs    || expected
    5.0G   || 10.0G   // below min clamps up
    10.0G  || 10.0G   // min boundary
    24.0G  || 24.0G   // in range
    32.0G  || 32.0G   // max boundary
    40.0G  || 32.0G   // above max clamps down
  }

  @Unroll
  def 'offset is clamped to −5.0..+5.0 °C before adding (offset=#offset -> #expected)'() {
    expect:
    approx(lib.dabv2ResolveRoomTargetC(20.0G, null, offset as BigDecimal), expected)

    where:
    offset || expected
    -9.0G  || 15.0G   // clamps to -5 -> 20 - 5
    -5.0G  || 15.0G   // min boundary
    5.0G   || 25.0G   // max boundary
    9.0G   || 25.0G   // clamps to +5 -> 20 + 5
  }

  // =========================================================================
  // dabv2BackoffIntervalMs — §R4 §6.2 (R4.4, R4.5, R4.6, R4.7)
  //   retryAfterMs != null -> min(retryAfterMs, 60000)
  //   else                 -> min(1000 * 2^attempt, 60000)
  // =========================================================================

  def 'Retry-After is honored when present (R4.4)'() {
    expect:
    lib.dabv2BackoffIntervalMs(0, 5000L) == 5000L
  }

  def 'Retry-After is clamped to the 60 s cap (R4.5)'() {
    expect:
    lib.dabv2BackoffIntervalMs(3, 120000L) == 60000L
  }

  @Unroll
  def 'absent Retry-After uses exponential base 1 s ×2 (attempt=#attempt -> #expected ms) (R4.6)'() {
    expect:
    lib.dabv2BackoffIntervalMs(attempt, null) == expected

    where:
    attempt || expected
    0       || 1000L    // 1 s
    1       || 2000L    // 2 s
    2       || 4000L    // 4 s
    3       || 8000L    // 8 s
    4       || 16000L   // 16 s
    5       || 32000L   // 32 s
  }

  def 'exponential backoff is capped at 60 s (R4.7)'() {
    expect: 'large attempts saturate at the cap, never beyond'
    lib.dabv2BackoffIntervalMs(6, null) == 60000L
    lib.dabv2BackoffIntervalMs(20, null) == 60000L
  }

  // =========================================================================
  // dabv2DetectCirculation — §R6 (R6.1, R6.2, R6.4)
  // =========================================================================

  @Unroll
  def 'circulation detection: operatingState=#opState fanMode=#fanMode -> #expected'() {
    expect:
    lib.dabv2DetectCirculation(opState, fanMode) == expected

    where:
    opState    | fanMode || expected
    'fan only' | 'auto'  || true    // R6.1 fan-only operating state
    'fan only' | 'on'    || true    // R6.1 regardless of fanMode
    'idle'     | 'on'    || true    // R6.2 fan forced on while idle
    'idle'     | 'auto'  || false   // R6.4 idle + auto is not circulation
    'cooling'  | 'on'    || false   // R6.4 actively conditioning
    'heating'  | 'auto'  || false   // R6.4 actively conditioning
    'idle'     | null    || false   // R6.4 no fan signal
  }

  // =========================================================================
  // dabv2CirculationTargets — §R6 (R6.3, R6.10–R6.13)
  //   active-room vents (or all vents when !closeInactive) -> circulationPct
  // =========================================================================

  def 'closing inactive vents targets only active-room vents at circulation % (R6.12)'() {
    given:
    List ventIds = ['v1', 'v2', 'v3']
    Map roomActiveById = ['v1': true, 'v2': false, 'v3': true]

    when:
    Map targets = lib.dabv2CirculationTargets(ventIds, 50.0G, roomActiveById, true)

    then: 'inactive-room vent v2 is excluded; active vents open to 50%'
    targets.keySet() as Set == ['v1', 'v3'] as Set
    approx(targets['v1'], 50.0G)
    approx(targets['v3'], 50.0G)
  }

  def 'not closing inactive vents targets ALL vents at circulation % (R6.13)'() {
    given:
    List ventIds = ['v1', 'v2', 'v3']
    Map roomActiveById = ['v1': true, 'v2': false, 'v3': true]

    when:
    Map targets = lib.dabv2CirculationTargets(ventIds, 35.0G, roomActiveById, false)

    then: 'every vent opens to the circulation %, regardless of room activity'
    targets.keySet() as Set == ['v1', 'v2', 'v3'] as Set
    targets.values().every { approx(it, 35.0G) }
  }

  // =========================================================================
  // dabv2SelectStructureId — §5.1 (R2.17, R2.18)
  // =========================================================================

  def 'a configured id present in the response wins (R2.16/R2.17)'() {
    given:
    List structures = [[id: 'sA'], [id: 'sB'], [id: 'sC']]

    when:
    Map sel = lib.dabv2SelectStructureId(structures, 'sB')

    then:
    sel.id == 'sB'
    sel.requireSelection == false
  }

  def 'a single structure is adopted automatically (R2.17)'() {
    given:
    List structures = [[id: 'only']]

    when:
    Map sel = lib.dabv2SelectStructureId(structures, null)

    then:
    sel.id == 'only'
    sel.requireSelection == false
  }

  def 'more than one structure with none configured requires explicit selection (R2.18)'() {
    given:
    List structures = [[id: 's1'], [id: 's2']]

    when:
    Map sel = lib.dabv2SelectStructureId(structures, null)

    then: 'no blind first() — refuse to auto-pick'
    sel.id == null
    sel.requireSelection == true
  }

  def 'a configured id absent from a multi-structure response still requires selection (R2.18)'() {
    given:
    List structures = [[id: 's1'], [id: 's2']]

    when:
    Map sel = lib.dabv2SelectStructureId(structures, 'sX')

    then:
    sel.requireSelection == true
  }

  // =========================================================================
  // dabv2PuckRevision — §R1 (R1.2, R1.3)
  // =========================================================================

  def "ep_puck2 hardware-version-name classifies as PUCK2 (R1.2)"() {
    expect:
    lib.dabv2PuckRevision(['hardware-version-name': 'ep_puck2'], null) == 'PUCK2'
  }

  def "device-type 'PUCK2' on the hardware-version sub-resource classifies as PUCK2 (R1.2)"() {
    given:
    Map hwSub = [attributes: ['device-type': 'PUCK2']]

    expect:
    lib.dabv2PuckRevision([:], hwSub) == 'PUCK2'
  }

  def "device-type 'PUCK' classifies as PUCK"() {
    given:
    Map hwSub = [attributes: ['device-type': 'PUCK']]

    expect:
    lib.dabv2PuckRevision([:], hwSub) == 'PUCK'
  }

  @Unroll
  def 'an unrecognized revision classifies as UNKNOWN but is still onboarded (R1.3)'() {
    expect:
    lib.dabv2PuckRevision(attrs, hwSub) == 'UNKNOWN'

    where:
    attrs                                 | hwSub
    [:]                                   | null
    ['hardware-version-name': 'ep_other'] | null
    [:]                                   | [attributes: ['device-type': 'GIZMO']]
  }
}
