package org.mdpnp.apps.testapp.physionet;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeTableColumn;
import javafx.scene.control.TreeTableView;

/**
 * PhysioNetBrowserApp
 * ===================
 * JavaFX controller for the PhysioNet Browser OpenICE app.
 *
 * Provides a three-panel layout:
 *   Left   - searchable/filterable database list (all PhysioNet databases,
 *             with PARADIGM-priority datasets highlighted)
 *   Middle - record list for the selected database
 *   Right  - signal info tree + download panel
 *
 * All network I/O runs on a single-thread ExecutorService so the FX
 * thread is never blocked. Results are marshalled back via
 * Platform.runLater().
 *
 * WFDB files are downloaded to a fixed path relative to the working
 * directory: ./physionet_data/{db_slug}/{record_base_name}.*
 *
 * The PhysioNet REST endpoints used:
 *   Record list   : https://physionet.org/files/{db}/{version}/RECORDS
 *   Header file   : https://physionet.org/files/{db}/{version}/{record}.hea
 *   Data files    : https://physionet.org/files/{db}/{version}/{record}.*
 */
public class PhysioNetBrowserApp {

    private static final Logger log =
        LoggerFactory.getLogger(PhysioNetBrowserApp.class);

    // ---- Fixed download root relative to working directory ----
    public static final String DOWNLOAD_ROOT = "physionet_data";

    // ---- PhysioNet REST base ----
    private static final String PN_FILES   = "https://physionet.org/files/";

    // ---- PARADIGM priority database slugs ----
    private static final Map<String, String> PRIORITY_DBS = new HashMap<>();
    static {
        PRIORITY_DBS.put("vtac",               "VTaC - VT Alarm Benchmark (ICU) *");
        PRIORITY_DBS.put("mimic4wdb",          "MIMIC-IV Waveform Database *");
        PRIORITY_DBS.put("mimic3wdb",          "MIMIC-III Waveform Database *");
        PRIORITY_DBS.put("mimic3wdb-matched",  "MIMIC-III Waveform Matched Subset *");
        PRIORITY_DBS.put("mimicdb",            "MIMIC (original) *");
        PRIORITY_DBS.put("mitdb",              "MIT-BIH Arrhythmia Database *");
        PRIORITY_DBS.put("challenge-2015",     "PhysioNet/CinC Challenge 2015 - ICU Alarms *");
        PRIORITY_DBS.put("challenge-2017",     "PhysioNet/CinC Challenge 2017 - AF *");
        PRIORITY_DBS.put("vitaldb",            "VitalDB - Intraoperative Vital Signs *");
    }

    // ---- Annotation extensions to probe for during download ----
    private static final String[] ANNOTATION_EXTS =
        { "atr", "qrs", "ann", "st", "alarm", "apn", "ari", "ecg" };

    // ---- FXML injected fields ----

    @FXML private TextField     usernameField;
    @FXML private PasswordField passwordField;

    @FXML private TextField dbSearchField;
    @FXML private CheckBox  priorityOnlyCb;
    @FXML private ListView<DbEntry> dbListView;
    @FXML private Label     dbCountLabel;

    @FXML private TextField recSearchField;
    @FXML private ListView<String> recListView;
    @FXML private Label     recCountLabel;

    @FXML private TreeTableView<SignalRow> signalTreeTable;
    @FXML private TreeTableColumn<SignalRow, String> propCol;
    @FXML private TreeTableColumn<SignalRow, String> valCol;
    @FXML private TextArea  commentsArea;

    @FXML private Label     coverageBadgeLabel;
    @FXML private Label     selectedLabel;
    @FXML private Label     downloadPathLabel;
    @FXML private Button    downloadBtn;
    @FXML private ProgressBar progressBar;
    @FXML private TextArea  logArea;

    // ---- State ----
    private final ObservableList<DbEntry> allDbs     = FXCollections.observableArrayList();
    private final ObservableList<DbEntry> filteredDbs = FXCollections.observableArrayList();
    private final ObservableList<String>  allRecords  = FXCollections.observableArrayList();
    private final ObservableList<String>  filteredRecs = FXCollections.observableArrayList();

