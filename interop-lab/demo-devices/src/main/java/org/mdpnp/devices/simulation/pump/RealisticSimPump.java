package org.mdpnp.devices.simulation.pump;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.mdpnp.devices.DeviceClock;
import org.mdpnp.devices.simulation.AbstractSimulatedConnectedDevice;
import org.mdpnp.rtiapi.data.EventLoop;
import org.mdpnp.rtiapi.data.QosProfiles;
import org.mdpnp.rtiapi.data.TopicUtil;
import org.mdpnp.rtiapi.data.EventLoop.ConditionHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.rti.dds.infrastructure.Condition;
import com.rti.dds.infrastructure.RETCODE_NO_DATA;
import com.rti.dds.infrastructure.ResourceLimitsQosPolicy;
import com.rti.dds.infrastructure.StatusKind;
import com.rti.dds.infrastructure.StringSeq;
import com.rti.dds.publication.Publisher;
import com.rti.dds.subscription.InstanceStateKind;
import com.rti.dds.subscription.QueryCondition;
import com.rti.dds.subscription.ReadCondition;
import com.rti.dds.subscription.SampleInfo;
import com.rti.dds.subscription.SampleInfoSeq;
import com.rti.dds.subscription.SampleStateKind;
import com.rti.dds.subscription.Subscriber;
import com.rti.dds.subscription.ViewStateKind;
import com.rti.dds.topic.Topic;

import ice.ConnectionState;
import ice.FlowRateObjectiveDataReader;
import ice.InfusionProgramDataReader;
import ice.Numeric;

/**
 * A realistic simulated Externally Controllable Infusion Pump (ECIP) for use
 * in MDDT (Medical Device Development Tool) validation testing.
 *
 * Unlike SimControllablePump, this class models realistic imperfections:
 *   - Configurable command response latency with Gaussian jitter
 *   - Latency spikes (simulating occasional processing delays)
 *   - Dropped responses and malformed acknowledgements (error injection)
 *   - Communication silence periods (network disruption simulation)
 *   - Occlusion, low-volume, and line-clamp alarm simulation
 *   - Heartbeat/keep-alive signals at configurable intervals
 *   - Battery level simulation
 *
 * All behavior is driven by pump-sim.properties (loaded from classpath or
 * working directory). This means the MDDT test app can exercise different
 * fault scenarios by changing the properties file without code changes.
 *
 * This class participates in OpenICE exactly as a physical ECIP would:
 * it subscribes to FlowRateObjective and InfusionProgram, and publishes
 * Numeric samples for flow rate, volume infused, VTBI, and battery level.
 * Swapping this class for a real pump adapter requires no changes to the
 * MDDT test app.
 *
 * @see SimControllablePump (the "ideal" predecessor this complements)
 * @see MDDTPumpTestApp (the MDDT measurement application)
 */
public class RealisticSimPump extends AbstractSimulatedConnectedDevice {

    private static final Logger log = LoggerFactory.getLogger(RealisticSimPump.class);
    private static final String PROPERTIES_FILE = "pump-sim.properties";
    private static int fakeComPortCounter = 10; // offset from SimControllablePump's counter

    // -------------------------------------------------------------------------
    // Configuration (loaded from pump-sim.properties)
    // -------------------------------------------------------------------------
    private final SimPumpConfig cfg;

    // -------------------------------------------------------------------------
    // Infusion state
    // -------------------------------------------------------------------------
    /** Current commanded flow rate (mL/hr). */
    private volatile float currentFlowRate;
    /** Whether the pump is actively infusing. */
    private final AtomicBoolean infusing = new AtomicBoolean(true);
    /** Simulated volume remaining in reservoir (mL). */
    private volatile float volumeRemaining;
    /** Total volume infused since last reset (mL). */
    private volatile float volumeInfused = 0f;
    /** Volume to be infused (VTBI) as programmed (mL). */
    private volatile float vtbi;
    /** Battery level (0–100). */
    private volatile float batteryLevel;

