package org.mdpnp.apps.testapp.acliva;

/**
 * Abstraction over a BIS measurement source.
 *
 * In simulation mode this is backed by BISVistaSimulator (Schnider + noise).
 * In live mode this will be backed by a DDS subscriber reading the
 * MDC_EEG_BIS numeric from the real BIS monitor via OpenICE.
 *
 * The simulation loop calls only getBIS() and getCe() — it never knows
 * which implementation is underneath.
 */
public interface BisSource {

    /**
     * Return the current BIS value (0-100).
     * In simulation mode this advances the PK/PD model by one timestep
     * using the last rate set via the associated PumpAdapter.
     * In live mode this returns the most recently received DDS value.
     */
    double getBIS();

    /**
     * Return the current effect-site concentration in mcg/mL.
     * In simulation mode this is computed by the Schnider model.
     * In live mode this returns NaN (not available from real monitor).
     */
    double getCe();
}
