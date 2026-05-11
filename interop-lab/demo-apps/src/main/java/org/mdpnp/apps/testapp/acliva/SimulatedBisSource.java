package org.mdpnp.apps.testapp.acliva;

/**
 * Simulated BIS source backed by BISVistaSimulator (Schnider PK/PD + noise).
 *
 * The simulation loop calls getBIS() each tick. This implementation
 * advances the Schnider model by one timestep using whatever rate was
 * last set via setRate(), and returns the resulting noisy BIS.
 *
 * To swap to a real BIS monitor, replace this with a DdsBisSource that
 * subscribes to MDC_EEG_BIS on the OpenICE DDS bus — the simulation loop
 * needs no changes.
 */
public class SimulatedBisSource implements BisSource {

    private final BISVistaSimulator simulator;
    private final double            deltaTimeMin;
    private double                  lastRate = 0.0;

    /**
     * @param simulator    configured Schnider model for this patient
     * @param deltaTimeMin simulation timestep in minutes (e.g. 1.0/60.0 = 1 second)
     */
    public SimulatedBisSource(BISVistaSimulator simulator, double deltaTimeMin) {
        this.simulator    = simulator;
        this.deltaTimeMin = deltaTimeMin;
    }

    /**
     * Tell the simulator what rate the pump is currently running at.
     * Called by the simulation loop after each pump command.
     */
    public void setRate(double rateMlPerHour) {
        this.lastRate = rateMlPerHour;
    }

    /**
     * Advance the PK/PD model by one timestep and return noisy BIS.
     */
    @Override
    public double getBIS() {
        return simulator.tick(lastRate, deltaTimeMin);
    }

    /**
     * Return the current effect-site concentration from the Schnider model.
     */
    @Override
    public double getCe() {
        return simulator.getCe();
    }

    /**
     * Reset the compartment state (call when starting a new simulation run).
     */
    public void reset() {
        simulator.reset();
        lastRate = 0.0;
    }
}
