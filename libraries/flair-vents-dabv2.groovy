library(
  name: 'FlairVentsDabv2',
  namespace: 'bot.flair',
  author: 'Jaime Botero',
  description: 'Pure DAB v2 algorithm modules (Context, Learning, Safety Floor, Allocator, Model I/O) for the Flair Vents app.',
  category: 'Integrations'
)

// Flair Vents - DAB v2 pure algorithm library.
//
// Consolidates the pure DAB v2 modules (Context_Mapper, Learning_Model,
// Safety_Floor, Allocator, Model I/O) into a single Hubitat Library so the app
// can pull them with one `#include bot.flair.FlairVentsDabv2` on-device and
// ship them inside a single Hubitat Bundle. PURE: no Hubitat platform APIs, no
// wall-clock time, no randomness, no state/atomicState - the identical source
// compiles under the off-device Spock harness and inside the app sandbox.
//
// No `import` statements and no `package` line: the Hubitat library parser
// wants the `library()` call first (imports before it trigger "Cannot parse
// library definition"), and an `#include`d library cannot carry a `package`
// line. Java/Groovy types are therefore fully qualified inline
// (@groovy.transform.CompileStatic, groovy.json.JsonOutput/JsonSlurper).
//
// Copyright 2024 Jaime Botero. Licensed under the Apache License, Version 2.0.


// ############################################################################
// UNROLLED on-device API (methods-only). Hubitat's sandbox rejects user classes
// (even via #include), so the app calls these top-level methods, pulled in by
// `#include bot.flair.FlairVentsDabv2`. Value objects are plain Maps. The
// @CompileStatic classes further below are retained ONLY during migration (for
// the off-device specs that still construct them) and are deleted once every
// caller and test uses this methods API. Names are module-prefixed (ctx*/lrn*/
// sf*/alloc*/mio*) because there is no class namespace at the top level.
// ############################################################################

// ---- Context_Mapper (unrolled mirror of class Dabv2Context) ----
@groovy.transform.Field static final double CTX_COLD_C = 10.0d
@groovy.transform.Field static final double CTX_HOT_C = 25.0d
@groovy.transform.Field static final int CTX_DAY_START = 7
@groovy.transform.Field static final int CTX_DAY_END = 21
@groovy.transform.Field static final double CTX_OCC_FACTOR = 0.9d
@groovy.transform.Field static final double CTX_DOOR_FACTOR = 0.9d
@groovy.transform.Field static final double CTX_FACTOR_MIN = 0.5d
@groovy.transform.Field static final double CTX_FACTOR_MAX = 1.5d

// Classify an outdoor temperature into a coarse band: 0 cold (< CTX_COLD_C),
// 2 hot (> CTX_HOT_C), 1 mild otherwise; a missing reading degrades to mild.
int ctxOutdoorBand(Double outdoorTempC) {
  if (outdoorTempC == null) { return 1 }
  if (outdoorTempC < CTX_COLD_C) { return 0 }
  if (outdoorTempC > CTX_HOT_C) { return 2 }
  return 1
}

// Daytime decision: an explicit sun state wins; else the hour window [start,end).
boolean ctxIsDaytime(int hour, String sunState = null) {
  return sunState != null ? sunState == 'above_horizon' : (hour >= CTX_DAY_START && hour < CTX_DAY_END)
}

// Build the resolved context as a Map (the unrolled replacement for Context).
Map ctxBuild(int hour, Double outdoorTempC = null, Boolean occupied = null,
             Boolean doorsOpen = null, String sunState = null) {
  return [
    hour       : hour,
    isDaytime  : ctxIsDaytime(hour, sunState),
    outdoorBand: ctxOutdoorBand(outdoorTempC),
    occupied   : occupied,
    doorsOpen  : doorsOpen
  ]
}

// Map a context to one of 4 regimes: 0 day-mild, 1 day-hot, 2 night-mild,
// 3 night-hot (cold collapses with mild).
int ctxRegimeIndex(Map ctx) {
  int base = ctx.isDaytime ? 0 : 2
  return base + (ctx.outdoorBand == 2 ? 1 : 0)
}

// Apply bounded occupancy/door multipliers to a learned rate, clamped to
// [CTX_FACTOR_MIN, CTX_FACTOR_MAX]; null/false readings contribute neutral 1.0.
double ctxApplyMultipliers(double rate, Map ctx, String mode = 'cooling') {
  double factor = 1.0d
  if (ctx.occupied == Boolean.TRUE) { factor *= CTX_OCC_FACTOR }
  if (ctx.doorsOpen == Boolean.TRUE) { factor *= CTX_DOOR_FACTOR }
  factor = Math.max(CTX_FACTOR_MIN, Math.min(CTX_FACTOR_MAX, factor))
  return rate * factor
}

// ---- Shared helper ----
// Clamp value into [lo, hi]. Shared by every unrolled module.
double dabv2Clamp(double value, double lo, double hi) {
  return Math.max(lo, Math.min(hi, value))
}

// ---- Learning_Model (unrolled mirror of class Dabv2Learning) ----
// Value objects are Maps with the SAME keys as the old class fields:
//   VentCurve            -> [breakpoints:List<Integer>, flows:List<Double>,
//                            counts:List<Integer>, seed:List<Double>]
//   Effectiveness        -> [eRoom:double, leak:double]
//   RegimeCell           -> [rate:double, n:int]
//   ModeEfficiency       -> [baseline:Double|null, n:int, regimes:List<Map>]
//   RoomEfficiencyModel  -> [cooling:Map, heating:Map]
@groovy.transform.Field static final double LRN_LEAK_MAX = 0.35d
@groovy.transform.Field static final double LRN_LEAK_DEFAULT = 0.1d
@groovy.transform.Field static final int LRN_MODEL_MIN_N = 8
@groovy.transform.Field static final List<Integer> LRN_CURVE_BREAKPOINTS =
    Collections.unmodifiableList([0, 5, 10, 20, 35, 50, 75, 100])
@groovy.transform.Field static final double LRN_CURVE_ALPHA0 = 0.5d
@groovy.transform.Field static final double LRN_CURVE_ALPHA_MIN = 0.05d
@groovy.transform.Field static final double LRN_KNEE_EPS = 0.02d
@groovy.transform.Field static final int LRN_EFF_REGIME_COUNT = 4
@groovy.transform.Field static final int LRN_REGIME_MIN_N = 3
@groovy.transform.Field static final double LRN_RATE_MIN = 0.001d
@groovy.transform.Field static final double LRN_RATE_MAX = 1.5d
@groovy.transform.Field static final double LRN_RATE_ALPHA0 = 0.10d
@groovy.transform.Field static final double LRN_RATE_ALPHA_MIN = 0.01d

double lrnFlowLinear(double leak, double apertureFrac) {
  double leakC = dabv2Clamp(leak, 0.0d, 1.0d)
  double a = dabv2Clamp(apertureFrac, 0.0d, 1.0d)
  return leakC + ((1.0d - leakC) * a)
}

double lrnInterpCurve(List<Integer> breakpoints, List<Double> flows, double aperturePct) {
  if (flows.isEmpty()) { return 0.0d }
  double loBp = breakpoints.get(0) as double
  double hiBp = breakpoints.get(breakpoints.size() - 1) as double
  double a = dabv2Clamp(aperturePct, loBp, hiBp)
  if (a <= loBp) { return dabv2Clamp(flows.get(0), 0.0d, 1.0d) }
  if (a >= hiBp) { return dabv2Clamp(flows.get(flows.size() - 1), 0.0d, 1.0d) }
  for (int k = 1; k < breakpoints.size(); k++) {
    double lo = breakpoints.get(k - 1) as double
    double hi = breakpoints.get(k) as double
    if (a <= hi) {
      double span = hi - lo
      double frac = span <= 0 ? 0.0d : (a - lo) / span
      double value = flows.get(k - 1) + (frac * (flows.get(k) - flows.get(k - 1)))
      return dabv2Clamp(value, 0.0d, 1.0d)
    }
  }
  return dabv2Clamp(flows.get(flows.size() - 1), 0.0d, 1.0d)
}

List<Double> lrnIsotonic(List<Double> values, List<Double> weights) {
  List<double[]> blocks = new ArrayList<double[]>()
  for (int k = 0; k < values.size(); k++) {
    double curV = values.get(k)
    double curW = weights.get(k)
    double curN = 1.0d
    while (!blocks.isEmpty() && blocks.get(blocks.size() - 1)[0] > curV) {
      double[] prev = blocks.remove(blocks.size() - 1)
      double pv = prev[0]
      double pw = prev[1]
      double pn = prev[2]
      double totalW = pw + curW
      curV = totalW > 0 ? ((pv * pw) + (curV * curW)) / totalW : curV
      curW = totalW
      curN = pn + curN
    }
    blocks.add([curV, curW, curN] as double[])
  }
  List<Double> out = new ArrayList<Double>(values.size())
  for (double[] block : blocks) {
    int span = (int) block[2]
    for (int s = 0; s < span; s++) { out.add(block[0]) }
  }
  return out
}

