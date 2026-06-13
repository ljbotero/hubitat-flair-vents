
// DAB v2 vent-dispatch GROUPING property test (Task 9.3; R15.1/15.3, R15.5).
//
// Implements Correctness Property 16 from design.md EXACTLY (one property per
// feature method, tagged with the exact property heading):
//
//   Property 16: Grouping consistency
//   "For any room served by multiple smart vents, all vents in the group
//    receive identical commanded targets."
//   Validates: Requirements 15.1, 15.3, 15.5.
//
// The guarantee spans the WHOLE dispatch pipeline, not just one helper: the
// per-thermostat evaluate (Context_Mapper -> Learning_Model -> Allocator ->
// group-normalize -> Safety_Floor) produces ONE floor-safe target per room, and
// the group dispatch planner (`dabV2PlanGroupDispatch`) makes a SINGLE
// anti-chatter/cooldown decision per room-group and applies the one target to
// every physical vent in that room. Independent rounding or independent
// anti-chatter evaluation can therefore never split a group (R15.3).
//
// Each iteration generates a zone of 1-5 rooms where SOME rooms are served by
// 2-4 smart vents, runs the real evaluate + plan + expand path on the app under
// the hubitat_ci sandbox, and asserts that within every room every commanded
// vent shares one identical target. The class name contains "Property" so
// `./gradlew test --tests '*Property*'` selects it; the `where:` block drives
// >= PropertyGen.ITERATIONS (100) reproducible randomized scenarios.
//
// Run `./gradlew test --tests '*Dabv2DispatchGrouping*'`.

import me.biocomp.hubitat_ci.util.CapturingLog
import me.biocomp.hubitat_ci.api.app_api.AppExecutor
import me.biocomp.hubitat_ci.app.HubitatAppSandbox
import me.biocomp.hubitat_ci.validation.Flags
import spock.lang.Shared
import spock.lang.Specification

class Dabv2DispatchGroupingPropertySpec extends Specification {

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

  // Built once and reused: evaluate is deterministic and the planner is PURE, so
  // a single sandbox script can serve every randomized iteration (no per-iter
  // state mutation that would leak across runs).
  @Shared Object script

  def setupSpec() {
    final log = new CapturingLog()
    AppExecutor executorApi = Mock {
      _ * getState() >> [:]
      _ * getAtomicState() >> [:]
      _ * getLog() >> log
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    script = sandbox.run('api': executorApi,
      'validationFlags': VALIDATION_FLAGS,
      'userSettingValues': [safetyFloorPct: 40, thermostat1AdditionalStandardVents: 1])
    script.atomicState = [:]
  }

  def 'Feature: hubitat-flair-vents-dab-v2, Property 16: grouped vents receive identical commanded targets'() {
    given: 'a randomized zone where some rooms are served by multiple smart vents'
    def g = PropertyGen.forIteration(i)
    String mode = g.nextBoolean() ? 'cooling' : 'heating'
    boolean heating = mode == 'heating'
    double setpointC = g.nextDouble(19.0d, 23.0d)
    int nRooms = g.nextInt(1, 5)

    List roomData = []
    (0..<nRooms).each { int k ->
      // At least one room (k==0) is forced multi-vent so grouping is exercised.
      int nVents = (k == 0) ? g.nextInt(2, 4) : g.nextInt(1, 4)
      List ventIds = (0..<nVents).collect { "r${k}#v${it}".toString() }
      double tempC = heating ? setpointC - g.nextDouble(-3.0d, 6.0d)
                             : setpointC + g.nextDouble(-3.0d, 6.0d)
      roomData << [
        roomId: "r${k}".toString(),
        tempC: tempC,
        active: true,
        coolingRate: g.nextDouble(0.02d, 0.3d),
        heatingRate: g.nextDouble(0.02d, 0.3d),
        currentOpen: (double) g.nextInt(0, 20) * 5.0d,
        ventIds: ventIds,
      ]
    }

    when: 'the zone is evaluated and the group dispatch is planned + expanded'
    def result = script.evaluateDabV2Zone(roomData, setpointC, mode, null, [hour: 12], null)
    List rooms = roomData.findAll { result.targets.containsKey(it.roomId) }
    def plan = script.dabV2PlanGroupDispatch(
        result.targets, result.floorRequiredRooms, rooms, [:], 1_000_000L, [:])
    Map perVent = script.dabV2ExpandGroupTargets(plan)

    then: 'every APPLIED room-group commands one identical target to all its vents'
    plan.every { d ->
      def targets = d.ventIds.collect { perVent[it] }.findAll { it != null }
      targets.unique().size() <= 1
    }

    and: 'no room ever has two vents commanded to different targets (no split, R15.3)'
    roomData.every { rd ->
      def targets = (rd.ventIds ?: []).collect { perVent[it] }.findAll { it != null }
      targets.unique().size() <= 1
    }

    and: 'each applied per-vent target equals that room-group\'s floor-safe target'
    plan.findAll { it.apply }.every { d ->
      d.ventIds.every { vid ->
        perVent[vid] == null || Math.abs((perVent[vid] as double) - (d.target as double)) < 1e-9d
      }
    }

    where:
    i << (0..<PropertyGen.ITERATIONS)
  }
}
