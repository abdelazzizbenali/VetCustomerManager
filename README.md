# VetCustomerManager 4.0

Veterinary customer manager — now a **JavaFX desktop app** that stores everything in **your own
online Supabase database**, keeps a **local offline copy** of it, and is delivered as a
**ready-to-use Windows `.exe`** (no Java or anything else to install for your users).

![platform](https://img.shields.io/badge/platform-Windows-blue) ![ui](https://img.shields.io/badge/UI-JavaFX%2021-teal)
![db](https://img.shields.io/badge/database-Supabase%20(PostgreSQL)-green) ![sync](https://img.shields.io/badge/sync-offline--first-orange)

---

## What's new in 4.0

| Area | Before (v3) | Now (v4) |
|---|---|---|
| UI | Swing · Material theme | **JavaFX 21**, dark ocean-teal theme, dashboard, status bar |
| Database | local MySQL on one PC | **Supabase (online PostgreSQL)** — data follows you on every PC |
| Offline work | (DB had to be up) | **SQLite cache** — full offline mode, syncs automatically later |
| Mobile apps | — | ready: mobile apps just use the same Supabase tables/API |
| Scanner | camera only | **USB scanner (plug & play) + serial COM scanner + camera/QR** |
| Startup | silent checks | **loading screen** checking folder, DB, sound, camera, scanner, connection |
| Delivery | fat `.jar` | **`.exe` installer** (jpackage, Java embedded — nothing to install) |

Features carried over & extended: daily usage (sales/services with partial sizes), clients with
paid/unpaid tracking, **client animals — registered pets (dogs, cats…) AND livestock counters
(sheep ×45, cows ×12…)**, medicine stock with low-stock & expiry alerts, appointments,
scan sounds (`Found.wav` / `notFound.wav`), and all your original icons.

---

## 1 · Create your online database (5 minutes, one time)

1. Go to <https://supabase.com> → **New project** (the free tier is plenty).
2. Open the **SQL Editor** → **New query**, paste the whole
   [`supabase/schema.sql`](supabase/schema.sql) file from this repo and press **Run**.
   You should see *"Success. No rows returned"*.
3. Go to **Project Settings → API** and copy:
   - **Project URL** (`https://xxxxxxxxxxxx.supabase.co`)
   - **anon public key** (the long `eyJ…` string — this is normal, it is a JWT)

That's all. The schema creates: `medicines`, `clients`, `client_animals`, `transactions`,
`appointments`, `app_meta` (+ triggers for stock deduction and `updated_at` for sync).

---

## 2 · Get the ready-to-use `.exe`

### Option A — automatic (recommended): GitHub Actions

Every push to this repo builds the installer on Microsoft's servers — you don't need to install
any build tool:

1. Open the **Actions** tab of the repo → choose the latest **Build** run (or run it manually
   with **Run workflow**).
2. When it finishes, download the artifact **`VetCustomerManager-Windows-EXE`** — inside is
   `VetCustomerManager-4.0.0.exe`.

### Option B — on your own Windows PC

1. Install once: JDK 21 ([Adoptium Temurin 21](https://adoptium.net)), Apache Maven,
   WiX Toolset v3 (from <https://wixtoolset.org>).
2. Double-click **`build-exe.bat`** (in this repository).
3. Your installer appears in **`dist\VetCustomerManager-4.0.0.exe`**.

The produced installer is self-contained (Java 21 + JavaFX are embedded via `jlink`+`jpackage`).
Target computers **do not need Java** — they just run the installer / app. By design it installs
**per-user** (no admin rights needed), with a Start-menu entry and desktop shortcut.

---

## 3 · First launch

1. The program shows a **loading screen** and checks, one by one: application folder, local
   database, sound system, camera, barcode-scanner driver and online-database reachability.
2. On the **first launch** the setup screen asks for your **Project URL** and **API key**
   (from step 1). Press **Test connection** — you should read *"Connection OK"*.
3. The main window opens and downloads your data (first sync). From then on the app
   **synchronizes every 30 s** (configurable) and right after every change.

If the database can't be reached, you can **Work offline**: everything you do is stored locally
and **uploads automatically** when the connection returns — the status bar (bottom) always shows
where you stand: `Online database connected` / `Synchronizing…` / `Offline mode` / `N changes
waiting to upload`.

Per-user data lives in `%APPDATA%\VetCustomerManager\` (`config.properties`, `data.sqlite`,
`app.log`). Settings → "Local data" shows the path and CSV export.

---

## 4 · Barcode / QR scanners

| Device | Setup | How it works |
|---|---|---|
| **USB keyboard scanner** (~"dumb" USB gun) | nothing — it works out of the box | rapid key-bursts ending with ENTER are detected globally by the app; scanning a product pre-fills the sale in **Daily Usage**, finds it in **Medicines**, and plays `Found.wav` / `notFound.wav` |
| **Serial (COM) scanner** | Settings → Scanner → pick the COM port & baud rate | read on a background thread, each line = one scan |
| **Any camera / webcam** | *Camera scan* buttons (Daily Usage, Medicines, barcode field) | live preview window decodes **barcodes and QR codes** (ZXing) |

The startup check tells you what it found. If you plug a USB scanner later, no restart is needed —
the "driver" is built in (HID keyword-wedge detection).

---

## 5 · Architecture (for contributors)

```
Desktop app (JavaFX 21, this repo)
 ├── UI            dev.parent.ui     Dashboard / DailyUsage / Clients / Medicines / Appointments / Settings
 ├── Scanner       dev.parent.scanner  HID keyboard-wedge hook · jSerialComm · ZXing camera dialog
 ├── Data          dev.parent.db     DAOs -> SQLite (local cache, always what the UI uses)
 │                                 SyncService <-> SupabaseClient (PostgREST REST API)
 └── Supabase      PostgreSQL + triggers   (sole source of truth, shared with future mobile apps)
```

**Offline-first sync model**

- Every row has a stable `uuid`; devices upsert on it (`?on_conflict=uuid`) — no ID collisions.
- `updated_at` (server trigger) drives incremental pulls: each device downloads only rows changed
  since its last sync per table.
- Deletes are **soft deletes** (`is_deleted`) so deletions propagate to every device.
- Stock deduction for a sale happens **once**, in the server trigger
  (`apply_stock_deduction` = the old `SellMedicinePartial` procedure). The app mirrors the same
  rules locally so stock looks right even while offline. Upload order is
  `clients → medicines → animals → transactions → appointments` (FK-safe), and stock columns are
  omitted from a medicine push when that medicine has pending sales — so no double deduction.
- Conflict strategy: a locally-edited (dirty) row always wins on the next push
  (last-writer-wins, local priority). Good enough for a clinic's own data entry.

**Adding mobile apps later**: they talk to the same Supabase project (REST or the official
supabase mobile SDKs), reuse the same tables/uuid conventions, and everything stays in sync with
the desktop apps.

**Development**

```bash
mvn javafx:run          # run from source
mvn package             # build the jar
```

Code layout: `dev.parent.VetApp` (startup flow), `.Launcher`, `config/`, `db/`, `model/`,
`scanner/`, `ui/`, `util/`. UI is written in plain Java + `theme.css` (no FXML), so it is trivial
to follow and refactor.

---

## 6 · Security notes

- The app uses your **anon** key, stored per-user in `config.properties`. The shipped RLS
  policies allow full access to that key (equivalent to the old single-PC app). Before deploying
  broadly, read the warning comment in `supabase/schema.sql` (§10) and consider adding
  Supabase Auth + stricter policies.
- Never commit your API key anywhere in the repo. The app asks for it at runtime.

## 7 · Ideas / roadmap

- Realtime (Supabase websockets) push instead of 30 s polling — easy drop-in later.
- Supabase Auth + per-employee accounts and audit log.
- Receipt printing of the daily journal; PDF export of client balances.
- The mobile companion app talked about above.

## 4.0 architecture: frontend / backend split

The project is a two-process desktop suite inside a single installer:

- **frontend/** `vetms-client` — JavaFX UI (Java 21, virtual threads). Every
  screen runs on the FX thread with background work on virtual threads
  (`Bg`), while the scan events flow through client-side pipeline stages
  (`net/ScanRouter` -> resolve -> route -> deliver).
- **backend/** `vetms-server` — Spring Boot microservice (loopback only,
  per-boot bearer token). It OWNS the camera and the scan pipeline:
  provider selection at runtime (native sidecar `scan-cam.exe` or the
  Java webcam driver, Dynamsoft premium decode when licensed), decode on
  virtual threads, SSE event stream (`/api/scan/events`) with routing
  hints (add-to-cart / search-medicine / capture / notify), preview frames,
  `/api/checks` for the splash screen and server-side product lookup.
  It keeps scanning even when the UI is closed; the UI re-attaches.

The bundled installer produces two executables (`VetCustomerManager.exe`
and `vetms-server.exe`). The UI spawns/attaches the backend automatically.

### HTTP surface (token-guarded, 127.0.0.1)
`GET /api/health` · `GET /api/checks` · `GET /api/scan/status` ·
`POST /api/scan/provider|start|stop|reload|context` · `GET /api/scan/events` (SSE) ·
`GET /api/scan/frame.jpg` · `GET /api/products/by-barcode/{code}` · `GET /api/products/search`

### Database
`supabase/schema.sql` now ends with an optional `scan_log` audit table the
backend appends to on every unique scan (safe to skip: the backend detects
its absence and keeps working).
