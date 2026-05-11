package org.mdpnp.apps.testapp.physionet;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * WfdbMdcMapper
 * =============
 * Shared utility for mapping WFDB signal names to ISO/IEEE 11073-10101
 * MDC nomenclature codes as used throughout the OpenICE platform.
 *
 * MDC identifiers are drawn from:
 *   - rosetta.idl  (rosetta.MDC_XXX constants)
 *   - ice.idl      (ice.MDC_XXX constants for ICE-specific extensions)
 * both located in data-types/x73-idl/src/main/idl/ in the mdpnp repo.
 *
 * The mapping format mirrors the existing device .map files in
 * interop-lab/demo-devices (e.g. bernoulli.map, intellivue.map):
 *   deviceSignalName  →  rosetta.MDC_XXX  →  N|W
 * where N = Numeric (ice.Numeric topic) and W = Waveform (ice.SampleArray topic).
 *
 * Matching strategy (in priority order):
 *   1. Exact match (case-insensitive) against the canonical WFDB name
 *   2. Alias match against known alternate spellings across PhysioNet databases
 *   3. No match → MatchConfidence.NONE
 *
 * Usage:
 *   Optional<MdcMapping> m = WfdbMdcMapper.lookup("PLETH");
 *   m.ifPresent(mapping -> {
 *       String metricId = mapping.mdcId;      // "MDC_PULS_OXIM_PLETH"
 *       String unit     = mapping.mdcUnit;    // "MDC_DIM_MILLI_VOLT" etc.
 *       boolean isWave  = mapping.isWaveform; // true
 *       MatchConfidence c = mapping.confidence;
 *   });
 *
 * Both PhysioNetBrowserApp (for display annotation) and the future
 * PhysioNetReplayDevice (for DDS publishing) depend on this class.
 * All changes to the mapping table should be made here only.
 */
public final class WfdbMdcMapper {

    private WfdbMdcMapper() {}

    // -----------------------------------------------------------------------
    // Public data types
    // -----------------------------------------------------------------------

    public enum MatchConfidence {
        /** Exact canonical WFDB name match. */
        EXACT,
        /** Known alias or alternate spelling — high confidence. */
        ALIAS,
        /** No MDC mapping found. */
        NONE
    }

    public enum SignalType {
        /** Published on ice.Numeric DDS topic. */
        NUMERIC,
        /** Published on ice.SampleArray DDS topic. */
        WAVEFORM
    }

    /**
     * An MDC mapping result for a single WFDB signal.
     */
    public static final class MdcMapping {
        /** The canonical WFDB signal name this was matched from. */
        public final String wfdbName;
        /** The MDC metric identifier string, e.g. "MDC_PULS_OXIM_PLETH". */
        public final String mdcId;
        /** The MDC unit code string, e.g. "MDC_DIM_MILLI_VOLT". */
        public final String mdcUnit;
        /** Whether this signal is a waveform (SampleArray) or numeric. */
        public final SignalType signalType;
        /** Confidence level of the match. */
        public final MatchConfidence confidence;
        /**
         * Human-readable description of the physiological parameter.
         * Suitable for display in the browser signal info panel.
         */
        public final String description;

        MdcMapping(String wfdbName, String mdcId, String mdcUnit,
                   SignalType signalType, MatchConfidence confidence,
                   String description) {
            this.wfdbName   = wfdbName;
            this.mdcId      = mdcId;
            this.mdcUnit    = mdcUnit;
            this.signalType = signalType;
            this.confidence = confidence;
            this.description = description;
        }

        /** Display string for the browser tree, e.g. "✅ MDC_PULS_OXIM_PLETH (Waveform)" */
        public String displayLabel() {
            String icon = confidence == MatchConfidence.EXACT   ? "✅"
                        : confidence == MatchConfidence.ALIAS   ? "⚠"
                        :                                         "⬜";
            String type = signalType == SignalType.WAVEFORM ? "Waveform" : "Numeric";
            String inferred = confidence == MatchConfidence.ALIAS ? " (inferred)" : "";
            return icon + " " + mdcId + inferred + "  [" + type + "]";
        }
    }

    // -----------------------------------------------------------------------
    // Mapping table entry (internal)
    // -----------------------------------------------------------------------

    private static final class Entry {
        final String   mdcId;
        final String   mdcUnit;
        final SignalType signalType;
        final String   description;
        final List<String> aliases; // lower-case alternate WFDB spellings

