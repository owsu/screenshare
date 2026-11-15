# WiSafe MVP

Android Wi-Fi collection → FastAPI → SQLite → explainable co-movement scores.
The MVP needs no Databricks account. Kotlin handles the Android APIs directly;
the API can also support a React Native UI later.

## Run the backend

Python 3.11+ is required. From this folder in PowerShell:

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -e '.[test]'
$env:WISAFE_API_KEY = .\.venv\Scripts\python.exe -c "import secrets; print(secrets.token_urlsafe(32))"
.\.venv\Scripts\python.exe -m uvicorn wisafe.api:create_app --factory --host 127.0.0.1 --port 8000
```

If the Windows Python alias fails, use an installed Python executable for the
first command. This workspace already has a `.venv` with dependencies installed.
Keep the generated token for the Android app. Requests use
`Authorization: Bearer <token>`. Without a configured key the API rejects requests.
Open `http://127.0.0.1:8000/docs` for the request schemas. Database defaults to
`data/wisafe.sqlite3`; override with `WISAFE_DB`. Existing `data/shadowtrace.sqlite3`
databases are reused automatically. Legacy `shadowtrace` launch commands and
`SHADOWTRACE_*` environment variables remain supported. The Android application ID
is retained so installing the update preserves its device ID and queued scans.
This is a single-owner MVP: a bearer token grants access to all device IDs.

## Run Android

