
import groovy.transform.CompileStatic

/**
 * In-memory form of a {@code *.expected.json} parity fixture (task 8.1,
 * R17.4) — the Reference-generated output the Groovy allocator is compared
 * against by the parity gate (task 8.3 / Property 17).
 *
 * Each expected fixture is REGENERATED from the Python Reference by
 * {@code tools/gen_parity_fixtures} and stamped with the Reference git SHA
 * ({@link #referenceCommit}) so the evidence is reproducible and traceable
 * (R17.4).
 *
 * Test-support data holder; see {@link ParityFixtures} for the loader.
 */
@CompileStatic
class ParityExpected {
  /** Stable scenario id (matches the fixture file name stem). */
  String id
  /** Reference commanded open % per ACTIVE room (post safety-floor). */
  Map<String, Double> targets = new LinkedHashMap<String, Double>()
  /** Reference predicted active-room spread at the horizon (deg C); may be null. */
  Double predictedSpreadC
  /** Reference airflow-limited room ids. */
  Set<String> airflowLimited = new LinkedHashSet<String>()
  /** Whether the Reference safety floor had to raise apertures. */
  boolean floorBinding
  /** Reference combined open % of the floored allocation; may be null. */
  Double combinedOpenPct
  /** Reference git SHA the expected output was generated from (R17.4). */
  String referenceCommit
  /** Allowed aperture mismatch in percentage points (default 1, R17.2). */
  int toleranceAperturePoints = 1
}
