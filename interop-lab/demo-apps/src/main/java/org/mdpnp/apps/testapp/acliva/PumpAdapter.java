package org.mdpnp.apps.testapp.acliva;

/**
 * Abstraction over a propofol infusion pump.
 *
 * In simulation mode this is backed by SimulatedPumpAdapter which simply
 * stores the commanded rate in memory and forwards it to SimulatedBisSource.
 *
 * In live mode this will be backed by BDAlarisAdapter which writes a
 * FlowRateObjective to the OpenICE DDS bus, causing the real BD Alaris
 * pump to change its infusion rate.
 *
 * The simulation loop calls only setRate() and getLastCommandedRate() —
 * it never knows which implementation is underneath.
 *
 * THREAD SAFETY: setRate() will be called from the simulation background
 * thread. Implementations must be thread-safe.
 */
public interface PumpAdapter {

    /**
     * Command the pump to run at the specified rate.
     *
     * @param rateMlPerHour target infusion rate in mL/hr
     *                      (will be clamped to [getMinRate(), getMaxRate()])
     */
    void setRate(double rateMlPerHour);

    /**
     * Return the rate most recently commanded via setRate().
     * This is the commanded rate, not necessarily the actual pump rate —
     * the real pump may take up to ~1 second to respond.
     */
    double getLastCommandedRate();

    /** Minimum rate this pump accepts (typically 0.0 mL/hr). */
    double getMinRate();

    /** Maximum rate this pump accepts. */
    double getMaxRate();

    /** Human-readable name for display in the GUI. */
    String getPumpName();
}
