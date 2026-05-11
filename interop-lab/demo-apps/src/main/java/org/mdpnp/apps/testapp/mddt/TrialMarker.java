package org.mdpnp.apps.testapp.mddt;

/**
 * TrialMarker — the sole communication contract between PumpActuatorApp
 * and DDSDataLoggerApp.
 *
 * Published to the DDS bus by PumpActuatorApp whenever a trial event occurs.
 * Subscribed to by DDSDataLoggerApp to correlate commanded values with
 * observed pump responses and compute latency.
 *
 * This class is the Java representation of what would be defined in IDL as:
 *
 *   struct TrialMarker {
 *       long long  runId;        // Monotonic run ID (increments each Start press)
 *       long       trialNum;     // Trial number within run (1-based; 0 for run-level events)
 *       string     eventType;    // RUN_START | TRIAL_START | CMD_SENT | RUN_COMPLETE | RUN_STOPPED
 *       string     cmdType;      // RATE_CHANGE | PAUSE | RESUME | VTBI_SET | BOLUS |
 *                                //   BAD_CMD_OVER | BAD_CMD_UNDER | RUN_START | RUN_COMPLETE
 *       float      value;        // Primary commanded value (rate mL/hr, VTBI mL, etc.)
 *       string     params;       // Additional key=value details (e.g. "bolusRate=200 bolusVolume=5")
 *       long long  timestampMs;  // Wall-clock ms when the actuator sent the command
 *   };
 *
 * === Topic name ===
 *   "MDDTTrialMarker"
 *
 * === QoS ===
 *   Reliability: RELIABLE — the logger must not miss CMD_SENT events.
 *   Durability:  TRANSIENT_LOCAL — late-joining logger receives recent markers.
 *   History:     KEEP_LAST depth=100
 *
 * === Why a separate DDS topic rather than a log file or socket? ===
 *   Using DDS means the actuator and logger can run in separate JVM processes,
 *   on separate machines, or be replaced independently. It also means the
 *   TrialMarker stream is itself observable by any future MDDT tool (e.g. a
 *   real-time dashboard) without any code changes to either app.
 *
 * NOTE: In the full OpenICE integration, this class would be generated from
 * the IDL definition above using RTI DDS code generation tools, and
 * TrialMarkerDataWriter/DataReader/TypeSupport would be generated classes.
 * The stubs below exist to allow the app code to compile and be reviewed
 * before the IDL and build system integration is completed.
 *
 * TODO: Define TrialMarker.idl, add to the ice module IDL build, regenerate,
 * and replace these stubs with the generated classes.
 */
public class TrialMarker {

    /** Monotonically increasing ID for each run (each press of Start in PumpActuatorApp). */
    public long runId;

    /**
     * Trial number within the run (1-based).
     * 0 for run-level events (RUN_START, RUN_COMPLETE, RUN_STOPPED).
     */
    public int trialNum;

    /**
     * Event type:
     *   RUN_START     — emitted when the actuator starts a new run
     *   TRIAL_START   — emitted at the beginning of each trial (before command)
     *   CMD_SENT      — emitted immediately after the DDS objective is written
     *   RUN_COMPLETE  — emitted after all N trials complete normally
     *   RUN_STOPPED   — emitted if the user presses Stop early
     */
    public String eventType;

    /**
     * Command type:
     *   RATE_CHANGE | PAUSE | RESUME | VTBI_SET | BOLUS |
     *   BAD_CMD_OVER | BAD_CMD_UNDER
     * For run-level events (RUN_START, RUN_COMPLETE, RUN_STOPPED), this
     * field equals the eventType string.
     */
    public String cmdType;

    /**
     * Primary commanded value.
     * For RATE_CHANGE: the commanded flow rate in mL/hr.
     * For VTBI_SET:    the commanded VTBI in mL.
     * For BOLUS:       the bolus volume in mL.
     * For PAUSE/RESUME/BAD_CMD: 0 or the out-of-range value.
     */
    public float value;

    /**
     * Additional parameters as a single key=value string.
     * Example: "bolusRate=200 bolusVolume=5" for a bolus command.
     * Kept as a single string to avoid IDL complexity; the logger parses it.
     */
    public String params;

    /**
     * Wall-clock timestamp in milliseconds when the actuator sent the command.
     * This is the reference time the logger uses to compute latency:
     *   latency = observed_state_change_time - timestampMs
     */
    public long timestampMs;

    public TrialMarker() {
        eventType = "";
        cmdType   = "";
        params    = "";
    }

    @Override
    public String toString() {
        return String.format("TrialMarker{runId=%d trial=%d event=%s cmd=%s value=%.2f ts=%d}",
            runId, trialNum, eventType, cmdType, value, timestampMs);
    }
}
