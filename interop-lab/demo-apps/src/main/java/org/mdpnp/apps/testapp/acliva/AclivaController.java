package org.mdpnp.apps.testapp.acliva;

/**
 * ACLIVA PID controller for closed-loop propofol infusion.
 *
 * PID gains from Liu et al. 2011: Kp=0.8, Ki=0.02, Kd=0.5
 *
 * Design: calculateDeltaRate() returns a CHANGE in mL/hr to be added to
 * the previous pump rate. The caller maintains the running rate and clamps
 * it to [minRate, maxRate]. This matches the Python reference implementation
 * and avoids the problem of the controller outputting an absolute rate that
 * is far too low when BIS is only moderately above target.
 *
 * Example usage in simulation loop:
 *   currentRate = currentRate + controller.calculateDeltaRate(currentBis, dt);
 *   currentRate = Math.max(0, Math.min(120, currentRate));
 */
public class AclivaController {

    private final double kp;
    private final double ki;
    private final double kd;
    private final double targetBis;

    private final double minRate = 0.0;
    private final double maxRate = 120.0;
    private final double integralClamp = 100.0; // anti-windup clamp

    private double integralError  = 0.0;
    private double previousError  = 0.0;

    public AclivaController(double kp, double ki, double kd, double targetBis) {
        this.kp        = kp;
        this.ki        = ki;
        this.kd        = kd;
        this.targetBis = targetBis;
    }

    public void reset() {
        integralError = 0.0;
        previousError = 0.0;
    }

    /**
     * Calculate the change in infusion rate for this timestep.
     *
     * @param currentBis   current measured BIS value
     * @param deltaTimeMin elapsed time since last call, in minutes
     * @return delta mL/hr to ADD to the current pump rate
     *         (caller must clamp result to [0, 120] mL/hr)
     */
    public double calculateDeltaRate(double currentBis, double deltaTimeMin) {
        // Positive error = BIS above target = patient too light = need more drug
        double error = currentBis - targetBis;

        // Accumulate integral with anti-windup clamp
        integralError += error * deltaTimeMin;
        if (integralError >  integralClamp) integralError =  integralClamp;
        if (integralError < -integralClamp) integralError = -integralClamp;

        // Derivative term
        double derivative = (deltaTimeMin > 0)
            ? (error - previousError) / deltaTimeMin
            : 0.0;

        previousError = error;

        // Return delta rate — caller adds this to the running rate
        return kp * error + ki * integralError + kd * derivative;
    }

    // ── Retained for backward compatibility ─────────────────────────────────

    /**
     * @deprecated Use calculateDeltaRate() and add the result to the previous
     *             rate yourself. This method returns an absolute rate which
     *             causes steady-state offset when error is small.
     */
    @Deprecated
    public double calculateRate(double currentBis, double deltaTimeMin) {
        return calculateDeltaRate(currentBis, deltaTimeMin);
    }
}
