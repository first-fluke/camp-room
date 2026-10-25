# Camp Room worker

Python 3.14 runner for the shared campground and weather cache. It calls the authenticated API refresh endpoint; provider keys, SQL, leases and cache publication stay inside the API. The Android app never receives Turso or provider credentials.

```bash
uv sync --all-packages
uv run --package camp-room-worker camp-room-worker --once
uv run --package camp-room-worker camp-room-worker --interval 600
```

Start the API first. Configuration is loaded from the repository `.env` without overriding exported environment variables:

| Variable | Default / purpose |
| --- | --- |
| `CAMP_WORKER_API_URL` | `http://127.0.0.1:8000/api/v1`; HTTPS required outside loopback |
| `CAMP_ADMIN_TOKEN` | Required server token; shared with the API |
| `CAMP_WORKER_INTERVAL_SECONDS` | `600`, measured after each completed run |
| `CAMP_WORKER_TIMEOUT_SECONDS` | `60`, per request; maximum 90 |

The API refreshes the catalog and weather for the current trip plus at most two alternatives. Catalog/weather TTLs are 600/300 seconds. A 120-second database lease prevents overlapping publishers; only its current owner can publish. Refresh history lives in `cr_refresh_runs`, independent of cache retention.

The runner retries transport errors, HTTP 429 and 5xx at most three times with bounded backoff. Authentication, redirects and invalid payloads fail immediately. SIGINT/SIGTERM interrupt backoff and scheduling; an in-flight request completes or reaches its configured timeout. `--force` bypasses cache TTL but still respects the database lease.

Exit codes: 0 refreshed/cached/busy, 1 transient failure or unavailable provider, 2 configuration/authentication/contract failure, 130 cancellation. Logs contain event codes and counts only; upstream bodies, URLs and credentials are excluded. No API keys means live information remains unavailable; fixtures are explicitly labelled demo data according to the API mode.

No scheduler or paid infrastructure is provisioned. Use `--once` with an existing scheduler or run the foreground interval process under the chosen process manager.

```bash
.venv/bin/python -m pytest apps/worker/tests -q
```
