package org.mdpnp.apps.testapp.datavalidation;

import javafx.animation.AnimationTimer;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MjpegAviRecorder
 * ================
 * Pure Java MJPEG-in-AVI screen recorder. Zero external dependencies.
 * Captures frames from a JavaFX Scene at TARGET_FPS and writes an AVI
 * file playable in VLC, Windows Media Player, and most browsers.
 *
 * Header offsets verified against the AVI RIFF specification.
 * Key fix over previous version: strh.Length is at offset 140, not 152;
 * Priority+Language are 16-bit fields, not one 32-bit field; biHeight is
 * positive (bottom-up bitmap) per AVI convention; idx1 offsets are relative
 * to the start of the 'movi' fourcc tag, not the LIST chunk header.
 */
public class MjpegAviRecorder {

    private static final Logger log =
        LoggerFactory.getLogger(MjpegAviRecorder.class);

    private static final int   TARGET_FPS   = 15;
    private static final long  FRAME_NS     = 1_000_000_000L / TARGET_FPS;
    private static final float JPEG_QUALITY = 0.82f;
    private static final int   QUEUE_CAP    = 30;

    private final Scene scene;
    private volatile String lastOutputPath;

    private final AtomicBoolean          recording    = new AtomicBoolean(false);
    private AnimationTimer               captureTimer;
    private Thread                       encoderThread;
    private BlockingQueue<WritableImage> frameQueue;
    private long                         lastCaptureNs;

    // AVI file state (encoder thread only after open)
    private RandomAccessFile raf;
    private int              frameW, frameH, frameCount;
    private long             moviDataStart;  // file pos of first byte AFTER 'movi' tag
    private List<long[]>     frameIndex;     // {fileOffset, jpegSize} per frame

    // Placeholder offsets to fix up at close
    private long offRiffSize, offTotalFrames, offStrhLength, offMoviSize;

    private ImageWriter     jpegWriter;
    private ImageWriteParam jpegParams;

    public MjpegAviRecorder(Scene scene) { this.scene = scene; }

    public boolean isRecording()       { return recording.get(); }
    public String  getLastOutputPath() { return lastOutputPath; }

    // -- Public API ------------------------------------------------------------

    public String startRecording() throws IOException {
        if (recording.getAndSet(true)) return lastOutputPath;

        String ts   = LocalDateTime.now()
            .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        lastOutputPath = System.getProperty("user.home")
            + File.separator + "DataValidation_" + ts + ".avi";

        frameW     = ((int) scene.getWidth())  & ~1;
        frameH     = ((int) scene.getHeight()) & ~1;
        frameCount = 0;
        frameIndex = new ArrayList<>(1800);

        jpegWriter = ImageIO.getImageWritersByFormatName("jpeg").next();
        jpegParams = jpegWriter.getDefaultWriteParam();
        jpegParams.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        jpegParams.setCompressionQuality(JPEG_QUALITY);

        raf = new RandomAccessFile(lastOutputPath, "rw");
        writePlaceholderHeaders();

        frameQueue    = new ArrayBlockingQueue<>(QUEUE_CAP);
        encoderThread = new Thread(this::encoderLoop, "mjpeg-encoder");
        encoderThread.setDaemon(true);
        encoderThread.start();

        lastCaptureNs = 0;
        captureTimer  = new AnimationTimer() {
            @Override public void handle(long now) {
                if (!recording.get()) { stop(); return; }
                if (now - lastCaptureNs < FRAME_NS) return;
                lastCaptureNs = now;
                WritableImage img = scene.snapshot(null);
                if (!frameQueue.offer(img))
                    log.debug("Frame dropped -- encoder busy");
            }
        };
        captureTimer.start();
        log.info("Recording started: {}", lastOutputPath);
        return lastOutputPath;
    }

