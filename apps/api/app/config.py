from dataclasses import dataclass
from pathlib import Path
import os
from dotenv import load_dotenv

ROOT = Path(__file__).resolve().parents[3]
load_dotenv(ROOT / ".env", override=False)

@dataclass(frozen=True)
class Settings:
    mode: str = "demo"
    db_path: Path = ROOT / "apps/api/data/camp-room.sqlite3"
    gocamping_key: str = ""
    kma_hub_key: str = ""
    kma_service_key: str = ""

    @classmethod
    def from_env(cls):
        mode = os.getenv("CAMP_DATA_MODE", "demo").lower()
        if mode not in {"demo", "auto", "live"}:
            raise ValueError("CAMP_DATA_MODE must be demo, auto or live")
        return cls(mode=mode, db_path=Path(os.getenv("CAMP_DB_PATH", str(cls.db_path))),
                   gocamping_key=os.getenv("GOCAMPING_SERVICE_KEY", ""),
                   kma_hub_key=os.getenv("KMA_API_HUB_KEY", ""),
                   kma_service_key=os.getenv("KMA_SERVICE_KEY", ""))