int lrnCurveKnee(List<Integer> breakpoints, List<Double> flows) {
  if (flows.isEmpty()) { return 100 }
  double last = flows.get(flows.size() - 1)
  double full = last != 0.0d ? last : 1.0d
  double target = (1.0d - LRN_KNEE_EPS) * full
  int n = Math.min(breakpoints.size(), flows.size())
  for (int k = 0; k < n; k++) {
    if (flows.get(k) >= target) { return breakpoints.get(k).intValue() }
  }
  return breakpoints.get(breakpoints.size() - 1).intValue()
}

double lrnCurveInverse(List<Integer> breakpoints, List<Double> flows,
    double flowFraction, int kneePct) {
  if (flows.isEmpty()) { return 0.0d }
  double f = dabv2Clamp(flowFraction, 0.0d, 1.0d)
  double kneeFlow = lrnInterpCurve(breakpoints, flows, (double) kneePct)
  if (f >= kneeFlow) { return (double) kneePct }
  if (f <= flows.get(0)) { return (double) breakpoints.get(0).intValue() }
  for (int k = 1; k < breakpoints.size(); k++) {
    double loF = flows.get(k - 1)
    double hiF = flows.get(k)
    if (f <= hiF) {
      double span = hiF - loF
      if (span <= 0) { return (double) breakpoints.get(k - 1).intValue() }
      double frac = (f - loF) / span
      double loBp = (double) breakpoints.get(k - 1).intValue()
      double hiBp = (double) breakpoints.get(k).intValue()
      return loBp + (frac * (hiBp - loBp))
    }
  }
  return (double) kneePct
}

// VentCurve as a Map: [breakpoints, flows, counts, seed].
Map lrnSeedLinear(double leak) {
  double leakC = dabv2Clamp(leak, 0.0d, LRN_LEAK_MAX)
  List<Integer> bps = new ArrayList<Integer>(LRN_CURVE_BREAKPOINTS)
  List<Double> flows = new ArrayList<Double>(bps.size())
  for (int bp : bps) { flows.add(lrnFlowLinear(leakC, (bp as double) / 100.0d)) }
  flows.set(flows.size() - 1, 1.0d)
  List<Integer> counts = new ArrayList<Integer>(bps.size())
  for (int k = 0; k < bps.size(); k++) { counts.add(0) }
  return [breakpoints: bps, flows: flows, counts: counts, seed: new ArrayList<Double>(flows)]
}

Map lrnVentCurveToMap(Map curve) {
  return [
    breakpoints: new ArrayList<Integer>((List) curve.breakpoints),
    flow: new ArrayList<Double>((List) curve.flows),
    counts: new ArrayList<Integer>((List) curve.counts)
  ]
}

Map lrnVentCurveFromMap(Object data) {
  if (!(data instanceof Map)) { return lrnSeedLinear(LRN_LEAK_DEFAULT) }
  Map map = (Map) data
  List rawBps = (map.get('breakpoints') ?: LRN_CURVE_BREAKPOINTS) as List
  List<Integer> bps = new ArrayList<Integer>(rawBps.size())
  for (Object b : rawBps) { bps.add(((Number) b).intValue()) }
  List rawFlows = (map.get('flow') ?: []) as List
  List<Double> flows = new ArrayList<Double>(rawFlows.size())
  for (Object f : rawFlows) { flows.add(((Number) f).doubleValue()) }
  if (flows.size() != bps.size()) {
    double leak = flows.isEmpty() ? LRN_LEAK_DEFAULT : flows.get(0)
    return lrnSeedLinear(leak)
  }
  List rawCounts = (map.get('counts') ?: []) as List
  List<Integer> counts = new ArrayList<Integer>(bps.size())
  for (Object c : rawCounts) { counts.add(c instanceof Number ? ((Number) c).intValue() : 0) }
  while (counts.size() < bps.size()) { counts.add(0) }
  if (counts.size() > bps.size()) { counts = counts.subList(0, bps.size()) }
  return [breakpoints: bps, flows: flows, counts: counts, seed: new ArrayList<Double>(flows)]
}

int lrnVentCurveTotalSamples(Map curve) {
  int sum = 0
  for (int c : (List<Integer>) curve.counts) { sum += c }
  return sum
}

List<Double> lrnVentCurveEffectiveFlows(Map curve) {
  return lrnVentCurveTotalSamples(curve) < LRN_MODEL_MIN_N ?
      (List<Double>) curve.seed : (List<Double>) curve.flows
}

double lrnVentCurveFlow(Map curve, double aperturePct) {
  return lrnInterpCurve((List<Integer>) curve.breakpoints, lrnVentCurveEffectiveFlows(curve), aperturePct)
}

int lrnVentCurveKnee(Map curve) {
  return lrnCurveKnee((List<Integer>) curve.breakpoints, lrnVentCurveEffectiveFlows(curve))
}

double lrnVentCurveInverse(Map curve, double flowFraction) {
  List<Double> ef = lrnVentCurveEffectiveFlows(curve)
  int kneePct = lrnCurveKnee((List<Integer>) curve.breakpoints, ef)
  return lrnCurveInverse((List<Integer>) curve.breakpoints, ef, flowFraction, kneePct)
}

private int lrnNearestIndex(Map curve, double aperturePct) {
  List<Integer> breakpoints = (List<Integer>) curve.breakpoints
  double a = dabv2Clamp(aperturePct, 0.0d, 100.0d)
  int bestI = 0
  double bestD = Math.abs(a - (breakpoints.get(0) as double))
  for (int k = 0; k < breakpoints.size(); k++) {
    double d = Math.abs(a - (breakpoints.get(k) as double))
    if (d < bestD) { bestD = d; bestI = k }
  }
  return bestI
}

private void lrnVentCurveNormalize(Map curve) {
  List<Double> flows = (List<Double>) curve.flows
  double last = flows.get(flows.size() - 1)
  if (last > 0) {
    List<Double> scaled = new ArrayList<Double>(flows.size())
    for (double f : flows) { scaled.add(f / last) }
    flows = scaled
    curve.flows = flows
  }
  flows.set(0, dabv2Clamp(flows.get(0), 0.0d, LRN_LEAK_MAX))
}

Map lrnVentCurveUpdate(Map curve, double aperturePct, double observedFlow) {
  if (!Double.isFinite(observedFlow)) { return curve }
  List<Double> flows = (List<Double>) curve.flows
  List<Integer> counts = (List<Integer>) curve.counts
  double sample = dabv2Clamp(observedFlow, 0.0d, 1.0d)
  int idx = lrnNearestIndex(curve, aperturePct)
  int count = counts.get(idx)
  if (count <= 0) {
    flows.set(idx, sample)
  } else {
    double alpha = Math.max(LRN_CURVE_ALPHA_MIN, LRN_CURVE_ALPHA0 / Math.sqrt((double) (count + 1)))
    flows.set(idx, flows.get(idx) + (alpha * (sample - flows.get(idx))))
  }
  counts.set(idx, count + 1)
  List<Double> weights = new ArrayList<Double>(counts.size())
  for (int c : counts) { weights.add((double) c + 1.0d) }
  curve.flows = lrnIsotonic(flows, weights)
  lrnVentCurveNormalize(curve)
  return curve
}

// Effectiveness as [eRoom, leak].
Map lrnDeriveEffectiveness(double slope, double intercept, int n) {
  double denom = (slope * 100.0d) + intercept
  double eRoom = Math.max(0.0d, denom)
  boolean untrusted = n < LRN_MODEL_MIN_N || denom <= 0.0d
  double leak = untrusted ? LRN_LEAK_DEFAULT : dabv2Clamp(intercept / denom, 0.0d, LRN_LEAK_MAX)
  return [eRoom: eRoom, leak: leak]
}

double lrnPredictedRate(double eRoom, double leak, double aperturePct) {
  return Math.max(0.0d, eRoom) * lrnFlowLinear(leak, aperturePct / 100.0d)
}

double lrnGroupCombinedFlow(List<Double> leaks, List<Double> apertureFracs) {
  if (leaks.size() != apertureFracs.size()) {
    throw new IllegalArgumentException('leaks and apertureFracs must have the same length')
  }
  int n = leaks.size()
  if (n == 0) { return 0.0d }
  double total = 0.0d
  for (int k = 0; k < n; k++) {
    total += lrnFlowLinear(leaks.get(k).doubleValue(), apertureFracs.get(k).doubleValue())
  }
  return total / n
}

double lrnGroupPredictedRate(double eRoom, List<Double> leaks, List<Double> aperturePcts) {
  List<Double> fracs = new ArrayList<Double>(aperturePcts.size())
  for (Double pct : aperturePcts) { fracs.add(pct.doubleValue() / 100.0d) }
  return Math.max(0.0d, eRoom) * lrnGroupCombinedFlow(leaks, fracs)
}

// ModeEfficiency / RoomEfficiencyModel factories (Maps).
Map lrnNewMode() {
  List<Map> cells = new ArrayList<Map>(LRN_EFF_REGIME_COUNT)
  for (int k = 0; k < LRN_EFF_REGIME_COUNT; k++) { cells.add([rate: 0.0d, n: 0]) }
  return [baseline: (Double) null, n: 0, regimes: cells]
}

Map lrnNewRoomModel() {
  return [cooling: lrnNewMode(), heating: lrnNewMode()]
}

Map lrnModeOf(Map model, String mode) {
  return 'heating'.equals(mode) ? (Map) model.heating : (Map) model.cooling
}

