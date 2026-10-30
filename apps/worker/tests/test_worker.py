import json
from threading import Event

import httpx
import pytest

from camp_room_worker.runner import WorkerConfig, refresh_once


def config(**kwargs):
    return WorkerConfig("http://127.0.0.1:8000/api/v1", "test-only-token", backoff=0, **kwargs)


def payload(status="refreshed", provider="demo"):
    return {"status": status, "campsites": 6, "weatherQueries": 3, "providers": [{"status": provider}]}


def test_authenticated_refresh_uses_expected_contract_and_redacted_logs():
    logs = []

    def server(request):
        assert request.url.path == "/api/v1/admin/providers/refresh"
        assert request.headers["authorization"] == "Bearer test-only-token"
        assert json.loads(request.content) == {"force": True}
        return httpx.Response(200, json=payload())

    with httpx.Client(transport=httpx.MockTransport(server)) as client:
        result = refresh_once(client, config(), force=True, log=logs.append)
    assert result.exit_code == 0 and result.campsites == 6
    assert "test-only-token" not in json.dumps(logs) + repr(config())


def test_transient_failures_stop_at_three_attempts():
    calls = []

    def server(request):
        calls.append(request)
        return httpx.Response(503, text="private debug token must not be logged")

    logs = []
    with httpx.Client(transport=httpx.MockTransport(server)) as client:
        result = refresh_once(client, config(), log=logs.append)
    assert result.exit_code == 1 and result.attempts == len(calls) == 3
    assert "private debug" not in json.dumps(logs)


def test_transport_retry_recovers():
    calls = 0

    def server(request):
        nonlocal calls
        calls += 1
        if calls == 1:
            raise httpx.ConnectError("secret in upstream URL", request=request)
        return httpx.Response(200, json=payload("cached"))

    with httpx.Client(transport=httpx.MockTransport(server)) as client:
        result = refresh_once(client, config(), log=lambda _: None)
    assert result.status == "cached" and result.attempts == 2


@pytest.mark.parametrize("status", [401, 403, 400, 302])
def test_auth_validation_and_redirect_failures_are_not_retried(status):
    calls = []

    def server(request):
        calls.append(request)
        return httpx.Response(status, headers={"Location": "https://other.invalid/collect"})

    with httpx.Client(transport=httpx.MockTransport(server)) as client:
        result = refresh_once(client, config(), log=lambda _: None)
    assert len(calls) == 1 and result.exit_code == 2


def test_busy_lease_is_not_retried():
    with httpx.Client(transport=httpx.MockTransport(lambda _: httpx.Response(200, json=payload("busy")))) as client:
        result = refresh_once(client, config(), log=lambda _: None)
    assert result.status == "busy" and result.attempts == 1 and result.exit_code == 0


def test_unavailable_provider_does_not_claim_success():
    with httpx.Client(transport=httpx.MockTransport(lambda _: httpx.Response(200, json=payload(provider="unavailable")))) as client:
        result = refresh_once(client, config(), log=lambda _: None)
    assert result.provider_failure and result.exit_code == 1


def test_invalid_success_body_is_rejected():
    with httpx.Client(transport=httpx.MockTransport(lambda _: httpx.Response(200, json={"status": "refreshed"}))) as client:
        result = refresh_once(client, config(), log=lambda _: None)
    assert result.error_code == "invalid_response" and result.exit_code == 2


def test_cancellation_prevents_request():
    stop = Event()
    stop.set()
    with httpx.Client(transport=httpx.MockTransport(lambda _: pytest.fail("Request after cancellation"))) as client:
        result = refresh_once(client, config(), stop=stop, log=lambda _: None)
    assert result.exit_code == 130 and result.attempts == 0


@pytest.mark.parametrize("url", ["http://example.com/api/v1", "https://user:password@example.com", "https://example.com?token=secret", "file:///tmp/foo"])
def test_insecure_or_embedded_credential_destinations_are_rejected(url):
    with pytest.raises(ValueError):
        WorkerConfig(url, "test-only-token")


def test_missing_token_fails_before_network():
    with pytest.raises(ValueError):
        WorkerConfig("http://127.0.0.1:8000/api/v1", "")
