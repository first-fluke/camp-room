# Camp Room API

FastAPI implements the shared contract in `../../docs/plans/contracts/camp-room-api.md`.
The API stores campsites, receiving policies, trips, scenes, vehicle simulator state, products, inventory, carts, orders, idempotency keys, provider caches and refresh history in normalized relational tables. Turso/libSQL is selected when server credentials are configured; local SQLite uses the same schema for isolated tests and recordings. `CAMP_DB_NAMESPACE` selects a workspace and `CAMP_PROFILE_ID` selects a server-owned demo profile. These identifiers are not client authentication; authenticated profile ownership is still needed before a public multi-user deployment.

## Run

From the repository root:

```bash
uv sync --all-packages --frozen --python 3.14.8
.venv/bin/python -m uvicorn app.main:app --app-dir apps/api --host 127.0.0.1 --port 8000
```

Interactive OpenAPI: `http://127.0.0.1:8000/docs`; health: `/health`.
The server is intentionally bound to loopback for local demonstration; production multi-user auth and deployment are separate work.

```bash
.venv/bin/python -m pytest apps/api/tests -q
```

The runtime and source require Python 3.14 or later; verification uses CPython 3.14.8. `uv.lock` pins resolved runtime dependencies. The Dockerfile also runs `uv sync --all-packages --frozen --no-dev`; Docker was not available in this environment, so the image has not been built here. Local tests use temporary normalized databases and a stateful Hrana HTTP double backed by real SQLite transactions. External GoCamping/KMA responses remain mocked; actual Turso verification is reported separately.

## Data providers

Copy the root `.env.example` to a local `.env` and set server-only credentials. The application loads root `.env` without overwriting process environment variables. It does not expose credentials in API responses or exception diagnostics. Never put provider tokens in `NEXT_PUBLIC_*`.

| Mode | Behavior |
|---|---|
| `demo` (default) | Explicit fictional campsite/availability/weather, vehicle/navigation simulators, demo products/payment/delivery |
| `auto` | Requests configured public-data providers, falls back to clearly labelled demo data with a safe failure reason |
| `live` | Requests real public-data providers; missing keys or errors leave data unavailable, without fixture fallback |

`GOCAMPING_SERVICE_KEY` calls the official GoCamping `basedList` endpoint using `serviceKey`, `MobileOS=ETC`, `MobileApp=CampRoom`, `_type=json`. Both encoded and decoded portal keys are accepted. Current coverage is the first100 records and is stated in source metadata; this is not a claim of nationwide complete coverage. Cache lifetime10minutes.

GoCamping supplies location and facility information, not reservation inventory, price, permission to sleep inside a vehicle, EV charging permission, or parcel receiving terms. Those fields stay null/unknown or unavailable; auto campsite count is not interpreted as permission for car sleeping. A production partner/operating-policy adapter must supply the missing conditions before enabling them.

`KMA_API_HUB_KEY` calls the official API Hub `getVilageFcst` with `authKey`. Optional `KMA_SERVICE_KEY` uses the equivalent data.go.kr endpoint with `serviceKey`. The adapter computes the5km forecast grid, selects an issue at least1hour old, and filters returned forecasts to the actual stay. It never extends short-range forecasts into dates absent from the response. Cache lifetime5minutes. Forecast and cabin sensor values remain distinct.

Primary API specifications:

- https://www.data.go.kr/data/15101933/openapi.do
- https://apihub.kma.go.kr/apiList.do?seqApi=10&seqApiSub=286

Actual provider signups, approvals and tokens are external account steps and were not completed by this implementation agent.

## Demo effects and validation

Navigation starts only a stored simulator intent. Scene control observes simulator state and reports each command separately; the cabin temperature sensor does not instantly become the requested HVAC target. Unknown motion state blocks control and does not imply parked. Native SDK adapters are owned by `apps/pleos`, not this HTTP server.

Cart stock and money are recalculated on the server. Quotes validate recipient, home address or campsite reference, operator receiving permission and `checkIn <= ETA < checkOut`. Campsite address is derived from the selected site, not blindly trusted client input. Demo scheduled delivery has an explicitly fictional ETA. Orders are `DEMO-*` / `demo_confirmed`; no real payment, reservation, carrier dispatch or physical vehicle command occurs. Idempotency is persisted and transactionally rechecked; retries after the cart is cleared return the same order, while changed payloads conflict.

## Persistence and Turso

Set `TURSO_DATABASE_URL` and `TURSO_AUTH_TOKEN` together in the server environment. `CAMP_DB_NAMESPACE` defaults to `camp-room`. Neither the native APK nor the admin browser receives the database token. An explicit `CAMP_DB_PATH` always selects local SQLite even when Turso credentials exist; this keeps demo recordings and tests isolated. Without either remote credentials or an explicit path, the local default is `apps/api/data/camp-room.sqlite3`.

