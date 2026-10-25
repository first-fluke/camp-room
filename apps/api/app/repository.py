import json
import sqlite3
from pathlib import Path
from typing import Callable, TypeVar
from .fixtures import initial_state

T = TypeVar("T")

class Repository:
    """One SQLite transaction per user operation, shared safely across API workers."""
    def __init__(self, path: Path):
        self.path = path
        path.parent.mkdir(parents=True, exist_ok=True)
        db = self.connect()
        try:
            db.execute("CREATE TABLE IF NOT EXISTS state (id INTEGER PRIMARY KEY CHECK(id=1), document TEXT NOT NULL)")
            db.execute("INSERT OR IGNORE INTO state(id,document) VALUES(1,?)", (json.dumps(initial_state(), ensure_ascii=False),))
        finally:
            db.close()

    def connect(self):
        connection = sqlite3.connect(str(self.path), timeout=10, isolation_level=None)
        connection.execute("PRAGMA busy_timeout=10000")
        return connection

    def read(self) -> dict:
        db = self.connect()
        try:
            return json.loads(db.execute("SELECT document FROM state WHERE id=1").fetchone()[0])
        finally:
            db.close()

    def transact(self, change: Callable[[dict], T]) -> T:
        db = self.connect()
        try:
            db.execute("BEGIN IMMEDIATE")
            state = json.loads(db.execute("SELECT document FROM state WHERE id=1").fetchone()[0])
            result = change(state)
            db.execute("UPDATE state SET document=? WHERE id=1", (json.dumps(state, ensure_ascii=False),))
            db.execute("COMMIT")
            return result
        except BaseException:
            if db.in_transaction:
                db.execute("ROLLBACK")
            raise
        finally:
            db.close()
