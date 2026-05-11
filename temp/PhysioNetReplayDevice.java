package org.mdpnp.apps.testapp.physionet;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ice.Numeric;
import ice.NumericDataWriter;
import ice.SampleArray;
import ice.SampleArrayDataWriter;

/**
 * PhysioNetReplayDevice
 * =====================
 * Manages playback of a downloaded WFDB record, driving the transport
 * controls (play/pause/stop/speed) and publishing waveform frames via
 * DDS when writers are provided.
 *
 * This class intentionally does NOT extend AbstractSimulatedConnectedDevice
 * for the initial implementation - DDS publishing is handled through
 * injected SampleArrayDataWriter and NumericDataWriter references which
 * may be null (in which case playback runs in UI-only mode with no DDS output).
 * Full device integration will be added once file I/O is validated.
 */
public class PhysioNetReplayDevice {

    private static final Logger log =
        LoggerFactory.getLogger(PhysioNetReplayDevice.class);

    // -- Playback engine constants ------------------------------------------
    /** Wall-clock tick interval in milliseconds. Controls publish cadence. */
    private static final long   TICK_MS     = 40;   // 25 fps publish rate
    /**
     * Number of waveform samples published per channel per tick at 1? speed.
     * At TICK_MS=40ms this represents 40ms of data per tick regardless of fs,
     * since we scale samplesPerTick = fs * TICK_MS/1000.
     * Recalculated on each record load.
     */
    private volatile int        samplesPerTick = 10;

    // -- Speed control - volatile so the ticker thread sees UI-thread writes --
    private volatile double     speedMultiplier = 1.0;
    private volatile boolean    loopEnabled     = true;
    private volatile boolean    playing         = false;

    // -- Current record state ----------------------------------------------
    private volatile WfdbReader.WfdbRecord currentRecord = null;
    private volatile int                   samplePointer = 0;

    /** Per-channel metadata: MDC mapping + whether it is a waveform or numeric. */
    private final List<ChannelContext> channels = new ArrayList<>();

    // -- DDS writers (owned by this device instance) -----------------------
    // These are obtained from the parent class infrastructure, not Spring beans.
    // SampleArrayDataWriter and NumericDataWriter are created via the
    // DomainParticipant provided by AbstractSimulatedConnectedDevice.
    private SampleArrayDataWriter saWriter = null;
    private NumericDataWriter     numWriter = null;

