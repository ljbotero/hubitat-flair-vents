
// DAB v2 observability surfaces — per-room grouping + system summary (Task 9.8;
// R13.1/13.2/13.3/13.4, R14.1/14.2/14.3/14.4/14.5/14.6, R9.7, R11.12).
//
// App-orchestration (non-pure) diagnostics behavior exercised against
// lightweight fakes of the Hubitat surface (child devices + add/delete/sendEvent,
// state/atomicState). The pure allocation/learning math lives in the libraries
// and is unit/property-tested separately; these tests verify only the SEAM the
// app owns: turning an evaluation result + learned model into per-room and
// system-summary diagnostic surfaces. Covers:
//   - one diagnostic child device PER managed room, each carrying ONLY that
//     room's values (temperature, signed error-to-setpoint, commanded open %,
//     airflow-limited indicator, learned cooling/heating efficiency, that room's
//     vent leak + knee) — no cross-room cramming (R14.6, R13.3, R9.7, R11.12);
//   - one system-summary device carrying zone-wide values (spread, max error,
//     hold/recalculating/idle status, 24 h counters, per-strategy metrics) and
//     NO per-room rows (R14.1, R14.2, R13.1, R13.2);
//   - per-room devices are created/removed as the topology changes (R14.6);
//   - repeated error notifications are coalesced (R14.5);
//   - no Flair credentials are logged (R14.4);
//   - the whole diagnostic surface is opt-in: when disabled, NOTHING is created
//     or emitted and control is never affected (R14.6).
//
// Run `./gradlew test --tests '*Dabv2Observability*'`.

import me.biocomp.hubitat_ci.util.CapturingLog
import me.biocomp.hubitat_ci.api.app_api.AppExecutor
import me.biocomp.hubitat_ci.api.common_api.ChildDeviceWrapper
import me.biocomp.hubitat_ci.app.HubitatAppSandbox
import me.biocomp.hubitat_ci.validation.Flags
import spock.lang.Shared
import spock.lang.Specification

class Dabv2ObservabilityTest extends Specification {

  @Shared Script lib = new GroovyShell().parse(new File('libraries/flair-vents-dabv2.groovy'))

  private static final String TOKEN = 'super-secret-flair-token-xyz789'
  private static final String APP_FILE = Dabv2AppHarness.combinedAppText()
  private static final String ROOM_PREFIX = 'dabv2-diag-room-'
  private static final String SUMMARY_DNI = 'dabv2-diag-summary'
  private static final List VALIDATION_FLAGS = [
            Flags.DontValidateMetadata,
            Flags.DontValidatePreferences,
            Flags.DontValidateDefinition,
            Flags.DontRestrictGroovy,
            Flags.DontRequireParseMethodInDevice,
            Flags.AllowWritingToSettings,
            Flags.AllowReadingNonInputSettings
          ]

  private CapturingLog log
  private Map stateMap
  private Map atomicStateMap
  private Map<String, Object> devices
  private List deviceList
  // Captured sendEvent calls keyed by device DNI -> list of [name, value] events.
  private Map<String, List> events

  private Object fakeDiagDevice(String dni) {
    Map self = [:]
    self.dni = dni
    self.getDeviceNetworkId = { -> self.dni }
    self.getLabel = { -> "Diag ${self.dni}".toString() }
    self.getId = { -> self.dni }
    self.hasAttribute = { Object... a -> true }
    self.currentValue = { Object... a -> null }
    self.toString = { -> "FakeDiag(${self.dni})".toString() }
    return self as ChildDeviceWrapper
  }

  private Object buildScript(Map userSettings = [:], Map state = [:], Map atomic = [:]) {
    log = new CapturingLog()
    stateMap = ([flairAccessToken: TOKEN] + state)
    atomicStateMap = atomic
    devices = [:]
    deviceList = []
    events = [:]
    AppExecutor executorApi = Mock {
      _ * getState() >> stateMap
      _ * getAtomicState() >> atomicStateMap
      _ * getLog() >> log
      _ * getChildDevice(_) >> { String id -> deviceList.find { it.getDeviceNetworkId() == id } }
      _ * getChildDevices() >> deviceList
      _ * addChildDevice(_, _, _, _) >> { String ns, String type, String dni, Map props ->
        def d = fakeDiagDevice(dni)
        devices[dni] = d
        deviceList << d
        return d
      }
      _ * deleteChildDevice(_) >> { String dni ->
        devices.remove(dni)
        deviceList.removeAll { it.getDeviceNetworkId() == dni }
        return null
      }
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi,
      'validationFlags': VALIDATION_FLAGS,
      'userSettingValues': ([debugLevel: 1, safetyFloorPct: 40,
                             dabV2DiagnosticsEnabled: true] + userSettings))
    script.state = stateMap
    script.atomicState = atomicStateMap
    // Capture every diagnostic event per device without a real sendEvent.
    script.metaClass.safeSendEvent = { dev, Map ev ->
      String dni = dev.getDeviceNetworkId()
      if (events[dni] == null) { events[dni] = [] }
      events[dni] << [ev.name, ev.value]
    }
    return script
  }