        Entry(String mdcId, String mdcUnit, SignalType signalType,
              String description, String... aliases) {
            this.mdcId       = mdcId;
            this.mdcUnit     = mdcUnit;
            this.signalType  = signalType;
            this.description = description;
            this.aliases     = new ArrayList<>();
            for (String a : aliases) this.aliases.add(a.toLowerCase(Locale.ROOT));
        }
    }

    // -----------------------------------------------------------------------
    // The mapping table
    // Key = canonical WFDB signal name, lower-cased for lookup.
    // MDC IDs match rosetta.idl / ice.idl in the mdpnp repo exactly.
    // Unit codes match units.idl.
    // -----------------------------------------------------------------------

    private static final Map<String, Entry> CANONICAL = new HashMap<>();
    private static final Map<String, String> ALIAS_TO_CANONICAL = new HashMap<>();

    static {
        // ── ECG leads (Waveforms) ──────────────────────────────────────────
        add("I",       "MDC_ECG_ELEC_POTL_I",    "MDC_DIM_MILLI_VOLT", SignalType.WAVEFORM,
            "ECG Lead I",
            "ecg", "ecg1", "lead i", "lead_i", "ekg");

        add("II",      "MDC_ECG_ELEC_POTL_II",   "MDC_DIM_MILLI_VOLT", SignalType.WAVEFORM,
            "ECG Lead II",
            "ecg2", "lead ii", "lead_ii", "ii.");

        add("III",     "MDC_ECG_ELEC_POTL_III",  "MDC_DIM_MILLI_VOLT", SignalType.WAVEFORM,
            "ECG Lead III",
            "ecg3", "lead iii", "lead_iii");

        add("AVR",     "MDC_ECG_ELEC_POTL_AVR",  "MDC_DIM_MILLI_VOLT", SignalType.WAVEFORM,
            "ECG Lead aVR",
            "avr", "aavr");

        add("AVL",     "MDC_ECG_ELEC_POTL_AVL",  "MDC_DIM_MILLI_VOLT", SignalType.WAVEFORM,
            "ECG Lead aVL",
            "avl", "aavl");

        add("AVF",     "MDC_ECG_ELEC_POTL_AVF",  "MDC_DIM_MILLI_VOLT", SignalType.WAVEFORM,
            "ECG Lead aVF",
            "avf", "aavf");

        add("V",       "MDC_ECG_ELEC_POTL_V1",   "MDC_DIM_MILLI_VOLT", SignalType.WAVEFORM,
            "ECG Precordial Lead V (generic)",
            "v1", "ecgv");

        add("V1",      "MDC_ECG_ELEC_POTL_V1",   "MDC_DIM_MILLI_VOLT", SignalType.WAVEFORM,
            "ECG Precordial Lead V1");

        add("V2",      "MDC_ECG_ELEC_POTL_V2",   "MDC_DIM_MILLI_VOLT", SignalType.WAVEFORM,
            "ECG Precordial Lead V2");

        add("V3",      "MDC_ECG_ELEC_POTL_V3",   "MDC_DIM_MILLI_VOLT", SignalType.WAVEFORM,
            "ECG Precordial Lead V3");

        add("V4",      "MDC_ECG_ELEC_POTL_V4",   "MDC_DIM_MILLI_VOLT", SignalType.WAVEFORM,
            "ECG Precordial Lead V4");

        add("V5",      "MDC_ECG_ELEC_POTL_V5",   "MDC_DIM_MILLI_VOLT", SignalType.WAVEFORM,
            "ECG Precordial Lead V5");

        add("V6",      "MDC_ECG_ELEC_POTL_V6",   "MDC_DIM_MILLI_VOLT", SignalType.WAVEFORM,
            "ECG Precordial Lead V6");

        // ── Pulse oximetry (Waveforms + Numerics) ─────────────────────────
        add("PLETH",   "MDC_PULS_OXIM_PLETH",    "MDC_DIM_DIMLESS",    SignalType.WAVEFORM,
            "Photoplethysmography (pulse oximeter)",
            "ppg", "plet", "pleth.", "spo2_wave", "spco2wave");

        add("SPO2",    "MDC_PULS_OXIM_SAT_O2",   "MDC_DIM_PERCENT",    SignalType.NUMERIC,
            "Peripheral Oxygen Saturation",
            "spo2", "sao2", "o2sat", "sat", "sp02", "%spo2", "pox");

        // ── Arterial blood pressure (Waveform + derived Numerics) ─────────
        add("ABP",     "MDC_PRESS_BLD_ART",      "MDC_DIM_MMHG",       SignalType.WAVEFORM,
            "Arterial Blood Pressure (waveform)",
            "art", "aobp", "arterial", "abp.", "ibp", "bp");

        add("ABP_SYS", "MDC_PRESS_BLD_ART_ABP_SYS", "MDC_DIM_MMHG",   SignalType.NUMERIC,
            "Arterial BP Systolic",
            "abpsys", "sys", "sbp", "art_sys");

        add("ABP_DIAS","MDC_PRESS_BLD_ART_ABP_DIA", "MDC_DIM_MMHG",    SignalType.NUMERIC,
            "Arterial BP Diastolic",
            "abpdias", "dias", "dbp", "art_dias");

        add("ABP_MEAN","MDC_PRESS_BLD_ART_ABP_MEAN","MDC_DIM_MMHG",    SignalType.NUMERIC,
            "Arterial BP Mean",
            "abpmean", "map", "mean", "art_mean");

        // ── Non-invasive blood pressure ────────────────────────────────────
        add("NIBP_SYS","MDC_PRESS_CUFF_SYS",     "MDC_DIM_MMHG",       SignalType.NUMERIC,
            "Non-Invasive BP Systolic",
            "nbpsys", "nsbp", "cuff_sys");

        add("NIBP_DIAS","MDC_PRESS_CUFF_DIA",    "MDC_DIM_MMHG",       SignalType.NUMERIC,
            "Non-Invasive BP Diastolic",
            "nbpdias", "ndbp", "cuff_dias");

        add("NIBP_MEAN","MDC_PRESS_CUFF_MEAN",   "MDC_DIM_MMHG",       SignalType.NUMERIC,
            "Non-Invasive BP Mean",
            "nbpmean", "cuff_mean");

        // ── Heart rate / Pulse rate ────────────────────────────────────────
        add("HR",      "MDC_ECG_HEART_RATE",     "MDC_DIM_BEAT_PER_MIN", SignalType.NUMERIC,
            "Heart Rate (ECG-derived)",
            "heartrate", "heart_rate", "ecg_hr");

        add("PULSE",   "MDC_PULS_OXIM_PULS_RATE","MDC_DIM_BEAT_PER_MIN", SignalType.NUMERIC,
            "Pulse Rate (oximetry-derived)",
            "pulserate", "pulse_rate", "pr");

        // ── Respiration ────────────────────────────────────────────────────
        add("RESP",    "MDC_RESP_RATE",           "MDC_DIM_RESP_PER_MIN", SignalType.NUMERIC,
            "Respiratory Rate",
            "rr", "rrate", "resp_rate", "respiratoryrate");

        add("AIRFLOW", "MDC_AWAY_FLOW_EXP",       "MDC_DIM_L_PER_MIN",   SignalType.WAVEFORM,
            "Airway Flow (expiratory)",
            "flow", "af");

        // ── CO2 ────────────────────────────────────────────────────────────
        add("CO2",     "MDC_AWAY_CO2",            "MDC_DIM_MMHG",        SignalType.WAVEFORM,
            "CO2 Waveform (capnography)",
            "capno", "capnograph", "co2_wave");

        add("ETCO2",   "MDC_AWAY_CO2_ET",         "MDC_DIM_MMHG",        SignalType.NUMERIC,
            "End-Tidal CO2",
            "etco2", "et_co2", "endtidalco2");

        // ── Intracranial pressure ──────────────────────────────────────────
        add("ICP",     "MDC_PRESS_INTRA_CRAN",   "MDC_DIM_MMHG",        SignalType.WAVEFORM,
            "Intracranial Pressure",
            "icp.");

        // ── Central venous pressure ────────────────────────────────────────
        add("CVP",     "MDC_PRESS_VEN_CVP",       "MDC_DIM_MMHG",        SignalType.WAVEFORM,
            "Central Venous Pressure",
            "cvp.");

        // ── Temperature ────────────────────────────────────────────────────
        add("TEMP",    "MDC_TEMP_BLD",            "MDC_DIM_DEGC",        SignalType.NUMERIC,
            "Blood/Body Temperature",
            "t1", "temp1", "temperature", "bt");

        // ── SpO2 / EEG derivatives sometimes found in PhysioNet ───────────
        add("EEG",     "MDC_EEG_ELEC_POTL",      "MDC_DIM_MICRO_VOLT",  SignalType.WAVEFORM,
            "Electroencephalogram",
            "eeg1", "eeg2");

        add("EMG",     "MDC_EMG_ELEC_POTL",       "MDC_DIM_MICRO_VOLT",  SignalType.WAVEFORM,
            "Electromyogram",
            "emg1", "emg2");
    }