The additive migration in `app/migrations/001_normalized.sql` defines 32 STRICT tables, including lookup, relation and history tables. Composite workspace keys and foreign keys prevent cross-workspace references. Integer KRW values, nonnegative inventory, canonical dates, stay lengths and bounded quantities are checked by the database. Order item names/prices and delivery addresses are immutable historical facts; subtotals and totals are derived. The superseded empty `camp_room_state` prototype table, if present, is left untouched and never read. Independent review is recorded in `../../docs/database/SCHEMA_REVIEW.md`.

Both SQLite and Turso use an explicit `BEGIN IMMEDIATE` transaction per mutation. API views are assembled from rows in memory and changes are persisted to typed columns and relation rows. Stock decrements use conditional `UPDATE ... WHERE available >= requested`; order, order items, delivery, idempotency and cart clearing commit together. Turso's authenticated HTTPS `/v2/pipeline` connection retains its baton across checked reads and writes. COMMIT is sent only after every prior SQL result and affected-row guard succeeded. An error closes the uncommitted stream and rolls it back. No unknown write outcome is retried automatically; if a COMMIT response is lost, submitting the same order idempotency key recovers the stored order.

Remote blocking I/O runs in worker threads rather than on FastAPI's event loop. All data values use bound parameters. API errors contain a generic storage message; tokens, SQL parameters and provider response bodies are excluded. Current scene temperature limits are simulator recipe validation, not assertions about real SDK hardware capability.

Run the opt-in live probe only against the intended configured database:

```bash
.venv/bin/python apps/api/scripts/verify_turso.py --run-remote
```

It inspects the existing schema first, creates one random verification workspace, checks concurrent cart updates, callback and SQL-error rollback, two HTTP orders competing for final stock, duplicate-key orders, a deliberately lost real COMMIT response, receiving overrides, fulfillment and durable refresh. It then deletes only its own child rows in foreign-key order; foreign keys stay enabled. Shared schema/lookup definitions remain installed. The sanitized result is written to `artifacts/qa/turso-results.json`. It does not reset the default namespace or create real payments/orders with a merchant.

Official protocol reference: https://docs.turso.tech/sdk/http/reference

## Operations API

The admin web application is a separate server client of these endpoints. Every request requires `Authorization: Bearer <CAMP_ADMIN_TOKEN>`. Missing server configuration returns 503; absent or incorrect credentials return 401. Keep the token in server-only environment variables. The web login password and session secret are separate values managed by `apps/admin`.

| Method and path | Behavior |
|---|---|
| `POST /api/v1/admin/providers/refresh` | Publish durable provider/weather caches with `{ "force": false }`; return `refreshed`, `cached` or `busy` |
| `GET /api/v1/admin/overview` | Database backend/namespace, public provider status, campsites, demo orders and counts; no credentials |
| `PATCH /api/v1/admin/campsites/{id}/receiving` | Persist `acceptsParcels`, `location`, and `instructions`; require a receiving location when enabled |
| `PATCH /api/v1/admin/orders/{id}` | Persist `fulfillmentStatus` and `adminNote` under an order's demo `fulfillment` metadata |

Receiving overrides are applied to campsite results and are rechecked against the latest transactional state when an order is created. Fulfillment changes preserve `demo_confirmed` payment status and never trigger payments, refunds or carrier actions. A cancelled demo fulfillment label does not claim a real refund or restore stock automatically.


## Durable provider refresh

Catalog and weather caches survive restarts with 600-second and 300-second TTLs. Weather keys contain the campsite and full stay; a configuration/mode variant replaces that same cache key transactionally. Availability cache keys include workspace, campsite, check-in, check-out, guests and slot type. The separate campsite overview table contains explicitly fictional demo availability or unknown coverage; it is never a dated live booking inventory claim.

The worker calls the authenticated refresh API. A 120-second database lease allows one publisher per workspace; every publication verifies ownership and expiry within its transaction. Runs retain start/end time, outcome, attempts and safe diagnostic codes. A failed refresh preserves successful cached observations and prior success history while reporting the new provider failure. Stale data is accompanied by the failure reason and original update time. Each run refreshes the current trip and at most two alternative campsites, keeping external API fan-out bounded. Operator receiving overrides survive provider refreshes.

## OpenAPI and native generation

`app/responses.py` defines explicit response DTOs. Public operation IDs are stable camelCase names, and `openapi.json` is the versioned, generated server contract. Exporting the contract requires neither provider credentials nor a remote database connection:

```bash
.venv/bin/python apps/api/scripts/export_openapi.py
.venv/bin/python apps/api/scripts/export_openapi.py --check
```

The native build generates its Ktor/kotlinx client from this document. A changed contract must be exported and the generated client rebuilt in the same change. Free-form `Any` response schemas and manually copied JSON client contracts are not used.
