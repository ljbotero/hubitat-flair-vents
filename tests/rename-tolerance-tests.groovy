import me.biocomp.hubitat_ci.util.CapturingLog
import me.biocomp.hubitat_ci.api.app_api.AppExecutor
import me.biocomp.hubitat_ci.api.common_api.ChildDeviceWrapper
import me.biocomp.hubitat_ci.app.HubitatAppSandbox
import me.biocomp.hubitat_ci.validation.Flags
import spock.lang.Specification

/**
 * R7.5 — Rename tolerance / stable network-ID identity (task 5.1).
 *
 * Validates: Requirements 7.29, 7.30, 7.31, 7.32, 7.33.
 * Design: §R7.5; reuses the Correctness Property 21 onboarding-idempotency
 * harness conventions (tests/onboarding-idempotency-property-tests.groovy):
 * a sandboxed app whose child-device registry is keyed on network ID, an
 * `addChildDevice` spy that counts creations per network ID, and the same
 * map-coerced 200-OK response proxy that `isValidResponse` accepts.
 *
 * Requirement mapping (requirements.md R7.5, items 29-33):
 *   7.29 — identity/control is the stable network ID, not the label/name.
 *   7.30 — a Hubitat-label rename keeps control working with no re-discovery.
 *   7.31 — a Flair-name change keeps control working with no re-discovery.
 *   7.32 — a blank/missing display name derives a deterministic ID-based label.
 *   7.33 — a label/name change never creates a duplicate child device.
 *
 * The production anchors are `handleDeviceList` (the structures/{id}/pucks +
 * vents discovery path), `makeRealDevice` (network-ID keying + idempotent
 * `addChildDevice`), and `getChildDevice(deviceId)` (the control-resolution
 * seam used by patchVent / setRoomActive / DAB dispatch).
 *
 * STRICT TDD NOTE (task 5.1): much of R7.5 already holds from the task 2.2
 * network-ID identity work (`handleDeviceList` blank-name fallback +
 * `makeRealDevice` idempotency). These specs therefore act primarily as
 * REGRESSION GUARDS encoding the already-correct behavior; task 5.2 confirms
 * them. Any assertion that is genuinely red is reported back to the orchestrator.
 */
class RenameToleranceSpec extends Specification {

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

  // ---------------------------------------------------------------------------
  // Property (PropertyGen-driven, reuses the Property 21 idempotency invariant
  // under rename): for any recognized device and any sequence of Hubitat-label
  // and Flair-name changes, control resolves to the SAME network-ID-keyed child
  // and discovery never creates a duplicate.
  // ---------------------------------------------------------------------------
  def 'R7.5 rename tolerance: control resolves to the same network-ID device across any label/name change with no duplicate'() {
    given: 'a randomized recognized device and two arbitrary Flair names (either may be blank)'
    def g = PropertyGen.forIteration(i)
    List blanks = [null, '', '   ', '\t']
    String id = "dev-${i}".toString()
    String type = g.nextBoolean() ? 'vents' : 'pucks'
    def flairName1 = g.nextBoolean() ? "Room ${i} A".toString() : g.pick(blanks)
    def flairName2 = g.nextBoolean() ? "Room ${i} B".toString() : g.pick(blanks)

    and: 'a sandboxed app whose child registry is keyed on network ID'
    Map children = [:]
    Map addCounts = [:].withDefault { 0 }
    def script = buildDiscoveryScript(children, addCounts)

    when: 'the device is discovered, the user renames it in Hubitat, the Flair name changes, and discovery re-runs'
    runDeviceList(script, [[id: id, type: type, name: flairName1]])
    def created = children[id]
    // Simulate a user renaming the Hubitat device label.
    created.setLabel("User Renamed ${i}".toString())
    runDeviceList(script, [[id: id, type: type, name: flairName2]])

    then: 'exactly one child exists, keyed on the stable network ID (7.29, 7.33)'
    children.size() == 1
    addCounts[id] == 1

    and: 'control resolves to the very same device object regardless of label/name churn (7.29, 7.30, 7.31)'
    children[id].is(created)
    script.getChildDevice(id).is(created)
    script.getChildDevice(id).getDeviceNetworkId() == id

    and: 'the user-supplied Hubitat label is never clobbered by a Flair-name change (7.30)'
    created.getLabel() == "User Renamed ${i}".toString()

    where:
    i << (0..<PropertyGen.ITERATIONS)
  }

  // ---------------------------------------------------------------------------
  // Example: 7.32 — a blank/missing display name derives a deterministic
  // ID-based label ("Vent-${id}" / "Puck-${id}") rather than dropping the device.
  // ---------------------------------------------------------------------------
  def 'R7.32 blank/missing Flair name derives a deterministic ID-based label'() {
    given:
    Map children = [:]
    Map addCounts = [:].withDefault { 0 }
    def script = buildDiscoveryScript(children, addCounts)

    when: 'a recognized device is discovered with a blank/missing name'
    runDeviceList(script, [[id: deviceId, type: type, name: blankName]])

    then: 'it is onboarded once with the ID-derived fallback label'
    children.size() == 1
    addCounts[deviceId] == 1
    children[deviceId].getLabel() == expectedLabel

    where:
    deviceId   | type    | blankName | expectedLabel
    'vent-001' | 'vents' | null      | 'Vent-vent-001'
    'vent-002' | 'vents' | ''        | 'Vent-vent-002'
    'vent-003' | 'vents' | '   '     | 'Vent-vent-003'
    'puck-001' | 'pucks' | null      | 'Puck-puck-001'
    'puck-002' | 'pucks' | '\t'      | 'Puck-puck-002'
  }

