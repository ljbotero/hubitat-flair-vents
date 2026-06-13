
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import spock.lang.Specification
import spock.lang.Unroll
import spock.lang.Shared

/**
 * Unit tests for the PURE persisted-state model I/O (task 7.1):
 * {@code Dabv2ModelIo} schema-v2 {@code encode}/{@code decode} and the ordered
 * state-size {@code bound} strategy.
 *
 * Covers design.md "Persisted state schema v2" + "State-size bounding strategy"
 * (R12.1, R12.2):
 *
 *   (a) encode -> decode is LOSSLESS for a representative in-memory model
 *       (re-encoding a decoded model yields the identical compact map, and the
 *       map survives a JSON string round-trip);
 *   (b) the documented bounding strategy — applied IN ORDER: drop per-vent `bp`,
 *       round stored flows/rates to 4 significant digits, prune the
 *       least-recently-updated vent/room entries — keeps a representative
 *       100-room / 200-vent model under the conservative byte budget;
 *   (c) bounding NEVER silently truncates: every step it takes is observable on
 *       the returned result (which steps ran, which entries were dropped/pruned),
 *       and a model already under budget is returned untouched. A dropped `bp`
 *       array is rebuilt from the shared constant on decode so the curve stays
 *       usable.
 *
 * PURE-core contract (R18.2 / R18.7): NO Hubitat APIs, time, randomness, state.
 *
 * Validates: Requirements 12.1, 12.2.
 */
class ModelIoSchemaRoundtripUnitSpec extends Specification {

  // The methods-only DAB v2 library, loaded as a script so its top-level
  // methods and @Field constants are callable off-device.
  @Shared
  Script lib = new GroovyShell().parse(new File('libraries/flair-vents-dabv2.groovy'))

  private static final double EPS = 1e-9d

  // ---------------------------------------------------------------------------
  // Builders for a representative in-memory model.
  // ---------------------------------------------------------------------------

  private Map roomModel(double cBase, int cN,
      double hBase, int hN, long seed) {
    Map rm = lib.lrnNewRoomModel()
    rm.cooling.baseline = cBase
    rm.cooling.n = cN
    rm.heating.baseline = hBase
    rm.heating.n = hN
    for (int k = 0; k < lib.LRN_EFF_REGIME_COUNT; k++) {
      rm.cooling.regimes[k].rate = 0.01d + ((((seed + k) % 7) + 1) * 0.0017d)
      rm.cooling.regimes[k].n = (int) ((seed + k) % 11)
      rm.heating.regimes[k].rate = 0.02d + ((((seed + k) % 5) + 1) * 0.0021d)
      rm.heating.regimes[k].n = (int) ((seed + k) % 9)
    }
    return rm
  }

  private Map ventMode(double leak, int n, int knee, long seed) {
    Map curve = lib.lrnSeedLinear(leak)
    // Fold a few observations so flows diverge from a pristine seed.
    lib.lrnVentCurveUpdate(curve, 5.0d, leak + 0.31d)
    lib.lrnVentCurveUpdate(curve, 35.0d, 0.7d + ((seed % 5) * 0.01d))
    lib.lrnVentCurveUpdate(curve, 75.0d, 0.95d)
    Map vm = lib.mioNewVentMode()
    vm.leak = leak
    vm.n = n
    vm.knee = knee
    vm.curve = curve
    vm.sx = 1234.5d + seed
    vm.sy = 67.89d + (seed * 0.5d)
    vm.sxx = 98765.4321d + seed
    vm.sxy = 4321.1234d + seed
    return vm
  }

  private Map ventEff(double leak, long seed) {
    Map ve = [cooling: null, heating: null]
    ve.cooling = ventMode(leak, 55, 50, seed)
    ve.heating = ventMode(leak + 0.02d, 30, 75, seed + 3)
    return ve
  }