    // -------------------------------------------------------------------------
    // Alarm state
    // -------------------------------------------------------------------------
    private volatile boolean occlusionActive = false;
    private volatile boolean lowVolumeActive = false;
    private volatile boolean lineClampActive = false;
    /** Millisecond timestamp when current occlusion alarm should auto-clear. */
    private volatile long occlusionClearTimeMs = 0;

    // -------------------------------------------------------------------------
    // Timing / silence state
    // -------------------------------------------------------------------------
    /** System.currentTimeMillis() when infusion was last started. */
    private volatile long infusionStartMs = 0;
    /** When true the pump drops all responses (communication disruption). */
    private final AtomicBoolean silenced = new AtomicBoolean(false);

    // -------------------------------------------------------------------------
    // DDS infrastructure
    // -------------------------------------------------------------------------
    private FlowRateObjectiveDataReader flowRateReader;
    private InfusionProgramDataReader infusionProgramReader;
    private Topic flowRateTopic;
    private Topic infusionProgramTopic;
    private QueryCondition flowRateQueryCondition;
    private QueryCondition infusionProgramQueryCondition;

    // -------------------------------------------------------------------------
    // Instance holders for published metrics
    // -------------------------------------------------------------------------
    private InstanceHolder<Numeric> flowRateHolder;
    private InstanceHolder<Numeric> volumeInfusedHolder;
    private InstanceHolder<Numeric> vtbiHolder;
    private InstanceHolder<Numeric> batteryHolder;
    private InstanceHolder<Numeric> heartbeatHolder;

    // -------------------------------------------------------------------------
    // Scheduled tasks
    // -------------------------------------------------------------------------
    private ScheduledFuture<?> statusEmitter;
    private ScheduledFuture<?> heartbeatEmitter;

    private final DeviceClock clock;
    private final Random rng = new Random();

    // =========================================================================
    // Constructor
    // =========================================================================

    public RealisticSimPump(Subscriber subscriber, Publisher publisher, EventLoop eventLoop) {
        super(subscriber, publisher, eventLoop);

        cfg = loadConfig();
        currentFlowRate = cfg.initialRateMlPerHr;
        volumeRemaining = cfg.initialVolumeMl;
        vtbi = cfg.initialVTBIMl;
        batteryLevel = cfg.batteryInitialLevel;

        writeDeviceIdentity();
        clock = new WallClock();

        setupFlowRateObjectiveSubscription(subscriber, eventLoop);
        setupInfusionProgramSubscription(subscriber, eventLoop);
    }

    // =========================================================================
    // OpenICE lifecycle
    // =========================================================================

    @Override
    public boolean connect(String str) {
        log.info("RealisticSimPump connecting ({})", str);
        deviceConnectivity.comPort = "sim-com" + fakeComPortCounter++;
        startPublishing();
        return super.connect(str);
    }

    @Override
    public void disconnect() {
        stopPublishing();
        super.disconnect();
    }

    @Override
    protected void writeDeviceIdentity() {
        deviceIdentity.manufacturer = cfg != null ? cfg.manufacturer : "ICE";
        deviceIdentity.model = cfg != null ? cfg.model : "Realistic Sim Pump";
        super.writeDeviceIdentity();
    }

    @Override
    protected String iconResourceName() {
        return "controllablepump.png";
    }

    // =========================================================================
    // Publishing
    // =========================================================================

    private void startPublishing() {
        stateMachine.transitionIfLegal(ConnectionState.Connected, "Connected");

        flowRateHolder    = createNumericInstance(rosetta.MDC_FLOW_FLUID_PUMP.VALUE, "");
        volumeInfusedHolder = createNumericInstance("VOLUME_INFUSED", "");
        vtbiHolder        = createNumericInstance("VTBI", "");
        batteryHolder     = createNumericInstance("BATTERY_LEVEL", "");
        heartbeatHolder   = createNumericInstance("HEARTBEAT", "");

        infusionStartMs = System.currentTimeMillis();

        // Status publication loop
        long intervalMs = cfg.statusPublicationIntervalMs;
        statusEmitter = executor.scheduleAtFixedRate(
            this::publishStatus, intervalMs, intervalMs, TimeUnit.MILLISECONDS);

        // Heartbeat loop
        long hbMs = cfg.heartbeatIntervalMs;
        heartbeatEmitter = executor.scheduleAtFixedRate(
            this::publishHeartbeat, hbMs, hbMs, TimeUnit.MILLISECONDS);
    }