  // ---------------------------------------------------------------------------
  // Example: 7.30 — a Hubitat-label rename is tolerated; re-discovery neither
  // duplicates the device nor overwrites the user's chosen label.
  // ---------------------------------------------------------------------------
  def 'R7.30 a Hubitat-label rename is tolerated with no re-discovery and no duplicate'() {
    given:
    Map children = [:]
    Map addCounts = [:].withDefault { 0 }
    def script = buildDiscoveryScript(children, addCounts)

    when: 'a vent is discovered, the user renames the Hubitat label, then discovery re-runs'
    runDeviceList(script, [[id: 'vent-42', type: 'vents', name: 'Office Vent']])
    def original = children['vent-42']
    original.setLabel('Guest Bedroom')
    runDeviceList(script, [[id: 'vent-42', type: 'vents', name: 'Office Vent']])

    then: 'the same single child remains, resolvable by network ID, with the user label intact'
    children.size() == 1
    addCounts['vent-42'] == 1
    script.getChildDevice('vent-42').is(original)
    original.getLabel() == 'Guest Bedroom'
  }

  // ---------------------------------------------------------------------------
  // Example: 7.31 — a Flair-name change is tolerated; control still resolves and
  // no duplicate child is created.
  // ---------------------------------------------------------------------------
  def 'R7.31 a Flair-name change is tolerated with no re-discovery and no duplicate'() {
    given:
    Map children = [:]
    Map addCounts = [:].withDefault { 0 }
    def script = buildDiscoveryScript(children, addCounts)

    when: 'a puck is discovered, then re-discovered after its Flair name changes'
    runDeviceList(script, [[id: 'puck-7', type: 'pucks', name: 'Kitchen']])
    def original = children['puck-7']
    runDeviceList(script, [[id: 'puck-7', type: 'pucks', name: 'Pantry']])

    then: 'control resolves to the same network-ID-keyed device and nothing is duplicated'
    children.size() == 1
    addCounts['puck-7'] == 1
    script.getChildDevice('puck-7').is(original)
  }

  // ---------------------------------------------------------------------------
  // Example: 7.29 / 7.33 — identity is the network ID, not the label/name: two
  // distinct IDs sharing the SAME Flair name yield two distinct children, and
  // repeated discovery with churned names never duplicates either.
  // ---------------------------------------------------------------------------
  def 'R7.29/R7.33 identity is the network ID: identical names on distinct IDs do not collapse or duplicate'() {
    given:
    Map children = [:]
    Map addCounts = [:].withDefault { 0 }
    def script = buildDiscoveryScript(children, addCounts)

    when: 'two devices share one Flair name but differ by network ID, then discovery re-runs with churned names'
    runDeviceList(script, [
      [id: 'vent-A', type: 'vents', name: 'Living Room'],
      [id: 'vent-B', type: 'vents', name: 'Living Room']
    ])
    runDeviceList(script, [
      [id: 'vent-A', type: 'vents', name: 'Living Room Renamed'],
      [id: 'vent-B', type: 'vents', name: '']
    ])

    then: 'each network ID is its own child and neither is duplicated'
    children.keySet() == (['vent-A', 'vent-B'] as Set)
    addCounts['vent-A'] == 1
    addCounts['vent-B'] == 1
  }

  // ---------------------------------------------------------------------------
  // Helpers (mirror the Property 21 onboarding-idempotency harness conventions).
  // ---------------------------------------------------------------------------

  /** Drive the structures/{id}/pucks + vents discovery path with a logical device set. */
  private void runDeviceList(script, List devices) {
    def listData = devices.collect {
      [id: it.id, type: it.type, attributes: [name: it.name]]
    }
    script.handleDeviceList(okResponse([data: listData]), [deviceType: 'vents'])
  }

  private Object buildDiscoveryScript(Map children, Map addCounts) {
    final log = new CapturingLog()
    AppExecutor executorApi = Mock(AppExecutor) {
      _ * getState() >> [:]
      _ * getAtomicState() >> [activeRequests: 50]
      _ * getLog() >> log
      _ * getSetting('debugLevel') >> 1
      _ * getChildDevice(_) >> { String id -> children[id] }
      _ * addChildDevice(*_) >> { args ->
        String dni = args[2]?.toString()
        String lbl = (args[3] instanceof Map) ? args[3]?.label?.toString() : null
        addCounts[dni] = (addCounts[dni] ?: 0) + 1
        // Backing map gives the coerced wrapper a mutable label so a Hubitat
        // rename (setLabel) can be simulated; identity stays the network ID.
        Map backing = [networkId: dni, label: lbl]
        def child = [
          getDeviceNetworkId: { -> backing.networkId },
          getId             : { -> backing.networkId },
          getLabel          : { -> backing.label },
          setLabel          : { String l -> backing.label = l },
        ] as ChildDeviceWrapper
        children[dni] = child
        return child
      }
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS,
      'userSettingValues': ['debugLevel': 1])
    script.atomicState = [activeRequests: 50]
    // Isolate discovery from vent-trait processing collaborators.
    script.metaClass.processVentTraits = { device, details -> }
    return script
  }

  // Map-coerced HTTP response proxy. isValidResponse() short-circuits on
  // hasProperty('hasError') == false, so this is treated as a valid response.
  private static Map okResponse(Map json) {
    return [
      hasProperty: { String p -> false },
      getStatus  : { -> 200 },
      getJson    : { -> json },
    ]
  }
}
