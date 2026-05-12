package org.mdpnp.apps.testapp.datavalidation;

/**
 * PpgSqiEngine
 * ============
 * Pure Java implementation of the PPG Signal Quality Index algorithm.
 * No Python, no external service, no network calls.
 *
 * Computes a composite SQI score (0.0-1.0) from three independent measures,
 * following the approach described in:
 *
 *   Le et al., "vital_sqi: A Python package for physiological signal quality
 *   control", Frontiers in Physiology, Vol. 13, 2022.
 *   DOI: 10.3389/fphys.2022.1020458
 *
 * The three measures are:
 *
 *   1. Perfusion index (20% weight)
 *      AC/DC amplitude ratio. A disconnected sensor or motion that eliminates
 *      the pulsatile component drives this to near zero.
 *
 *   2. Spectral band power ratio (30% weight)
 *      Fraction of signal power in the physiological PPG band (0.5-4.0 Hz,
 *      i.e. 30-240 bpm). Computed via Welch's method with a Hann window.
 *      Artifact and electrical interference add energy outside this band.
 *
 *   3. Autocorrelation peak score (50% weight)
 *      Peak normalized autocorrelation in the cardiac cycle lag range
 *      (0.4-2.0 s, i.e. 30-150 bpm). Valid PPG has strong periodicity;
 *      random noise and flatline signals do not. This is the strongest
 *      single discriminator between clean PPG and artifact.
 *
 * Composite: SQI = 0.20*perfusion + 0.30*spectral + 0.50*autocorr
 * Threshold: ACCEPT if SQI >= 0.40, REJECT otherwise.
 *
 * All FFT operations use the in-house radix-2 DIT FFT below, requiring
 * no external dependencies.
 */
public class PpgSqiEngine {

    // -- Configuration ---------------------------------------------------------
    public static final double THRESHOLD      = 0.40;
    private static final double PPG_BAND_LOW  = 0.5;   // Hz (30 bpm)
    private static final double PPG_BAND_HIGH = 4.0;   // Hz (240 bpm)
    private static final double LAG_MIN_S     = 0.4;   // s  (150 bpm max)
    private static final double LAG_MAX_S     = 2.0;   // s  (30 bpm min)
    private static final int    AUTOCORR_STEPS = 50;   // samples in lag search

    // -- Result record ---------------------------------------------------------

    public static final class SqiResult {
        public final double sqi;
        public final double perfusion;
        public final double spectral;
        public final double autocorr;
        public final String verdict;
        public final long   computeMs;

        SqiResult(double sqi, double perfusion, double spectral,
                  double autocorr, long computeMs) {
            this.sqi       = sqi;
            this.perfusion = perfusion;
            this.spectral  = spectral;
            this.autocorr  = autocorr;
            this.verdict   = sqi >= THRESHOLD ? "ACCEPT" : "REJECT";
            this.computeMs = computeMs;
        }

        @Override
        public String toString() {
            return String.format("SqiResult{sqi=%.3f verdict=%s perf=%.3f spec=%.3f autocorr=%.3f %dms}",
                sqi, verdict, perfusion, spectral, autocorr, computeMs);
        }
    }

    // -- Public API ------------------------------------------------------------

    /**
     * Compute the composite PPG SQI for a window of samples.
     *
     * @param samples  PPG samples in any physical unit (mV, AU -- unit does
     *                 not matter; only relative amplitude is used)
     * @param fs       Sampling frequency in Hz
     * @return         SqiResult with composite and individual scores
     */
    public SqiResult compute(float[] samples, double fs) {
        long t0 = System.currentTimeMillis();

        if (samples == null || samples.length < 20 || fs <= 0) {
            return new SqiResult(0.0, 0.0, 0.0, 0.0,
                                 System.currentTimeMillis() - t0);
        }

        // Convert to double and remove DC offset for spectral/autocorr analysis
        double[] x    = toDouble(samples);
        double[] xDc  = removeDc(x);

        double perf   = computePerfusion(x);
        double spec   = computeSpectral(xDc, fs);
        double autocorr = computeAutocorr(xDc, fs);

        double sqi    = clip(0.20 * perf + 0.30 * spec + 0.50 * autocorr);
        long elapsed  = System.currentTimeMillis() - t0;

        return new SqiResult(sqi, perf, spec, autocorr, elapsed);
    }

    // -- Measure 1: Perfusion index --------------------------------------------

    /**
     * AC/DC amplitude ratio, normalized to [0, 1].
     * Perfect score (1.0) at perfusion index >= 50%.
     */
    private double computePerfusion(double[] x) {
        double min = x[0], max = x[0], sum = 0.0;
        for (double v : x) {
            if (v < min) min = v;
            if (v > max) max = v;
            sum += v;
        }
        double dc = Math.abs(sum / x.length) + 1e-9;
        double ac = max - min;
        double perf = (ac / dc) / 0.5;  // normalize: 50% perf = score 1.0
        return clip(perf);
    }

