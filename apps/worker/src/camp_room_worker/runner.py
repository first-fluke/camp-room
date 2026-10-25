"""HTTP scheduling only; adapters, leases and durable writes belong to the API."""
from __future__ import annotations

import json
from dataclasses import dataclass, field
from datetime import datetime, timezone
from threading import Event
from typing import Callable
from urllib.parse import urlsplit

import httpx


@dataclass(frozen=True)
class WorkerConfig:
    api_url: str
    admin_token: str = field(repr=False)
    interval: float = 600
    timeout: float = 60
    max_attempts: int = 3
    backoff: float = 1

    def __post_init__(self) -> None:
        parsed = urlsplit(self.api_url)
        if (parsed.scheme not in {"http", "https"} or not parsed.hostname
                or parsed.username or parsed.password or parsed.query or parsed.fragment):
            raise ValueError("CAMP_WORKER_API_URL must be an HTTP(S) API base URL without credentials or query")
        if parsed.scheme == "http" and parsed.hostname not in {"localhost", "127.0.0.1", "::1"}:
            raise ValueError("CAMP_WORKER_API_URL requires HTTPS outside loopback")
        if not self.admin_token.strip() or "\n" in self.admin_token or "\r" in self.admin_token:
            raise ValueError("CAMP_ADMIN_TOKEN is required")
        if not 60 <= self.interval <= 86400 or not 1 <= self.timeout <= 90:
            raise ValueError("Worker interval must be 60–86400 seconds and timeout 1–90 seconds")
        if not 1 <= self.max_attempts <= 3 or not 0 <= self.backoff <= 5:
            raise ValueError("Worker attempts must be 1–3 and backoff 0–5 seconds")


@dataclass(frozen=True)
class RefreshResult:
    status: str
    attempts: int
    campsites: int = 0
    weather_queries: int = 0
    provider_failure: bool = False
    error_code: str | None = None

    @property
    def exit_code(self) -> int:
        if self.status == "cancelled":
            return 130
        if self.error_code in {"unauthorized", "request_rejected", "invalid_response"}:
            return 2
        return 1 if self.error_code or self.provider_failure else 0


def json_log(event: dict) -> None:
    print(json.dumps({"time": datetime.now(timezone.utc).isoformat(), **event}, separators=(",", ":")), flush=True)


def refresh_once(
    client: httpx.Client,
    config: WorkerConfig,
    *,
    force: bool = False,
    stop: Event | None = None,
    log: Callable[[dict], None] = json_log,
) -> RefreshResult:
    """Retry transient failures only. Never log response bodies, URLs or headers."""
    stopped = stop if stop is not None else Event()
    for attempt in range(1, config.max_attempts + 1):
        if stopped.is_set():
            return RefreshResult("cancelled", attempt - 1)
        retry_delay = min(5, config.backoff * 2 ** (attempt - 1))
        try:
            response = client.post(
                config.api_url.rstrip("/") + "/admin/providers/refresh",
                headers={"Authorization": f"Bearer {config.admin_token}"},
                json={"force": force},
                timeout=httpx.Timeout(config.timeout, connect=min(10, config.timeout)),
                follow_redirects=False,
            )
        except httpx.TransportError:
            code = "transport_error"
        else:
            if response.status_code == 200:
                try:
                    body = response.json()
                    if not isinstance(body, dict) or body.get("status") not in {"refreshed", "cached", "busy"}:
                        raise ValueError("status")
                    if any(type(body.get(k)) is not int or body[k] < 0 for k in ("campsites", "weatherQueries")):
                        raise ValueError("counts")
                    providers = body.get("providers")
                    if not isinstance(providers, list) or any(not isinstance(p, dict) or not isinstance(p.get("status"), str) for p in providers):
                        raise ValueError("providers")
                    degraded = any(p["status"] in {"unavailable", "fallback"} for p in providers)
                    result = RefreshResult(body["status"], attempt, body["campsites"], body["weatherQueries"], degraded)
                except (ValueError, TypeError, KeyError):
                    return RefreshResult("failed", attempt, error_code="invalid_response")
                log({"event": "refresh_complete", "status": result.status, "attempts": attempt,
                     "campsites": result.campsites, "weatherQueries": result.weather_queries,
                     "providerFailure": result.provider_failure})
                return result
            if response.status_code in {401, 403}:
                return RefreshResult("failed", attempt, error_code="unauthorized")
            if response.status_code != 429 and response.status_code < 500:
                return RefreshResult("failed", attempt, error_code="request_rejected")
            code = "rate_limited" if response.status_code == 429 else "server_unavailable"
            try:
                retry_delay = max(retry_delay, min(5, max(0, float(response.headers.get("Retry-After", "0")))))
            except ValueError:
                pass
        log({"event": "refresh_retry" if attempt < config.max_attempts else "refresh_failed", "attempt": attempt, "errorCode": code})
        if attempt == config.max_attempts:
            return RefreshResult("failed", attempt, error_code=code)
        if stopped.wait(retry_delay):
            return RefreshResult("cancelled", attempt)
    raise AssertionError("Unreachable attempt budget")


def run_loop(client: httpx.Client, config: WorkerConfig, *, once: bool, force: bool, stop: Event) -> int:
    while not stop.is_set():
        result = refresh_once(client, config, force=force, stop=stop)
        if result.error_code:
            json_log({"event": "refresh_result", "status": result.status, "errorCode": result.error_code})
        if once or result.exit_code in {2, 130}:
            return result.exit_code
        # No overlapping request in this process. API lease handles other workers.
        if stop.wait(config.interval):
            break
    return 130
