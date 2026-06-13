
import spock.lang.Specification
import spock.lang.Shared

/**
 * Property-based test for the PURE Allocator movement-gating decision (task
 * 6.8): {@code Dabv2Allocator.shouldApply(current, proposed, rooms, settings,
 * gate)}.
 *
 * Implements Correctness Property 11 from design.md EXACTLY (one property per
 * feature method, tagged with the exact property heading):
 *
 *   Property 11: Movement-gating soundness
 *   "For any current and proposed allocations, shouldApply returns true only
 *    when a move is strictly required to reach the safety floor (immediate,
 *    bypassing both gates), OR both the predicted current spread exceeds the
 *    guardrail AND the predicted improvement is at least the deadband."
 *   Validates: Requirements 10.1, 10.2, 10.3, 10.5.
 *
 * <b>Decision under test (mirrors Reference {@code balance.should_apply}, A5).</b>
 * The decision is a PURE function of five inputs and is verified against an
 * independent oracle that re-derives the expected boolean from the spec text:
 *
 *   1. {@code gate.floorRequiresMove} ⇒ TRUE immediately (R10.5) — a move
 *      strictly required to raise a vent to the airflow-safety floor bypasses
 *      the guardrail, the improvement deadband, AND the anti-chatter/batch
 *      gates.
 *   2. Otherwise, honor the app-evaluated anti-chatter cooldown and per-cycle/
 *      per-window batch caps (R10.4): {@code withinAntiChatterCooldown} or
 *      {@code batchLimitReached} ⇒ FALSE (hold).
 *   3. Otherwise require the predicted CURRENT spread
 *      ({@code gate.predictedCurrentSpreadC}) to be strictly ABOVE
 *      {@code spreadGuardrailC} (R10.1/10.2) — at or below the guardrail we hold
 *      rather than chase further equalization.
 *   4. AND require the predicted improvement (current − proposed predicted
 *      spread, the proposed spread computed by {@link Dabv2Allocator#predictedSpread}
 *      over the {@code proposed} targets) to be at least
 *      {@code spreadImprovementDeadbandC} (R10.3).
 *
 * <b>Scenario coverage.</b> Each iteration is steered into one of five
 * categories so the property exercises every branch across >= 100 runs:
 * FLOOR_REQUIRED (bypass ⇒ true), COOLDOWN/BATCH (hold even above guardrail),
 * BELOW_GUARDRAIL (hold), ABOVE_GUARDRAIL_BELOW_DEADBAND (hold), and
 * ABOVE_GUARDRAIL_IMPROVING (move ⇒ true). The assertion is exact equivalence
 * to the oracle on EVERY iteration regardless of category, so the categories
 * only guarantee branch coverage — they are not separate assertions.
 *
 * The class name contains "Property" so `./gradlew test --tests '*Property*'`
 * selects it; the `where:` block drives >= PropertyGen.ITERATIONS (100)
 * reproducible randomized scenarios.
 */
class AllocatorShouldApplyPropertySpec extends Specification {

  // The methods-only DAB v2 library, loaded as a script so its top-level
  // methods and @Field constants are callable off-device.
  @Shared
  Script lib = new GroovyShell().parse(new File('libraries/flair-vents-dabv2.groovy'))

  def 'Feature: hubitat-flair-vents-dab-v2, Property 11: Movement-gating soundness'() {
    given: 'a randomized gating scenario steered into one of five branch categories'
    def g = PropertyGen.forIteration(i)
    Map scenario = makeScenario(g, i)
    List rooms = scenario.rooms as List
    Map<String, Double> current = scenario.current as Map<String, Double>
    Map<String, Double> proposed = scenario.proposed as Map<String, Double>
    Map s = scenario.settings as Map
    Map gate = scenario.gate as Map

    when: 'the movement gate is evaluated'
    boolean actual = lib.allocShouldApply(current, proposed, rooms, s, gate)

    and: 'the expected decision is re-derived independently from the spec text'
    boolean expected = oracle(rooms, proposed, s, gate)

    then: 'shouldApply matches the oracle exactly (Property 11 soundness)'
    actual == expected

    where:
    i << (0..<PropertyGen.ITERATIONS)
  }

  // ---------------------------------------------------------------------------
  // Independent oracle — re-derives the expected boolean straight from the
  // requirement text (R10.5 floor bypass; R10.4 cooldown/batch; R10.1/10.2
  // guardrail; R10.3 deadband). It calls predictedSpread ONLY for the proposed
  // side; the current side is read from the gate (the app supplies it).
  // ---------------------------------------------------------------------------

  private boolean oracle(List rooms, Map<String, Double> proposed,
      Map s, Map gate) {
    if (gate.floorRequiresMove) {
      return true
    }
    if (gate.withinAntiChatterCooldown || gate.batchLimitReached) {
      return false
    }
    double currentSpread = gate.predictedCurrentSpreadC
    if (currentSpread <= s.spreadGuardrailC) {
      return false
    }
    double proposedSpread =
        lib.allocPredictedSpread(rooms, proposed, gate.mode, gate.setpointC, s.horizonMin)
    return (currentSpread - proposedSpread) >= s.spreadImprovementDeadbandC
  }