double lrnEmaStep(Double value, double sample, int n) {
  if (value == null) { return sample }
  double alpha = Math.max(LRN_RATE_ALPHA_MIN, LRN_RATE_ALPHA0 / Math.sqrt((double) n))
  return value.doubleValue() + (alpha * (sample - value.doubleValue()))
}

Map lrnUpdateRoomEfficiency(Map model, Double sample, int regimeIdx, String mode = 'cooling') {
  if (sample == null || !Double.isFinite(sample.doubleValue())) { return model }
  double s = Math.max(0.0d, sample.doubleValue())
  int idx = (int) dabv2Clamp((double) regimeIdx, 0.0d, (double) (LRN_EFF_REGIME_COUNT - 1))
  Map sub = lrnModeOf(model, mode)
  sub.n = ((int) sub.n) + 1
  sub.baseline = (Double) lrnEmaStep((Double) sub.baseline, s, (int) sub.n)
  Map cell = (Map) ((List) sub.regimes).get(idx)
  cell.n = ((int) cell.n) + 1
  Double prior = ((int) cell.n) > 1 ? (Double) cell.rate : (Double) null
  cell.rate = lrnEmaStep(prior, s, (int) cell.n)
  return model
}

double lrnEffectiveRate(Map model, int regimeIdx, String mode = 'cooling') {
  int idx = (int) dabv2Clamp((double) regimeIdx, 0.0d, (double) (LRN_EFF_REGIME_COUNT - 1))
  Map sub = lrnModeOf(model, mode)
  Map cell = (Map) ((List) sub.regimes).get(idx)
  if (((int) cell.n) >= LRN_REGIME_MIN_N && ((double) cell.rate) > 0.0d) {
    return dabv2Clamp((double) cell.rate, LRN_RATE_MIN, LRN_RATE_MAX)
  }
  double base = sub.baseline != null ? ((Double) sub.baseline).doubleValue() : 0.0d
  return dabv2Clamp(base, LRN_RATE_MIN, LRN_RATE_MAX)
}

// ---- AllocSettings factory (shared by Safety_Floor + Allocator) ----
// AllocSettings is now a Map with the same keys as the old class fields. Build
// one with dabv2NewAllocSettings([overrides]); unspecified keys take defaults.
Map dabv2NewAllocSettings(Map overrides = [:]) {
  Map base = [
    safetyFloorPct            : 40.0d,
    conventionalVents         : 0,
    conventionalOpenPct       : 100.0d,
    inactiveOpenPctSum        : 0.0d,
    inactiveCount             : 0,
    granularity               : 5,
    crosscoupling             : true,
    hysteresisC               : 0.3d,
    airflowLimitedMarginPct   : 5.0d,
    airflowLimitedErrorC      : 0.5d,
    horizonMin                : 30.0d,
    spreadGuardrailC          : 1.0d,
    spreadImprovementDeadbandC: 0.3d
  ]
  if (overrides) { base.putAll(overrides) }
  return base
}

// ---- Safety_Floor (unrolled mirror of class Dabv2SafetyFloor) ----
// RoomAllocInput and AllocSettings are Maps (same keys as the old fields). The
// FloorLogger interface is replaced by an optional Groovy Closure logger. apply
// returns a 2-element List [resultMap, floorBinding] (was Tuple2).
@groovy.transform.Field static final double SF_FLOOR_MIN_PCT = 20.0d
@groovy.transform.Field static final double SF_FLOOR_MAX_PCT = 90.0d
@groovy.transform.Field static final double SF_FLOOR_DEFAULT_PCT = 40.0d
@groovy.transform.Field static final double SF_FLOOR_EPS = 1e-9d
@groovy.transform.Field static final int SF_MAX_FLOOR_ITERATIONS = 10000

double sfClampSafetyFloor(Object value) {
  if (!(value instanceof Number)) { return SF_FLOOR_DEFAULT_PCT }
  double floor = ((Number) value).doubleValue()
  if (Double.isNaN(floor) || Double.isInfinite(floor)) { return SF_FLOOR_DEFAULT_PCT }
  if (floor < SF_FLOOR_MIN_PCT || floor > SF_FLOOR_MAX_PCT) { return SF_FLOOR_DEFAULT_PCT }
  return floor
}

double sfCombinedOpenPct(Map perVentTargets, Map s) {
  int nSmart = perVentTargets == null ? 0 : perVentTargets.size()
  int conventionalVents = Math.max(0, (int) s.conventionalVents)
  double inactiveOpenSum = (double) s.inactiveOpenPctSum
  int inactiveDevices = inactiveOpenSum > 0.0d ? Math.max(0, (int) s.inactiveCount) : 0
  int deviceCount = nSmart + conventionalVents + inactiveDevices
  if (deviceCount <= 0) { return 0.0d }
  double sumTargets = 0.0d
  if (perVentTargets != null) {
    for (Object v : perVentTargets.values()) {
      sumTargets += (v == null ? 0.0d : ((Number) v).doubleValue())
    }
  }
  double numerator = sumTargets +
    (conventionalVents * (double) s.conventionalOpenPct) + inactiveOpenSum
  return numerator / deviceCount
}

List sfApply(Map targets, List rooms, Map s, Closure logger = null) {
  double floor = sfClampSafetyFloor(s.safetyFloorPct)
  Map<String, Double> result = new LinkedHashMap<String, Double>()
  if (targets != null) {
    for (Map.Entry e : targets.entrySet()) {
      result.put((String) e.key, e.value == null ? 0.0d : ((Number) e.value).doubleValue())
    }
  }
  Map roomById = sfIndexRooms(rooms)
  int step = Math.max(1, (int) s.granularity)
  boolean floorBinding = false

  int iterations = 0
  while (sfCombinedForRooms(result, roomById, s) < floor - SF_FLOOR_EPS &&
         iterations < SF_MAX_FLOOR_ITERATIONS) {
    iterations++
    String candidate = sfPickEligibleActive(result, roomById)
    if (candidate == null) { break }
    double raised = Math.min(100.0d, result.get(candidate).doubleValue() + step)
    result.put(candidate, raised)
    floorBinding = true
  }

  List inactiveRooms = new ArrayList()
  if (rooms != null) {
    for (Object r : rooms) {
      if (r != null && !((boolean) ((Map) r).active) && ((Map) r).roomId != null) {
        inactiveRooms.add(r)
      }
    }
  }
  int conventionalVents = Math.max(0, (int) s.conventionalVents)
  int inactiveCount = Math.max(0, (int) s.inactiveCount)
  int totalDevices = sfExpandAllByVentCount(result, roomById).size() + conventionalVents + inactiveCount

  if (inactiveCount > 0 && !inactiveRooms.isEmpty() &&
      sfPickEligibleActive(result, roomById) == null &&
      sfTotalAirflowCombined(result, roomById, s, sfEmptyReopened(), totalDevices) < floor - SF_FLOOR_EPS) {
    if (logger != null) {
      double combined = sfTotalAirflowCombined(result, roomById, s, sfEmptyReopened(), totalDevices)
      logger.call('Safety floor ' + floor + '% unmet (combined ' + combined +
        '% on the total-airflow view) after exhausting active not-yet-satisfied + ' +
        'conventional capacity; reopening inactive vents as a last resort ' +
        '(floor precedence over inactive-room hold).')
    }
    Map<String, Double> reopened = new LinkedHashMap<String, Double>()
    int guard = 0
    for (Object roomObj : inactiveRooms) {
      Map room = (Map) roomObj
      if (sfTotalAirflowCombined(result, roomById, s, reopened, totalDevices) >= floor - SF_FLOOR_EPS) {
        break
      }
      double cur = reopened.get(room.roomId) == null ? 0.0d : reopened.get(room.roomId).doubleValue()
      while (cur < 100.0d &&
             sfTotalAirflowCombined(result, roomById, s, reopened, totalDevices) < floor - SF_FLOOR_EPS &&
             guard < SF_MAX_FLOOR_ITERATIONS) {
        guard++
        cur = Math.min(100.0d, cur + step)
        reopened.put((String) room.roomId, cur)
        floorBinding = true
      }
    }
    for (Map.Entry<String, Double> e : reopened.entrySet()) { result.put(e.key, e.value) }
  }

  return [result, Boolean.valueOf(floorBinding)]
}

Map sfEmptyReopened() { return new LinkedHashMap<String, Double>() }

Map sfIndexRooms(List rooms) {
  Map m = new LinkedHashMap()
  if (rooms != null) {
    for (Object r : rooms) {
      if (r != null && ((Map) r).roomId != null) { m.put(((Map) r).roomId, r) }
    }
  }
  return m
}

Map<String, Double> sfExpandPerVent(Map result, Map roomById) {
  Map<String, Double> perVent = new LinkedHashMap<String, Double>()
  for (Map.Entry e : result.entrySet()) {
    Map r = (Map) roomById.get(e.key)
    if (r == null) { continue }
    double pct = e.value == null ? 0.0d : ((Number) e.value).doubleValue()
    if (!((boolean) r.active) && pct <= 0.0d) { continue }
    List<String> vents = (List<String>) r.ventIds
    if (vents != null && !vents.isEmpty()) {
      for (String vid : vents) { perVent.put(vid, pct) }
    } else {
      perVent.put((String) e.key, pct)
    }
  }
  return perVent
}