    private String currentDbSlug    = null;
    private String currentDbVersion = null;
    private String currentRecord    = null;

    private final ExecutorService executor =
        Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "physionet-io");
            t.setDaemon(true);
            return t;
        });

    // ---- Lifecycle ----

    /**
     * Called by the factory after FXML injection is complete.
     * (Named initialize() to align with @FXML convention but called
     * explicitly because we don't use @FXML initialize signature here.)
     */
    public void initialize() {
        setupSignalTree();
        setupListeners();
        updateDownloadPathLabel();
        downloadBtn.setDisable(true);
        progressBar.setVisible(false);
        loadDatabaseList();
    }

    public void activate() {
        // Nothing needed on activation - browser is stateless between activations
    }

    public void stop() {
        // Nothing to pause
    }

    public void destroy() {
        executor.shutdownNow();
    }

    // ---- UI setup ----

    private void setupSignalTree() {
        propCol.setCellValueFactory(p ->
            new SimpleStringProperty(p.getValue().getValue().property));
        valCol.setCellValueFactory(p ->
            new SimpleStringProperty(p.getValue().getValue().value));
        propCol.setPrefWidth(200);
        valCol.setPrefWidth(360);
        signalTreeTable.setShowRoot(false);
        signalTreeTable.setRoot(new TreeItem<>(new SignalRow("", "")));
    }

    private void setupListeners() {
        // Database search / priority filter
        dbSearchField.textProperty().addListener((o, ov, nv) -> filterDatabases());
        priorityOnlyCb.selectedProperty().addListener((o, ov, nv) -> filterDatabases());

        // Database selection
        dbListView.getSelectionModel().selectedItemProperty()
            .addListener((o, ov, nv) -> { if (nv != null) onDbSelected(nv); });

        // Record search
        recSearchField.textProperty().addListener((o, ov, nv) -> filterRecords());

        // Record selection
        recListView.getSelectionModel().selectedItemProperty()
            .addListener((o, ov, nv) -> { if (nv != null) onRecordSelected(nv); });
    }

    private void updateDownloadPathLabel() {
        Path root = Paths.get(DOWNLOAD_ROOT).toAbsolutePath();
        downloadPathLabel.setText("Download root: " + root);
    }

    // ---- Database loading ----

    private void loadDatabaseList() {
        // PhysioNet's /rest/database-list/ endpoint has been retired.
        // We use a curated list of PARADIGM-relevant databases with known slugs
        // and versions. Record listing and downloads use /files/{slug}/{version}/
        // which remains functional.
        List<DbEntry> entries = new ArrayList<>();

        // Format: slug, title, version, isPriority
        // Versions verified against live PhysioNet file URLs.
        Object[][] CURATED = {
            // -- Alarm / arrhythmia (annotated) --
            { "vtac",             "VTaC - VT Alarm Benchmark (ICU, annotated)",  "1.0",   true  },
            { "challenge-2015",   "PhysioNet/CinC Challenge 2015 - ICU Alarms",  "1.0.0", true  },
            { "challenge-2017",   "PhysioNet/CinC Challenge 2017 - AF",          "1.0.0", true  },
            { "mitdb",            "MIT-BIH Arrhythmia Database",                 "1.0.0", true  },
            // -- MIMIC --
            { "mimic4wdb",        "MIMIC-IV Waveform Database",                  "0.1.0", true  },
            { "mimic3wdb",        "MIMIC-III Waveform Database",                 "1.0",   true  },
            { "mimic3wdb-matched","MIMIC-III Waveform Matched Subset",           "1.0",   true  },
            { "mimic4-ecg",       "MIMIC-IV-ECG (12-lead, 800K records)",        "1.0",   true  },
            // -- Intraoperative --
            { "vitaldb",          "VitalDB - Intraoperative Vital Signs",        "1.0.0", true  },
            // -- Sleep / respiration --
            { "apnea-ecg",        "Apnea-ECG Database",                          "1.0.0", false },
            { "bidmc",            "BIDMC PPG and Respiration Dataset",            "1.0.0", false },
            // -- Classic benchmarks --
            { "afdb",             "MIT-BIH Atrial Fibrillation Database",        "1.0.0", false },
            { "ltafdb",           "Long-Term AF Database",                        "1.0.0", false },
            { "svdb",             "MIT-BIH Supraventricular Arrhythmia Database","1.0.0", false },
            { "nstdb",            "MIT-BIH Noise Stress Test Database",           "1.0.0", false },
            { "ptb-xl",           "PTB-XL ECG Dataset",                           "1.0.3", false },
        };

        for (Object[] row : CURATED) {
            String slug     = (String)  row[0];
            String title    = (String)  row[1];
            String version  = (String)  row[2];
            boolean isPri   = (Boolean) row[3];
            String label    = isPri
                ? PRIORITY_DBS.getOrDefault(slug, title + " *")
                : title + "  [" + slug + "]";
            entries.add(new DbEntry(slug, title, label, isPri, version));
        }

        allDbs.setAll(entries);
        filterDatabases();
        dbCountLabel.setText(entries.size() + " databases (curated PARADIGM list)");
        log("Curated database list loaded.");
    }

    private void filterDatabases() {
        String q        = dbSearchField.getText().toLowerCase().trim();
        boolean priOnly = priorityOnlyCb.isSelected();
        filteredDbs.clear();
        for (DbEntry db : allDbs) {
            if (priOnly && !db.priority) continue;
            if (!q.isEmpty() && !db.label.toLowerCase().contains(q)
                             && !db.slug.toLowerCase().contains(q)) continue;
            filteredDbs.add(db);
        }
        dbListView.setItems(filteredDbs);
        dbCountLabel.setText(filteredDbs.size() + " of " + allDbs.size() + " databases");
    }

    // ---- Record loading ----

    private void onDbSelected(DbEntry db) {
        currentDbSlug    = db.slug;
        currentDbVersion = null;
        currentRecord    = null;
        recListView.setItems(FXCollections.observableArrayList());
        recCountLabel.setText("Loading...");
        clearSignalInfo();
        downloadBtn.setDisable(true);
        selectedLabel.setText("No record selected.");

        executor.submit(() -> {
            try {
                String version = db.version;

                // Fetch the RECORDS file.
                // Some databases (e.g. VTaC) store records in a waveforms/
                // subdirectory rather than the root. Try root first, then fallback.
                String recordsUrl = PN_FILES + db.slug + "/" + version + "/RECORDS";
                String recordsBody;
                String recordPrefix = ""; // prepended to each record path if using subdir
                try {
                    recordsBody = get(recordsUrl,
                        usernameField.getText().trim(),
                        passwordField.getText().trim());
                } catch (IOException e404) {
                    // Try waveforms/ subdirectory fallback
                    recordsUrl = PN_FILES + db.slug + "/" + version + "/waveforms/RECORDS";
                    recordsBody = get(recordsUrl,
                        usernameField.getText().trim(),
                        passwordField.getText().trim());
                    recordPrefix = "waveforms/";
                }
                final String recPfx = recordPrefix;
                String[] lines = recordsBody.split("\\r?\\n");
                List<String> records = new ArrayList<>();
                for (String line : lines) {
                    String l = line.trim();
                    if (!l.isEmpty()) records.add(recPfx + l);
                }
                final String resolvedVersion = version;
                Platform.runLater(() -> {
                    currentDbVersion = resolvedVersion;
                    allRecords.setAll(records);
                    filterRecords();
                });
            } catch (Exception e) {
                log.error("Failed to load records for " + db.slug, e);
                Platform.runLater(() ->
                    recCountLabel.setText("Error: " + e.getMessage()));
            }
        });
    }

    private void filterRecords() {
        String q = recSearchField.getText().toLowerCase().trim();
        filteredRecs.clear();
        for (String rec : allRecords) {
            if (!q.isEmpty() && !rec.toLowerCase().contains(q)) continue;
            filteredRecs.add(rec);
        }
        recListView.setItems(filteredRecs);
        recCountLabel.setText(filteredRecs.size() + " of " + allRecords.size() + " records");
    }

    // ---- Header / signal info ----

    private void onRecordSelected(String record) {
        currentRecord = record;
        selectedLabel.setText("Selected:  " + currentDbSlug + "  /  " + record);
        downloadBtn.setDisable(false);
        clearSignalInfo();
        commentsArea.setText("Loading header...");

        executor.submit(() -> {
            try {
                // Use the version that was resolved when the database was selected.
                // currentDbVersion is set by onDbSelected via Platform.runLater,
                // which always completes before the user can click a record.
                String version = currentDbVersion != null ? currentDbVersion : "1.0";
                // Strip any trailing directory component to get the base record name
                String recPath = record.endsWith("/")
                    ? record.substring(0, record.length() - 1)
                    : record;
                String heaUrl = PN_FILES + currentDbSlug + "/" + version
                    + "/" + recPath + ".hea";
                String heaContent = get(heaUrl,
                    usernameField.getText().trim(),
                    passwordField.getText().trim());
                HeaderInfo info = parseHea(heaContent);

                // Probe the ANNOTATORS file for this database - it lives at
                // the database root, not per-record. Attempt it once per db selection
                // and cache; for now we probe on every record selection (cheap HEAD).
                String annotUrl = PN_FILES + currentDbSlug + "/" + version + "/ANNOTATORS";
                try {
                    String annotContent = get(annotUrl,
                        usernameField.getText().trim(),
                        passwordField.getText().trim());
                    for (String line : annotContent.split("\\r?\\n")) {
                        String trimmed = line.trim();
                        if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                            // Format: ext<TAB>type<TAB>description
                            String ext = trimmed.split("\\s+")[0];
                            if (!ext.isEmpty()) info.annotators.add(ext);
                        }
                    }
                } catch (Exception ignored) {
                    // No ANNOTATORS file - not all databases have one
                }

                Platform.runLater(() -> populateSignalTree(info));
            } catch (Exception e) {
                log.error("Failed to read header for " + record, e);
                Platform.runLater(() -> commentsArea.setText("Header error: " + e.getMessage()));
            }
        });
    }

    private void clearSignalInfo() {
        signalTreeTable.getRoot().getChildren().clear();
        commentsArea.clear();
        coverageBadgeLabel.setText("");
    }

    private void populateSignalTree(HeaderInfo info) {
        TreeItem<SignalRow> root = signalTreeTable.getRoot();
        root.getChildren().clear();

        // -- Record metadata ----------------------------------------------
        root.getChildren().add(leaf("Sampling Frequency (Hz)", String.valueOf(info.fs)));
        root.getChildren().add(leaf("Number of Signals",       String.valueOf(info.nSig)));
        double durS = info.fs > 0 ? (double) info.sigLen / info.fs : 0;
        root.getChildren().add(leaf("Signal Length (samples)", String.valueOf(info.sigLen)));
        root.getChildren().add(leaf("Duration",
            String.format("%.1f s  (%.2f min)", durS, durS / 60)));
        root.getChildren().add(leaf("Start Time",
            info.baseTime.isEmpty() ? "N/A" : info.baseTime));
        root.getChildren().add(leaf("Start Date",
            info.baseDate.isEmpty() ? "N/A" : info.baseDate));

        // -- MDC coverage summary badge -----------------------------------
        WfdbMdcMapper.CoverageSummary coverage =
            WfdbMdcMapper.summarize(info.sigNames);
        coverageBadgeLabel.setText(coverage.badge());

        // -- Signals subtree - with per-signal MDC annotation ------------
        TreeItem<SignalRow> sigNode = new TreeItem<>(
            new SignalRow("Signals", info.nSig + " channels  -  " + coverage.badge()));
        sigNode.setExpanded(true);

        for (int i = 0; i < info.sigNames.size(); i++) {
            String wfdbName = info.sigNames.get(i);
            String wfdbUnit = i < info.units.size() ? info.units.get(i) : "";

            java.util.Optional<WfdbMdcMapper.MdcMapping> mapping =
                WfdbMdcMapper.lookup(wfdbName);

            // Channel label: "ch 0  PLETH [nu]"
            String chLabel = "  ch " + i + "  " + wfdbName
                + (wfdbUnit.isEmpty() ? "" : "  [" + wfdbUnit + "]");

            // MDC annotation line
            String mdcLabel = mapping
                .map(WfdbMdcMapper.MdcMapping::displayLabel)
                .orElse("-  no MDC mapping");

            TreeItem<SignalRow> chNode = new TreeItem<>(
                new SignalRow(chLabel, mdcLabel));

            // If mapped, add detail sub-rows for the MDC id, unit, and description
            if (mapping.isPresent()) {
                WfdbMdcMapper.MdcMapping m = mapping.get();
                chNode.getChildren().add(leaf("    metric_id", m.mdcId));
                chNode.getChildren().add(leaf("    unit",      m.mdcUnit));
                chNode.getChildren().add(leaf("    type",
                    m.signalType == WfdbMdcMapper.SignalType.WAVEFORM
                        ? "Waveform  (ice.SampleArray)"
                        : "Numeric   (ice.Numeric)"));
                chNode.getChildren().add(leaf("    description", m.description));
            }

            sigNode.getChildren().add(chNode);
        }
        root.getChildren().add(sigNode);

        // -- Annotations subtree - always shown unconditionally -----------
        // Annotations are the clinical ground-truth labels (e.g. VTaC true/false
        // alarm adjudications, MIT-BIH beat labels). They do not map to MDC
        // metric IDs - they are metadata about the waveform, not signals.
        if (!info.annotators.isEmpty()) {
            TreeItem<SignalRow> annNode = new TreeItem<>(
                new SignalRow("Annotations",
                    "*  " + info.annotators.size() + " annotator type(s)  -  always downloaded"));
            annNode.setExpanded(true);
            for (String ann : info.annotators) {
                annNode.getChildren().add(
                    leaf("  *  ." + ann, annotatorDescription(ann)));
            }
            root.getChildren().add(annNode);
        } else {
            root.getChildren().add(leaf("Annotations", "*  none found in ANNOTATORS file"));
        }

        // -- Header comments ----------------------------------------------
        commentsArea.setText(
            info.comments.isEmpty() ? "(no comments in header)"
                                    : String.join("\n", info.comments));
    }

    /**
     * Returns a plain-English description of common WFDB annotator extensions.
     * These are the suffixes of annotation files (.atr, .qrs, etc.).
     */
    private static String annotatorDescription(String ext) {
        switch (ext.toLowerCase(java.util.Locale.ROOT)) {
            case "atr": return "Reference beat/rhythm annotations";
            case "qrs": return "QRS complex annotations";
            case "ann": return "General annotations";
            case "st":  return "ST segment change annotations";
            case "alarm": return "Alarm event annotations (true/false)";
            case "apn": return "Apnea annotations";
            case "ari": return "Arrhythmia annotations";
            case "ecg": return "ECG waveform annotations";
            case "man": return "Manual annotations";
            case "bib": return "Beat-indexed beat annotations";
            default:    return "Annotation file (."+ext+")";
        }
    }

    private TreeItem<SignalRow> leaf(String prop, String val) {
        return new TreeItem<>(new SignalRow(prop, val));
    }

    // ---- Download ----

    @FXML
    private void onDownload() {
        if (currentDbSlug == null || currentRecord == null) return;

        String username = usernameField.getText().trim();
        String password = passwordField.getText().trim();
        String version  = currentDbVersion != null ? currentDbVersion : "1.0";
        String record   = currentRecord;
        String dbSlug   = currentDbSlug;

        // Compute local destination directory
        String recBase = record.contains("/")
            ? record.substring(record.lastIndexOf('/') + 1)
            : record;
        Path destDir = Paths.get(DOWNLOAD_ROOT, dbSlug).toAbsolutePath();

        logArea.clear();
        log("Starting download");
        log("  Database : " + dbSlug + "  v" + version);
        log("  Record   : " + record);
        log("  Dest     : " + destDir);
        log("");

        downloadBtn.setDisable(true);
        progressBar.setVisible(true);
        progressBar.setProgress(-1);   // indeterminate

        executor.submit(() -> {
            try {
                Files.createDirectories(destDir);

                // Always download .hea and .dat
                List<String> filesToGet = new ArrayList<>();
                filesToGet.add(record + ".hea");
                filesToGet.add(record + ".dat");

                // Probe for annotation files (HEAD request)
                for (String ext : ANNOTATION_EXTS) {
                    String probe = PN_FILES + dbSlug + "/" + version
                        + "/" + record + "." + ext;
                    if (headExists(probe, username, password)) {
                        filesToGet.add(record + "." + ext);
                    }
                }

                Platform.runLater(() ->
                    log("Downloading " + filesToGet.size() + " file(s)..."));

                for (String filePath : filesToGet) {
                    String fName  = filePath.contains("/")
                        ? filePath.substring(filePath.lastIndexOf('/') + 1)
                        : filePath;
                    String fileUrl = PN_FILES + dbSlug + "/" + version + "/" + filePath;
                    Platform.runLater(() -> log("  -> " + fName));
                    downloadFile(fileUrl, destDir.resolve(fName), username, password);
                }

                Platform.runLater(() -> {
                    log("");
                    log("OK Download complete.");
                    log("  Files saved to: " + destDir);
                    log("  Ready for replay into OpenICE via PhysioNetReplayDevice.");
                    progressBar.setVisible(false);
                    downloadBtn.setDisable(false);
                });

            } catch (Exception e) {
                log.error("Download failed", e);
                Platform.runLater(() -> {
                    log("X Download failed: " + e.getMessage());
                    progressBar.setVisible(false);
                    downloadBtn.setDisable(false);
                });
            }
        });
    }

    // ---- HTTP helpers ----

    /**
     * GET request returning response body as a String.
     * Passes Basic Auth credentials if username is non-empty.
     */
    private String get(String urlStr, String username, String password) throws IOException {
        try {
            URL url = URI.create(urlStr).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            HttpURLConnection.setFollowRedirects(true);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(30_000);
            conn.setRequestProperty("User-Agent", "OpenICE-PhysioNetBrowser/1.0");
            conn.setRequestProperty("Accept", "application/json");
            if (username != null && !username.isEmpty()) {
                String creds = Base64.getEncoder().encodeToString(
                    (username + ":" + password).getBytes(StandardCharsets.UTF_8));
                conn.setRequestProperty("Authorization", "Basic " + creds);
            }
            int code = conn.getResponseCode();
            if (code == 401) throw new IOException(
                "Authentication required - enter PhysioNet credentials.");
            if (code == 404) throw new IOException(
                "Not found (404): " + urlStr);
            if (code >= 400) throw new IOException(
                "HTTP error " + code + " for " + urlStr);
            try (InputStream is = conn.getInputStream();
                 BufferedReader br = new BufferedReader(
                     new InputStreamReader(is, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line).append('\n');
                return sb.toString();
            }
        } catch (java.net.SocketTimeoutException e) {
            throw new IOException("Connection timed out reaching physionet.org - check network access from the OpenICE JVM", e);
        }
    }

    /**
     * HEAD request to test whether a file exists without downloading it.
     */
    private boolean headExists(String urlStr, String username, String password) {
        try {
            URL url = URI.create(urlStr).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            HttpURLConnection.setFollowRedirects(true);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestMethod("HEAD");
            conn.setConnectTimeout(5_000);
            conn.setReadTimeout(5_000);
            conn.setRequestProperty("User-Agent", "OpenICE-PhysioNetBrowser/1.0");
            conn.setRequestProperty("Accept", "*/*");
            if (username != null && !username.isEmpty()) {
                String creds = Base64.getEncoder().encodeToString(
                    (username + ":" + password).getBytes(StandardCharsets.UTF_8));
                conn.setRequestProperty("Authorization", "Basic " + creds);
            }
            return conn.getResponseCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Download a single file from PhysioNet into destFile.
     */
    private void downloadFile(String urlStr, Path destFile,
                               String username, String password)
            throws IOException {
        try {
            URL url = URI.create(urlStr).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            HttpURLConnection.setFollowRedirects(true);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(120_000);
            conn.setRequestProperty("User-Agent", "OpenICE-PhysioNetBrowser/1.0");
            conn.setRequestProperty("Accept", "*/*");
            if (username != null && !username.isEmpty()) {
                String creds = Base64.getEncoder().encodeToString(
                    (username + ":" + password).getBytes(StandardCharsets.UTF_8));
                conn.setRequestProperty("Authorization", "Basic " + creds);
            }
            int code = conn.getResponseCode();
            if (code == 401) throw new IOException(
                "Authentication required - enter PhysioNet credentials.");
            if (code >= 400) throw new IOException("HTTP " + code + " for " + urlStr);

            try (InputStream is = conn.getInputStream();
                 FileOutputStream fos = new FileOutputStream(destFile.toFile())) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) != -1) fos.write(buf, 0, n);
            }
        } catch (java.net.SocketTimeoutException e) {
            throw new IOException("Connection timed out reaching physionet.org - check network access from the OpenICE JVM", e);
        }
    }

    // ---- WFDB .hea parser ----

    /**
     * Minimal parser for the WFDB header format.
     * Line 1: record_name nSig fs sigLen base_time base_date
     * Lines 2..n+1: signal spec (filename format gain baseline adc_res adc_zero name)
     * Remaining lines starting with '#': comments
     */
    private HeaderInfo parseHea(String content) {
        HeaderInfo info = new HeaderInfo();
        String[] lines = content.split("\\r?\\n");
        boolean firstLine = true;
        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("#")) {
                info.comments.add(line.substring(1).trim());
                continue;
            }
            if (firstLine) {
                firstLine = false;
                // Format: record nSig [fs [/counterFreq [baseCounter]] [sigLen [baseTime [baseDate]]]]
                String[] parts = line.split("\\s+");
                if (parts.length >= 2) {
                    try { info.nSig = Integer.parseInt(parts[1]); } catch (Exception ignored) {}
                }
                if (parts.length >= 3) {
                    // fs may contain /counterFreq suffix
                    String fsPart = parts[2].contains("/")
                        ? parts[2].substring(0, parts[2].indexOf('/'))
                        : parts[2];
                    try { info.fs = Double.parseDouble(fsPart); } catch (Exception ignored) {}
                }
                if (parts.length >= 4) {
                    try { info.sigLen = Long.parseLong(parts[3]); } catch (Exception ignored) {}
                }
                if (parts.length >= 5) info.baseTime = parts[4];
                if (parts.length >= 6) info.baseDate = parts[5];
            } else if (info.sigNames.size() < info.nSig) {
                // Signal specification line
                // Format: filename format gain baseline adcRes adcZero initVal checksum blockSize sigName
                String[] parts = line.split("\\s+");
                // Signal name is the last token; unit may follow in parentheses in gain field
                String sigName = parts.length >= 9 ? parts[8] : ("ch" + info.sigNames.size());
                info.sigNames.add(sigName);
                // Unit is embedded in gain field as "gain(unit)" e.g. "200(mV)"
                String unit = "";
                if (parts.length >= 3) {
                    String gainField = parts[2];
                    int lp = gainField.indexOf('(');
                    int rp = gainField.indexOf(')');
                    if (lp >= 0 && rp > lp) {
                        unit = gainField.substring(lp + 1, rp);
                    }
                }
                info.units.add(unit);
            }
        }
        return info;
    }

    // ---- Logging helper ----

    private void log(String msg) {
        // Must be called on FX thread
        Platform.runLater(() -> {
            if (logArea != null) {
                logArea.appendText(msg + "\n");
            }
        });
    }

    // ---- Inner data classes ----

    static class DbEntry {
        final String slug;
        final String title;
        final String label;
        final boolean priority;
        final String version;   // add this field

        DbEntry(String slug, String title, String label, boolean priority, String version) {
            this.slug     = slug;
            this.title    = title;
            this.label    = label;
            this.priority = priority;
            this.version  = version;
        }
        @Override public String toString() { return label; }
    }

    static class SignalRow {
        final String property;
        final String value;
        SignalRow(String property, String value) {
            this.property = property;
            this.value    = value;
        }
    }

    static class HeaderInfo {
        int          nSig       = 0;
        double       fs         = 0;
        long         sigLen     = 0;
        String       baseTime   = "";
        String       baseDate   = "";
        List<String> sigNames   = new ArrayList<>();
        List<String> units      = new ArrayList<>();
        List<String> comments   = new ArrayList<>();
        List<String> annotators = new ArrayList<>(); // probed from ANNOTATORS file
    }
}
