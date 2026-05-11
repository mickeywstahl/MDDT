package org.mdpnp.apps.testapp.acliva;

import java.util.Random;

public class BISVistaSimulator {

    // Patient parameters
    private double v1, v2, v3;
    private double cl1, cl2, cl3;
    private double ke0;

    // Compartment masses (mg)
    private double x1 = 0;
    private double x2 = 0;
    private double x3 = 0;

    // Effect site concentration (mg/L = mcg/mL)
    private double ce = 0;

    // Schnider/Struys validated PD parameters for Propofol
    private static final double E0    = 93.0;   // Baseline BIS (Struys et al.)
    private static final double EMAX  = 93.0;   // Maximum effect
    private static final double EC50  = 3.4;    // Concentration for 50% effect (mcg/mL)
    private static final double GAMMA = 1.47;   // Hill coefficient (Struys et al.)

    // BIS measurement noise — simulates real BIS monitor signal variability.
    // Std dev of ~2 units matches published BIS monitor noise characteristics.
    private static final double NOISE_STD = 2.0;
    private final Random rng = new Random(42);
    private boolean noiseEnabled = false;

    public BISVistaSimulator(int age, int weight, int height, String sex) {
        double lbm = "Male".equals(sex)
            ? 1.1 * weight - 128.0 * Math.pow((double) weight / height, 2)
            : 1.07 * weight - 148.0 * Math.pow((double) weight / height, 2);

        // Schnider PK parameters
        this.v1  = 4.27;
        this.v2  = 18.9 - 0.391 * (age - 53);
        this.v3  = 238;

        this.cl1 = 1.89 + 0.0456 * (weight - 77) - 0.0681 * (lbm - 59) + 0.0264 * (height - 177);
        this.cl2 = 1.29 - 0.024 * (age - 53);
        this.cl3 = 0.836;

        this.ke0 = 0.456; // Fixed ke0 for Schnider
    }

    /**
     * Enable or disable Gaussian measurement noise on the BIS output.
     */
    public void setNoiseEnabled(boolean enabled) {
        this.noiseEnabled = enabled;
    }

    public void reset() {
        x1 = 0;
        x2 = 0;
        x3 = 0;
        ce = 0;
    }

    /**
     * Advance the PK/PD model by one timestep.
     *
     * @param infusionRate mL/hr of 1% Propofol (10 mg/mL)
     * @param deltaTimeMin time step in minutes
     * @return Current BIS value (0-100), with noise if enabled
     */
    public double tick(double infusionRate, double deltaTimeMin) {
        // Convert mL/hr * 10 mg/mL to mg/min
        double doseRateMgMin = (infusionRate * 10.0) / 60.0;

        // Rate constants (min^-1)
        double k10 = cl1 / v1;
        double k12 = cl2 / v1;
        double k21 = cl2 / v2;
        double k13 = cl3 / v1;
        double k31 = cl3 / v3;

        // Sub-divide the timestep for Euler stability
        int steps = 10;
        double dt = deltaTimeMin / steps;

        for (int i = 0; i < steps; i++) {
            double c1 = x1 / v1; // Central compartment concentration (mg/L = mcg/mL)

            double dx1 = doseRateMgMin - (k10 * x1) - (k12 * x1) + (k21 * x2) - (k13 * x1) + (k31 * x3);
            double dx2 = (k12 * x1) - (k21 * x2);
            double dx3 = (k13 * x1) - (k31 * x3);
            double dCe = ke0 * (c1 - ce);

            x1 += dx1 * dt;
            x2 += dx2 * dt;
            x3 += dx3 * dt;
            ce += dCe * dt;

            if (x1 < 0) x1 = 0;
            if (x2 < 0) x2 = 0;
            if (x3 < 0) x3 = 0;
            if (ce < 0) ce = 0;
        }

        double bis = calculateBis(ce);

        if (noiseEnabled) {
            bis += rng.nextGaussian() * NOISE_STD;
            bis = Math.max(0, Math.min(100, bis));
        }

        return bis;
    }

    public double getCe() {
        return ce;
    }

    private double calculateBis(double ce) {
        // Emax (Hill) model: BIS = E0 - Emax * Ce^gamma / (Ce^gamma + EC50^gamma)
        double ceG   = Math.pow(ce, GAMMA);
        double ec50G = Math.pow(EC50, GAMMA);
        double effect = E0 - EMAX * (ceG / (ceG + ec50G));
        return Math.max(0, Math.min(100, effect));
    }
}