    // -- Measure 2: Spectral band power ratio ----------------------------------

    /**
     * Fraction of signal power in the PPG physiological band (0.5-4.0 Hz),
     * computed via Welch's method. Normalized: 40% in band = score 1.0.
     */
    private double computeSpectral(double[] x, double fs) {
        int nperseg = Math.min(x.length, Math.max((int)(fs * 2), 64));

        double[][] psdResult = welchPsd(x, fs, nperseg);
        if (psdResult == null) return 0.0;

        double[] freqs = psdResult[0];
        double[] psd   = psdResult[1];

        double total = 1e-12;
        double band  = 0.0;
        for (int i = 0; i < freqs.length; i++) {
            total += psd[i];
            if (freqs[i] >= PPG_BAND_LOW && freqs[i] <= PPG_BAND_HIGH) {
                band += psd[i];
            }
        }

        double ratio = (band / total) / 0.4;  // normalize: 40% in band = 1.0
        return clip(ratio);
    }

    /**
     * Welch's method PSD estimate.
     * Splits signal into 50%-overlapping Hann-windowed segments,
     * computes FFT of each, averages the power spectra.
     *
     * @return double[2][] where [0] = frequencies, [1] = PSD values,
     *         or null if insufficient data.
     */
    private double[][] welchPsd(double[] x, double fs, int nperseg) {
        int n    = x.length;
        int step = nperseg / 2;  // 50% overlap

        // Hann window coefficients
        double[] hann     = new double[nperseg];
        double   winPower = 0.0;
        for (int i = 0; i < nperseg; i++) {
            hann[i]   = 0.5 * (1.0 - Math.cos(2.0 * Math.PI * i / nperseg));
            winPower += hann[i] * hann[i];
        }
        if (winPower < 1e-12) return null;

        // FFT size: next power of 2 >= nperseg
        int fftSize = nextPow2(nperseg);
        int specLen = fftSize / 2 + 1;  // one-sided spectrum length

        double[] accumPsd = new double[specLen];
        int      nSeg     = 0;

        double[] fftRe = new double[fftSize];
        double[] fftIm = new double[fftSize];

        int pos = 0;
        while (pos + nperseg <= n) {
            // Fill and window
            for (int i = 0; i < fftSize; i++) {
                if (i < nperseg) {
                    fftRe[i] = x[pos + i] * hann[i];
                } else {
                    fftRe[i] = 0.0;
                }
                fftIm[i] = 0.0;
            }

            // In-place radix-2 FFT
            fft(fftRe, fftIm, fftSize);

            // Accumulate one-sided power spectrum
            for (int i = 0; i < specLen; i++) {
                double power = (fftRe[i]*fftRe[i] + fftIm[i]*fftIm[i])
                               / (winPower * fs);
                if (i > 0 && i < specLen - 1) power *= 2.0; // double for one-sided
                accumPsd[i] += power;
            }

            nSeg++;
            pos += step;
        }

        if (nSeg == 0) return null;

        // Average and compute frequency axis
        double[] freqs = new double[specLen];
        double[] psd   = new double[specLen];
        for (int i = 0; i < specLen; i++) {
            freqs[i] = i * fs / fftSize;
            psd[i]   = accumPsd[i] / nSeg;
        }

        return new double[][]{freqs, psd};
    }

    // -- Measure 3: Autocorrelation peak score ---------------------------------

    /**
     * Peak normalized autocorrelation in the cardiac lag range [0.4s, 2.0s].
     * Sampled at AUTOCORR_STEPS evenly-spaced lags for efficiency.
     */
    private double computeAutocorr(double[] x, double fs) {
        int n      = x.length;
        int lagMin = Math.max(1, (int)(LAG_MIN_S * fs));
        int lagMax = Math.min(n - 1, (int)(LAG_MAX_S * fs));

        if (lagMin >= lagMax) return 0.0;

        // Denominator: sum of squared values
        double norm = 1e-12;
        for (double v : x) norm += v * v;

        int    step   = Math.max(1, (lagMax - lagMin) / AUTOCORR_STEPS);
        double maxAc  = 0.0;

        for (int lag = lagMin; lag < lagMax; lag += step) {
            double dot = 0.0;
            int    len = n - lag;
            for (int i = 0; i < len; i++) {
                dot += x[i] * x[i + lag];
            }
            double ac = dot / norm;
            if (ac > maxAc) maxAc = ac;
        }

        return clip(maxAc);
    }