  private List eventNames(String dni) {
    (events[dni] ?: []).collect { it[0] }
  }

  private Object eventValue(String dni, String name) {
    (events[dni] ?: []).find { it[0] == name }?.getAt(1)
  }

  // A learned model with per-room efficiency + per-vent leak/knee.
  private Object learnedModel() {
    def model = lib.mioNewModel()
    Map rm = lib.lrnNewRoomModel()
    rm.cooling.baseline = 0.0173d
    rm.cooling.n = 7
    rm.heating.baseline = 0.0211d
    rm.heating.n = 5
    model.roomEff.put('living', rm)
    def ve = [cooling: null, heating: null]
    def c = lib.mioNewVentMode(); c.leak = 0.12d; c.knee = 75; c.curve = lib.lrnSeedLinear(0.12d)
    def h = lib.mioNewVentMode(); h.leak = 0.12d; h.knee = 75; h.curve = lib.lrnSeedLinear(0.12d)
    ve.cooling = c; ve.heating = h
    model.ventEff.put('v-living', ve)
    return model
  }

  // A representative active cooling zone result over two rooms.
  private Map zoneResult() {
    return [balancing: true, action: 'cooling', mode: 'cooling',
            targets: ['living': 80.0d, 'office': 0.0d],
            airflowLimited: (['living'] as Set),
            floorRequiredRooms: ([] as Set),
            predictedSpreadC: 1.4d, combinedOpenPct: 60.0d,
            newAnchor: false, cycleId: 4L]
  }

  private List roomData() {
    return [
      [roomId: 'living', tempC: 25.0, active: true, coolingRate: 0.1, ventIds: ['v-living']],
      [roomId: 'office', tempC: 21.0, active: true, coolingRate: 0.1, ventIds: ['v-office']]
    ]
  }

  // ---------------------------------------------------------------------------
  // Per-room diagnostic data: each room carries ONLY its own values (R14.6)
  // ---------------------------------------------------------------------------

  def "gatherDabV2RoomDiagnostics carries each room's own values and no cross-room data"() {
    setup:
    def script = buildScript()
    def model = learnedModel()

    when:
    Map diag = script.gatherDabV2RoomDiagnostics(zoneResult(), roomData(), model, 22.0)

    then: 'one entry per managed room'
    diag.keySet() == ['living', 'office'] as Set

    and: 'living carries its OWN values only'
    Map living = diag['living']
    (living.temperature as BigDecimal) == 25.0
    // cooling: signed error-to-setpoint = temp - setpoint = 25 - 22 = +3 (needs cooling)
    (living.signedErrorC as BigDecimal) == 3.0
    (living.proposedOpenPct as BigDecimal) == 80.0
    living.airflowLimited == true
    living.active == true
    Math.abs((living.coolingEfficiency as double) - 0.0173d) <= 1e-6d
    Math.abs((living.heatingEfficiency as double) - 0.0211d) <= 1e-6d
    Math.abs((living.ventLeak as double) - 0.12d) <= 1e-6d
    (living.ventKnee as int) == 75

    and: 'office is at/below setpoint => negative signed error (overcooled) and not limited'
    Map office = diag['office']
    (office.signedErrorC as BigDecimal) == -1.0
    office.airflowLimited == false

    and: 'no room map contains another room\'s id as a key (no cramming)'
    diag.every { rid, attrs -> !attrs.keySet().any { it == 'living' || it == 'office' } }
  }

  // ---------------------------------------------------------------------------
  // System summary: zone-wide values only, NO per-room rows (R14.1/14.2)
  // ---------------------------------------------------------------------------

  def "gatherDabV2SystemSummary carries zone-wide values and no per-room rows"() {
    setup:
    def script = buildScript()
    def model = learnedModel()
    Map diag = script.gatherDabV2RoomDiagnostics(zoneResult(), roomData(), model, 22.0)
    Map counters = [recalc24h: 12, hold24h: 3]

    when:
    Map sum = script.gatherDabV2SystemSummary(zoneResult(), diag, counters, null)

    then: 'spread, max active-room error, status, and 24 h counters are present'
    Math.abs((sum.spreadC as double) - 1.4d) <= 1e-6d
    // max active-room |signed error| across living(+3) and office(-1) is 3
    (sum.maxErrorC as BigDecimal) == 3.0
    sum.status == 'recalculating' || sum.status == 'hold'
    (sum.recalc24h as int) == 12
    (sum.hold24h as int) == 3
    sum.strategy != null

    and: 'NO per-room rows are crammed onto the summary (no roomIds, no per-room temp)'
    !sum.containsKey('living')
    !sum.containsKey('office')
    !sum.containsKey('temperature')
    !sum.containsKey('proposedOpenPct')
  }

