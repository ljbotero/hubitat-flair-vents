
import spock.lang.Specification
import spock.lang.Shared
import spock.lang.Unroll

/**
 * Unit tests for the PURE Context_Mapper module (task 3.1), unrolled API.
 *
 * Hubitat's sandbox rejects user classes, so the DAB v2 modules ship as a
 * methods-only library (`libraries/flair-vents-dabv2.groovy`). These specs load
 * that library the same way the off-device harness does and exercise its
 * top-level `ctx*` methods + `CTX_*` constants. The resolved context is a plain
 * Map (the unrolled replacement for the old Context value object).
 *
 * Mirrors the Reference `hvac_vent_optimizer/context.py` contract EXACTLY
 * (cited in design.md "Context_Mapper" section, R11.5 / R11.6):
 *   - ctxOutdoorBand: 0 cold (<10C), 2 hot (>25C), 1 mild; missing -> mild
 *   - ctxIsDaytime: explicit sun state wins; else hour in [7, 21)
 *   - ctxBuild: pure, takes already-resolved primitives, graceful missing defaults
 *   - ctxRegimeIndex: 0 day-mild, 1 day-hot, 2 night-mild, 3 night-hot
 *   - ctxApplyMultipliers: occupancy/door bounded multipliers, clamped to
 *                          [0.5, 1.5]; missing/false -> neutral 1.0
 *
 * The class name contains "Context" so `./gradlew test --tests '*Context*'`
 * selects it.
 */
class ContextMapperTests extends Specification {

  // The methods-only DAB v2 library, loaded as a script so its top-level
  // methods and @Field constants are callable off-device.
  @Shared
  Script lib = new GroovyShell().parse(new File('libraries/flair-vents-dabv2.groovy'))

  // -------------------------------------------------------------------------
  // Module constants (concrete contract pinned by the tests)
  // -------------------------------------------------------------------------
  def 'constants have the expected concrete values'() {
    expect:
    lib.CTX_COLD_C == 10.0d
    lib.CTX_HOT_C == 25.0d
    lib.CTX_DAY_START == 7
    lib.CTX_DAY_END == 21
    lib.CTX_OCC_FACTOR == 0.9d
    lib.CTX_DOOR_FACTOR == 0.9d
    lib.CTX_FACTOR_MIN == 0.5d
    lib.CTX_FACTOR_MAX == 1.5d
  }

  def 'secondary multipliers sit inside the clamp band'() {
    expect:
    lib.CTX_FACTOR_MIN <= lib.CTX_OCC_FACTOR
    lib.CTX_OCC_FACTOR <= lib.CTX_FACTOR_MAX
    lib.CTX_FACTOR_MIN <= lib.CTX_DOOR_FACTOR
    lib.CTX_DOOR_FACTOR <= lib.CTX_FACTOR_MAX
  }

  // -------------------------------------------------------------------------
  // ctxOutdoorBand: 0 cold (< COLD_C), 1 mild, 2 hot (> HOT_C); missing -> mild
  // -------------------------------------------------------------------------
  @Unroll
  def 'ctxOutdoorBand(#outdoorC) == #expected'() {
    expect:
    lib.ctxOutdoorBand(outdoorC as Double) == expected

    where:
    outdoorC || expected
    -5.0d    || 0   // cold
    5.0d     || 0   // cold
    9.99d    || 0   // cold (just below COLD_C)
    10.0d    || 1   // mild (COLD_C boundary is NOT cold: strict <)
    15.0d    || 1   // mild
    25.0d    || 1   // mild (HOT_C boundary is NOT hot: strict >)
    25.01d   || 2   // hot (just above HOT_C)
    30.0d    || 2   // hot
  }

  def 'ctxOutdoorBand with a missing reading degrades to mild'() {
    expect:
    lib.ctxOutdoorBand(null) == 1
  }

  // -------------------------------------------------------------------------
  // ctxIsDaytime: hour in [DAY_START, DAY_END), unless a sun state is provided
  // -------------------------------------------------------------------------
  @Unroll
  def 'ctxIsDaytime(#hour) (no sun state) is #expected'() {
    expect:
    lib.ctxIsDaytime(hour) == expected

    where:
    hour || expected
    0    || false
    6    || false   // before DAY_START
    7    || true    // DAY_START inclusive
    12   || true
    20   || true
    21   || false   // DAY_END exclusive
    23   || false
  }

  def 'ctxIsDaytime: an explicit sun state overrides the hour heuristic'() {
    expect:
    // Sun above horizon at 03:00 -> day (sun wins over the hour window).
    lib.ctxIsDaytime(3, 'above_horizon')
    // Sun below horizon at noon -> night (sun wins over the hour window).
    !lib.ctxIsDaytime(12, 'below_horizon')
  }