double sfCombinedForRooms(Map result, Map roomById, Map s) {
  return sfCombinedOpenPct(sfExpandPerVent(result, roomById), s)
}

String sfPickEligibleActive(Map result, Map roomById) {
  String best = null
  double bestErr = 0.0d
  for (Map.Entry e : result.entrySet()) {
    String roomId = (String) e.key
    Map r = (Map) roomById.get(roomId)
    if (r == null || !((boolean) r.active) || ((double) r.signedErrorC) <= 0.0d) { continue }
    double pct = e.value == null ? 0.0d : ((Number) e.value).doubleValue()
    if (pct >= 100.0d - SF_FLOOR_EPS) { continue }
    double err = (double) r.signedErrorC
    if (best == null || err > bestErr || (err == bestErr && roomId.compareTo(best) > 0)) {
      best = roomId
      bestErr = err
    }
  }
  return best
}

int sfVentCountOf(String roomId, Map roomById) {
  Map r = (Map) roomById.get(roomId)
  if (r != null && r.ventIds != null && !((List) r.ventIds).isEmpty()) {
    return ((List) r.ventIds).size()
  }
  return 1
}

Map<String, Double> sfExpandAllByVentCount(Map commanded, Map roomById) {
  Map<String, Double> out = new LinkedHashMap<String, Double>()
  for (Map.Entry e : commanded.entrySet()) {
    int vc = sfVentCountOf((String) e.key, roomById)
    double pct = e.value == null ? 0.0d : ((Number) e.value).doubleValue()
    for (int i = 0; i < vc; i++) { out.put(e.key + '\u0000' + i, Double.valueOf(pct)) }
  }
  return out
}

double sfTotalAirflowCombined(Map result, Map roomById, Map s, Map reopened, int totalDevices) {
  if (totalDevices <= 0) { return 0.0d }
  double sumNew = 0.0d
  for (Object v : sfExpandAllByVentCount(result, roomById).values()) {
    sumNew += (v == null ? 0.0d : ((Number) v).doubleValue())
  }
  double reopenedSum = 0.0d
  for (Map.Entry e : reopened.entrySet()) {
    int vc = sfVentCountOf((String) e.key, roomById)
    reopenedSum += vc * (e.value == null ? 0.0d : ((Number) e.value).doubleValue())
  }
  double numerator = sumNew +
    (Math.max(0, (int) s.conventionalVents) * (double) s.conventionalOpenPct) +
    ((double) s.inactiveOpenPctSum) + reopenedSum
  return numerator / totalDevices
}

// ---- Allocator (unrolled mirror of class Dabv2Allocator) ----
// AllocResult -> Map [targets, predictedFinishMin, predictedSpreadC, airflowLimited, floorBinding]
// DuctSignals -> Map [ductTempC, ductPressure]   (either null when unavailable)
// GateContext -> Map [floorRequiresMove, predictedCurrentSpreadC, withinAntiChatterCooldown,
//                     batchLimitReached, mode, setpointC]
@groovy.transform.Field static final double ALLOC_RATE_FLOOR = 1e-9d
@groovy.transform.Field static final double ALLOC_APERTURE_MIN = 0.0d
@groovy.transform.Field static final double ALLOC_APERTURE_MAX = 100.0d

// GateContext factory (Map). Mirrors the old class default field values so a
// caller can override only the fields it cares about.
Map allocNewGateContext(Map overrides = [:]) {
  Map base = [
    floorRequiresMove        : false,
    predictedCurrentSpreadC  : 0.0d,
    withinAntiChatterCooldown: false,
    batchLimitReached        : false,
    mode                     : 'cooling',
    setpointC                : 0.0d
  ]
  if (overrides) { base.putAll(overrides) }
  return base
}

// Resolve the effective setpoint a single room should be driven toward. When no
// per-room target map is supplied (null/empty) OR a room has no entry, this
// returns the shared thermostat setpoint unchanged, so callers/fixtures that omit
// the map (or supply an all-setpoint map) see byte-for-byte identical behavior
// (R3.3). When a room carries its own resolved target, that target is used so the
// room's balancing converges toward its own comfort level (R3.7).
double allocRoomSetpoint(Map r, double setpointC, Map perRoomTargetC) {
  if (perRoomTargetC == null || perRoomTargetC.isEmpty() || r == null || r.roomId == null) {
    return setpointC
  }
  Object t = perRoomTargetC.get(r.roomId)
  return t == null ? setpointC : ((Number) t).doubleValue()
}

Map allocAllocate(List rooms, double setpointC, String mode, Map s, Map duct = null,
    Map perRoomTargetC = null) {
  boolean heating = allocIsHeating(mode)
  double hyst = (double) s.hysteresisC
  int step = Math.max(1, (int) s.granularity)

  Map<String, Double> targets = new LinkedHashMap<String, Double>()
  Map<String, Double> finishMin = new LinkedHashMap<String, Double>()
  Set<String> airflowLimited = new LinkedHashSet<String>()

  List unsatisfied = new ArrayList()
  if (rooms != null) {
    for (Object ro : rooms) {
      Map r = (Map) ro
      if (r == null || !((boolean) r.active) || r.roomId == null) { continue }
      double sp = allocRoomSetpoint(r, setpointC, perRoomTargetC)
      if (allocIsSatisfied(r, sp, heating, hyst)) {
        targets.put((String) r.roomId, 0.0d)
        finishMin.put((String) r.roomId, 0.0d)
      } else {
        unsatisfied.add(r)
      }
    }
  }

  double tauStar = 0.0d
  Map bottleneck = null
  for (Object ro : unsatisfied) {
    Map r = (Map) ro
    double sp = allocRoomSetpoint(r, setpointC, perRoomTargetC)
    double rateAtKnee = Math.max(ALLOC_RATE_FLOOR, ((double) r.efficiency) * allocFlowAt(r, (double) allocKneeOf(r)))
    double tau = allocConvErr(r, sp, heating, hyst) / rateAtKnee
    if (tau > tauStar) { tauStar = tau; bottleneck = r }
  }

  for (Object ro : unsatisfied) {
    Map r = (Map) ro
    int knee = allocKneeOf(r)
    double sp = allocRoomSetpoint(r, setpointC, perRoomTargetC)
    double err = allocConvErr(r, sp, heating, hyst)
    double flowAtKnee = allocFlowAt(r, (double) knee)
    double leak = allocLeakOf(r)

    double aperture
    if (tauStar <= 0.0d) {
      aperture = 0.0d
    } else if (r.is(bottleneck)) {
      aperture = (double) knee
    } else {
      double requiredRate = err / tauStar
      double requiredFlow = requiredRate / Math.max(ALLOC_RATE_FLOOR, (double) r.efficiency)
      if (requiredFlow <= leak) {
        aperture = 0.0d
      } else if (requiredFlow >= flowAtKnee) {
        aperture = (double) knee
      } else {
        aperture = allocRoundToGranularity(allocInverseOf(r, requiredFlow), step)
      }
    }

    aperture = dabv2Clamp(aperture, ALLOC_APERTURE_MIN, Math.min(ALLOC_APERTURE_MAX, (double) knee))
    targets.put((String) r.roomId, aperture)

    double rate = Math.max(ALLOC_RATE_FLOOR, ((double) r.efficiency) * allocFlowAt(r, aperture))
    finishMin.put((String) r.roomId, err / rate)
  }

  allocDetectAirflowLimited(rooms, targets, setpointC, heating, s, airflowLimited, perRoomTargetC)

  if (((boolean) s.crosscoupling) && !airflowLimited.isEmpty() && !allocDuctVetoes(duct, heating, setpointC)) {
    allocApplyCrossCoupling(rooms, targets, finishMin, setpointC, heating, hyst, s, perRoomTargetC)
  }

  return [
    targets           : targets,
    predictedFinishMin: finishMin,
    predictedSpreadC  : allocPredictedSpread(rooms, targets, mode, setpointC, (double) s.horizonMin, perRoomTargetC),
    airflowLimited    : airflowLimited,
    floorBinding      : false
  ]
}

double allocPredictedSpread(List rooms, Map targets, String mode, double setpointC, double horizonMin,
    Map perRoomTargetC = null) {
  if (rooms == null || targets == null) { return 0.0d }
  boolean heating = allocIsHeating(mode)
  double minT = Double.POSITIVE_INFINITY
  double maxT = Double.NEGATIVE_INFINITY
  int n = 0
  for (Object ro : rooms) {
    Map r = (Map) ro
    if (r == null || !((boolean) r.active) || r.roomId == null) { continue }
    double sp = allocRoomSetpoint(r, setpointC, perRoomTargetC)
    Object tgt = targets.get(r.roomId)
    double aperture = tgt == null ? 0.0d : dabv2Clamp(((Number) tgt).doubleValue(), ALLOC_APERTURE_MIN, ALLOC_APERTURE_MAX)
    double rate = Math.max(0.0d, ((double) r.efficiency) * allocFlowAt(r, aperture))
    double tempC = (double) r.tempC
    double projected
    if (heating) {
      projected = tempC + (rate * horizonMin)
      if (tempC >= sp) { projected = Math.min(projected, sp) }
    } else {
      projected = tempC - (rate * horizonMin)
      if (tempC <= sp) { projected = Math.max(projected, sp) }
    }
    if (projected < minT) { minT = projected }
    if (projected > maxT) { maxT = projected }
    n++
  }
  return n < 2 ? 0.0d : (maxT - minT)
}