  def "an inactive room's error is excluded from the summary max error"() {
    setup: 'the biggest outlier room is INACTIVE (deliberately unconditioned)'
    def script = buildScript()
    def model = learnedModel()
    List rooms = [
      [roomId: 'living', tempC: 25.0, active: true, coolingRate: 0.1, ventIds: ['v-living']],
      [roomId: 'office', tempC: 30.0, active: false, coolingRate: 0.1, ventIds: ['v-office']]
    ]
    Map diag = script.gatherDabV2RoomDiagnostics(zoneResult(), rooms, model, 22.0)

    when:
    Map sum = script.gatherDabV2SystemSummary(zoneResult(), diag, [:], null)

    then: 'living (+3) drives the max error, not the inactive office (+8)'
    (sum.maxErrorC as BigDecimal) == 3.0
  }

  // ---------------------------------------------------------------------------
  // publish: one device per room + one summary, each carrying only its own data
  // ---------------------------------------------------------------------------

  def "publishDabV2Diagnostics creates one device per room plus a summary, each with only its own values"() {
    setup:
    def script = buildScript()
    def model = learnedModel()

    when:
    script.publishDabV2Diagnostics(zoneResult(), roomData(), model, 22.0)

    then: 'a per-room device exists for each managed room plus exactly one summary device'
    devices.containsKey(ROOM_PREFIX + 'living')
    devices.containsKey(ROOM_PREFIX + 'office')
    devices.containsKey(SUMMARY_DNI)
    devices.size() == 3

    and: "the living room device only ever received living's own values"
    eventNames(ROOM_PREFIX + 'living').contains('temperature')
    eventNames(ROOM_PREFIX + 'living').contains('signedErrorC')
    eventNames(ROOM_PREFIX + 'living').contains('airflowLimited')
    (eventValue(ROOM_PREFIX + 'living', 'proposedOpenPct') as BigDecimal) == 80.0

    and: 'the office device received a DIFFERENT (its own) proposed open %'
    (eventValue(ROOM_PREFIX + 'office', 'proposedOpenPct') as BigDecimal) == 0.0

    and: 'the summary device carries spread/status but no per-room temperature'
    eventNames(SUMMARY_DNI).contains('spreadC')
    eventNames(SUMMARY_DNI).contains('status')
    !eventNames(SUMMARY_DNI).contains('temperature')
  }

  // ---------------------------------------------------------------------------
  // Topology change: per-room devices created/removed as rooms come and go (R14.6)
  // ---------------------------------------------------------------------------

  def "stale per-room devices are removed when a room leaves the topology"() {
    setup:
    def script = buildScript()
    def model = learnedModel()
    // The AUTHORITATIVE topology (discovered rooms across all zones): pruning
    // keys off this, never off a single publish's room set.
    script.atomicState.ventsByRoomId = [living: ['v-living'], office: ['v-office']]
    // First publish over two rooms creates both per-room devices.
    script.publishDabV2Diagnostics(zoneResult(), roomData(), model, 22.0)
    assert devices.containsKey(ROOM_PREFIX + 'office')

    when: 'the office room ACTUALLY leaves the topology and we publish again'
    script.atomicState.ventsByRoomId = [living: ['v-living']]
    Map shrunk = zoneResult()
    shrunk.targets = ['living': 80.0d]
    List oneRoom = [[roomId: 'living', tempC: 25.0, active: true, coolingRate: 0.1, ventIds: ['v-living']]]
    script.publishDabV2Diagnostics(shrunk, oneRoom, model, 22.0)

    then: "the departed room's device is deleted; the surviving room's remains"
    !devices.containsKey(ROOM_PREFIX + 'office')
    devices.containsKey(ROOM_PREFIX + 'living')
    devices.containsKey(SUMMARY_DNI)
  }