1. Install [Android Studio](https://developer.android.com/studio), Android SDK
   Platform 35, and SDK Build Tools 35.0.0 through its SDK Manager.
2. Open the `android` folder as the project. Set the Gradle JDK to **17**.
   The project uses AGP 8.9.2 and Gradle 8.11.1.
3. Sync Gradle and run the debug app on an Android 10+ phone or emulator.
4. Enter the backend URL and token. Select `auto`, `active`, or `passive`, grant
   **precise location**, enable location services and Wi-Fi, then tap Start.

For an emulator the default URL is `http://10.0.2.2:8000`. A physical phone can
use `adb reverse tcp:8000 tcp:8000`, then `http://localhost:8000` in the debug app.
Both paths keep the backend bound to your computer's loopback interface.
Use an HTTPS URL for remote access; release builds block cleartext HTTP.
Emulators cannot reproduce real Wi-Fi co-movement; use a physical phone to
validate detection. Monitoring is foreground-only and stops when leaving the app.
No background monitoring or continuous 10-second fresh-scan guarantee is claimed.

The app polls the Android scan cache every **5 seconds**, requests scans every
**35 seconds** while foreground monitoring is active, and immediately processes
Android scan-completion broadcasts. A one-second countdown shows the next request
and the age of the most recent scan. The cooldown persists across app launches.
Android normally allows four foreground requests per two minutes; other apps and
the operating system can also provide scan results. A request is not a guarantee
of a fresh scan. Both collector and server
check per-AP timestamps; cached sightings cannot repeatedly earn evidence.
See [Android's scan restrictions](https://developer.android.com/develop/connectivity/wifi/wifi-scan).
The SQLite outbox retries in FIFO order and persists across app restarts. It holds
up to 5,000 batches, then reports that it is full rather than dropping queued data.
Fix the server/credentials and reconnect to drain it. Invalid queued batches return
an error and require investigation rather than being silently discarded.

## Detection behavior

The Android UI has four destinations: **Home** for live diagnostics and mode
controls, **Networks** for the phone's cache and stored evidence, **Learn** for six
illustrated lessons plus interactive library/walking/bus scenarios, and **Setup**
for the backend connection. The first launch opens the guide; it can be skipped
or revisited at any time. The server URL is remembered; the token stays in memory.

The network counter shows scan-cache results independently of GPS and server
availability; those results can be stale. The Networks screen search filters both
current and earlier networks by a case-insensitive name or BSSID substring.
Movement links summarize stored evidence.
Wi-Fi scan age, location accuracy/age, connection status and queued batches are
shown separately. No movement links while stationary is expected. Zero visible
networks calls for checking Wi-Fi/permission/scan status, not assuming safety.
Network cards show SSID, BSSID, signal, the BSSID's last synced evidence, and then
scan age. Records already in the phone's cache are not repeated under earlier
observations. Within each section, higher evidence scores appear first; unsynced
cached networks appear last. Cached cards use BSSID as a stable tie-breaker and
reorder when synced scores change. Names use Android's modern SSID reader when
available and can fall
back to the last known name for the same BSSID, explicitly labeled as historical.
If Android never provides a name, the card says “Network name unavailable” rather
than incorrectly asserting that the network is hidden. Cached data without a
server record says “Evidence not synced yet”; it does not invent a zero score.
Paused and incomplete-coverage states label earlier data as potentially out of date.
The original vector illustrations teach concepts; they are not maps of real APs.

UI design references: [Material component examples](https://developer.android.com/design/ui/mobile/guides/components/material-overview)
and [Android accessibility guidance](https://developer.android.com/design/ui/mobile/guides/foundations/accessibility).
Guided learning also draws on [Duolingo's explanation of its tab redesign](https://blog.duolingo.com/core-tabs-redesign/).
The interface uses native views, scalable text, labeled controls, system-bar insets,
and touch targets of at least 48 dp without additional UI dependencies.
The current styling is a native Android adaptation of AdminMart's **Modernize**
template listed on 21st.dev. [Source, adaptation details and license](docs/ui/template-source.md).

Emulator screenshots: [Home](docs/ui/monitor.png), [Live collection](docs/ui/live.png),
[Learn](docs/ui/learn.png), [Networks](docs/ui/networks.png), and [Setup](docs/ui/setup.png).
The redesign was built and linted locally and
visually checked on an API 37 emulator, including enlarged system text. Real
Wi-Fi collection still requires validation on a physical phone.

Defaults are transparent heuristics, not calibrated probabilities:

| Condition | Behavior |
| --- | --- |
| Library / idle / explicit passive | No score growth; reset movement anchor |
| Active requested while idle | Motion validation still applies |
| Same BSSID across locations | +15 per confirmed transition |
| Third location | Further evidence after another transition |
| Shared movement with N APs | Each receives 15/N, reducing transit false positives |
| Same SSID / different BSSIDs | Independent tracks |
| Changed BSSID | New track; no inferred device identity |
| Known campus/bus infrastructure | User label suppresses future increments and clears score |
| WPA2/WPA3/open | Stored as metadata; no scoring effect |
| Stale scans / invalid GPS / no updates | Insufficient monitoring coverage; explain which input is missing |
| Time passing | Exponential score decay, one-hour half-life |

A transition requires at least 200 m **after subtracting both accuracy radii**,
within 30 minutes of its anchor. Locations must be at most 15 seconds old with
accuracy ≤50 m. Scans must be at most 30 seconds old and within 10 seconds of the
location fix. Implausible travel above 65 m/s is rejected. Displacement must agree
with reported speed when available; otherwise speed is estimated from GPS.
Slow GPS drift can still fool GPS-only motion inference; real-device tuning is
required. Explicit passive mode overrides active inference. Scores cap at 100;
10 indicates possible co-movement and 30 repeated co-movement. Read-time decay
works even when no further scans arrive. Freshness expires after 60 seconds.

Bus Wi-Fi can genuinely move with the user. A BSSID identifies an observed AP
interface, not a person, and can be spoofed or randomized. Co-movement cannot
establish malicious intent. No observations must never be presented as safety.
Infrastructure is labeled manually; there is no reliable automatic campus classifier.
Fresh AP observations create stored tracks even without valid GPS, with zero score
and no movement anchor. Only a subsequent usable scan/location pairing can create
an anchor. Recent reused location fixes are accepted without fabricating motion.

## API

| Endpoint | Purpose |
| --- | --- |
| `POST /v1/scans` | Validate and atomically ingest a batch |
| `GET /v1/devices/{id}/status` | Current mode, coverage, decayed scores |
| `GET /v1/devices/{id}/evidence?after_id=0` | Explain scored transitions |
| `PUT /v1/devices/{id}/infrastructure/{bssid}` | Body: `{"known_infrastructure":true}` |
| `GET /v1/devices/{id}/export?after_id=0` | Paginated NDJSON observations |

`captured_at` is UTC Unix seconds; `elapsed_us` and AP `timestamp_us` use Android's
monotonic microseconds since boot. Device ID persists across launches; session
UUID changes each collector run. Duplicate batch identities are idempotent; a
conflicting payload or out-of-order capture returns HTTP 409. Observation uniqueness
is `(device_id, session_id, bssid, timestamp_us)`. Transactions serialize scoring.
Exact retries remain safe after an acknowledgement is lost. Uploads must be FIFO.

## Verify

To rebuild the APK in PowerShell, run this from the repository root:

```powershell
.\build-apk.ps1
```

This selects an installed **JDK 17**, overrides the Gradle daemon JVM for the
build, and prints the APK path only on success. It leaves your terminal's Java
settings unchanged afterward. Use `-Lint` to include Android lint, or
`-JdkPath "C:\path\to\jdk-17"` to choose a specific installation. If PowerShell
blocks script execution, run `powershell -ExecutionPolicy Bypass -File .\build-apk.ps1`.
The Android build requires JDK 17; Java 25 is incompatible with its Gradle 8.11.1
wrapper. Android Studio should also have **Gradle JDK = 17** selected.

```powershell
.\.venv\Scripts\python.exe -m unittest discover -s tests -v
.\.venv\Scripts\python.exe -m wisafe.demo
cd android
.\gradlew.bat :app:assembleDebug :app:lintDebug
```

Backend tests cover motion, idle mode, identity separation, shared transit,
cached results, GPS rejection, missing coverage, decay, restarts, API validation,
auth, and export. Android build/lint also runs in the included GitHub Actions
workflow when pushed. Local Android compilation requires SDK/JDK setup above.

## Optional analytics later

Export SQLite observations without running the API:

```powershell
.\.venv\Scripts\python.exe -m wisafe.export --device DEVICE_UUID --output exports/observations.ndjson
```

`--after-id` supports incremental export; output reports the last ID. If Databricks
is needed later, install `.[databricks]`, configure SDK authentication, and add
`--volume-path /Volumes/catalog/schema/volume/observations.ndjson`. Import
`databricks/ingest.py` as a notebook and set its source/target widgets. Its Delta
merge deduplicates retries by natural observation key. No Databricks upload occurs
unless that option is provided. This integration requires a workspace to validate.

Location/BSSID data is stored in local SQLite and exports; the API token stays
in app memory. Use test data while developing. This MVP has no automatic retention,
database encryption, multi-user authorization, or production deployment configuration.