boolean allocShouldApply(Map current, Map proposed, List rooms, Map s, Map gate) {
  if (gate == null) { return false }
  if ((boolean) gate.floorRequiresMove) { return true }
  if (((boolean) gate.withinAntiChatterCooldown) || ((boolean) gate.batchLimitReached)) { return false }
  double currentSpread = (double) gate.predictedCurrentSpreadC
  if (currentSpread <= ((double) s.spreadGuardrailC)) { return false }
  double proposedSpread = allocPredictedSpread(rooms, proposed, (String) gate.mode, (double) gate.setpointC, (double) s.horizonMin)
  return (currentSpread - proposedSpread) >= ((double) s.spreadImprovementDeadbandC)
}

boolean allocIsHeating(String mode) {
  return mode != null && mode.toLowerCase().contains('heat')
}

boolean allocIsSatisfied(Map r, double setpointC, boolean heating, double hyst) {
  return heating ? (((double) r.tempC) >= setpointC + hyst) : (((double) r.tempC) <= setpointC - hyst)
}

double allocConvErr(Map r, double setpointC, boolean heating, double hyst) {
  return heating ? ((setpointC + hyst) - ((double) r.tempC)) : (((double) r.tempC) - (setpointC - hyst))
}

double allocFlowAt(Map r, double aperturePct) {
  if (r.curve != null) { return lrnVentCurveFlow((Map) r.curve, aperturePct) }
  return lrnFlowLinear((double) r.leak, aperturePct / 100.0d)
}

int allocKneeOf(Map r) {
  return r.curve != null ? lrnVentCurveKnee((Map) r.curve) : 100
}

double allocLeakOf(Map r) {
  return r.curve != null ? lrnVentCurveFlow((Map) r.curve, 0.0d) : ((double) r.leak)
}

double allocInverseOf(Map r, double flowFraction) {
  if (r.curve != null) { return lrnVentCurveInverse((Map) r.curve, flowFraction) }
  double leak = dabv2Clamp((double) r.leak, 0.0d, 1.0d)
  double denom = 1.0d - leak
  if (denom <= ALLOC_RATE_FLOOR) { return 0.0d }
  double a = (dabv2Clamp(flowFraction, 0.0d, 1.0d) - leak) / denom
  return dabv2Clamp(a, 0.0d, 1.0d) * 100.0d
}

double allocRoundToGranularity(double value, int step) {
  if (step <= 0) { return dabv2Clamp(value, 0.0d, 100.0d) }
  // Round the computed intermediate target to the configured grid using
  // round-half-UP (Math.round), so an exactly-halfway value snaps to the HIGHER
  // multiple deterministically (R5.3) and stays consistent with the legacy
  // `roundToNearestMultiple` and `dabV2GroupNormalize`. (Math.rint rounds
  // half-to-even, which sent ties to the LOWER multiple and violated R5.3.)
  double rounded = (double) (Math.round(value / (double) step) * (long) step)
  return dabv2Clamp(rounded, 0.0d, 100.0d)
}

double allocOffTargetError(Map r, double setpointC, boolean heating) {
  return heating ? (setpointC - ((double) r.tempC)) : (((double) r.tempC) - setpointC)
}

void allocDetectAirflowLimited(List rooms, Map targets, double setpointC, boolean heating,
    Map s, Set<String> airflowLimited, Map perRoomTargetC = null) {
  if (rooms == null) { return }
  for (Object ro : rooms) {
    Map r = (Map) ro
    if (r == null || !((boolean) r.active) || r.roomId == null) { continue }
    Object tgt = targets.get(r.roomId)
    if (tgt == null) { continue }
    double sp = allocRoomSetpoint(r, setpointC, perRoomTargetC)
    double aperture = ((Number) tgt).doubleValue()
    double knee = (double) allocKneeOf(r)
    double off = allocOffTargetError(r, sp, heating)
    if (aperture >= (knee - ((double) s.airflowLimitedMarginPct)) && off > ((double) s.airflowLimitedErrorC)) {
      airflowLimited.add((String) r.roomId)
    }
  }
}

void allocApplyCrossCoupling(List rooms, Map targets, Map finishMin,
    double setpointC, boolean heating, double hyst, Map s, Map perRoomTargetC = null) {
  for (Object ro : rooms) {
    Map r = (Map) ro
    if (r == null || !((boolean) r.active) || r.roomId == null) { continue }
    if (!targets.containsKey(r.roomId)) { continue }
    double sp = allocRoomSetpoint(r, setpointC, perRoomTargetC)
    if (allocOffTargetError(r, sp, heating) <= 0.0d) {
      targets.put((String) r.roomId, 0.0d)
      if (allocIsSatisfied(r, sp, heating, hyst)) {
        finishMin.put((String) r.roomId, 0.0d)
      } else {
        double rate = Math.max(ALLOC_RATE_FLOOR, ((double) r.efficiency) * allocFlowAt(r, 0.0d))
        finishMin.put((String) r.roomId, allocConvErr(r, sp, heating, hyst) / rate)
      }
    }
  }
}

boolean allocDuctVetoes(Map duct, boolean heating, double setpointC) {
  if (duct == null) { return false }
  if (duct.ductPressure != null && ((Number) duct.ductPressure).doubleValue() <= 0.0d) { return true }
  if (duct.ductTempC != null) {
    double t = ((Number) duct.ductTempC).doubleValue()
    return heating ? (t <= setpointC) : (t >= setpointC)
  }
  return false
}

// ---- Model_IO (unrolled mirror of class Dabv2ModelIo) ----
// Dabv2Model -> Map [version, roomEff:Map, ventEff:Map, metrics:Map, cycle:Map|null,
//                    counters:Map|null, preAdjustFlags:Map]
// VentEff    -> Map [cooling:VentMode-map, heating:VentMode-map]
// VentMode   -> Map [leak, n, knee, curve:lrn-curve-map, sx, sy, sxx, sxy]
// BoundResult-> Map [encoded, finalBytes, withinBudget, droppedBreakpoints, rounded,
//                    prunedVents:List, prunedRooms:List, stepsApplied:List]
@groovy.transform.Field static final int MIO_SCHEMA_VERSION = 2
@groovy.transform.Field static final int MIO_SIG_DIGITS = 4
@groovy.transform.Field static final int MIO_CONSERVATIVE_BYTE_BUDGET = 80000

Map mioNewModel() {
  return [
    version       : MIO_SCHEMA_VERSION,
    roomEff       : new LinkedHashMap(),
    ventEff       : new LinkedHashMap(),
    metrics       : new LinkedHashMap(),
    cycle         : null,
    counters      : null,
    preAdjustFlags: new LinkedHashMap()
  ]
}

Map mioNewVentMode() {
  return [leak: 0.0d, n: 0, knee: 0, curve: (Map) null, sx: 0.0d, sy: 0.0d, sxx: 0.0d, sxy: 0.0d]
}

Map mioEncode(Map model) {
  Map out = new LinkedHashMap()
  out.put('v', MIO_SCHEMA_VERSION)
  Map roomEff = new LinkedHashMap()
  if (model.roomEff != null) {
    for (Map.Entry e : ((Map) model.roomEff).entrySet()) { roomEff.put(e.key, mioEncodeRoom((Map) e.value)) }
  }
  out.put('roomEff', roomEff)
  Map ventEff = new LinkedHashMap()
  if (model.ventEff != null) {
    for (Map.Entry e : ((Map) model.ventEff).entrySet()) { ventEff.put(e.key, mioEncodeVent((Map) e.value)) }
  }
  out.put('ventEff', ventEff)
  out.put('metrics', mioDeepCopyMap((Map) model.metrics))
  out.put('cycle', model.cycle == null ? null : mioDeepCopyMap((Map) model.cycle))
  out.put('counters', model.counters == null ? null : mioDeepCopyMap((Map) model.counters))
  out.put('preAdjustFlags', mioDeepCopyMap((Map) model.preAdjustFlags))
  return out
}

private Map mioEncodeRoom(Map rm) {
  return [c: mioEncodeMode(rm == null ? null : (Map) rm.cooling), h: mioEncodeMode(rm == null ? null : (Map) rm.heating)]
}

private Map mioEncodeMode(Map me) {
  Map m = new LinkedHashMap()
  if (me == null) {
    m.put('b', null); m.put('rg', new ArrayList()); m.put('n', 0); return m
  }
  m.put('b', me.baseline)
  List rg = new ArrayList()
  if (me.regimes != null) {
    for (Object cellObj : (List) me.regimes) { Map cell = (Map) cellObj; rg.add([cell.rate, cell.n]) }
  }
  m.put('rg', rg)
  m.put('n', me.n)
  return m
}

private Map mioEncodeVent(Map ve) {
  return [c: mioEncodeVentMode(ve == null ? null : (Map) ve.cooling), h: mioEncodeVentMode(ve == null ? null : (Map) ve.heating)]
}

