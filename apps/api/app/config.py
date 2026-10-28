from dataclasses import dataclass, field
from pathlib import Path
import os
from dotenv import load_dotenv

ROOT = Path(__file__).resolve().parents[3]
load_dotenv(ROOT / ".env", override=False)

@dataclass(frozen=True)
class Settings:
    mode: str = "demo"
    db_path: Path = ROOT / "apps/api/data/camp-room.sqlite3"
    gocamping_key: str = field(default="", repr=False)
    kma_hub_key: str = field(default="", repr=False)
    kma_service_key: str = field(default="", repr=False)
    turso_url: str = ""
    turso_token: str = field(default="", repr=False)
    db_namespace: str = "camp-room"
    profile_id: str = "default"
    force_local: bool = False
    admin_token: str = field(default="", repr=False)

    @classmethod
    def from_env(cls):
        mode = os.getenv("CAMP_DATA_MODE", "demo").lower()
        if mode not in {"demo", "auto", "live"}:
            raise ValueError("CAMP_DATA_MODE must be demo, auto or live")
        force_local = bool(os.getenv("CAMP_DB_PATH"))
        turso_url = os.getenv("TURSO_DATABASE_URL", "").strip()
        turso_token = os.getenv("TURSO_AUTH_TOKEN", "").strip()
        if not force_local and bool(turso_url) != bool(turso_token):
            raise ValueError("TURSO_DATABASE_URL and TURSO_AUTH_TOKEN must be configured together")
        return cls(mode=mode, db_path=Path(os.getenv("CAMP_DB_PATH", str(cls.db_path))),
                   gocamping_key=os.getenv("GOCAMPING_SERVICE_KEY", ""),
                   kma_hub_key=os.getenv("KMA_API_HUB_KEY", ""),
                   kma_service_key=os.getenv("KMA_SERVICE_KEY", ""),
                   turso_url=turso_url, turso_token=turso_token,
                   db_namespace=os.getenv("CAMP_DB_NAMESPACE", "camp-room"),
                   profile_id=os.getenv("CAMP_PROFILE_ID", "default"),
                   force_local=force_local, admin_token=os.getenv("CAMP_ADMIN_TOKEN", ""))
