# NIFTY Live Options Trading Analytics Backend — Step 1

A Spring Boot backend that connects to **Angel One SmartAPI** and streams
NIFTY spot, startup ATM CE/PE, and a fixed weekly ITM CE/PE pair over SmartStream.
All option tokens, strikes and expiries are discovered from the Scrip Master.

## Fixed weekly ITM CE/PE and separate ATM CE/PE

The original CE-only walkthrough below describes the foundation; the current
pipeline subscribes to all four option roles plus NIFTY spot.

- On first startup, round spot to `round(spot / strikeStep) * strikeStep`.
- Fixed ITM CE strike = rounded ATM minus `itmDepth * strikeStep`.
- Fixed ITM PE strike = rounded ATM plus `itmDepth * strikeStep`.
- Example: initial spot 25412.30, step 50, depth 1: ATM 25400,
  fixed ITM CE 25350 and fixed ITM PE 25450. These are illustrative,
  not hard-coded selections. Exact ITM strikes must exist in the master.
- `FixedItmSelectionService` saves the two identities atomically to
  `data/fixed-itm.json`. Restarting with a different spot restores the same
  unexpired pair and validates token, symbol, strike and expiry against the master.
- ATM CE/PE are independently selected from current startup spot for that same
  expiry using the existing nearest-available-strike selector. They are **not
  continuously recentered on incoming ticks**.
- The fixed pair is ITM at initial selection, not necessarily later. It is never
  moved merely because spot changes. Overlapping ATM/fixed tokens are subscribed
  once, but ticks log both labels.
- Expiry is the nearest listed non-expired NIFTY expiry (including monthly-expiry
  weeks). Dates use Asia/Kolkata. Keep the selection through expiry day;
  the first startup on a later day creates a new pair. There is **no automatic
  in-process expiry rollover**: restart after expiry to select the next pair.

Configuration:

```properties
angelone.strike-step=50
angelone.itm-depth=${ANGELONE_ITM_DEPTH:1}
angelone.fixed-itm-state-path=${ANGELONE_FIXED_ITM_STATE_PATH:data/fixed-itm.json}
```

Depth 2 means 100 points on either side of the initial rounded ATM with step 50.
Depth/step changes do not replace a saved unexpired ITM pair. Keep the state file
across restarts; for deployment use a durable absolute path. Deleting it resets
the weekly selection. This local store supports **one application instance per
state file**, not concurrent writers. It stores instrument metadata only, no
credentials. Missing saved contracts, corrupt state or storage errors stop
pipeline initialization rather than silently replacing the pair.

Startup logs show each fixed symbol, token, strike and expiry. Tick labels are
`NIFTY`, `ATM CE`, `ATM PE`, `FIXED ITM CE`, and `FIXED ITM PE`, each followed by
price and exchange time. To verify persistence, record fixed identities, restart
with the same state path and confirm both identities remain unchanged. Offline
selection tests are available with:

```powershell
.\gradlew.bat test --tests "com.example.demo.service.FixedItmSelectionServiceTest"
```

---

## Table of contents