    public void stopRecording() {
        if (!recording.getAndSet(false)) return;
        if (captureTimer != null) { captureTimer.stop(); captureTimer = null; }

        try { frameQueue.put(new WritableImage(1, 1)); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        try { if (encoderThread != null) encoderThread.join(10_000); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        try {
            writeIndexAndFixHeaders();
            raf.close();
            log.info("Recording saved: {} ({} frames)", lastOutputPath, frameCount);
        } catch (IOException e) {
            log.error("Error finalizing AVI: {}", e.getMessage());
        }
    }

    // -- Encoder thread --------------------------------------------------------

    private void encoderLoop() {
        while (true) {
            WritableImage img;
            try { img = frameQueue.take(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            if (!recording.get() && (int) img.getWidth() <= 1) break;
            try { writeVideoFrame(encodeJpeg(img)); }
            catch (IOException e) { log.error("Frame encode error: {}", e.getMessage()); }
        }
    }

    private byte[] encodeJpeg(WritableImage fx) throws IOException {
        BufferedImage bi = SwingFXUtils.fromFXImage(fx, null);
        if (bi.getWidth() != frameW || bi.getHeight() != frameH
                || bi.getType() != BufferedImage.TYPE_3BYTE_BGR) {
            BufferedImage out = new BufferedImage(
                frameW, frameH, BufferedImage.TYPE_3BYTE_BGR);
            out.getGraphics().drawImage(bi, 0, 0, frameW, frameH, null);
            bi = out;
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream(frameW * frameH / 4);
        MemoryCacheImageOutputStream mcios = new MemoryCacheImageOutputStream(baos);
        jpegWriter.setOutput(mcios);
        jpegWriter.write(null, new IIOImage(bi, null, null), jpegParams);
        mcios.flush();
        return baos.toByteArray();
    }

    // -- AVI writing -----------------------------------------------------------

    /**
     * Write AVI header with placeholder sizes. All offsets are tracked so
     * writeIndexAndFixHeaders() can fill them in correctly on close.
     *
     * Byte offsets (verified against AVI spec):
     *   [  0] "RIFF"
     *   [  4] RIFF size (placeholder)         <- offRiffSize
     *   [  8] "AVI "
     *   [ 12] "LIST"
     *   [ 16] hdrl LIST size (placeholder)
     *   [ 20] "hdrl"
     *   [ 24] "avih"
     *   [ 28] avih size = 56
     *   [ 32..87] avih data
     *   [ 48] TotalFrames (placeholder)       <- offTotalFrames
     *   [ 88] "LIST"
     *   [ 92] strl LIST size (placeholder)
     *   [ 96] "strl"
     *   [100] "strh"
     *   [104] strh size = 56
     *   [108..163] strh data
     *   [140] Length (placeholder)            <- offStrhLength
     *   [164] "strf"
     *   [168] strf size = 40
     *   [172..211] BITMAPINFOHEADER
     *   [212] "LIST"
     *   [216] movi LIST size (placeholder)    <- offMoviSize
     *   [220] "movi"
     *   [224] <- first 00dc frame goes here   <- moviDataStart
     */
    private void writePlaceholderHeaders() throws IOException {
        int usPerFrame = 1_000_000 / TARGET_FPS;

        // RIFF AVI
        w4("RIFF");
        offRiffSize = pos();  wU32(0);    // [4]
        w4("AVI ");                        // [8]

        // LIST hdrl
        w4("LIST");                        // [12]
        long offHdrlSz = pos(); wU32(0);  // [16]
        w4("hdrl");                        // [20]
        long hdrlStart = pos();            // [24]

        // avih  [24..87]
        w4("avih"); wU32(56);             // [24][28]
        wU32(usPerFrame);                  // [32]
        wU32(TARGET_FPS * frameW * frameH * 3); // [36]
        wU32(0);                           // [40]
        wU32(0x10);                        // [44]
        offTotalFrames = pos(); wU32(0);  // [48]
        wU32(0);                           // [52]
        wU32(1);                           // [56]
        wU32(frameW * frameH * 3);         // [60]
        wU32(frameW);                      // [64]
        wU32(frameH);                      // [68]
        wU32(0); wU32(0); wU32(0); wU32(0); // [72..87]

        // LIST strl  [88..211]
        w4("LIST");                        // [88]
        long offStrlSz = pos(); wU32(0);  // [92]
        w4("strl");                        // [96]
        long strlStart = pos();            // [100]

        // strh  [100..163]
        w4("strh"); wU32(56);             // [100][104]
        w4("vids");                        // [108]
        w4("MJPG");                        // [112]
        wU32(0);                           // [116] Flags
        wU16(0);                           // [120] Priority
        wU16(0);                           // [122] Language
        wU32(0);                           // [124] InitialFrames
        wU32(1);                           // [128] Scale
        wU32(TARGET_FPS);                  // [132] Rate
        wU32(0);                           // [136] Start
        offStrhLength = pos(); wU32(0);   // [140]
        wU32(frameW * frameH * 3);         // [144]
        wI32(-1);                          // [148] Quality
        wU32(0);                           // [152] SampleSize
        wU16(0); wU16(0);                  // [156][158]
        wU16(frameW); wU16(frameH);        // [160][162]

        // strf / BITMAPINFOHEADER  [164..211]
        w4("strf"); wU32(40);             // [164][168]
        wU32(40);                          // [172]
        wI32(frameW);                      // [176]
        wI32(frameH);                      // [180] positive = bottom-up
        wU16(1);                           // [184]
        wU16(24);                          // [186]
        w4("MJPG");                        // [188]
        wU32(frameW * frameH * 3);         // [192]
        wI32(0); wI32(0);                  // [196][200]
        wU32(0); wU32(0);                  // [204][208]

        // Fix strl and hdrl sizes
        long now = pos();
        seek(offStrlSz); wU32((int)(now - strlStart + 4));
        seek(offHdrlSz); wU32((int)(now - hdrlStart + 4));
        seek(now);

        // LIST movi  [212..]
        w4("LIST");                        // [212]
        offMoviSize = pos(); wU32(0);     // [216]
        w4("movi");                        // [220]
        moviDataStart = pos();             // [224]
    }

    private synchronized void writeVideoFrame(byte[] jpeg) throws IOException {
        long frameOffset = pos();
        frameIndex.add(new long[]{frameOffset, jpeg.length});
        w4("00dc");
        wU32(jpeg.length);
        raf.write(jpeg);
        if ((jpeg.length & 1) != 0) raf.write(0); // pad to even
        frameCount++;
    }

    private void writeIndexAndFixHeaders() throws IOException {
        long idxStart  = pos();
        long moviBase  = moviDataStart - 4; // 'movi' fourcc position

        // idx1
        w4("idx1");
        wU32(frameIndex.size() * 16);
        for (long[] e : frameIndex) {
            w4("00dc");
            wU32(0x10);                         // AVIIF_KEYFRAME
            wU32((int)(e[0] - moviBase));       // offset from 'movi' tag
            wU32((int) e[1]);                   // JPEG data size
        }

        long fileEnd = pos();

        // Fix RIFF size
        seek(offRiffSize);   wU32((int)(fileEnd - 8));
        // Fix avih TotalFrames
        seek(offTotalFrames); wU32(frameCount);
        // Fix strh Length
        seek(offStrhLength);  wU32(frameCount);
        // Fix movi LIST size (from after size field to start of idx1)
        seek(offMoviSize);    wU32((int)(idxStart - offMoviSize - 4));
    }

    // -- Write helpers ---------------------------------------------------------

    private void w4(String s)   throws IOException { raf.write(s.getBytes("ASCII")); }
    private void wU32(int v)    throws IOException {
        raf.write(v & 0xFF); raf.write((v>>8)&0xFF);
        raf.write((v>>16)&0xFF); raf.write((v>>24)&0xFF);
    }
    private void wI32(int v)    throws IOException { wU32(v); }
    private void wU16(int v)    throws IOException {
        raf.write(v & 0xFF); raf.write((v>>8) & 0xFF);
    }
    private long pos()          throws IOException { return raf.getFilePointer(); }
    private void seek(long off) throws IOException { raf.seek(off); }
}
