"""Export the actual FastAPI contract; --check fails on uncommitted schema drift."""
from __future__ import annotations
import argparse
import json
from pathlib import Path
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "apps/api"))
from app.config import Settings
from app.main import create_app


def export() -> str:
    # No credentials or external connections are required to generate API types.
    with tempfile.TemporaryDirectory(prefix="camp-room-openapi-") as directory:
        app = create_app(Settings(db_path=Path(directory) / "contract.sqlite3", force_local=True))
        schema = app.openapi()
        app.state.service.repo.close()
    return json.dumps(schema, indent=2, ensure_ascii=False, sort_keys=True) + "\n"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    path = ROOT / "apps/api/openapi.json"
    document = export()
    if args.check:
        if not path.exists() or path.read_text() != document:
            raise SystemExit("OpenAPI drift: run apps/api/scripts/export_openapi.py and regenerate the native client")
        print("OpenAPI contract is current")
    else:
        path.write_text(document)
        print("Exported apps/api/openapi.json")


if __name__ == "__main__":
    main()