  /** A small but fully-populated model exercising every schema-v2 section. */
  private Map representativeModel() {
    Map m = lib.mioNewModel()
    m.roomEff['living'] = roomModel(0.0170d, 42, 0.0210d, 30, 1L)
    m.roomEff['bedroom'] = roomModel(0.0231d, 18, 0.0185d, 12, 2L)
    m.ventEff['vent-a'] = ventEff(0.10d, 1L)
    m.ventEff['vent-b'] = ventEff(0.05d, 2L)
    m.metrics['balance'] = [
      adjPerCycle: 3.5d, movePerCycle: 2.0d, avgSpread: 0.8d,
      maxSpread: 2.4d, timeAboveGuardrailMin: 12.0d, avgErr: 0.5d, maxErr: 1.7d
    ]
    m.cycle = [id: 'cyc-123', mode: 'cooling', anchorTargets: [living: 60.0d, bedroom: 40.0d],
               startedMs: 1700000000000L]
    m.counters = [recalc24h: 7, hold24h: 19, windowStartMs: 1699990000000L]
    m.preAdjustFlags = [living: true]
    return m
  }

  /** A large representative model: 100 rooms, 200 smart vents (two per room). */
  private Map largeModel() {
    Map m = lib.mioNewModel()
    for (int r = 0; r < 100; r++) {
      String room = 'room-' + r
      m.roomEff[room] = roomModel(
        0.0100d + (r * 0.00013d), 20 + r,
        0.0200d + (r * 0.00017d), 15 + r, (long) r)
      m.ventEff[room + '#v1'] = ventEff(0.05d + ((r % 7) * 0.01d), (long) r)
      m.ventEff[room + '#v2'] = ventEff(0.06d + ((r % 5) * 0.01d), (long) (r + 100))
    }
    m.metrics['balance'] = [
      adjPerCycle: 3.123456789d, movePerCycle: 2.0d, avgSpread: 0.812345d,
      maxSpread: 2.456789d, timeAboveGuardrailMin: 12.0d, avgErr: 0.534d, maxErr: 1.789d
    ]
    m.counters = [recalc24h: 7, hold24h: 19, windowStartMs: 1699990000000L]
    return m
  }

  // ---------------------------------------------------------------------------
  // (a) round-trip lossless
  // ---------------------------------------------------------------------------

  def 'encode produces the compact schema-v2 shape with short keys'() {
    when:
    Map<String, Object> enc = lib.mioEncode(representativeModel())

    then: 'top-level schema-v2 sections present, stamped v=2'
    enc.v == 2
    enc.roomEff instanceof Map
    enc.ventEff instanceof Map
    enc.metrics instanceof Map
    enc.cycle instanceof Map
    enc.counters instanceof Map
    enc.preAdjustFlags instanceof Map

    and: 'roomEff uses c/h with b/rg/n short keys'
    Map living = (Map) ((Map) enc.roomEff).get('living')
    Map livingC = (Map) living.get('c')
    Math.abs((livingC.get('b') as double) - 0.0170d) <= EPS
    livingC.get('n') == 42
    ((List) livingC.get('rg')).size() == lib.LRN_EFF_REGIME_COUNT
    ((List) ((List) livingC.get('rg')).get(0)).size() == 2

    and: 'ventEff carries bp/f/cnt + leak/knee/n + regression sums per mode'
    Map ventA = (Map) ((Map) enc.ventEff).get('vent-a')
    Map ventAC = (Map) ventA.get('c')
    ((List) ventAC.get('bp')) == lib.LRN_CURVE_BREAKPOINTS
    ((List) ventAC.get('f')).size() == lib.LRN_CURVE_BREAKPOINTS.size()
    ((List) ventAC.get('cnt')).size() == lib.LRN_CURVE_BREAKPOINTS.size()
    ventAC.containsKey('sx')
    ventAC.containsKey('sy')
    ventAC.containsKey('sxx')
    ventAC.containsKey('sxy')
  }