    // -- FFT (radix-2 DIT, in-place, Cooley-Tukey) ----------------------------

    /**
     * In-place radix-2 decimation-in-time FFT.
     * Input: re[] and im[] of length n (must be a power of 2).
     * Output: re[] and im[] contain the complex DFT coefficients.
     */
    private void fft(double[] re, double[] im, int n) {
        // Bit-reversal permutation
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                double tmp = re[i]; re[i] = re[j]; re[j] = tmp;
                       tmp = im[i]; im[i] = im[j]; im[j] = tmp;
            }
        }
        // Cooley-Tukey butterfly
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2.0 * Math.PI / len;
            double wRe = Math.cos(ang);
            double wIm = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double curRe = 1.0, curIm = 0.0;
                for (int j = 0; j < len / 2; j++) {
                    double uRe = re[i + j];
                    double uIm = im[i + j];
                    double vRe = re[i+j+len/2]*curRe - im[i+j+len/2]*curIm;
                    double vIm = re[i+j+len/2]*curIm + im[i+j+len/2]*curRe;
                    re[i + j]         = uRe + vRe;
                    im[i + j]         = uIm + vIm;
                    re[i+j+len/2]     = uRe - vRe;
                    im[i+j+len/2]     = uIm - vIm;
                    double nextRe     = curRe*wRe - curIm*wIm;
                    curIm             = curRe*wIm + curIm*wRe;
                    curRe             = nextRe;
                }
            }
        }
    }

    // -- Utilities -------------------------------------------------------------

    private double[] toDouble(float[] x) {
        double[] d = new double[x.length];
        for (int i = 0; i < x.length; i++) d[i] = x[i];
        return d;
    }

    private double[] removeDc(double[] x) {
        double mean = 0.0;
        for (double v : x) mean += v;
        mean /= x.length;
        double[] out = new double[x.length];
        for (int i = 0; i < x.length; i++) out[i] = x[i] - mean;
        return out;
    }

    private double clip(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    private int nextPow2(int n) {
        int p = 1;
        while (p < n) p <<= 1;
        return p;
    }

    // -- Self-test (callable from main or unit test) ----------------------------

    /**
     * Run three validation cases and print results.
     * Called via: java -cp ... PpgSqiEngine
     * Expected:
     *   clean PPG    -> ACCEPT (SQI near 0.91)
     *   flat/sensor  -> REJECT (SQI near 0.10)
     *   pure noise   -> REJECT (SQI near 0.31)
     */
    public static void main(String[] args) {
        PpgSqiEngine engine = new PpgSqiEngine();
        double fs = 100.0;
        int    n  = 500;

        float[] cleanPpg  = generateSine(n, fs, 1.2, 1.0f, 0.05f);
        float[] flat      = generateFlat(n, 0.5f, 0.001f);
        float[] noise     = generateNoise(n);

        String[][] cases = {
            {"clean PPG",   "ACCEPT"},
            {"flat/sensor", "REJECT"},
            {"pure noise",  "REJECT"},
        };
        float[][] signals = {cleanPpg, flat, noise};

        boolean allPass = true;
        for (int i = 0; i < cases.length; i++) {
            SqiResult r = engine.compute(signals[i], fs);
            boolean pass = r.verdict.equals(cases[i][1]);
            allPass &= pass;
            System.out.printf("[%s] %-15s sqi=%.3f perf=%.3f spec=%.3f autocorr=%.3f -> %s%n",
                pass ? "PASS" : "FAIL", cases[i][0],
                r.sqi, r.perfusion, r.spectral, r.autocorr, r.verdict);
        }
        System.out.println(allPass ? "All tests passed." : "SOME TESTS FAILED.");
        System.exit(allPass ? 0 : 1);
    }

    // Signal generators for self-test
    private static float[] generateSine(int n, double fs, double freq,
                                         float amp, float noiseAmp) {
        float[] x = new float[n];
        java.util.Random rng = new java.util.Random(42);
        for (int i = 0; i < n; i++) {
            x[i] = (float)(amp * Math.sin(2 * Math.PI * freq * i / fs)
                    + noiseAmp * rng.nextGaussian());
        }
        return x;
    }

    private static float[] generateFlat(int n, float level, float noiseAmp) {
        float[] x = new float[n];
        java.util.Random rng = new java.util.Random(42);
        for (int i = 0; i < n; i++) {
            x[i] = level + (float)(noiseAmp * rng.nextGaussian());
        }
        return x;
    }

    private static float[] generateNoise(int n) {
        float[] x = new float[n];
        java.util.Random rng = new java.util.Random(42);
        for (int i = 0; i < n; i++) {
            x[i] = (float)rng.nextGaussian();
        }
        return x;
    }
}