- [Architecture overview](#architecture-overview)
- [How ATM is retrieved (end-to-end)](#how-atm-is-retrieved-end-to-end)
- [Project structure](#project-structure)
- [Configuration](#configuration)
- [Running the app](#running-the-app)
- [Testing](#testing)
- [SmartStream protocol notes](#smartstream-protocol-notes)
- [Known limitations / Step 1 scope](#known-limitations--step-1-scope)

---

## Architecture overview

```
                    ┌────────────────────────────┐
                    │   TradingApplication        │  (ApplicationRunner —
                    │   (orchestrator)             │   runs once at startup)
                    └───────────┬────────────────┘
                                │
        ┌───────────────────────┼───────────────────────┬─────────────────────┐
        ▼                       ▼                       ▼                     ▼
┌───────────────┐   ┌───────────────────┐   ┌──────────────────────┐  ┌──────────────────────┐
│ NiftySpotPrice │   │ ScripMasterService │   │ NiftyOptionSelector  │  │ AngelOneAuthService   │
│ Service        │   │                    │   │                      │  │                       │
│ (REST quote)   │   │ (download + parse  │   │ (ATM strike +        │  │ (login, JWT + feed    │
│                │   │  Scrip Master,     │   │  nearest CE match)   │  │  token, TOTP)         │
│                │   │  nearest expiry)   │   │                      │  │                       │
└───────┬────────┘   └─────────┬──────────┘   └──────────┬───────────┘  └───────────┬───────────┘
        │                      │                          │                          │
        └──────────────────────┴──────────────────────────┴──────────────────────────┘
                                             │
                                             ▼
                              ┌───────────────────────────────┐
                              │  AngelOneMarketDataService      │
                              │  (SmartStream WebSocket client:  │
                              │   subscribe + decode LTP ticks)  │
                              └───────────────────────────────┘
                                             │
                                             ▼
                          Console logs:  NIFTY = 23347.65
                                         ATM CE = 118.35
```

---

## How ATM is retrieved (end-to-end)

This is the core logic of Step 1. On every application startup,
`TradingApplication` (an `ApplicationRunner`) executes the following pipeline:

### 1. Get the current NIFTY spot price
`NiftySpotPriceService` calls Angel One's **Market Quote REST API**
(`POST /rest/secure/angelbroking/market/v1/quote/`) for the NSE index token
`99926000` (configured as `angelone.niftyQuoteToken`, defaults hard-coded in
`AngelOneProperties`) and returns the last traded price (LTP), e.g. `23348.15`.

### 2. Download & parse the Angel One Scrip Master
`ScripMasterService` downloads the full instrument dump from:
```
https://margincalculator.angelone.in/OpenAPI_File/files/OpenAPIScripMaster.json
```
(configurable via `angelone.scrip-master-url`). This is a large JSON array
containing **every tradable instrument** across all exchanges/segments.

The service filters this list down to NIFTY index options only, keeping a
row only if **all** of the following match:

| Field              | Required value | Meaning                                   |
|--------------------|-----------------|--------------------------------------------|
| `exch_seg`         | `NFO`           | NSE Futures & Options segment              |
| `instrumenttype`   | `OPTIDX`        | Index option (not stock option/future)     |
| `name`             | `NIFTY`         | The underlying is NIFTY 50                 |

For every matching row, it also:
- **Parses the expiry** (`expiry` field, format `ddMMMyyyy`, e.g. `30SEP2026`)
  into a `LocalDate` (case-insensitive month parsing, e.g. `SEP`/`Sep` both work).
- **Converts the strike price**: Angel One stores strikes **multiplied by
  100** in the Scrip Master (e.g. `2500000` really means strike `25000.00`),
  so the service divides by `100.0`.
- **Derives the option type** (`CE` or `PE`) from the trading symbol suffix
  (e.g. `NIFTY30SEP2625000CE` → `CE`).
- Builds an `OptionContract` record with: `token`, `symbol`, `name`,
  `exchSeg`, `instrumentType`, `expiry`, `strike`, `optionType`, `lotSize`.

The parsed list is **cached in memory for 1 hour** (`CACHE_TTL_MILLIS`) to
avoid re-downloading the (multi-MB) file on every request.

### 3. Find the nearest expiry
From all parsed NIFTY option contracts, `ScripMasterService
.getNiftyOptionsForNearestExpiry()`:
- Filters out any expiry **before today** (already expired contracts).
- Picks the **minimum (soonest) remaining expiry date** — this is always the
  current weekly/monthly expiry, whichever comes first chronologically.
- Returns only the contracts (`CE` + `PE`, all strikes) belonging to that
  single nearest expiry date, wrapped in a `NiftyExpiryContracts(expiry,
  contracts)` record.

### 4. Calculate the ATM strike
`NiftyOptionSelector.selectAtmCe(spotPrice, contracts, strikeStep)` computes:

```
atmStrike = round(spotPrice / strikeStep) * strikeStep
```

With the default `strikeStep = 50` (configurable via
`angelone.strike-step`), a spot price of `23348.15` rounds to the nearest
50-point strike: `23350`.

### 5. Find the matching ATM CE contract
From the nearest-expiry contract list, the selector:
- Filters to **CE (Call) contracts only**.
- Picks the contract whose `strike` has the **smallest absolute difference**
  from the computed `atmStrike` (handles cases where the exact strike isn't
  listed, by picking the closest available one).
- Throws `IllegalStateException` if no CE contract is found at all (should
  not normally happen given a valid expiry).

The result is a fully-populated `OptionContract` — **no token, symbol,
strike, or expiry is ever hard-coded**; everything is derived live from the
Scrip Master + live spot price on every run.

### 6. Authenticate with Angel One
`AngelOneAuthService`:
- Generates a fresh **TOTP** code from the Base32 secret
  (`angelone.totp-secret`) using a self-contained RFC 6238 implementation
  (`TotpGenerator`) — no external TOTP library dependency.
- Calls `POST /rest/auth/angelbroking/user/v1/loginByPassword` with
  `clientcode`, `password` (trading PIN), and `totp`.
- Caches the returned **JWT token** and **feed token** in memory, refreshing
  automatically after 5 hours (`TOKEN_TTL_MILLIS`).

### 7. Connect to SmartStream and subscribe
`AngelOneMarketDataService.start(jwtToken, feedToken, atmCe)`:
- Opens a WebSocket connection to
  `wss://smartapisocket.angelone.in/smart-stream`, authenticated via headers
  (`Authorization: Bearer <jwt>`, `x-api-key`, `x-client-code`,
  `x-feed-token`).
- Sends a JSON subscription request in **LTP mode** for two instruments:
  - `NSE_CM` (exchange type `1`), token `26000` → NIFTY 50 index.
  - `NSE_FO` (exchange type `2`), token = the dynamically discovered ATM CE
    token.
- Sends a periodic heartbeat/ping every 20 seconds to keep the connection
  alive.

### 8. Decode and log live ticks
Incoming binary WebSocket frames are buffered and decoded per Angel One's
documented SmartStream LTP packet layout:

| Byte offset | Field              |
|-------------|---------------------|
| 0           | Subscription mode   |
| 1           | Exchange type       |
| 2–26        | Token (25 bytes)    |
| 43–50       | Last traded price (`long`, paise, little-endian) |

The price is converted from paise to rupees:
```
price = lastTradedPrice / 100.0
```

Matching ticks are logged as:
```
NIFTY = 23348.15
ATM CE = 118.35
```

---

## Project structure

```
src/main/java/com/example/demo/
├── DemoApplication.java                Spring Boot entry point
├── TradingApplication.java             Orchestrates the full Step 1 pipeline on startup
├── config/
│   └── AngelOneProperties.java         All Angel One / trading configuration (typed)
├── controller/
│   ├── NiftyPriceController.java       GET /api/nifty/spot (REST quote, independent of the streaming pipeline)
│   └── GlobalExceptionHandler.java     Surfaces service errors as JSON instead of generic 500 pages
├── dto/
│   ├── NiftySpotPrice.java             REST quote response record
│   └── OptionContract.java             Parsed option contract record (token, symbol, expiry, strike, type, lot size)
├── service/
│   ├── AngelOneAuthService.java        Login, TOTP, JWT + feed token caching
│   ├── NiftySpotPriceService.java      NIFTY spot price via Market Quote REST API
│   ├── ScripMasterService.java         Downloads/parses/filters/caches the Scrip Master; resolves nearest expiry
│   ├── NiftyOptionSelector.java        ATM strike calculation + nearest CE contract selection
│   └── AngelOneMarketDataService.java  SmartStream WebSocket client (connect, subscribe, decode ticks)
└── util/
    └── TotpGenerator.java              Self-contained RFC 6238 TOTP generator (Base32 secret → 6-digit code)
```

---

## Configuration

All settings live in `src/main/resources/application.properties`. Secrets
default to environment variables (fill in real values via env vars, **do not
commit real secrets to source control**):

```properties
spring.application.name=nifty-trading
server.port=8282

# Angel One SmartAPI credentials
angelone.api-key=${ANGELONE_API_KEY:}
angelone.client-code=${ANGELONE_CLIENT_CODE:}
angelone.password=${ANGELONE_PASSWORD:}          # trading PIN, not your web login password
angelone.totp-secret=${ANGELONE_TOTP_SECRET:}    # Base32 secret from SmartAPI profile setup
angelone.local-ip=${ANGELONE_LOCAL_IP:127.0.0.1}
angelone.public-ip=${ANGELONE_PUBLIC_IP:127.0.0.1}
angelone.mac-address=${ANGELONE_MAC_ADDRESS:00:00:00:00:00:00}

# Instrument / ATM configuration
angelone.scrip-master-url=https://margincalculator.angelone.in/OpenAPI_File/files/OpenAPIScripMaster.json
angelone.nifty-token=26000     # NSE_CM token for NIFTY 50 index (SmartStream subscription)
angelone.strike-step=50        # Strike interval used to round spot price to the nearest ATM strike
```

> Note: `angelone.nifty-quote-token` (defaults to `99926000`) is used
> internally by `NiftySpotPriceService` for the REST Market Quote API — this
> is a separate token space from the SmartStream `nifty-token` (`26000`)
> used for WebSocket subscriptions.

### Getting your Angel One credentials
1. Create an app on the [SmartAPI developer portal](https://smartapi.angelbroking.com/) to get your `api-key`.
2. Your `client-code` is your Angel One login ID (e.g. `A123456`).
3. `password` is your **trading PIN** (numeric), not your website password.
4. `totp-secret` is the Base32 secret shown when you set up TOTP/2FA for the
   SmartAPI (usually a QR code you scan into Google Authenticator — the
   underlying Base32 string is what you need here).

---

## Running the app

```powershell
cd demo
./gradlew.bat bootRun
```

On startup, watch the console for (in order):
1. `Downloading Angel One Scrip Master from ...`
2. `Nearest NIFTY expiry resolved: <date> with <N> contracts`
3. `NIFTY spot price: <value>`
4. `ATM CE selected: symbol=... token=... strike=... expiry=...`
5. `SmartStream WebSocket connected`
6. Continuous live ticks:
   ```
   NIFTY = 23348.55
   ATM CE = 118.85
   ```

Ticks only update meaningfully during **NSE market hours** (9:15 AM – 3:30 PM
IST, Monday–Friday).

---

## Testing

### 1. Full pipeline (console logs)
Run `./gradlew.bat bootRun` and watch the console as described above. Any
failure at any stage (auth, scrip master download, spot price, ATM
selection, WebSocket) is logged with a full stack trace and a clear message.

### 2. REST endpoint in isolation
While the app is running, in a separate terminal:
```powershell
curl.exe -s http://localhost:8282/api/nifty/spot
```
This exercises only `AngelOneAuthService` + `NiftySpotPriceService`,
independent of the Scrip Master / WebSocket pipeline — useful for isolating
login/auth issues.

### 3. Negative test (bad credentials)
Temporarily set an invalid `angelone.totp-secret` or `angelone.password`,
restart, and confirm you get a clear `Angel One login failed: ...` error
instead of a silent hang.

### 4. Sanity-check ATM math
Compare the logged spot price against the logged `ATM CE selected:
strike=...` — the strike should always be the nearest multiple of
`angelone.strike-step` (default 50) to the spot. E.g. spot `23348` → strike
`23350`.

### 5. Stop the app
`Ctrl+C` in the terminal, or:
```powershell
Get-Process -Name java | Stop-Process -Force
```

---

## SmartStream protocol notes

The official `smartapi-java` SDK could not be resolved from Maven Central or
JitPack in this environment (both dependency coordinates failed to
resolve). Instead, `AngelOneMarketDataService` implements the **publicly
documented SmartStream v2 binary tick protocol** directly using the JDK's
built-in `java.net.http.WebSocket` client — no extra runtime dependency
required. If you have access to the official SDK artifact in your build
environment, you can swap out the WebSocket connection/decoding logic in
`AngelOneMarketDataService` for the SDK's ticker class without touching the
rest of the pipeline (`ScripMasterService`, `NiftyOptionSelector`,
`AngelOneAuthService` remain unchanged).

---

## Known limitations / Step 1 scope

As per the Step 1 requirements, the following are **intentionally not
implemented** yet:
- Order placement
- Option Greeks
- Support/resistance analytics
- Angular frontend
- MongoDB persistence

These will be addressed in subsequent steps.

ws://localhost:8282/ws/tick-snapshot
curl -X POST http://localhost:8282/api/premium-reference/rolling/refresh

