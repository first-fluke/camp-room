# Camp Room API

FastAPI implements the shared contract in `../../docs/plans/contracts/camp-room-api.md`.
The original starter's unused authentication, queues and PostgreSQL infrastructure are removed from this demo application. A SQLite repository persists the single fictional demo account's trip, vehicle state, cart, inventory and orders; user operations use `BEGIN IMMEDIATE` transactions and parameterized SQL.

## Run

From the repository root:

```bash
uv sync --all-packages
.venv/bin/python -m uvicorn app.main:app --app-dir apps/api --host 127.0.0.1 --port 8000
```

Interactive OpenAPI: `http://127.0.0.1:8000/docs`; health: `/health`.
The server is intentionally bound to loopback for local demonstration; production multi-user auth and deployment are separate work.

```bash
.venv/bin/python -m pytest apps/api/tests/test_camp_room.py -q
```

Verified with CPython3.13.16; source requires3.12 or later. Tests create isolated temporary databases and use mocked HTTP responses for external-provider contracts; no provider account or token is claimed as provisioned or live-tested.

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

Optional `CAMP_DB_PATH` selects another SQLite file. Default is `apps/api/data/camp-room.sqlite3` and is ignored by Git. To reset your **local demo data**, stop the server and deliberately remove that database file, then restart. Do not reset data during an active test or recording.