  // ---------------------------------------------------------------------------
  // Scenario generation — picks a branch category, then builds active rooms,
  // current/proposed target maps, settings (guardrail/deadband/horizon), and a
  // GateContext whose fields realize that category.
  // ---------------------------------------------------------------------------

  private static final List<String> CATEGORIES = [
    'FLOOR_REQUIRED', 'COOLDOWN_OR_BATCH', 'BELOW_GUARDRAIL',
    'ABOVE_GUARDRAIL_BELOW_DEADBAND', 'ABOVE_GUARDRAIL_IMPROVING',
  ]

  private Map makeScenario(PropertyGen g, int i) {
    String category = CATEGORIES[i % CATEGORIES.size()]
    String mode = g.nextBoolean() ? 'cooling' : 'heating'
    boolean heating = mode == 'heating'
    double setpointC = g.nextDouble(18.0d, 24.0d)

    Map s = lib.dabv2NewAllocSettings()
    s.spreadGuardrailC = g.nextDouble(0.2d, 5.0d)
    s.spreadImprovementDeadbandC = g.nextDouble(0.0d, 2.0d)
    s.horizonMin = g.nextDouble(5.0d, 60.0d)

    // 1-4 active rooms (plus occasional inactive rooms that the gate ignores).
    int nActive = g.nextInt(1, 4)
    List rooms = []
    Map<String, Double> current = new LinkedHashMap<String, Double>()
    Map<String, Double> proposed = new LinkedHashMap<String, Double>()
    (0..<nActive).each { int k ->
      Map r = makeRoom(g, "a${k}", true, setpointC, heating)
      rooms << r
      current.put(r.roomId, (double) g.nextInt(0, 20) * 5.0d)
      proposed.put(r.roomId, (double) g.nextInt(0, 20) * 5.0d)
    }
    int nInactive = g.nextInt(0, 2)
    (0..<nInactive).each { int k ->
      rooms << makeRoom(g, "x${k}", false, setpointC, heating)
    }

    double proposedSpread =
        lib.allocPredictedSpread(rooms, proposed, mode, setpointC, s.horizonMin)

    Map gate = lib.allocNewGateContext()
    gate.mode = mode
    gate.setpointC = setpointC
    gate.floorRequiresMove = false
    gate.withinAntiChatterCooldown = false
    gate.batchLimitReached = false

    switch (category) {
      case 'FLOOR_REQUIRED':
        // Floor bypass wins regardless of every other field — randomize them.
        gate.floorRequiresMove = true
        gate.withinAntiChatterCooldown = g.nextBoolean()
        gate.batchLimitReached = g.nextBoolean()
        gate.predictedCurrentSpreadC = g.nextDouble(0.0d, 6.0d)
        break
      case 'COOLDOWN_OR_BATCH':
        // Above the guardrail (would otherwise move) but anti-chatter/batch hold.
        if (g.nextBoolean()) { gate.withinAntiChatterCooldown = true }
        else { gate.batchLimitReached = true }
        gate.predictedCurrentSpreadC = s.spreadGuardrailC + g.nextDouble(0.5d, 3.0d)
        break
      case 'BELOW_GUARDRAIL':
        // Current spread at/below the guardrail -> hold.
        gate.predictedCurrentSpreadC = g.nextDouble(0.0d, s.spreadGuardrailC)
        break
      case 'ABOVE_GUARDRAIL_BELOW_DEADBAND':
        // Above guardrail but the predicted improvement is below the deadband.
        gate.predictedCurrentSpreadC =
            proposedSpread + g.nextDouble(0.0d, Math.max(1e-6d, s.spreadImprovementDeadbandC))
        if (gate.predictedCurrentSpreadC <= s.spreadGuardrailC) {
          gate.predictedCurrentSpreadC = s.spreadGuardrailC + g.nextDouble(0.01d, 0.5d)
        }
        break
      default: // ABOVE_GUARDRAIL_IMPROVING
        // Above guardrail AND improvement clears the deadband -> move.
        gate.predictedCurrentSpreadC = Math.max(s.spreadGuardrailC, proposedSpread) +
            s.spreadImprovementDeadbandC + g.nextDouble(0.1d, 3.0d)
        break
    }

    return [rooms: rooms, current: current, proposed: proposed, settings: s, gate: gate]
  }

  private Map makeRoom(PropertyGen g, String roomId, boolean active,
      double setpointC, boolean heating) {
    double tempC = heating ? setpointC - g.nextDouble(-3.0d, 6.0d)
                           : setpointC + g.nextDouble(-3.0d, 6.0d)
    Map r = [:]
    r.roomId = roomId
    r.active = active
    r.tempC = tempC
    r.efficiency = g.nextDouble(0.02d, 0.5d)
    double leak = g.nextDouble(0.0d, 0.2d)
    r.leak = leak
    r.currentOpen = g.nextDouble(0.0d, 100.0d)
    r.ventIds = ["${roomId}#v0".toString()]
    r.signedErrorC = heating ? (setpointC - tempC) : (tempC - setpointC)
    r.curve = g.nextBoolean() ? lib.lrnSeedLinear(leak) : null
    return r
  }
}
