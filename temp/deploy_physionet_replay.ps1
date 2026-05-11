# deploy_physionet_replay.ps1
# Run from PowerShell: .\deploy_physionet_replay.ps1

$SRC      = "C:\mic\physnetfiles"
$JAVA_PKG = "C:\mic\mdpnp-private\interop-lab\demo-apps\src\main\java\org\mdpnp\apps\testapp\physionet"
$FXML_PKG = "C:\mic\mdpnp-private\interop-lab\demo-apps\src\main\resources\org\mdpnp\apps\testapp\physionet"

Write-Host ""
Write-Host "============================================================"
Write-Host " PhysioNet Replay Deployment"
Write-Host "============================================================"
Write-Host ""

# Verify source files
$files = @(
    "WfdbReader.java",
    "PhysioNetReplayDevice.java",
    "PhysioNetReplayApp.java",
    "PhysioNetReplayAppFactory.java",
    "PhysioNetReplayApp.fxml"
)

Write-Host "[0/3] Verifying source files..."
$missing = $false
foreach ($f in $files) {
    if (Test-Path "$SRC\$f") {
        Write-Host "   Found : $f"
    } else {
        Write-Host "  MISSING: $f"
        $missing = $true
    }
}
if ($missing) {
    Write-Host ""
    Write-Host "  ERROR: One or more source files missing. Aborting."
    exit 1
}
Write-Host ""

# Verify target directories
Write-Host "[1/3] Checking target directories..."
if (-not (Test-Path $JAVA_PKG)) {
    Write-Host "  ERROR: Java package directory not found:"
    Write-Host "  $JAVA_PKG"
    Write-Host "  Deploy the PhysioNet Browser files first."
    exit 1
}
Write-Host "   OK : $JAVA_PKG"

if (-not (Test-Path $FXML_PKG)) {
    Write-Host "  ERROR: FXML resources directory not found:"
    Write-Host "  $FXML_PKG"
    exit 1
}
Write-Host "   OK : $FXML_PKG"
Write-Host ""

# Copy Java files
Write-Host "[2/3] Copying Java source files..."
$javaFiles = @(
    "WfdbReader.java",
    "PhysioNetReplayDevice.java",
    "PhysioNetReplayApp.java",
    "PhysioNetReplayAppFactory.java"
)
foreach ($f in $javaFiles) {
    Copy-Item -Path "$SRC\$f" -Destination "$JAVA_PKG\$f" -Force
    if ($?) { Write-Host "   OK : $f" } else { Write-Host "  FAIL: $f" }
}
Write-Host ""

# Copy FXML file
Write-Host "[3/3] Copying FXML resource file..."
Copy-Item -Path "$SRC\PhysioNetReplayApp.fxml" -Destination "$FXML_PKG\PhysioNetReplayApp.fxml" -Force
if ($?) { Write-Host "   OK : PhysioNetReplayApp.fxml" } else { Write-Host "  FAIL: PhysioNetReplayApp.fxml" }

Write-Host ""
Write-Host "============================================================"
Write-Host " Done. Now run:"
Write-Host "   cd C:\mic\mdpnp-private"
Write-Host "   .\gradlew :interop-lab:demo-apps:compileJava"
Write-Host "============================================================"
Write-Host ""