private Map mioEncodeVentMode(Map vm) {
  if (vm == null) {
    vm = mioNewVentMode()
    vm.curve = lrnSeedLinear(LRN_LEAK_DEFAULT)
  }
  Map curve = vm.curve != null ? (Map) vm.curve : lrnSeedLinear((double) vm.leak)
  Map m = new LinkedHashMap()
  m.put('leak', vm.leak)
  m.put('n', vm.n)
  m.put('knee', vm.knee)
  m.put('bp', new ArrayList((List) curve.breakpoints))
  m.put('f', new ArrayList((List) curve.flows))
  m.put('cnt', new ArrayList((List) curve.counts))
  m.put('sx', vm.sx)
  m.put('sy', vm.sy)
  m.put('sxx', vm.sxx)
  m.put('sxy', vm.sxy)
  return m
}

Map mioDecode(Object data) {
  Map model = mioNewModel()
  if (!(data instanceof Map)) { return model }
  Map src = (Map) data
  Object ver = src.get('v')
  model.version = ver instanceof Number ? ((Number) ver).intValue() : MIO_SCHEMA_VERSION
  Object rooms = src.get('roomEff')
  if (rooms instanceof Map) {
    for (Object key : ((Map) rooms).keySet()) { ((Map) model.roomEff).put(key, mioDecodeRoom(((Map) rooms).get(key))) }
  }
  Object vents = src.get('ventEff')
  if (vents instanceof Map) {
    for (Object key : ((Map) vents).keySet()) { ((Map) model.ventEff).put(key, mioDecodeVent(((Map) vents).get(key))) }
  }
  Object metrics = src.get('metrics')
  if (metrics instanceof Map) { model.metrics = mioDeepCopyMap((Map) metrics) }
  Object cycle = src.get('cycle')
  model.cycle = cycle instanceof Map ? mioDeepCopyMap((Map) cycle) : null
  Object counters = src.get('counters')
  model.counters = counters instanceof Map ? mioDeepCopyMap((Map) counters) : null
  Object flags = src.get('preAdjustFlags')
  if (flags instanceof Map) { model.preAdjustFlags = mioDeepCopyMap((Map) flags) }
  return model
}

private Map mioDecodeRoom(Object data) {
  Map rm = lrnNewRoomModel()
  if (!(data instanceof Map)) { return rm }
  Map m = (Map) data
  rm.cooling = mioDecodeMode(m.get('c'))
  rm.heating = mioDecodeMode(m.get('h'))
  return rm
}

private Map mioDecodeMode(Object data) {
  Map me = lrnNewMode()
  if (!(data instanceof Map)) { return me }
  Map m = (Map) data
  Object b = m.get('b')
  me.baseline = b instanceof Number ? Double.valueOf(((Number) b).doubleValue()) : (Double) null
  Object n = m.get('n')
  me.n = n instanceof Number ? ((Number) n).intValue() : 0
  Object rg = m.get('rg')
  if (rg instanceof List) {
    List rgl = (List) rg
    for (int k = 0; k < LRN_EFF_REGIME_COUNT && k < rgl.size(); k++) {
      Object pair = rgl.get(k)
      if (pair instanceof List && ((List) pair).size() >= 2) {
        Map cell = (Map) ((List) me.regimes).get(k)
        cell.rate = mioNumD(((List) pair).get(0), 0.0d)
        cell.n = (int) mioNumD(((List) pair).get(1), 0.0d)
      }
    }
  }
  return me
}

private Map mioDecodeVent(Object data) {
  Map ve = [cooling: (Map) null, heating: (Map) null]
  if (!(data instanceof Map)) {
    ve.cooling = mioDecodeVentMode(null); ve.heating = mioDecodeVentMode(null); return ve
  }
  Map m = (Map) data
  ve.cooling = mioDecodeVentMode(m.get('c'))
  ve.heating = mioDecodeVentMode(m.get('h'))
  return ve
}

private Map mioDecodeVentMode(Object data) {
  Map vm = mioNewVentMode()
  if (!(data instanceof Map)) {
    vm.curve = lrnSeedLinear(LRN_LEAK_DEFAULT); return vm
  }
  Map m = (Map) data
  vm.leak = mioNumD(m.get('leak'), LRN_LEAK_DEFAULT)
  vm.n = (int) mioNumD(m.get('n'), 0.0d)
  vm.knee = (int) mioNumD(m.get('knee'), 100.0d)
  vm.sx = mioNumD(m.get('sx'), 0.0d)
  vm.sy = mioNumD(m.get('sy'), 0.0d)
  vm.sxx = mioNumD(m.get('sxx'), 0.0d)
  vm.sxy = mioNumD(m.get('sxy'), 0.0d)
  List<Integer> bps
  Object bp = m.get('bp')
  if (bp instanceof List && !((List) bp).isEmpty()) {
    bps = new ArrayList<Integer>()
    for (Object x : (List) bp) { bps.add((int) mioNumD(x, 0.0d)) }
  } else {
    bps = new ArrayList<Integer>(LRN_CURVE_BREAKPOINTS)
  }
  List<Double> flows = new ArrayList<Double>()
  Object f = m.get('f')
  if (f instanceof List) { for (Object x : (List) f) { flows.add(mioNumD(x, 0.0d)) } }
  List<Integer> counts = new ArrayList<Integer>()
  Object cnt = m.get('cnt')
  if (cnt instanceof List) { for (Object x : (List) cnt) { counts.add((int) mioNumD(x, 0.0d)) } }
  if (flows.size() != bps.size()) {
    vm.curve = lrnSeedLinear((double) vm.leak)
  } else {
    while (counts.size() < bps.size()) { counts.add(0) }
    if (counts.size() > bps.size()) { counts = counts.subList(0, bps.size()) }
    vm.curve = [breakpoints: bps, flows: flows, counts: counts, seed: new ArrayList<Double>(flows)]
  }
  return vm
}

String mioToJson(Map encoded) { return groovy.json.JsonOutput.toJson(encoded) }

int mioSerializedSize(Map model) { return mioToJson(mioEncode(model)).getBytes('UTF-8').length }

private int mioSizeOf(Map encoded) { return groovy.json.JsonOutput.toJson(encoded).getBytes('UTF-8').length }

Map mioNewBoundResult() {
  return [encoded: new LinkedHashMap(), finalBytes: 0, withinBudget: true,
          droppedBreakpoints: false, rounded: false,
          prunedVents: new ArrayList(), prunedRooms: new ArrayList(), stepsApplied: new ArrayList()]
}

Map mioBound(Map model, int byteBudget = MIO_CONSERVATIVE_BYTE_BUDGET) {
  Map res = mioNewBoundResult()
  Map enc = mioEncode(model)
  res.encoded = enc
  if (mioSizeOf(enc) <= byteBudget) { return mioFinalizeResult(res, byteBudget) }
  mioDropBreakpoints(enc)
  res.droppedBreakpoints = true
  ((List) res.stepsApplied).add('drop-bp')
  if (mioSizeOf(enc) <= byteBudget) { return mioFinalizeResult(res, byteBudget) }
  mioRoundEncoded(enc, MIO_SIG_DIGITS)
  res.rounded = true
  ((List) res.stepsApplied).add('round-4sig')
  if (mioSizeOf(enc) <= byteBudget) { return mioFinalizeResult(res, byteBudget) }
  ((List) res.stepsApplied).add('prune-lru')
  mioPruneUntilFits(enc, byteBudget, res)
  return mioFinalizeResult(res, byteBudget)
}

private Map mioFinalizeResult(Map res, int byteBudget) {
  res.finalBytes = mioSizeOf((Map) res.encoded)
  res.withinBudget = ((int) res.finalBytes) <= byteBudget
  return res
}

private void mioDropBreakpoints(Map enc) {
  Object vents = enc.get('ventEff')
  if (!(vents instanceof Map)) { return }
  for (Object v : ((Map) vents).values()) {
    if (v instanceof Map) {
      Object c = ((Map) v).get('c'); Object h = ((Map) v).get('h')
      if (c instanceof Map) { ((Map) c).remove('bp') }
      if (h instanceof Map) { ((Map) h).remove('bp') }
    }
  }
}

private void mioRoundEncoded(Map enc, int sig) {
  Object vents = enc.get('ventEff')
  if (vents instanceof Map) {
    for (Object v : ((Map) vents).values()) {
      if (v instanceof Map) { mioRoundVentMode(((Map) v).get('c'), sig); mioRoundVentMode(((Map) v).get('h'), sig) }
    }
  }
  Object rooms = enc.get('roomEff')
  if (rooms instanceof Map) {
    for (Object r : ((Map) rooms).values()) {
      if (r instanceof Map) { mioRoundRoomMode(((Map) r).get('c'), sig); mioRoundRoomMode(((Map) r).get('h'), sig) }
    }
  }
  Object metrics = enc.get('metrics')
  if (metrics instanceof Map) {
    for (Object strat : ((Map) metrics).values()) { if (strat instanceof Map) { mioRoundMapValues((Map) strat, sig) } }
  }
}

private void mioRoundVentMode(Object mode, int sig) {
  if (!(mode instanceof Map)) { return }
  Map m = (Map) mode
  mioRoundKey(m, 'leak', sig); mioRoundKey(m, 'sx', sig); mioRoundKey(m, 'sy', sig)
  mioRoundKey(m, 'sxx', sig); mioRoundKey(m, 'sxy', sig)
  Object f = m.get('f')
  if (f instanceof List) {
    List fl = (List) f
    for (int k = 0; k < fl.size(); k++) {
      Object x = fl.get(k)
      if (x instanceof Number) { fl.set(k, mioRoundSig(((Number) x).doubleValue(), sig)) }
    }
  }
}

