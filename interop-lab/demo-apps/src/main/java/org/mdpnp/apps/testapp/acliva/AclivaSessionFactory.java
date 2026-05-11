package org.mdpnp.apps.testapp.acliva;

/**
 * Factory that creates the BisSource and PumpAdapter for an ACLIVA session.
 *
 * THIS IS THE ONLY FILE THAT NEEDS TO CHANGE when moving from simulation
 * to real hardware. Everything else — the control loop, the GUI, the
 * AclivaController — stays identical.
 *
 * SIMULATION MODE (current):
 *   bisSource  = SimulatedBisSource (Schnider PK/PD + noise)
 *   pumpAdapter = SimulatedPumpAdapter (in-memory)
 *
 * LIVE MODE (future):
 *   bisSource  = DdsBisSource (reads MDC_EEG_BIS from real monitor via DDS)
 *   pumpAdapter = BDAlarisAdapter (writes FlowRateObjective to Alaris via DDS)
 *
 * To switch to live mode:
 *   1. Implement DdsBisSource (subscribes to OpenICE numerics topic,
 *      filters for MDC_EEG_BIS, returns latest value from getBIS())
 *   2. Complete BDAlarisAdapter TODOs (inject DDS writer, confirm Guardrails)
 *   3. Change the two lines in createSession() marked with SWAP HERE
 */
public class AclivaSessionFactory {

    /**
     * Timestep used by the simulation (1 second expressed in minutes).
     * Also used by the control loop sleep calculation.
     */
    public static final double DT_MINUTES = 1.0 / 60.0;

    public static class AclivaSession {
        public final BisSource   bisSource;
        public final PumpAdapter pumpAdapter;

        AclivaSession(BisSource bisSource, PumpAdapter pumpAdapter) {
            this.bisSource   = bisSource;
            this.pumpAdapter = pumpAdapter;
        }
    }

    /**
     * Create a session for the given patient parameters.
     * Returns matched BisSource + PumpAdapter pair wired together.
     */
    public static AclivaSession createSession(int age, int weight,
                                              int height, String sex) {
        // ── SIMULATION MODE ──────────────────────────────────────────────────
        // SWAP HERE (1 of 2): replace these two lines for live hardware

        BISVistaSimulator simulator = new BISVistaSimulator(age, weight, height, sex);
        simulator.setNoiseEnabled(true);
        SimulatedBisSource simBis = new SimulatedBisSource(simulator, DT_MINUTES);
        SimulatedPumpAdapter simPump = new SimulatedPumpAdapter(simBis);

        return new AclivaSession(simBis, simPump);

        // ── LIVE MODE (uncomment when ready) ─────────────────────────────────
        // SWAP HERE (2 of 2): comment out simulation block above, uncomment this
        //
        // DdsBisSource liveBis = new DdsBisSource(applicationContext);
        // BDAlarisAdapter livePump = new BDAlarisAdapter(ddsWriter);
        // return new AclivaSession(liveBis, livePump);
    }
}