  def 'encode -> decode -> encode is lossless (idempotent compact map)'() {
    given:
    Map original = representativeModel()

    when:
    Map<String, Object> enc1 = lib.mioEncode(original)
    Map decoded = lib.mioDecode(enc1)
    Map<String, Object> enc2 = lib.mioEncode(decoded)

    then: 're-encoding a decoded model reproduces the identical compact map'
    enc2 == enc1
  }

  def 'model survives a full JSON string round-trip'() {
    given:
    Map original = representativeModel()
    Map<String, Object> enc1 = lib.mioEncode(original)

    when: 'serialize to a JSON string and parse back (as the app persists state)'
    String json = lib.mioToJson(enc1)
    Object parsed = new JsonSlurper().parseText(json)
    Map decoded = lib.mioDecode(parsed)
    Map<String, Object> enc2 = lib.mioEncode(decoded)

    then: 'the compact map is preserved across the JSON boundary'
    enc2 == enc1
  }

  def 'decoded model preserves learned vent-curve flows and knee'() {
    given:
    Map original = representativeModel()

    when:
    Map decoded = lib.mioDecode(lib.mioEncode(original))

    then: 'the vent curve flows survive verbatim'
    def origC = original.ventEff['vent-a'].cooling
    def decC = decoded.ventEff['vent-a'].cooling
    decC.curve.flows == origC.curve.flows
    decC.curve.breakpoints == origC.curve.breakpoints
    decC.knee == origC.knee
    Math.abs(decC.leak - origC.leak) <= EPS
    Math.abs(decC.sxy - origC.sxy) <= EPS
  }

  // ---------------------------------------------------------------------------
  // (b) ordered bounding keeps a 100-room / 200-vent model under budget
  // ---------------------------------------------------------------------------

  def 'a representative 100-room / 200-vent model is bounded under the conservative budget'() {
    given:
    Map big = largeModel()
    int budget = lib.MIO_CONSERVATIVE_BYTE_BUDGET

    when:
    Map res = lib.mioBound(big, budget)

    then: 'the bounded payload fits under the conservative byte budget'
    res.withinBudget
    res.finalBytes <= budget

    and: 'the bounded map is still a valid, decodable schema-v2 payload'
    Map reloaded = lib.mioDecode(res.encoded)
    reloaded.version == 2
  }

  def 'the conservative budget is well under the ~100 KB working assumption'() {
    expect:
    lib.MIO_CONSERVATIVE_BYTE_BUDGET <= 100000
  }

  // ---------------------------------------------------------------------------
  // (c) bounding is observable and never silently truncates
  // ---------------------------------------------------------------------------

  def 'a model already under budget is returned untouched (no steps, no loss)'() {
    given:
    Map small = representativeModel()
    int generousBudget = 10_000_000

    when:
    Map res = lib.mioBound(small, generousBudget)

    then: 'nothing was dropped, rounded, or pruned'
    res.withinBudget
    res.stepsApplied.isEmpty()
    !res.droppedBreakpoints
    !res.rounded
    res.prunedVents.isEmpty()
    res.prunedRooms.isEmpty()

    and: 'the payload is unchanged from a plain encode'
    res.encoded == lib.mioEncode(small)
  }

  def 'bounding applies the documented steps strictly in the canonical order'() {
    given: 'a budget just under raw so at least the first step (drop-bp) is needed'
    Map m = largeModel()
    int rawBytes = lib.mioSerializedSize(m)
    int budget = rawBytes - 2000

    when:
    Map res = lib.mioBound(m, budget)
    List<String> canonical = ['drop-bp', 'round-4sig', 'prune-lru']

    then: 'whatever steps ran are a non-empty prefix of the canonical order, starting with drop-bp'
    !res.stepsApplied.isEmpty()
    res.stepsApplied == canonical.subList(0, res.stepsApplied.size())
    res.stepsApplied.first() == 'drop-bp'
    res.droppedBreakpoints

    and: 'the result fits the budget'
    res.withinBudget
    res.finalBytes <= budget
  }