  def "a room missing from ONE publish is NOT pruned while it remains in the topology"() {
    setup: 'both rooms exist in the authoritative topology'
    def script = buildScript()
    def model = learnedModel()
    script.atomicState.ventsByRoomId = [living: ['v-living'], office: ['v-office']]
    script.publishDabV2Diagnostics(zoneResult(), roomData(), model, 22.0)
    assert devices.containsKey(ROOM_PREFIX + 'office')

    when: 'office has a transiently unreadable temperature (or belongs to another zone) and is absent from this publish'
    Map shrunk = zoneResult()
    shrunk.targets = ['living': 80.0d]
    List oneRoom = [[roomId: 'living', tempC: 25.0, active: true, coolingRate: 0.1, ventIds: ['v-living']]]
    script.publishDabV2Diagnostics(shrunk, oneRoom, model, 22.0)

    then: "office's device survives; only a real topology change prunes it"
    devices.containsKey(ROOM_PREFIX + 'office')
    devices.containsKey(ROOM_PREFIX + 'living')
  }

  // ---------------------------------------------------------------------------
  // Opt-in: absence never affects control (R14.6)
  // ---------------------------------------------------------------------------

  def "when diagnostics are disabled nothing is created or emitted"() {
    setup:
    def script = buildScript([dabV2DiagnosticsEnabled: false])
    def model = learnedModel()

    when:
    script.publishDabV2Diagnostics(zoneResult(), roomData(), model, 22.0)

    then: 'no diagnostic devices are created and no events are emitted'
    devices.isEmpty()
    events.isEmpty()
  }

  // ---------------------------------------------------------------------------
  // Error notification coalescing (R14.5)
  // ---------------------------------------------------------------------------

  def "repeated error notifications are coalesced within the window then re-emitted after it"() {
    setup:
    def script = buildScript()
    Long t0 = 1_000_000L

    expect: 'first occurrence emits'
    script.dabV2ShouldNotifyError('flair-timeout', t0) == true

    and: 'an identical error a few seconds later is coalesced (suppressed)'
    script.dabV2ShouldNotifyError('flair-timeout', t0 + 5_000L) == false

    and: 'a DIFFERENT error key still emits'
    script.dabV2ShouldNotifyError('flair-auth', t0 + 5_000L) == true

    and: 'the same error after the coalesce window emits again'
    script.dabV2ShouldNotifyError('flair-timeout', t0 + 3_600_000L) == true
  }

  // ---------------------------------------------------------------------------
  // 24 h status counters (R14.1) — increment + rolling-window reset
  // ---------------------------------------------------------------------------

  def "status counters increment per observation and reset after the 24 h window"() {
    setup:
    def script = buildScript()
    Long t0 = 5_000_000L

    when: 'two recalculations and one hold are observed inside the window'
    script.dabV2RecordObservation('recalculating', t0)
    script.dabV2RecordObservation('recalculating', t0 + 60_000L)
    script.dabV2RecordObservation('hold', t0 + 120_000L)
    Map c = script.dabV2Counters()

    then:
    (c.recalc24h as int) == 2
    (c.hold24h as int) == 1

    when: 'an observation arrives after the 24 h window elapses'
    script.dabV2RecordObservation('recalculating', t0 + 25L * 3_600_000L)
    Map rolled = script.dabV2Counters()

    then: 'the window rolls over and counts restart from this observation'
    (rolled.recalc24h as int) == 1
    (rolled.hold24h as int) == 0
  }

  // ---------------------------------------------------------------------------
  // Status-page surface (R13/R14 surface 1): values mirrored into `state`
  // ---------------------------------------------------------------------------

  def "publish mirrors the surfaces into state and the status page renders them"() {
    setup:
    def script = buildScript()
    def model = learnedModel()

    expect: 'before any evaluation the status line is a friendly placeholder'
    script.renderDabV2DiagnosticsStatus().contains('no evaluation published yet')

    when:
    script.publishDabV2Diagnostics(zoneResult(), roomData(), model, 22.0)

    then: 'the latest summary + per-room surfaces are mirrored into state'
    stateMap.dabV2Diagnostics instanceof Map
    stateMap.dabV2Diagnostics.summary instanceof Map
    (stateMap.dabV2Diagnostics.rooms as Map).keySet() == ['living', 'office'] as Set

    and: 'the rendered status line reflects the published values'
    String rendered = script.renderDabV2DiagnosticsStatus()
    rendered.contains('spread')
    rendered.contains('strategy')
    !rendered.contains('no evaluation published yet')
  }

  // ---------------------------------------------------------------------------
  // Credentials are never logged during publish (R14.4)
  // ---------------------------------------------------------------------------

  def "publishing diagnostics never logs Flair credentials"() {
    setup:
    def script = buildScript()
    def model = learnedModel()

    when:
    script.publishDabV2Diagnostics(zoneResult(), roomData(), model, 22.0)

    then:
    log.records.every { rec ->
      String msg = rec[1]?.toString() ?: ''
      !msg.contains(TOKEN) && !msg.contains('Bearer') && !msg.toLowerCase().contains('authorization')
    }
  }
}