private void mioRoundRoomMode(Object mode, int sig) {
  if (!(mode instanceof Map)) { return }
  Map m = (Map) mode
  mioRoundKey(m, 'b', sig)
  Object rg = m.get('rg')
  if (rg instanceof List) {
    for (Object pair : (List) rg) {
      if (pair instanceof List && !((List) pair).isEmpty()) {
        List p = (List) pair
        Object rate = p.get(0)
        if (rate instanceof Number) { p.set(0, mioRoundSig(((Number) rate).doubleValue(), sig)) }
      }
    }
  }
}

private void mioRoundMapValues(Map m, int sig) {
  for (Object key : new ArrayList(m.keySet())) {
    Object val = m.get(key)
    if (val instanceof Number && !(val instanceof Integer) && !(val instanceof Long)) {
      m.put(key, mioRoundSig(((Number) val).doubleValue(), sig))
    }
  }
}

private void mioRoundKey(Map m, String key, int sig) {
  Object val = m.get(key)
  if (val instanceof Number) { m.put(key, mioRoundSig(((Number) val).doubleValue(), sig)) }
}

private void mioPruneUntilFits(Map enc, int byteBudget, Map res) {
  Map ventEff = enc.get('ventEff') instanceof Map ? (Map) enc.get('ventEff') : null
  Map roomEff = enc.get('roomEff') instanceof Map ? (Map) enc.get('roomEff') : null
  while (mioSizeOf(enc) > byteBudget && ventEff != null && !ventEff.isEmpty()) {
    Object oldest = ventEff.keySet().iterator().next()
    ventEff.remove(oldest); ((List) res.prunedVents).add(oldest)
  }
  while (mioSizeOf(enc) > byteBudget && roomEff != null && !roomEff.isEmpty()) {
    Object oldest = roomEff.keySet().iterator().next()
    roomEff.remove(oldest); ((List) res.prunedRooms).add(oldest)
  }
}

Map mioDefaultCounters() {
  return [recalc24h: 0, hold24h: 0, windowStartMs: 0L]
}

Map mioMigrate(Object raw) {
  Map model
  try {
    if (raw instanceof Map) {
      Map src = (Map) raw
      Object ver = src.get('v')
      if (ver instanceof Number && ((Number) ver).intValue() >= MIO_SCHEMA_VERSION) {
        model = mioDecode(src)
      } else {
        model = mioMigrateV1(src)
      }
    } else {
      model = mioNewModel()
    }
  } catch (Exception ignored) {
    model = mioNewModel()
  }
  mioBackfillDefaults(model)
  model.version = MIO_SCHEMA_VERSION
  return model
}

String mioExportModel(Map model) { return mioToJson(mioEncode(model)) }

Map mioImportModel(Object payload) {
  Object parsed = payload
  if (payload instanceof String) {
    try { parsed = new groovy.json.JsonSlurper().parseText((String) payload) }
    catch (Exception ignored) { parsed = null }
  }
  return mioMigrate(parsed)
}

private void mioBackfillDefaults(Map model) {
  if (model.metrics == null) { model.metrics = new LinkedHashMap() }
  if (model.preAdjustFlags == null) { model.preAdjustFlags = new LinkedHashMap() }
  if (model.counters == null) {
    model.counters = mioDefaultCounters()
  } else {
    Map d = mioDefaultCounters()
    for (Map.Entry e : d.entrySet()) {
      if (!((Map) model.counters).containsKey(e.key)) { ((Map) model.counters).put(e.key, e.value) }
    }
  }
}

private Map mioMigrateV1(Map src) {
  Map model = mioNewModel()
  for (Object recObj : mioExtractV1Rooms(src)) {
    if (!(recObj instanceof Map)) { continue }
    Map rec = (Map) recObj
    String roomKey = mioFirstString(rec.get('roomId'), rec.get('roomName'), rec.get('room'))
    String ventKey = mioFirstString(rec.get('ventId'), rec.get('vent'), roomKey)
    if (roomKey != null) {
      Map rm = (Map) ((Map) model.roomEff).get(roomKey)
      if (rm == null) { rm = lrnNewRoomModel(); ((Map) model.roomEff).put(roomKey, rm) }
      Double cr = mioSeedRate(rec.get('coolingRate'))
      Double hr = mioSeedRate(rec.get('heatingRate'))
      if (cr != null) { ((Map) rm.cooling).baseline = cr; ((Map) rm.cooling).n = Math.max((int) ((Map) rm.cooling).n, 1) }
      if (hr != null) { ((Map) rm.heating).baseline = hr; ((Map) rm.heating).n = Math.max((int) ((Map) rm.heating).n, 1) }
    }
    if (ventKey != null) {
      Map ve = (Map) ((Map) model.ventEff).get(ventKey)
      if (ve == null) { ve = [cooling: (Map) null, heating: (Map) null]; ((Map) model.ventEff).put(ventKey, ve) }
      ve.cooling = mioSeedVentMode(rec, 'cool')
      ve.heating = mioSeedVentMode(rec, 'heat')
    }
  }
  return model
}

private List mioExtractV1Rooms(Map src) {
  Object rooms = null
  Object eff = src.get('efficiencyData')
  if (eff instanceof Map) { rooms = ((Map) eff).get('roomEfficiencies') }
  if (rooms == null) { rooms = src.get('roomEfficiencies') }
  if (rooms == null) { rooms = src.get('rooms') }
  return rooms instanceof List ? (List) rooms : new ArrayList()
}

private Map mioSeedVentMode(Map rec, String prefix) {
  Map vm = mioNewVentMode()
  Object slopeO = mioFirstPresent(rec.get(prefix + 'Slope'), rec.get('slope'))
  Object interO = mioFirstPresent(rec.get(prefix + 'Intercept'), rec.get('intercept'))
  Object nO = mioFirstPresent(rec.get(prefix + 'N'), rec.get('regN'), rec.get('n'))
  double leak
  if (slopeO != null && interO != null) {
    double slope = mioNumD(slopeO, 0.0d)
    double intercept = mioNumD(interO, 0.0d)
    int regN = (int) mioNumD(nO, 0.0d)
    Map eff = lrnDeriveEffectiveness(slope, intercept, regN)
    leak = (double) eff.leak
    vm.n = regN
  } else {
    leak = LRN_LEAK_DEFAULT
  }
  vm.leak = leak
  vm.knee = 100
  vm.curve = lrnSeedLinear(leak)
  return vm
}

private Double mioSeedRate(Object value) {
  if (!(value instanceof Number)) { return (Double) null }
  double d = ((Number) value).doubleValue()
  if (!Double.isFinite(d) || d <= 0.0d) { return (Double) null }
  return Double.valueOf(dabv2Clamp(d, LRN_RATE_MIN, LRN_RATE_MAX))
}

private Object mioFirstPresent(Object... values) {
  for (Object v : values) { if (v != null) { return v } }
  return null
}

private String mioFirstString(Object... values) {
  for (Object v : values) {
    if (v != null) {
      String s = String.valueOf(v)
      if (!s.isEmpty() && s != 'null') { return s }
    }
  }
  return (String) null
}

double mioRoundSig(double value, int sig) {
  if (value == 0.0d || !Double.isFinite(value) || sig <= 0) { return value }
  double mag = Math.ceil(Math.log10(Math.abs(value)))
  int power = sig - (int) mag
  double scale = Math.pow(10.0d, (double) power)
  return Math.round(value * scale) / scale
}

private double mioNumD(Object value, double fallback) {
  return value instanceof Number ? ((Number) value).doubleValue() : fallback
}

private Map mioDeepCopyMap(Map src) {
  Map out = new LinkedHashMap()
  if (src == null) { return out }
  for (Object key : src.keySet()) { out.put(key, mioDeepCopyValue(src.get(key))) }
  return out
}

private Object mioDeepCopyValue(Object value) {
  if (value instanceof Map) { return mioDeepCopyMap((Map) value) }
  if (value instanceof List) {
    List out = new ArrayList()
    for (Object v : (List) value) { out.add(mioDeepCopyValue(v)) }
    return out
  }
  return value
}


// ---- v0.236 pure helpers (R1/R2/R3/R4/R6) ----
// Six zone-agnostic, side-effect-free helpers operating on plain Maps/lists/
// scalars. PURE: no Hubitat APIs, wall-clock, randomness, or state. Each one
// pins a behavior the app wires up later; the math lives here so the off-device
// Spock harness and the on-device sandbox share identical source.

// Clamp a BigDecimal into [lo, hi] (BigDecimal-native sibling of dabv2Clamp).
BigDecimal dabv2ClampBd(BigDecimal value, BigDecimal lo, BigDecimal hi) {
  if (value < lo) { return lo }
  if (value > hi) { return hi }
  return value
}