    // -----------------------------------------------------------------------
    // Registration helper
    // -----------------------------------------------------------------------

    private static void add(String canonical, String mdcId, String mdcUnit,
                             SignalType type, String description,
                             String... aliases) {
        String key = canonical.toLowerCase(Locale.ROOT);
        Entry e = new Entry(mdcId, mdcUnit, type, description, aliases);
        CANONICAL.put(key, e);
        // Register all aliases pointing back to the canonical key
        for (String alias : e.aliases) {
            ALIAS_TO_CANONICAL.put(alias, key);
        }
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Look up a WFDB signal name and return its MDC mapping if one exists.
     *
     * @param wfdbSignalName  The signal name as it appears in the WFDB .hea file.
     * @return Optional containing the MdcMapping, or empty if no mapping found.
     */
    public static Optional<MdcMapping> lookup(String wfdbSignalName) {
        if (wfdbSignalName == null || wfdbSignalName.isBlank()) {
            return Optional.empty();
        }
        String key = wfdbSignalName.trim().toLowerCase(Locale.ROOT);

        // 1 — exact canonical match
        Entry e = CANONICAL.get(key);
        if (e != null) {
            return Optional.of(new MdcMapping(
                wfdbSignalName, e.mdcId, e.mdcUnit, e.signalType,
                MatchConfidence.EXACT, e.description));
        }

        // 2 — alias match
        String canonicalKey = ALIAS_TO_CANONICAL.get(key);
        if (canonicalKey != null) {
            e = CANONICAL.get(canonicalKey);
            if (e != null) {
                return Optional.of(new MdcMapping(
                    wfdbSignalName, e.mdcId, e.mdcUnit, e.signalType,
                    MatchConfidence.ALIAS, e.description));
            }
        }

        return Optional.empty();
    }

    /**
     * Convenience: returns true if this signal name has any MDC mapping.
     */
    public static boolean isMapped(String wfdbSignalName) {
        return lookup(wfdbSignalName).isPresent();
    }

    /**
     * Returns a summary of mapping coverage for a list of signal names.
     * Used by the browser to compute the per-record coverage badge.
     *
     * @param signalNames  List of WFDB signal names from the .hea header.
     * @return CoverageSummary with counts and a display string.
     */
    public static CoverageSummary summarize(List<String> signalNames) {
        int exact   = 0;
        int alias   = 0;
        int unmapped = 0;
        for (String name : signalNames) {
            Optional<MdcMapping> m = lookup(name);
            if (m.isEmpty()) {
                unmapped++;
            } else if (m.get().confidence == MatchConfidence.EXACT) {
                exact++;
            } else {
                alias++;
            }
        }
        return new CoverageSummary(exact, alias, unmapped, signalNames.size());
    }

    // -----------------------------------------------------------------------
    // CoverageSummary — per-record mapping badge
    // -----------------------------------------------------------------------

    public static final class CoverageSummary {
        public final int exact;
        public final int alias;
        public final int unmapped;
        public final int total;

        CoverageSummary(int exact, int alias, int unmapped, int total) {
            this.exact    = exact;
            this.alias    = alias;
            this.unmapped = unmapped;
            this.total    = total;
        }

        public int mapped() { return exact + alias; }

        /**
         * Short badge string for display in the record list or signal panel.
         * Examples:  "✅ 4/4 mapped"   "⚠ 3/5 mapped"   "⬜ 0/2 mapped"
         */
        public String badge() {
            int mapped = mapped();
            String icon = mapped == total && unmapped == 0 ? "✅"
                        : mapped  > 0                      ? "⚠"
                        :                                    "⬜";
            return icon + " " + mapped + "/" + total + " signals mapped";
        }
    }
}
