from __future__ import annotations

import argparse
import os
import signal
from pathlib import Path
from threading import Event

import httpx
from dotenv import load_dotenv

from .runner import WorkerConfig, json_log, run_loop


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Refresh shared Camp Room campground and weather caches")
    parser.add_argument("--once", action="store_true", help="Run one bounded refresh and exit")
    parser.add_argument("--force", action="store_true", help="Bypass API cache TTL; leases still apply")
    parser.add_argument("--interval", type=float, help="Seconds between completed runs (default 600)")
    args = parser.parse_args(argv)
    load_dotenv(Path(__file__).resolve().parents[4] / ".env", override=False)
    try:
        config = WorkerConfig(
            api_url=os.getenv("CAMP_WORKER_API_URL", "http://127.0.0.1:8000/api/v1"),
            admin_token=os.getenv("CAMP_ADMIN_TOKEN", ""),
            interval=args.interval if args.interval is not None else float(os.getenv("CAMP_WORKER_INTERVAL_SECONDS", "600")),
            timeout=float(os.getenv("CAMP_WORKER_TIMEOUT_SECONDS", "60")),
        )
    except (ValueError, OverflowError):
        json_log({"event": "worker_configuration_error", "errorCode": "invalid_configuration"})
        return 2
    stop = Event()

    def request_stop(_signum, _frame) -> None:
        stop.set()

    signal.signal(signal.SIGINT, request_stop)
    signal.signal(signal.SIGTERM, request_stop)
    json_log({"event": "worker_started", "once": args.once, "intervalSeconds": config.interval})
    with httpx.Client() as client:
        return run_loop(client, config, once=args.once, force=args.force, stop=stop)


if __name__ == "__main__":
    raise SystemExit(main())