// ---- R3: per-room effective target (°C-internal) ----
// Absolute target wins over a non-zero offset; a missing absolute target falls
// back to the shared setpoint plus the (clamped) signed offset. Default offset
// 0 with no absolute target resolves exactly to the shared setpoint (today's
// behavior). Absolute clamps to [absMinC, absMaxC]; offset clamps to
// [offMinC, offMaxC] before it is added. (R3.2–R3.6)
BigDecimal dabv2ResolveRoomTargetC(
    BigDecimal sharedSetpointC,
    BigDecimal roomAbsTargetC,
    BigDecimal roomOffsetC,
    BigDecimal absMinC = 10.0G, BigDecimal absMaxC = 32.0G,
    BigDecimal offMinC = -5.0G, BigDecimal offMaxC = 5.0G) {
  if (roomAbsTargetC != null) {
    return dabv2ClampBd(roomAbsTargetC, absMinC, absMaxC)
  }
  BigDecimal offset = roomOffsetC == null ? 0.0G : roomOffsetC
  return sharedSetpointC + dabv2ClampBd(offset, offMinC, offMaxC)
}

// ---- R4: bounded retry backoff (ms) ----
// When the server supplies a Retry-After value, honor it clamped to the cap;
// otherwise use exponential backoff (baseMs * 2^attempt) clamped to the cap.
// Never exceeds capMs. (R4.4/R4.5/R4.6/R4.7)
long dabv2BackoffIntervalMs(int attempt, Long retryAfterMs, long baseMs = 1000L,
                            long capMs = 60000L) {
  if (retryAfterMs != null) {
    return Math.min(retryAfterMs.longValue(), capMs)
  }
  int exp = attempt < 0 ? 0 : attempt
  double scaled = (double) baseMs * Math.pow(2.0d, (double) exp)
  if (!Double.isFinite(scaled) || scaled >= (double) capMs) { return capMs }
  return Math.min((long) scaled, capMs)
}

// ---- R6: circulation (fan-only) detection ----
// True when the HVAC operating state is explicitly fan-only, or as a fallback
// when the fan is forced on while the system is idle. Anything else (actively
// conditioning, idle+auto, no fan signal) is not circulation. (R6.1/R6.2/R6.4)
boolean dabv2DetectCirculation(String operatingState, String fanMode) {
  if ('fan only'.equals(operatingState)) { return true }
  if ('on'.equals(fanMode) && 'idle'.equals(operatingState)) { return true }
  return false
}

// ---- R6: circulation vent targets ----
// Map each eligible vent to the circulation %. When closeInactive is true only
// vents in active rooms are targeted (inactive-room vents are excluded); when
// false every vent is targeted regardless of room activity. roomActiveById is
// keyed by vent id (per-vent active flag); a missing/false entry marks a vent
// as inactive. The app routes the result through sfApply so the floor wins.
// (R6.3/R6.10–R6.13)
Map dabv2CirculationTargets(List ventIds, BigDecimal circulationPct,
                            Map roomActiveById, boolean closeInactive) {
  Map targets = new LinkedHashMap()
  if (ventIds == null) { return targets }
  for (Object vidObj : ventIds) {
    String vid = vidObj == null ? null : String.valueOf(vidObj)
    if (vid == null) { continue }
    if (closeInactive) {
      Object active = roomActiveById == null ? null : roomActiveById.get(vid)
      if (active != Boolean.TRUE) { continue }
    }
    targets.put(vid, circulationPct)
  }
  return targets
}

// ---- R7.4: configurable minimum vent opening ----
// Raise each ACTIVE room-group's commanded aperture up to at least the resolved
// minimum opening so balancing never commands a vent below the user's floor for
// that vent (R7.22/R7.26). The per-vent override perVentMin[ventId] wins over
// the global minimum (R7.23/R7.24); when several vents share a room-group the
// group value is raised to the MAX of their resolved minimums so no member of
// the group falls below its own minimum (consistent with no-group-split). An
// inactive room-group that is being CLOSED (closeInactive) is left UNTOUCHED so
// the minimum never overrides the inactive-room close (R7.27/R7.28) — the floor
// (sfApply, run AFTER this) may still reopen it as a last resort. Every value is
// clamped to 0–100 (R7.25). PURE: no Hubitat/clock/RNG/state.
//
//   targets       : room-keyed commanded apertures (post group-normalize)
//   rooms         : per-room records [roomId, active, ventIds]
//   globalMinPct  : global minimum opening % (clamped 0–100 here)
//   perVentMin    : optional map ventId -> minimum % (overrides the global)
//   closeInactive : whether inactive rooms are being auto-closed
// (R7.22–R7.28)
Map dabv2ApplyMinOpening(Map targets, List rooms, Object globalMinPct,
                         Map perVentMin, boolean closeInactive) {
  Map out = new LinkedHashMap()
  if (targets == null) { return out }
  double gMin = globalMinPct instanceof Number ?
    dabv2Clamp(((Number) globalMinPct).doubleValue(), 0.0d, 100.0d) : 0.0d
  Map roomById = new LinkedHashMap()
  if (rooms != null) {
    for (Object r : rooms) {
      if (r != null && ((Map) r).roomId != null) {
        roomById.put(String.valueOf(((Map) r).roomId), r)
      }
    }
  }
  for (Map.Entry e : targets.entrySet()) {
    String roomId = String.valueOf(e.key)
    double cur = e.value == null ? 0.0d : ((Number) e.value).doubleValue()
    Map room = (Map) roomById.get(roomId)
    boolean active = room == null ? true : ((boolean) room.active)
    // Respect the inactive-room close: never raise an inactive (closed) group.
    if (!active && closeInactive) { out.put(roomId, cur); continue }
    // Effective minimum for this group = MAX over its vents of (per-vent override
    // else global). With no vent list, fall back to the global minimum.
    double eff = gMin
    List ventIds = room == null ? null : (List) room.ventIds
    if (ventIds != null && !ventIds.isEmpty()) {
      eff = 0.0d
      for (Object vidObj : ventIds) {
        String vid = vidObj == null ? null : String.valueOf(vidObj)
        double m = gMin
        if (vid != null && perVentMin != null && perVentMin.get(vid) instanceof Number) {
          m = dabv2Clamp(((Number) perVentMin.get(vid)).doubleValue(), 0.0d, 100.0d)
        }
        if (m > eff) { eff = m }
      }
    }
    double raised = cur < eff ? eff : cur
    out.put(roomId, dabv2Clamp(raised, 0.0d, 100.0d))
  }
  return out
}

// ---- R2: deterministic structure (Home Id) selection ----
// A configured id present in the response wins; a single returned structure is
// adopted automatically; more than one structure with none configured (or a
// configured id absent from the response) refuses to auto-pick and requires an
// explicit selection. Replaces the blind response.data.first() shortcut.
// (R2.17/R2.18)
Map dabv2SelectStructureId(List structures, String configuredId) {
  List list = structures == null ? new ArrayList() : structures
  if (configuredId != null && !configuredId.isEmpty()) {
    for (Object sObj : list) {
      if (sObj instanceof Map && configuredId.equals(String.valueOf(((Map) sObj).get('id')))) {
        return [id: configuredId, requireSelection: false]
      }
    }
  }
  if (list.size() == 1 && list.get(0) instanceof Map) {
    return [id: ((Map) list.get(0)).get('id'), requireSelection: false]
  }
  return [id: null, requireSelection: true]
}

// ---- R1: puck revision classification (classify, never gate) ----
// 'PUCK2' when the puck attributes carry hardware-version-name 'ep_puck2' or
// the hardware-version sub-resource reports device-type 'PUCK2'; 'PUCK' when
// device-type is 'PUCK'; otherwise 'UNKNOWN'. Unrecognized revisions are still
// onboarded by the caller — this only drives optional diagnostics. (R1.2/R1.3)
String dabv2PuckRevision(Map attrs, Map hwVersionSub = null) {
  String hwName = attrs == null ? null : (attrs.get('hardware-version-name') as String)
  String deviceType = null
  if (hwVersionSub != null && hwVersionSub.get('attributes') instanceof Map) {
    deviceType = ((Map) hwVersionSub.get('attributes')).get('device-type') as String
  }
  if ('ep_puck2'.equals(hwName) || 'PUCK2'.equals(deviceType)) { return 'PUCK2' }
  if ('PUCK'.equals(deviceType)) { return 'PUCK' }
  return 'UNKNOWN'
}

// ---- R7.2: HPM channel-aware display-version derivation ----
// Derive the displayed version from the HPM manifest fields. The stable channel
// derives from `version` (R7.6); the beta / early-release channel derives from
// `betaVersion` (R7.7). A null / missing / blank field (or the literal string
// 'null') falls back to the documented fallback label so the UI can NEVER
// render a null-derived "vnullbeta" string (R7.8). Pure: no Hubitat APIs, no
// state, operates on a plain manifest Map. `fallbackLabel` defaults to a safe,
// non-null placeholder when a caller omits it.
String dabv2DeriveDisplayVersion(Map manifest, String channel, String fallbackLabel = 'unknown') {
  String fb = (fallbackLabel == null || fallbackLabel.trim().isEmpty()) ? 'unknown' : fallbackLabel.trim()
  if (manifest == null) { return fb }
  boolean beta = (channel != null && 'beta'.equals(channel.trim().toLowerCase()))
  Object raw = beta ? manifest.get('betaVersion') : manifest.get('version')
  if (raw == null) { return fb }
  String s = String.valueOf(raw).trim()
  if (s.isEmpty() || 'null'.equalsIgnoreCase(s)) { return fb }
  return s
}