  // -------------------------------------------------------------------------
  // ctxBuild(...): pure, takes already-resolved values (NOT HA/Hubitat states)
  // -------------------------------------------------------------------------
  def 'context is populated from all resolved primitive fields'() {
    when:
    def ctx = lib.ctxBuild(14, 30.0d, true, false, null)

    then:
    ctx.hour == 14
    ctx.isDaytime
    ctx.outdoorBand == 2                  // 30 C -> hot
    ctx.occupied == Boolean.TRUE
    ctx.doorsOpen == Boolean.FALSE
  }

  def 'missing inputs use graceful defaults'() {
    when:
    def ctx = lib.ctxBuild(2, null, null, null, null)

    then:
    ctx.hour == 2
    !ctx.isDaytime                        // 02:00 -> night
    ctx.outdoorBand == 1                  // missing outdoor -> mild
    ctx.occupied == null                  // missing occupancy stays tri-state null
    ctx.doorsOpen == null                 // door sensor unset stays null
  }

  def 'an explicit sun state wins over the hour during context construction'() {
    when:
    def ctx = lib.ctxBuild(2, 15.0d, null, null, 'above_horizon')

    then:
    ctx.isDaytime                         // 02:00 but sun above horizon -> day
  }

  // -------------------------------------------------------------------------
  // ctxRegimeIndex: 0..3 over [day-mild, day-hot, night-mild, night-hot].
  // Cold collapses with mild (regimes only distinguish hot vs not-hot).
  // -------------------------------------------------------------------------
  @Unroll
  def 'ctxRegimeIndex hour=#hour outdoor=#outdoorC -> #expectedRegime'() {
    given:
    def ctx = lib.ctxBuild(hour, outdoorC as Double, null, null, null)

    expect:
    lib.ctxRegimeIndex(ctx) == expectedRegime

    where:
    hour | outdoorC || expectedRegime
    14   | 15.0d    || 0   // day  + mild -> day-mild
    14   | 30.0d    || 1   // day  + hot  -> day-hot
    2    | 15.0d    || 2   // night + mild -> night-mild
    2    | 30.0d    || 3   // night + hot  -> night-hot
    14   | 5.0d     || 0   // day  + cold collapses to day-mild
    2    | 5.0d     || 2   // night + cold collapses to night-mild
  }

  def 'ctxRegimeIndex is always in {0,1,2,3}'() {
    expect:
    [2, 14].each { hour ->
      [null, 5.0d, 15.0d, 30.0d].each { outdoorC ->
        def ctx = lib.ctxBuild(hour, outdoorC as Double, null, null, null)
        assert lib.ctxRegimeIndex(ctx) in [0, 1, 2, 3]
      }
    }
  }

  // -------------------------------------------------------------------------
  // ctxApplyMultipliers(rate, ctx, mode): bounded secondary multipliers
  // -------------------------------------------------------------------------
  def 'occupied applies the occupancy factor'() {
    given:
    def ctx = lib.ctxBuild(14, 20.0d, true, false, null)

    expect:
    approx(lib.ctxApplyMultipliers(0.10d, ctx, 'cooling'),
        0.10d * lib.CTX_OCC_FACTOR)
  }

  def 'open doors apply the door factor'() {
    given:
    def ctx = lib.ctxBuild(14, 20.0d, false, true, null)

    expect:
    approx(lib.ctxApplyMultipliers(0.10d, ctx, 'cooling'),
        0.10d * lib.CTX_DOOR_FACTOR)
  }

  def 'occupancy and open doors compound'() {
    given:
    def ctx = lib.ctxBuild(14, 20.0d, true, true, null)

    expect:
    approx(lib.ctxApplyMultipliers(0.10d, ctx, 'cooling'),
        0.10d * lib.CTX_OCC_FACTOR * lib.CTX_DOOR_FACTOR)
  }

  def 'missing occupancy/door sources default to a neutral multiplier'() {
    given:
    def ctx = lib.ctxBuild(14, 20.0d, null, null, null)

    expect:
    approx(lib.ctxApplyMultipliers(0.10d, ctx, 'cooling'), 0.10d)
  }

  def 'explicit false occupancy/door default to a neutral multiplier'() {
    given:
    def ctx = lib.ctxBuild(14, 20.0d, false, false, null)

    expect:
    approx(lib.ctxApplyMultipliers(0.10d, ctx, 'cooling'), 0.10d)
  }

  def 'the effective multiplier always stays within the clamp band'() {
    given:
    double rate = 0.10d

    expect:
    [null, false, true].each { occ ->
      [null, false, true].each { door ->
        def ctx = lib.ctxBuild(14, 20.0d, occ as Boolean, door as Boolean, null)
        double factor = lib.ctxApplyMultipliers(rate, ctx, 'cooling') / rate
        assert factor >= lib.CTX_FACTOR_MIN
        assert factor <= lib.CTX_FACTOR_MAX
      }
    }
  }

  // Small float tolerance helper (avoids brittle exact double equality).
  private static boolean approx(double actual, double expected) {
    return Math.abs(actual - expected) < 1e-9d
  }

}