  def 'dropping bp alone can suffice (round + prune are skipped when not needed)'() {
    given: 'a budget only slightly under raw — dropping the shared bp arrays is enough'
    Map m = largeModel()
    int rawBytes = lib.mioSerializedSize(m)
    int budget = rawBytes - 2000

    when:
    Map res = lib.mioBound(m, budget)

    then: 'only drop-bp ran; rounding and pruning were unnecessary'
    res.stepsApplied == ['drop-bp']
    res.droppedBreakpoints
    !res.rounded
    res.prunedVents.isEmpty()
    res.prunedRooms.isEmpty()
  }

  def 'dropping bp is reversible: decode rebuilds breakpoints from the shared constant'() {
    given:
    Map m = representativeModel()
    int rawBytes = lib.mioSerializedSize(m)

    when: 'force at least the drop-bp step with a budget under raw'
    Map res = lib.mioBound(m, (int) (rawBytes * 0.9d))
    Map reloaded = lib.mioDecode(res.encoded)

    then: 'the persisted map omits bp but decode restores the standard breakpoints'
    res.droppedBreakpoints
    !((Map) ((Map) res.encoded.ventEff).get('vent-a')).get('c').containsKey('bp')
    reloaded.ventEff['vent-a'].cooling.curve.breakpoints == lib.LRN_CURVE_BREAKPOINTS
  }

  def 'rounding limits stored flows to 4 significant digits'() {
    given:
    Map m = largeModel()
    int rawBytes = lib.mioSerializedSize(m)

    when:
    Map res = lib.mioBound(m, (int) (rawBytes * 0.65d))

    then: 'a sampled flow value carries no more than 4 significant digits'
    res.rounded
    Map anyVent = (Map) ((Map) res.encoded.ventEff).values().iterator().next()
    List flows = (List) ((Map) anyVent.get('c')).get('f')
    flows.every { Object f ->
      double v = (f as double)
      v == lib.mioRoundSig(v, lib.MIO_SIG_DIGITS)
    }
  }

  def 'pruning is a last resort, removes least-recently-updated entries, and is fully reported'() {
    given: 'a tiny budget that forces pruning after drop-bp + rounding'
    Map m = largeModel()
    int origVents = m.ventEff.size()
    int origRooms = m.roomEff.size()
    List<String> ventOrder = new ArrayList<String>(m.ventEff.keySet())
    int tinyBudget = 4000

    when:
    Map res = lib.mioBound(m, tinyBudget)

    then: 'all three documented steps ran, in order'
    res.stepsApplied == ['drop-bp', 'round-4sig', 'prune-lru']
    res.droppedBreakpoints
    res.rounded

    and: 'pruning actually removed entries and reported every one (no silent truncation)'
    !res.prunedVents.isEmpty()
    Map reloaded = lib.mioDecode(res.encoded)
    reloaded.ventEff.size() == origVents - res.prunedVents.size()
    reloaded.roomEff.size() == origRooms - res.prunedRooms.size()

    and: 'pruned vents are the least-recently-updated (front of the insertion order)'
    res.prunedVents == ventOrder.subList(0, res.prunedVents.size())

    and: 'every pruned id is genuinely absent from the bounded payload'
    res.prunedVents.every { String id -> !((Map) res.encoded.ventEff).containsKey(id) }
    res.prunedRooms.every { String id -> !((Map) res.encoded.roomEff).containsKey(id) }
  }

  def 'serializedSize matches the JSON byte length of the encoded model'() {
    given:
    Map m = representativeModel()

    expect:
    lib.mioSerializedSize(m) ==
      lib.mioToJson(lib.mioEncode(m)).getBytes('UTF-8').length
  }

  @Unroll
  def 'roundSig keeps #value to 4 significant digits as #expected'() {
    expect:
    Math.abs(lib.mioRoundSig(value, 4) - expected) <= 1e-12d

    where:
    value          | expected
    0.0d           | 0.0d
    0.0169999999d  | 0.017d
    0.123456789d   | 0.1235d
    12345.678d     | 12350.0d
    -0.00098712d   | -0.0009871d
  }
}
