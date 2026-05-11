package org.mdpnp.apps.testapp.physionet;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * WfdbReader
 * ==========
 * Pure-Java reader for WFDB signal records (.hea + .dat).
 *
 * Supports the three formats covering the vast majority of PhysioNet records:
 *
 *   Format 16  - little-endian signed 16-bit integers, 2 bytes/sample
 *   Format 212 - 12-bit samples packed 2-per-3-bytes (most space-efficient,
 *                used by MIT-BIH, MIMIC-III waveforms, VTaC, and others)
 *   Format 32  - little-endian signed 32-bit integers, 4 bytes/sample
 *
 * Physical conversion (WFDB Programmer's Guide ?2.5):
 *   physical_value = (digital_sample - baseline) / gain
 *
 * Usage:
 *   WfdbRecord rec = WfdbReader.read(heaFile);
 *   // rec.signals[ch][sample] - physical units (mV, mmHg, etc.)
 *   // rec.header.sigNames[ch], rec.header.units[ch], rec.header.fs
 *
 * All signals in the record are read into memory on construction.
 * For very long MIMIC records (hours), callers should use the windowed
 * read variant readWindow(heaFile, fromSample, toSample) instead.
 */
public final class WfdbReader {

    private WfdbReader() {}

    // -----------------------------------------------------------------------
    // Public data types
    // -----------------------------------------------------------------------

    /** Parsed WFDB header (.hea) for a single-segment record. */
    public static final class WfdbHeader {
        public String   recordName;
        public int      nSig;
        public double   fs;
        public long     sigLen;
        public String   baseTime = "";
        public String   baseDate = "";
        /** Per-channel signal file names (may all be the same for multiplexed). */
        public List<String>  fileNames  = new ArrayList<>();
        public List<String>  formats    = new ArrayList<>();
        public List<Double>  gains      = new ArrayList<>();
        public List<Integer> baselines  = new ArrayList<>();
        public List<String>  sigNames   = new ArrayList<>();
        public List<String>  units      = new ArrayList<>();
        public List<String>  comments   = new ArrayList<>();
        /** Samples-per-frame per channel (usually 1, but can be >1). */
        public List<Integer> spf        = new ArrayList<>();
    }

    /** A fully-loaded WFDB record: header + physical signal matrix. */
    public static final class WfdbRecord {
        public WfdbHeader header;
        /**
         * Physical signal values indexed [channel][sample].
         * Values are in the physical units declared in the header (mV, mmHg, etc.).
         * NaN indicates an invalid/missing sample.
         */
        public double[][] signals;

        /** Duration in seconds. */
        public double durationSeconds() {
            return header.fs > 0 ? (double) header.sigLen / header.fs : 0;
        }
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Read the complete record into memory.
     *
     * @param heaFile  The .hea header file. The .dat file must be alongside it.
     * @return         A fully populated WfdbRecord.
     * @throws IOException  on file read errors or unsupported format.
     */
    public static WfdbRecord read(File heaFile) throws IOException {
        WfdbHeader hdr = parseHeader(heaFile);
        double[][] signals = readSignals(heaFile.getParentFile(), hdr,
                                         0, (int) hdr.sigLen);
        WfdbRecord rec = new WfdbRecord();
        rec.header  = hdr;
        rec.signals = signals;
        return rec;
    }

    /**
     * Read a contiguous window of samples into memory (preferred for long records).
     *
     * @param heaFile    The .hea header file.
     * @param fromSample First sample index (inclusive, 0-based).
     * @param toSample   Last sample index (exclusive).
     */
    public static WfdbRecord readWindow(File heaFile,
                                         int fromSample,
                                         int toSample) throws IOException {
        WfdbHeader hdr = parseHeader(heaFile);
        int clampedTo = (int) Math.min(toSample, hdr.sigLen);
        double[][] signals = readSignals(heaFile.getParentFile(), hdr,
                                          fromSample, clampedTo);
        WfdbRecord rec = new WfdbRecord();
        rec.header  = hdr;
        rec.signals = signals;
        return rec;
    }

    /**
     * Parse just the header without reading signal data.
     * Used by the browser app for metadata display.
     */
    public static WfdbHeader parseHeader(File heaFile) throws IOException {
        WfdbHeader hdr = new WfdbHeader();
        List<String> lines = readLines(heaFile);
        boolean firstLine = true;
        int sigLinesRead = 0;

        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("#")) {
                hdr.comments.add(line.substring(1).trim());
                continue;
            }

            if (firstLine) {
                // Record line: record nSig [fs [/counterFreq [baseCounter]]
                //              [sigLen [baseTime [baseDate]]]]
                firstLine = false;
                String[] p = line.split("\\s+");
                hdr.recordName = p[0];
                if (p.length >= 2) hdr.nSig   = parseInt(p[1], 0);
                if (p.length >= 3) {
                    String fsPart = p[2].contains("/")
                        ? p[2].substring(0, p[2].indexOf('/')) : p[2];
                    hdr.fs = parseDouble(fsPart, 250.0);
                }
                if (p.length >= 4) hdr.sigLen   = parseLong(p[3], 0);
                if (p.length >= 5) hdr.baseTime = p[4];
                if (p.length >= 6) hdr.baseDate = p[5];

            } else if (sigLinesRead < hdr.nSig) {
                // Signal specification line:
                // fileName format [gain(unit)/baseline/adcRes/adcZero/initVal/checksum]
                // [blockSize] [sigName]
                sigLinesRead++;
                String[] p = line.split("\\s+");
                hdr.fileNames.add(p.length >= 1 ? p[0] : "");
                hdr.formats.add(  p.length >= 2 ? p[1] : "16");

                double gain     = 200.0; // WFDB default
                int    baseline = 0;
                String unit     = "mV";

                if (p.length >= 3) {
                    // Gain field: "200(mV)/1024/12/0" or "200" or "200(mV)"
                    String gainField = p[2];
                    int slash = gainField.indexOf('/');
                    String gainPart = slash >= 0
                        ? gainField.substring(0, slash) : gainField;
                    // Extract unit from parentheses: "200(mV)" -> unit="mV"
                    int lp = gainPart.indexOf('(');
                    int rp = gainPart.indexOf(')');
                    if (lp >= 0 && rp > lp) {
                        unit = gainPart.substring(lp + 1, rp);
                        gainPart = gainPart.substring(0, lp);
                    }
                    gain = parseDouble(gainPart, 200.0);
                    // Baseline may follow gain as /baseline
                    if (slash >= 0) {
                        String rest = gainField.substring(slash + 1);
                        int slash2 = rest.indexOf('/');
                        String baseStr = slash2 >= 0
                            ? rest.substring(0, slash2) : rest;
                        baseline = parseInt(baseStr, 0);
                    }
                }
                hdr.gains.add(gain);
                hdr.baselines.add(baseline);
                hdr.units.add(unit);

                // Samples-per-frame is encoded in the format field after 'x':
                // e.g. "212x2" means 2 samples/frame for this channel
                String fmt = hdr.formats.get(hdr.formats.size() - 1);
                int xIdx = fmt.indexOf('x');
                int spfVal = xIdx >= 0
                    ? parseInt(fmt.substring(xIdx + 1), 1) : 1;
                hdr.spf.add(spfVal);

                // Signal name is the last token (after optional blockSize)
                String name = "ch" + (sigLinesRead - 1);
                if (p.length >= 9)       name = p[8];
                else if (p.length >= 5)  name = p[p.length - 1];
                hdr.sigNames.add(name);
            }
        }

        // Fill any missing spf entries
        while (hdr.spf.size() < hdr.nSig) hdr.spf.add(1);

        return hdr;
    }

    // -----------------------------------------------------------------------
    // Signal reading
    // -----------------------------------------------------------------------

    private static double[][] readSignals(File dir, WfdbHeader hdr,
                                           int fromSample, int toSample)
            throws IOException {
        int nSamples = toSample - fromSample;
        double[][] out = new double[hdr.nSig][nSamples];

        // Group channels by their data file (multiplexed records share one .dat)
        // For simplicity we handle the common case: all channels in one .dat file.
        // Multi-file records have separate .dat per channel.
        String firstFile = hdr.fileNames.isEmpty() ? "" : hdr.fileNames.get(0);
        boolean multiplexed = hdr.fileNames.stream()
            .allMatch(f -> f.equals(firstFile));

        if (multiplexed) {
            readMultiplexed(dir, hdr, fromSample, toSample, out);
        } else {
            // Each channel has its own file
            for (int ch = 0; ch < hdr.nSig; ch++) {
                readSingleChannel(dir, hdr, ch, fromSample, toSample, out[ch]);
            }
        }
        return out;
    }

    /**
     * Read a multiplexed .dat file (all channels interleaved in one file).
     * Sample order on disk: [ch0_s0, ch1_s0, ..., chN_s0, ch0_s1, ...]
     */
    private static void readMultiplexed(File dir, WfdbHeader hdr,
                                         int fromSample, int toSample,
                                         double[][] out) throws IOException {
        String datName = hdr.fileNames.get(0);
        // Strip any path - dat file should be in same directory as .hea
        File datFile = new File(dir, new File(datName).getName());
        String fmt = cleanFormat(hdr.formats.get(0));
        int nSamples = toSample - fromSample;
        int nSig = hdr.nSig;

        try (RandomAccessFile raf = new RandomAccessFile(datFile, "r")) {
            switch (fmt) {
                case "16":
                    readFmt16Multiplexed(raf, hdr, fromSample, nSamples, nSig, out);
                    break;
                case "32":
                    readFmt32Multiplexed(raf, hdr, fromSample, nSamples, nSig, out);
                    break;
                case "212":
                    readFmt212Multiplexed(raf, hdr, fromSample, nSamples, nSig, out);
                    break;
                default:
                    throw new IOException("Unsupported WFDB format: " + fmt
                        + ". Supported: 16, 212, 32.");
            }
        }
    }

    private static void readFmt16Multiplexed(RandomAccessFile raf,
            WfdbHeader hdr, int fromSample, int nSamples,
            int nSig, double[][] out) throws IOException {
        // 2 bytes per sample, little-endian signed 16-bit
        int bytesPerFrame = nSig * 2;
        raf.seek((long) fromSample * bytesPerFrame);
        byte[] buf = new byte[bytesPerFrame * Math.min(nSamples, 4096)];
        int samplesDone = 0;

        while (samplesDone < nSamples) {
            int chunk = Math.min(4096, nSamples - samplesDone);
            int bytesToRead = chunk * bytesPerFrame;
            int bytesRead = raf.read(buf, 0, bytesToRead);
            if (bytesRead <= 0) break;
            int framesRead = bytesRead / bytesPerFrame;
            ByteBuffer bb = ByteBuffer.wrap(buf, 0, bytesRead)
                                      .order(ByteOrder.LITTLE_ENDIAN);
            for (int s = 0; s < framesRead; s++) {
                for (int ch = 0; ch < nSig; ch++) {
                    int digital = bb.getShort();
                    out[ch][samplesDone + s] = toPhysical(digital, hdr, ch);
                }
            }
            samplesDone += framesRead;
        }
    }

    private static void readFmt32Multiplexed(RandomAccessFile raf,
            WfdbHeader hdr, int fromSample, int nSamples,
            int nSig, double[][] out) throws IOException {
        // 4 bytes per sample, little-endian signed 32-bit
        int bytesPerFrame = nSig * 4;
        raf.seek((long) fromSample * bytesPerFrame);
        byte[] buf = new byte[bytesPerFrame * Math.min(nSamples, 4096)];
        int samplesDone = 0;

        while (samplesDone < nSamples) {
            int chunk = Math.min(4096, nSamples - samplesDone);
            int bytesToRead = chunk * bytesPerFrame;
            int bytesRead = raf.read(buf, 0, bytesToRead);
            if (bytesRead <= 0) break;
            int framesRead = bytesRead / bytesPerFrame;
            ByteBuffer bb = ByteBuffer.wrap(buf, 0, bytesRead)
                                      .order(ByteOrder.LITTLE_ENDIAN);
            for (int s = 0; s < framesRead; s++) {
                for (int ch = 0; ch < nSig; ch++) {
                    int digital = bb.getInt();
                    out[ch][samplesDone + s] = toPhysical(digital, hdr, ch);
                }
            }
            samplesDone += framesRead;
        }
    }

    /**
     * Format 212: two 12-bit samples packed into 3 bytes.
     * Byte layout for samples A (ch0) and B (ch1):
     *   byte0 = low 8 bits of A
     *   byte1 = low nibble: high 4 bits of A; high nibble: low 4 bits of B
     *   byte2 = high 8 bits of B
     * Sign-extend from 12 bits (two's complement).
     */
    private static void readFmt212Multiplexed(RandomAccessFile raf,
            WfdbHeader hdr, int fromSample, int nSamples,
            int nSig, double[][] out) throws IOException {
        // For format 212 with nSig channels, each frame is nSig*12 bits.
        // We read one frame at a time for simplicity.
        // Seek: each pair of channels takes 3 bytes. For nSig channels:
        //   bytesPerFrame = ceil(nSig * 1.5) = (nSig * 3 + 1) / 2
        // But PhysioNet always stores whole bytes, pairs of 12-bit samples in 3 bytes.
        // Most 212 records have nSig=2 (3 bytes/frame). For nSig>2, multiple passes.

        int pairsPerFrame  = (nSig + 1) / 2;   // ceil(nSig/2)
        int bytesPerFrame  = pairsPerFrame * 3;
        long byteOffset    = (long) fromSample * bytesPerFrame;
        raf.seek(byteOffset);

        byte[] frame = new byte[bytesPerFrame];

        for (int s = 0; s < nSamples; s++) {
            int bytesRead = raf.read(frame);
            if (bytesRead < bytesPerFrame) break;

            for (int pair = 0; pair < pairsPerFrame; pair++) {
                int b0 = frame[pair * 3]     & 0xFF;
                int b1 = frame[pair * 3 + 1] & 0xFF;
                int b2 = frame[pair * 3 + 2] & 0xFF;

                // First sample of pair
                int raw0 = b0 | ((b1 & 0x0F) << 8);
                if ((raw0 & 0x800) != 0) raw0 |= 0xFFFFF000; // sign-extend 12->32 bit
                int ch0 = pair * 2;
                if (ch0 < nSig) {
                    out[ch0][s] = toPhysical(raw0, hdr, ch0);
                }

                // Second sample of pair
                int raw1 = (b1 >> 4) | (b2 << 4);
                if ((raw1 & 0x800) != 0) raw1 |= 0xFFFFF000; // sign-extend
                int ch1 = pair * 2 + 1;
                if (ch1 < nSig) {
                    out[ch1][s] = toPhysical(raw1, hdr, ch1);
                }
            }
        }
    }

    /** Read a single-channel .dat file (non-multiplexed record). */
    private static void readSingleChannel(File dir, WfdbHeader hdr, int ch,
                                           int fromSample, int toSample,
                                           double[] out) throws IOException {
        String datName = new File(hdr.fileNames.get(ch)).getName();
        File datFile   = new File(dir, datName);
        String fmt     = cleanFormat(hdr.formats.get(ch));
        int nSamples   = toSample - fromSample;

        try (RandomAccessFile raf = new RandomAccessFile(datFile, "r")) {
            switch (fmt) {
                case "16": {
                    raf.seek((long) fromSample * 2);
                    byte[] buf = new byte[nSamples * 2];
                    raf.readFully(buf);
                    ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);
                    for (int s = 0; s < nSamples; s++)
                        out[s] = toPhysical(bb.getShort(), hdr, ch);
                    break;
                }
                case "32": {
                    raf.seek((long) fromSample * 4);
                    byte[] buf = new byte[nSamples * 4];
                    raf.readFully(buf);
                    ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);
                    for (int s = 0; s < nSamples; s++)
                        out[s] = toPhysical(bb.getInt(), hdr, ch);
                    break;
                }
                case "212": {
                    // Single-channel 212: consecutive 12-bit samples packed 2-per-3-bytes
                    long byteOffset = (fromSample / 2) * 3;
                    raf.seek(byteOffset);
                    byte[] buf = new byte[((nSamples + 1) / 2) * 3];
                    raf.read(buf);
                    int outIdx = 0;
                    int startParity = fromSample % 2; // 0 = start on first of pair
                    for (int i = 0; i < buf.length - 2 && outIdx < nSamples; i += 3) {
                        int b0 = buf[i]   & 0xFF;
                        int b1 = buf[i+1] & 0xFF;
                        int b2 = buf[i+2] & 0xFF;
                        int raw0 = b0 | ((b1 & 0x0F) << 8);
                        if ((raw0 & 0x800) != 0) raw0 |= 0xFFFFF000;
                        int raw1 = (b1 >> 4) | (b2 << 4);
                        if ((raw1 & 0x800) != 0) raw1 |= 0xFFFFF000;
                        if (startParity == 0) {
                            if (outIdx < nSamples) out[outIdx++] = toPhysical(raw0, hdr, ch);
                            if (outIdx < nSamples) out[outIdx++] = toPhysical(raw1, hdr, ch);
                        } else {
                            if (outIdx < nSamples) out[outIdx++] = toPhysical(raw1, hdr, ch);
                            startParity = 0;
                        }
                    }
                    break;
                }
                default:
                    throw new IOException("Unsupported WFDB format: " + fmt);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Convert a digital ADC sample to physical units.
     * physical = (digital - baseline) / gain
     * If gain is 0 (uncalibrated), returns the raw digital value.
     */
    private static double toPhysical(int digital, WfdbHeader hdr, int ch) {
        double gain     = ch < hdr.gains.size()     ? hdr.gains.get(ch)     : 200.0;
        int    baseline = ch < hdr.baselines.size() ? hdr.baselines.get(ch) : 0;
        if (gain == 0.0) return digital;
        return (digital - baseline) / gain;
    }

    /** Strip samples-per-frame suffix from format string: "212x2" -> "212" */
    private static String cleanFormat(String fmt) {
        int x = fmt.indexOf('x');
        return x >= 0 ? fmt.substring(0, x) : fmt;
    }

    private static List<String> readLines(File f) throws IOException {
        List<String> lines = new ArrayList<>();
        try (java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.FileReader(f, java.nio.charset.StandardCharsets.ISO_8859_1))) {
            String line;
            while ((line = br.readLine()) != null) lines.add(line);
        }
        return lines;
    }

    private static int    parseInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }
    private static long   parseLong(String s, long def) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return def; }
    }
    private static double parseDouble(String s, double def) {
        try { return Double.parseDouble(s.trim()); } catch (Exception e) { return def; }
    }
}