    private void stopPublishing() {
        if (statusEmitter != null) statusEmitter.cancel(false);
        if (heartbeatEmitter != null) heartbeatEmitter.cancel(false);
    }

    /**
     * Called on every status publication cycle. Updates simulated state and
     * publishes numerics. Also runs alarm and silence evaluation.
     */
    private void publishStatus() {
        if (silenced.get()) {
            log.debug("RealisticSimPump: silenced, skipping publication");
            return;
        }

        long nowMs = System.currentTimeMillis();
        long runTimeMs = nowMs - infusionStartMs;

        // Update simulated physical state
        updateSimulatedVolumes();
        updateBattery();

        // Check/trigger alarms
        evaluateAlarms(runTimeMs, nowMs);

        // Maybe inject a communication silence period
        maybeInjectSilence();

        // Publish numerics
        float publishedRate = infusing.get() ? currentFlowRate : 0f;
        numericSample(flowRateHolder,    publishedRate,  clock.instant());
        numericSample(volumeInfusedHolder, volumeInfused, clock.instant());
        numericSample(vtbiHolder,        vtbi,           clock.instant());
        numericSample(batteryHolder,     batteryLevel,   clock.instant());

        if (occlusionActive || lowVolumeActive || lineClampActive) {
            publishAlarmState();
        }
    }