    // -- Scheduler ---------------------------------------------------------
    private final ScheduledExecutorService scheduler =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "physionet-replay-tick");
            t.setDaemon(true);
            return t;
        });
    private ScheduledFuture<?> tickFuture = null;

    // -- Listener interface for the UI --------------------------------------
    public interface ReplayListener {
        /** Called on the replay thread each tick with the current sample index. */
        void onProgress(int sampleIndex, int totalSamples);
        /** Called when playback reaches end-of-record and loop is disabled. */
        void onPlaybackComplete();
    }
    private volatile ReplayListener listener = null;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    public PhysioNetReplayDevice(
            ice.SampleArrayDataWriter saWriter,
            ice.NumericDataWriter     numWriter) {
        this.saWriter  = saWriter;
        this.numWriter = numWriter;
        startTicker();
    }

    // -----------------------------------------------------------------------
    // Record loading
    // -----------------------------------------------------------------------

    /**
     * Load a WFDB record from disk and prepare for replay.
     * Safe to call on the UI thread - file I/O is done synchronously here
     * and should be wrapped in an executor by the caller for large records.
     *
     * @param heaFile  The .hea header file of the downloaded record.
     * @throws Exception on I/O or format errors.
     */
    public synchronized void loadRecord(File heaFile) throws Exception {
        boolean wasPlaying = playing;
        playing = false;

        WfdbReader.WfdbRecord rec = WfdbReader.read(heaFile);
        currentRecord = rec;
        samplePointer = 0;
        uniqueDeviceIdentifier = "PhysioNet-" + rec.header.recordName;

        // Calculate samples per tick from the record's sampling frequency
        samplesPerTick = Math.max(1,
            (int) Math.round(rec.header.fs * TICK_MS / 1000.0));

        // Build channel context - MDC mapping per signal
        channels.clear();
        for (int ch = 0; ch < rec.header.nSig; ch++) {
            String sigName = ch < rec.header.sigNames.size()
                ? rec.header.sigNames.get(ch) : "ch" + ch;
            WfdbMdcMapper.lookup(sigName).ifPresentOrElse(
                mapping -> channels.add(new ChannelContext(ch, sigName, mapping)),
                ()      -> {
                    // Unmapped signal - still publish using a vendor metric ID
                    channels.add(new ChannelContext(ch, sigName, null));
                    log.info("Channel {} '{}' has no MDC mapping - "
                             + "will publish with vendor metric ID", ch, sigName);
                }
            );
        }

        log.info("Loaded record '{}': {} channels @ {} Hz, {} samples ({} s)",
            rec.header.recordName, rec.header.nSig, rec.header.fs,
            rec.header.sigLen, String.format("%.1f", rec.durationSeconds()));

        if (wasPlaying) playing = true;
    }

    // -----------------------------------------------------------------------
    // Transport controls (called from JavaFX controller)
    // -----------------------------------------------------------------------

    public void play()  { playing = true;  }
    public void pause() { playing = false; }

    public void stop() {
        playing       = false;
        samplePointer = 0;
    }

    public void setSpeed(double multiplier) {
        this.speedMultiplier = multiplier;
    }

    public void setLoop(boolean loop) {
        this.loopEnabled = loop;
    }

    public void seekToSample(int sample) {
        WfdbReader.WfdbRecord rec = currentRecord;
        if (rec == null) return;
        samplePointer = Math.max(0, Math.min(sample, (int) rec.header.sigLen - 1));
    }

    public void setListener(ReplayListener l) { this.listener = l; }

    public boolean isPlaying() { return playing; }

    public WfdbReader.WfdbRecord getCurrentRecord() { return currentRecord; }

    public int getSamplePointer() { return samplePointer; }

    // -----------------------------------------------------------------------
    // Tick loop
    // -----------------------------------------------------------------------

    private void startTicker() {
        tickFuture = scheduler.scheduleAtFixedRate(
            this::tick, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
    }

    private void tick() {
        if (!playing) return;
        WfdbReader.WfdbRecord rec = currentRecord;
        if (rec == null) return;

        int totalSamples = (int) rec.header.sigLen;
        int ptr          = samplePointer;
        int advance      = (int) Math.max(1,
            Math.round(samplesPerTick * speedMultiplier));
        int endPtr       = Math.min(ptr + advance, totalSamples);

        // Publish waveform frames and numerics
        for (ChannelContext ctx : channels) {
            if (ctx.mapping == null) continue; // skip unmapped for now

            if (ctx.mapping.signalType == WfdbMdcMapper.SignalType.WAVEFORM) {
                publishWaveformFrame(rec, ctx, ptr, endPtr);
            } else {
                publishNumeric(rec, ctx, ptr);
            }
        }

        samplePointer = endPtr;

        ReplayListener l = listener;
        if (l != null) l.onProgress(samplePointer, totalSamples);

        // End of record
        if (samplePointer >= totalSamples) {
            if (loopEnabled) {
                samplePointer = 0;
            } else {
                playing = false;
                if (l != null) l.onPlaybackComplete();
            }
        }
    }

    // -----------------------------------------------------------------------
    // DDS publishing
    // -----------------------------------------------------------------------

    private void publishWaveformFrame(WfdbReader.WfdbRecord rec,
                                       ChannelContext ctx,
                                       int fromSample, int toSample) {
        if (saWriter == null) return;
        int len = toSample - fromSample;
        if (len <= 0) return;

        try {
            SampleArray sa = (SampleArray) saWriter.get_loan();
            if (sa == null) {
                sa = new SampleArray();
            }
            sa.unique_device_identifier = getUniqueDeviceIdentifier();
            sa.metric_id                = ctx.mapping.mdcId;
            sa.vendor_metric_id         = ctx.wfdbName;
            sa.instance_id              = ctx.channel;
            sa.frequency                = (float) rec.header.fs;
            sa.unit_id                  = ctx.mapping.mdcUnit;

            // Copy samples into the ice.FloatSeq values array
            sa.values.clear();
            double[] signal = rec.signals[ctx.channel];
            for (int i = fromSample; i < toSample && i < signal.length; i++) {
                sa.values.add((float) signal[i]);
            }

            sa.presentation_time.sec  = 0; // TODO: wire to record base_time + offset
            sa.presentation_time.nanosec = 0;
            sa.device_time.sec           = 0;
            sa.device_time.nanosec       = 0;

            saWriter.write(sa, ice.SampleArrayDataWriter.HANDLE_NIL);
        } catch (Exception e) {
            log.warn("SampleArray publish error for ch {}: {}", ctx.channel, e.getMessage());
        }
    }

    private void publishNumeric(WfdbReader.WfdbRecord rec,
                                 ChannelContext ctx,
                                 int sample) {
        if (numWriter == null) return;
        double[] signal = rec.signals[ctx.channel];
        if (sample >= signal.length) return;

        try {
            Numeric n = (Numeric) numWriter.get_loan();
            if (n == null) n = new Numeric();

            n.unique_device_identifier = getUniqueDeviceIdentifier();
            n.metric_id                = ctx.mapping.mdcId;
            n.vendor_metric_id         = ctx.wfdbName;
            n.instance_id              = ctx.channel;
            n.unit_id                  = ctx.mapping.mdcUnit;
            n.value                    = (float) signal[sample];
            n.presentation_time.sec    = 0;
            n.presentation_time.nanosec = 0;
            n.device_time.sec           = 0;
            n.device_time.nanosec       = 0;

            numWriter.write(n, ice.NumericDataWriter.HANDLE_NIL);
        } catch (Exception e) {
            log.warn("Numeric publish error for ch {}: {}", ctx.channel, e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Simple UDI - stable per record name
    // -----------------------------------------------------------------------

    private String uniqueDeviceIdentifier = "PhysioNetReplay";

    private String getUniqueDeviceIdentifier() {
        return uniqueDeviceIdentifier;
    }

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    public void shutdown() {
        playing = false;
        if (tickFuture != null) tickFuture.cancel(false);
        scheduler.shutdownNow();
    }

    // -----------------------------------------------------------------------
    // Static utility: scan physionet_data for available records
    // -----------------------------------------------------------------------

    /**
     * Scans the fixed WFDB download root for available records.
     * A valid record directory is one containing at least one .hea file
     * whose corresponding .dat file also exists.
     *
     * @return List of available .hea files, sorted by path.
     */
    public static List<File> scanAvailableRecords() {
        List<File> results = new ArrayList<>();
        Path root = Paths.get(PhysioNetBrowserApp.DOWNLOAD_ROOT);
        if (!Files.exists(root)) return results;

        try {
            Files.walk(root)
                .filter(p -> p.toString().endsWith(".hea"))
                .filter(p -> {
                    // Check that the corresponding .dat also exists
                    String datPath = p.toString().replace(".hea", ".dat");
                    return Files.exists(Paths.get(datPath));
                })
                .sorted()
                .forEach(p -> results.add(p.toFile()));
        } catch (Exception e) {
            log.warn("Error scanning physionet_data: {}", e.getMessage());
        }
        return results;
    }

    // -----------------------------------------------------------------------
    // Inner classes
    // -----------------------------------------------------------------------

    private static final class ChannelContext {
        final int                    channel;
        final String                 wfdbName;
        final WfdbMdcMapper.MdcMapping mapping; // null if unmapped

        ChannelContext(int channel, String wfdbName,
                       WfdbMdcMapper.MdcMapping mapping) {
            this.channel  = channel;
            this.wfdbName = wfdbName;
            this.mapping  = mapping;
        }
    }
}
