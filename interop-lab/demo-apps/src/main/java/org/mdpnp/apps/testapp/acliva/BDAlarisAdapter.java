package org.mdpnp.apps.testapp.acliva;

/**
 * BD Alaris pump adapter for live closed-loop operation via OpenICE DDS.
 *
 * This class sends FlowRateObjective commands to the BD Alaris pump
 * via the OpenICE DDS bus. The pump's OpenICE driver subscribes to
 * FlowRateObjective and translates the rate into the pump's proprietary
 * protocol.
 *
 * BEFORE USING IN LIVE MODE — confirm all TODOs with your clinical
 * pharmacist and biomedical engineer:
 *
 *   TODO 1: Confirm the exact model string published by the BD Alaris
 *           OpenICE driver (needed by AclivaSessionFactory to select
 *           this adapter). Check ice.Device.model when the pump is
 *           connected and visible in the OpenICE supervisor.
 *
 *   TODO 2: Confirm Guardrails soft limit and hard limit for propofol
 *           with your pharmacist. MAX_RATE must not exceed the hard limit
 *           or the pump will alarm and refuse the command.
 *
 *   TODO 3: Confirm whether the OpenICE Alaris driver requires a VTBI
 *           (Volume To Be Infused) alongside the rate command, or whether
 *           it runs indefinitely until stopped.
 *
 *   TODO 4: Confirm rate resolution — the Alaris accepts 0.1 mL/hr
 *           increments. Rates will be rounded to 1 decimal place before
 *           sending.
 *
 *   TODO 5: Measure actual pump response latency (time from FlowRateObjective
 *           publish to pump display updating). Typically 0.5-1.5 seconds.
 *           The dead band in AclivaController should be wide enough that
 *           this latency does not cause hunting.
 *
 *   TODO 6: Test with pump powered on but NOT patient-connected before
 *           any clinical use.
 *
 * WIRING:
 *   To activate this adapter, change AclivaSessionFactory.createPumpAdapter()
 *   to return a BDAlarisAdapter instead of SimulatedPumpAdapter.
 *   No other code changes are needed.
 */
public class BDAlarisAdapter implements PumpAdapter {

    // TODO 2: Confirm these limits with pharmacist for your institution's
    //         Guardrails propofol configuration
    private static final double MIN_RATE =   0.0;   // mL/hr
    private static final double MAX_RATE = 120.0;   // mL/hr — verify against Guardrails hard limit

    // Rate resolution of BD Alaris (0.1 mL/hr)
    private static final double RATE_RESOLUTION = 0.1;

    private volatile double lastCommandedRate = 0.0;

    // TODO: inject the DDS FlowRateObjectiveDataWriter here when wiring up
    // private final FlowRateObjectiveDataWriter writer;

    /**
     * TODO: accept DDS writer as constructor argument when wiring up.
     * For now this is a stub that compiles but does not communicate.
     */
    public BDAlarisAdapter() {
        // TODO: store writer reference
    }

    @Override
    public void setRate(double rateMlPerHour) {
        double clamped  = Math.max(MIN_RATE, Math.min(MAX_RATE, rateMlPerHour));
        // Round to Alaris resolution (0.1 mL/hr)
        double rounded  = Math.round(clamped / RATE_RESOLUTION) * RATE_RESOLUTION;

        // Skip redundant writes — avoid unnecessary DDS traffic
        if (Math.abs(rounded - lastCommandedRate) < RATE_RESOLUTION / 2.0) {
            return;
        }

        this.lastCommandedRate = rounded;

        // TODO: publish to DDS
        // FlowRateObjective obj = new FlowRateObjective();
        // obj.flow_rate = (float) rounded;
        // writer.write(obj, InstanceHandle_t.HANDLE_NIL);
        //
        // For now just log the command so integration testing is visible
        System.out.println("[BDAlarisAdapter] setRate -> " + rounded + " mL/hr");
    }

    @Override
    public double getLastCommandedRate() {
        return lastCommandedRate;
    }

    @Override
    public double getMinRate() {
        return MIN_RATE;
    }

    @Override
    public double getMaxRate() {
        return MAX_RATE;
    }

    @Override
    public String getPumpName() {
        return "BD Alaris (OpenICE)";
    }
}