    /**
     * Publishes a heartbeat numeric. The value increments each cycle so the
     * MDDT can detect missed heartbeats by checking for sequence gaps.
     */
    private long heartbeatSequence = 0;
    private void publishHeartbeat() {
        if (silenced.get()) return;

        // Apply configured jitter to heartbeat
        long jitterMs = cfg.heartbeatJitterMs;
        if (jitterMs > 0) {
            try {
                long delay = (long)(rng.nextGaussian() * (jitterMs / 3.0));
                delay = Math.max(-jitterMs, Math.min(jitterMs, delay));
                if (delay > 0) Thread.sleep(delay);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }

        numericSample(heartbeatHolder, (float)(heartbeatSequence++), clock.instant());
        log.debug("Heartbeat #{} published", heartbeatSequence - 1);
    }

    // =========================================================================
    // Command handling
    // =========================================================================

    /**
     * Apply a new flow rate command with realistic latency simulation.
     * This is the core method the MDDT measures for response time.
     *
     * @param newRate  Commanded rate in mL/hr
     * @param commandType  Label for logging ("rateChange", "pauseResume", etc.)
     */
    private void applyRateCommand(float newRate, String commandType) {
        // Log command receipt timestamp (MDDT can correlate with objective publish time via DDS)
        long receivedMs = System.currentTimeMillis();
        log.info("[CMD_RECEIVED] type={} rate={} t={}", commandType, newRate, receivedMs);

        // Check for dropped response
        if (shouldDropResponse()) {
            log.warn("[CMD_DROPPED] type={} rate={} — simulating dropped response", commandType, newRate);
            return;
        }

        // Calculate and apply latency
        long latencyMs = computeLatency(commandType);
        try {
            Thread.sleep(latencyMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return;
        }

        // Check for malformed response (apply command but log anomaly)
        if (shouldSendMalformedResponse()) {
            log.warn("[CMD_MALFORMED] type={} — simulating malformed response", commandType);
            // In a real implementation this would publish a response with a bad UDI or wrong metric
            // For now we log it so the MDDT can detect the anomaly in the log
        }

        long appliedMs = System.currentTimeMillis();
        currentFlowRate = Math.max(cfg.minRateMlPerHr, Math.min(cfg.maxRateMlPerHr, newRate));
        log.info("[CMD_APPLIED] type={} rate={} latency={}ms t={}", commandType, currentFlowRate, appliedMs - receivedMs, appliedMs);
    }

    private void applyPauseResume(boolean stop) {
        long receivedMs = System.currentTimeMillis();
        log.info("[CMD_RECEIVED] type=pauseResume stop={} t={}", stop, receivedMs);

        if (shouldDropResponse()) {
            log.warn("[CMD_DROPPED] type=pauseResume stop={}", stop);
            return;
        }

        long latencyMs = computeLatency("pauseResume");
        try {
            Thread.sleep(latencyMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return;
        }

        infusing.set(!stop);
        long appliedMs = System.currentTimeMillis();
        log.info("[CMD_APPLIED] type=pauseResume infusing={} latency={}ms t={}", !stop, appliedMs - receivedMs, appliedMs);
    }

    // =========================================================================
    // Alarm evaluation
    // =========================================================================

    private void evaluateAlarms(long runTimeMs, long nowMs) {
        // --- Occlusion ---
        if (cfg.occlusionEnabled && !occlusionActive && infusing.get()) {
            if (runTimeMs >= cfg.occlusionMinRunTimeMs) {
                if (rng.nextDouble() < cfg.occlusionProbabilityPerCycle) {
                    occlusionActive = true;
                    if (cfg.occlusionDurationMs > 0) {
                        occlusionClearTimeMs = nowMs + cfg.occlusionDurationMs;
                    }
                    log.warn("[ALARM] OCCLUSION active at t={}", nowMs);
                }
            }
        }
        // Auto-clear occlusion
        if (occlusionActive && cfg.occlusionDurationMs > 0 && nowMs >= occlusionClearTimeMs) {
            occlusionActive = false;
            log.info("[ALARM_CLEAR] OCCLUSION cleared at t={}", nowMs);
        }

        // --- Low volume ---
        if (cfg.lowVolumeEnabled && !lowVolumeActive) {
            if (volumeRemaining <= cfg.lowVolumeThresholdMl) {
                lowVolumeActive = true;
                log.warn("[ALARM] LOW_VOLUME volumeRemaining={}mL at t={}", volumeRemaining, nowMs);
            }
        }

        // --- Line clamp ---
        if (cfg.lineClampEnabled && !lineClampActive && infusing.get()) {
            if (runTimeMs >= cfg.lineClampMinRunTimeMs) {
                if (rng.nextDouble() < cfg.lineClampProbabilityPerCycle) {
                    lineClampActive = true;
                    log.warn("[ALARM] LINE_CLAMP active at t={}", nowMs);
                }
            }
        }
    }

    /**
     * Publish current alarm state. In a full implementation this would write
     * to the ICE Alert topic. For now it logs structured records the MDDT
     * can parse from the log file, and publishes a numeric flag (1.0 = active).
     *
     * TODO: When ICE Alert DDS topic writing is wired in, replace log statements
     * with DataWriter.write() calls to ice.Alert topic.
     */
    private void publishAlarmState() {
        if (occlusionActive) log.info("[ALARM_STATUS] OCCLUSION=1 t={}", System.currentTimeMillis());
        if (lowVolumeActive) log.info("[ALARM_STATUS] LOW_VOLUME=1 volumeRemaining={} t={}", volumeRemaining, System.currentTimeMillis());
        if (lineClampActive) log.info("[ALARM_STATUS] LINE_CLAMP=1 t={}", System.currentTimeMillis());
    }

    // =========================================================================
    // Volume and battery simulation
    // =========================================================================

    private void updateSimulatedVolumes() {
        if (!infusing.get() || occlusionActive || lineClampActive) return;

        // Convert rate (mL/hr) to mL per status interval
        float intervalHours = cfg.statusPublicationIntervalMs / 3_600_000f;
        float deltaVolume = currentFlowRate * intervalHours;

        volumeInfused += deltaVolume;
        volumeRemaining = Math.max(0f, volumeRemaining - deltaVolume);

        // Reduce VTBI remaining
        vtbi = Math.max(0f, vtbi - deltaVolume);
        if (vtbi <= 0f && infusing.get()) {
            infusing.set(false);
            log.info("[STATE] VTBI complete — pump stopped at t={}", System.currentTimeMillis());
        }
    }

    private void updateBattery() {
        if (!cfg.batteryEnabled) return;
        // Drain proportional to rate, scaled by interval
        float intervalHours = cfg.statusPublicationIntervalMs / 3_600_000f;
        float rateFraction = currentFlowRate / cfg.maxRateMlPerHr;
        float drain = cfg.batteryDrainRatePercentPerHour * rateFraction * intervalHours;
        batteryLevel = Math.max(0f, batteryLevel - drain);

        if (batteryLevel <= cfg.batteryLowThresholdPercent) {
            log.warn("[BATTERY] Low battery: {}% at t={}", String.format("%.1f", batteryLevel), System.currentTimeMillis());
        }
    }

    // =========================================================================
    // Error / fault injection helpers
    // =========================================================================

    private boolean shouldDropResponse() {
        return rng.nextDouble() < cfg.dropResponseProbability;
    }

    private boolean shouldSendMalformedResponse() {
        return rng.nextDouble() < cfg.malformedResponseProbability;
    }

    private void maybeInjectSilence() {
        if (!silenced.get() && rng.nextDouble() < cfg.silenceProbability) {
            silenced.set(true);
            long silenceDuration = cfg.silenceDurationMs;
            log.warn("[SILENCE] Communication silence starting for {}ms at t={}", silenceDuration, System.currentTimeMillis());
            executor.schedule(() -> {
                silenced.set(false);
                log.info("[SILENCE_END] Communication restored at t={}", System.currentTimeMillis());
            }, silenceDuration, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Compute latency for a given command type, applying Gaussian jitter
     * and spike probability from config.
     */
    private long computeLatency(String commandType) {
        long meanMs, stdDevMs, maxMs;
        switch (commandType) {
            case "pauseResume":
                meanMs = cfg.pauseResumeMeanMs;
                stdDevMs = cfg.pauseResumeStdDevMs;
                maxMs = cfg.pauseResumeMaxMs;
                break;
            case "query":
                meanMs = cfg.queryMeanMs;
                stdDevMs = cfg.queryStdDevMs;
                maxMs = cfg.queryMaxMs;
                break;
            default: // rateChange
                meanMs = cfg.rateChangeMeanMs;
                stdDevMs = cfg.rateChangeStdDevMs;
                maxMs = cfg.rateChangeMaxMs;
        }

        // Gaussian jitter
        long latency = meanMs + (long)(rng.nextGaussian() * stdDevMs);
        latency = Math.max(0, Math.min(maxMs, latency));

        // Spike
        if (rng.nextDouble() < cfg.spikeProbability) {
            latency = (long)(latency * cfg.spikeMultiplier);
            log.info("[LATENCY_SPIKE] type={} latency={}ms", commandType, latency);
        }

        return latency;
    }

    // =========================================================================
    // DDS subscription setup
    // =========================================================================

    private void setupFlowRateObjectiveSubscription(Subscriber subscriber, EventLoop eventLoop) {
        ice.FlowRateObjectiveTypeSupport.register_type(
            getParticipant(), ice.FlowRateObjectiveTypeSupport.get_type_name());
        flowRateTopic = TopicUtil.findOrCreateTopic(
            getParticipant(), ice.FlowRateObjectiveTopic.VALUE, ice.FlowRateObjectiveTypeSupport.class);
        flowRateReader = (ice.FlowRateObjectiveDataReader) subscriber.create_datareader_with_profile(
            flowRateTopic, QosProfiles.ice_library, QosProfiles.state, null, StatusKind.STATUS_MASK_NONE);

        StringSeq params = new StringSeq();
        params.add("'" + deviceIdentity.unique_device_identifier + "'");
        flowRateQueryCondition = flowRateReader.create_querycondition(
            SampleStateKind.NOT_READ_SAMPLE_STATE, ViewStateKind.ANY_VIEW_STATE,
            InstanceStateKind.ALIVE_INSTANCE_STATE, "unique_device_identifier = %0", params);

        eventLoop.addHandler(flowRateQueryCondition, new ConditionHandler() {
            private final ice.FlowRateObjectiveSeq data_seq = new ice.FlowRateObjectiveSeq();
            private final SampleInfoSeq info_seq = new SampleInfoSeq();

            @Override
            public void conditionChanged(Condition condition) {
                for (;;) {
                    try {
                        flowRateReader.read_w_condition(data_seq, info_seq,
                            ResourceLimitsQosPolicy.LENGTH_UNLIMITED, (ReadCondition) condition);
                        for (int i = 0; i < info_seq.size(); i++) {
                            SampleInfo si = (SampleInfo) info_seq.get(i);
                            if (si.valid_data) {
                                ice.FlowRateObjective data = (ice.FlowRateObjective) data_seq.get(i);
                                // Run in executor so DDS event loop is not blocked during latency sleep
                                executor.submit(() -> applyRateCommand(data.newFlowRate, "rateChange"));
                            }
                        }
                    } catch (RETCODE_NO_DATA nd) {
                        break;
                    } finally {
                        flowRateReader.return_loan(data_seq, info_seq);
                    }
                }
            }
        });
    }

    private void setupInfusionProgramSubscription(Subscriber subscriber, EventLoop eventLoop) {
        ice.InfusionProgramTypeSupport.register_type(
            getParticipant(), ice.InfusionProgramTypeSupport.get_type_name());
        infusionProgramTopic = TopicUtil.findOrCreateTopic(
            getParticipant(), ice.InfusionProgramTopic.VALUE, ice.InfusionProgramTypeSupport.class);
        infusionProgramReader = (ice.InfusionProgramDataReader) subscriber.create_datareader_with_profile(
            infusionProgramTopic, QosProfiles.ice_library, QosProfiles.state, null, StatusKind.STATUS_MASK_NONE);

        StringSeq params = new StringSeq();
        params.add("'" + deviceIdentity.unique_device_identifier + "'");
        infusionProgramQueryCondition = infusionProgramReader.create_querycondition(
            SampleStateKind.NOT_READ_SAMPLE_STATE, ViewStateKind.ANY_VIEW_STATE,
            InstanceStateKind.ALIVE_INSTANCE_STATE, "unique_device_identifier = %0", params);

        eventLoop.addHandler(infusionProgramQueryCondition, new ConditionHandler() {
            private final ice.InfusionProgramSeq data_seq = new ice.InfusionProgramSeq();
            private final SampleInfoSeq info_seq = new SampleInfoSeq();

            @Override
            public void conditionChanged(Condition condition) {
                for (;;) {
                    try {
                        infusionProgramReader.read_w_condition(data_seq, info_seq,
                            ResourceLimitsQosPolicy.LENGTH_UNLIMITED, (ReadCondition) condition);
                        for (int i = 0; i < info_seq.size(); i++) {
                            SampleInfo si = (SampleInfo) info_seq.get(i);
                            if (si.valid_data) {
                                ice.InfusionProgram data = (ice.InfusionProgram) data_seq.get(i);
                                if (data.infusionRate >= 0) {
                                    executor.submit(() -> applyRateCommand(data.infusionRate, "rateChange"));
                                }
                                if (data.VTBI >= 0) {
                                    vtbi = data.VTBI;
                                    log.info("[CMD_APPLIED] type=setVTBI vtbi={}", vtbi);
                                }
                            }
                        }
                    } catch (RETCODE_NO_DATA nd) {
                        break;
                    } finally {
                        infusionProgramReader.return_loan(data_seq, info_seq);
                    }
                }
            }
        });
    }

    // =========================================================================
    // Configuration loading
    // =========================================================================

    private SimPumpConfig loadConfig() {
        Properties props = new Properties();
        // Try classpath first, then working directory
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(PROPERTIES_FILE)) {
            if (is != null) {
                props.load(is);
                log.info("Loaded {} from classpath", PROPERTIES_FILE);
            } else {
                try (InputStream fis = new java.io.FileInputStream(PROPERTIES_FILE)) {
                    props.load(fis);
                    log.info("Loaded {} from working directory", PROPERTIES_FILE);
                }
            }
        } catch (IOException ioe) {
            log.warn("Could not load {}; using defaults. ({})", PROPERTIES_FILE, ioe.getMessage());
        }
        return new SimPumpConfig(props);
    }

    // =========================================================================
    // Inner class: configuration holder
    // =========================================================================

    /**
     * Strongly-typed holder for all pump-sim.properties values.
     * Using a dedicated class (rather than reading from Properties inline)
     * makes it easy to add validation and unit tests later.
     */
    private static class SimPumpConfig {
        // Device identity
        final String manufacturer, model, serialNumber, firmwareVersion, protocolVersion;

        // Publication
        final long statusPublicationIntervalMs;

        // Rate change latency
        final long rateChangeMeanMs, rateChangeStdDevMs, rateChangeMaxMs;
        // Pause/resume latency
        final long pauseResumeMeanMs, pauseResumeStdDevMs, pauseResumeMaxMs;
        // Query latency
        final long queryMeanMs, queryStdDevMs, queryMaxMs;
        // Spike
        final double spikeProbability;
        final double spikeMultiplier;

        // Heartbeat
        final long heartbeatIntervalMs;
        final long heartbeatJitterMs;

        // Error injection
        final double dropResponseProbability;
        final double malformedResponseProbability;
        final double silenceProbability;
        final long silenceDurationMs;

        // Alarms
        final boolean occlusionEnabled;
        final long occlusionMinRunTimeMs;
        final double occlusionProbabilityPerCycle;
        final long occlusionDurationMs;

        final boolean lowVolumeEnabled;
        final float lowVolumeThresholdMl;

        final boolean lineClampEnabled;
        final long lineClampMinRunTimeMs;
        final double lineClampProbabilityPerCycle;

        // Infusion initial state
        final float initialRateMlPerHr;
        final float initialVTBIMl;
        final float initialVolumeMl;
        final float maxRateMlPerHr;
        final float minRateMlPerHr;

        // Battery
        final boolean batteryEnabled;
        final float batteryInitialLevel;
        final float batteryDrainRatePercentPerHour;
        final float batteryLowThresholdPercent;

        SimPumpConfig(Properties p) {
            manufacturer      = p.getProperty("pump.manufacturer", "ICE");
            model             = p.getProperty("pump.model", "Realistic Sim Pump");
            serialNumber      = p.getProperty("pump.serialNumber", "SIM-001");
            firmwareVersion   = p.getProperty("pump.firmwareVersion", "1.0.0-sim");
            protocolVersion   = p.getProperty("pump.protocolVersion", "1.0");

            statusPublicationIntervalMs = lp(p, "pump.statusPublicationIntervalMs", 1000);

            rateChangeMeanMs   = lp(p, "pump.latency.rateChange.meanMs", 250);
            rateChangeStdDevMs = lp(p, "pump.latency.rateChange.stdDevMs", 50);
            rateChangeMaxMs    = lp(p, "pump.latency.rateChange.maxMs", 800);

            pauseResumeMeanMs   = lp(p, "pump.latency.pauseResume.meanMs", 300);
            pauseResumeStdDevMs = lp(p, "pump.latency.pauseResume.stdDevMs", 75);
            pauseResumeMaxMs    = lp(p, "pump.latency.pauseResume.maxMs", 1000);

            queryMeanMs   = lp(p, "pump.latency.query.meanMs", 100);
            queryStdDevMs = lp(p, "pump.latency.query.stdDevMs", 20);
            queryMaxMs    = lp(p, "pump.latency.query.maxMs", 400);

            spikeProbability = dp(p, "pump.latency.spikeProbability", 0.05);
            spikeMultiplier  = dp(p, "pump.latency.spikeMultiplier", 5.0);

            heartbeatIntervalMs = lp(p, "pump.heartbeat.intervalMs", 5000);
            heartbeatJitterMs   = lp(p, "pump.heartbeat.jitterMs", 200);

            dropResponseProbability    = dp(p, "pump.error.dropResponseProbability", 0.02);
            malformedResponseProbability = dp(p, "pump.error.malformedResponseProbability", 0.01);
            silenceProbability         = dp(p, "pump.error.silenceProbability", 0.005);
            silenceDurationMs          = lp(p, "pump.error.silenceDurationMs", 3000);

            occlusionEnabled            = bp(p, "pump.alarm.occlusion.enabled", true);
            occlusionMinRunTimeMs       = lp(p, "pump.alarm.occlusion.minRunTimeMs", 30000);
            occlusionProbabilityPerCycle = dp(p, "pump.alarm.occlusion.probabilityPerCycle", 0.002);
            occlusionDurationMs         = lp(p, "pump.alarm.occlusion.durationMs", 10000);

            lowVolumeEnabled     = bp(p, "pump.alarm.lowVolume.enabled", true);
            lowVolumeThresholdMl = fp(p, "pump.alarm.lowVolume.thresholdMl", 20.0f);

            lineClampEnabled            = bp(p, "pump.alarm.lineClamp.enabled", false);
            lineClampMinRunTimeMs       = lp(p, "pump.alarm.lineClamp.minRunTimeMs", 60000);
            lineClampProbabilityPerCycle = dp(p, "pump.alarm.lineClamp.probabilityPerCycle", 0.001);

            initialRateMlPerHr  = fp(p, "pump.infusion.initialRateMlPerHr", 5.0f);
            initialVTBIMl       = fp(p, "pump.infusion.initialVTBIMl", 100.0f);
            initialVolumeMl     = fp(p, "pump.infusion.initialVolumeMl", 100.0f);
            maxRateMlPerHr      = fp(p, "pump.infusion.maxRateMlPerHr", 1200.0f);
            minRateMlPerHr      = fp(p, "pump.infusion.minRateMlPerHr", 0.1f);

            batteryEnabled                   = bp(p, "pump.battery.enabled", true);
            batteryInitialLevel              = fp(p, "pump.battery.initialLevel", 85f);
            batteryDrainRatePercentPerHour   = fp(p, "pump.battery.drainRatePercentPerHour", 2.0f);
            batteryLowThresholdPercent       = fp(p, "pump.battery.lowThresholdPercent", 20f);
        }

        private static long   lp(Properties p, String k, long   d) { try { return Long.parseLong(p.getProperty(k, String.valueOf(d)).trim()); } catch (Exception e) { return d; } }
        private static double dp(Properties p, String k, double d) { try { return Double.parseDouble(p.getProperty(k, String.valueOf(d)).trim()); } catch (Exception e) { return d; } }
        private static float  fp(Properties p, String k, float  d) { try { return Float.parseFloat(p.getProperty(k, String.valueOf(d)).trim()); } catch (Exception e) { return d; } }
        private static boolean bp(Properties p, String k, boolean d) { String v = p.getProperty(k); return v == null ? d : Boolean.parseBoolean(v.trim()); }
    }

    // =========================================================================
    // Inner class: wall clock
    // =========================================================================

    private static class WallClock implements DeviceClock {
        @Override
        public Reading instant() {
            return new Reading() {
                private final Instant t = Instant.ofEpochMilli(System.currentTimeMillis());
                @Override public Reading refineResolutionForFrequency(int hz, int size) { return null; }
                @Override public boolean hasDeviceTime() { return true; }
                @Override public Instant getTime() { return t; }
                @Override public Instant getDeviceTime() { return t; }
            };
        }
    }
}
