package org.mdpnp.apps.testapp.acliva;

/**
 * Simulated pump adapter for use during simulation mode.
 *
 * Stores the commanded rate in memory and forwards it to the
 * SimulatedBisSource so the Schnider model uses the correct rate
 * on the next tick.
 *
 * To swap to a real BD Alaris pump, replace this with BDAlarisAdapter
 * in AclivaSessionFactory — the simulation loop needs no changes.
 */
public class SimulatedPumpAdapter implements PumpAdapter {

    private static final double MIN_RATE =   0.0;
    private static final double MAX_RATE = 120.0;

    private final SimulatedBisSource bisSource;
    private volatile double lastCommandedRate = 0.0;

    /**
     * @param bisSource the SimulatedBisSource that needs to know the
     *                  current rate so it can advance the PK/PD model correctly
     */
    public SimulatedPumpAdapter(SimulatedBisSource bisSource) {
        this.bisSource = bisSource;
    }

    @Override
    public void setRate(double rateMlPerHour) {
        double clamped = Math.max(MIN_RATE, Math.min(MAX_RATE, rateMlPerHour));
        this.lastCommandedRate = clamped;
        // Forward to simulator so the PK/PD model uses this rate on next tick
        bisSource.setRate(clamped);
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
        return "Simulated Infusion Pump";
    }
}
