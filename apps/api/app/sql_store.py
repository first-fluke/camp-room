"""Small synchronous SQLite/Hrana adapter with explicit, checked transactions.

Every pipeline result is checked before COMMIT is sent. HTTP failures are never
retried: a COMMIT response can be lost after the transaction has succeeded.
Official protocol: https://github.com/tursodatabase/libsql/blob/main/docs/HTTP_V2_SPEC.md
"""
from __future__ import annotations
from dataclasses import dataclass
from pathlib import Path
import re
import sqlite3
from typing import Any
from urllib.parse import urlsplit
import httpx
from .errors import DomainError


class StorageUnavailable(DomainError):
    def __init__(self, reason="storage_unavailable"):
        super().__init__("저장소 응답을 확인하지 못했습니다. 최신 상태를 확인한 뒤 다시 시도해 주세요.", 503)
        self.reason = reason


class SqlFailure(StorageUnavailable):
    def __init__(self, code="SQL_ERROR"):
        super().__init__()
        self.code = code if re.fullmatch(r"[A-Z0-9_]{1,80}", str(code)) else "SQL_ERROR"


@dataclass(frozen=True)
class Statement:
    sql: str
    args: tuple = ()
    expected: int | None = None


@dataclass(frozen=True)
class SqlResult:
    columns: tuple[str, ...]
    rows: tuple[tuple[Any, ...], ...]
    affected: int

    def dictionaries(self):
        return [dict(zip(self.columns, row)) for row in self.rows]


def check_affected(statement: Statement, result: SqlResult):
    if statement.expected is not None and result.affected != statement.expected:
        raise DomainError("다른 요청이 재고나 저장 상태를 변경했습니다. 최신 상태에서 다시 확인해 주세요.", 409)


class SQLiteSession:
    def __init__(self, path):
        self.connection = sqlite3.connect(str(path), timeout=10, isolation_level=None)
        self.connection.execute("PRAGMA busy_timeout=10000")
        self.connection.execute("PRAGMA foreign_keys=ON")

    def run(self, statements):
        results = []
        try:
            for statement in statements:
                cursor = self.connection.execute(statement.sql, statement.args)
                result = SqlResult(tuple(c[0] for c in (cursor.description or ())), tuple(cursor.fetchall()), max(cursor.rowcount, 0))
                check_affected(statement, result)
                results.append(result)
        except sqlite3.DatabaseError as exc:
            raise SqlFailure(getattr(exc, "sqlite_errorname", "SQL_ERROR")) from None
        return results

    def begin(self, write):
        self.run([Statement("BEGIN IMMEDIATE" if write else "BEGIN")])

    def commit(self):
        self.run([Statement("COMMIT")])

    def close(self):
        # Closing an uncommitted connection rolls back all its writes.
        self.connection.close()


class SQLiteDatabase:
    def __init__(self, path: Path):
        self.path = path
        path.parent.mkdir(parents=True, exist_ok=True)

    def session(self):
        return SQLiteSession(self.path)

    def execute(self, sql, args=()):
        session = self.session()
        try:
            return session.run([Statement(sql, tuple(args))])[0]
        finally:
            session.close()

    def close(self):
        pass


class TursoSession:
    def __init__(self, database):
        self.database = database
        self.url = database.url
        self.baton = None
        self.closed = False

    def _pipeline(self, requests):
        if self.closed:
            raise StorageUnavailable()
        body = {"baton": self.baton, "requests": requests}
        try:
            response = self.database.client.post(self.url, json=body)
            response.raise_for_status()
            payload = response.json()
            self.baton = payload["baton"]
            base_url = payload.get("base_url")
            if base_url:
                parsed = urlsplit(base_url)
                # Only a secure URL from the same Turso host family receives the token.
                host = parsed.hostname or ""
                original = urlsplit(self.database.url).hostname or ""
                trusted = host == original or (original.endswith(".turso.io") and host.endswith(".turso.io"))
                if parsed.scheme != "https" or parsed.username or parsed.password or not trusted or parsed.query or parsed.fragment:
                    raise ValueError("Invalid stream origin")
                self.url = base_url.rstrip("/") + "/v2/pipeline"
            results = payload["results"]
            if len(results) != len(requests):
                raise ValueError("Invalid pipeline results")
            for result in results:
                if result.get("type") != "ok":
                    raise SqlFailure(result.get("error", {}).get("code", "SQL_ERROR"))
            return results
        except SqlFailure:
            raise
        except httpx.HTTPStatusError as exc:
            raise StorageUnavailable("http_" + str(exc.response.status_code)) from None
        except httpx.HTTPError as exc:
            raise StorageUnavailable(type(exc).__name__) from None
        except (KeyError, TypeError, ValueError):
            # Do not echo SQL, parameters, authorization headers, or response bodies.
            raise StorageUnavailable("invalid_protocol_response") from None

    def run(self, statements):
        statements = list(statements)
        if not statements:
            return []
        requests = [{"type": "execute", "stmt": {"sql": s.sql, "args": [self.database.argument(a) for a in s.args], "want_rows": True}} for s in statements]
        raw_results = self._pipeline(requests)
        results = []
        try:
            for statement, raw in zip(statements, raw_results):
                value = raw["response"]["result"]
                result = SqlResult(tuple(c["name"] for c in value["cols"]), tuple(tuple(self.database.value(v) for v in row) for row in value["rows"]), int(value["affected_row_count"]))
                check_affected(statement, result)
                results.append(result)
        except (KeyError, TypeError, ValueError):
            raise StorageUnavailable() from None
        return results

    def begin(self, write):
        self.run([Statement("PRAGMA foreign_keys=ON"), Statement("BEGIN IMMEDIATE" if write else "BEGIN")])

    def commit(self):
        # Never batch COMMIT behind unchecked writes: Hrana continues after SQL errors.
        self.run([Statement("COMMIT")])

    def close(self):
        if not self.closed and self.baton:
            try:
                self._pipeline([{"type": "close"}])
            except StorageUnavailable:
                pass  # Server also closes abandoned streams; no writes are retried.
        self.closed = True


class TursoSQL:
    def __init__(self, url, token, *, transport=None):
        parsed = urlsplit(url)
        if parsed.scheme not in {"libsql", "https"} or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment or parsed.path not in {"", "/"}:
            raise ValueError("TURSO_DATABASE_URL must be a libsql:// or https:// database origin")
        if not token:
            raise ValueError("TURSO_AUTH_TOKEN is required")
        self.url = "https://" + parsed.netloc + "/v2/pipeline"
        self.client = httpx.Client(headers={"Authorization": "Bearer " + token}, timeout=httpx.Timeout(20, connect=10), follow_redirects=False, transport=transport)

    @staticmethod
    def argument(value):
        if value is None:
            return {"type": "null"}
        if isinstance(value, bool):
            return {"type": "integer", "value": str(int(value))}
        if isinstance(value, int):
            return {"type": "integer", "value": str(value)}
        if isinstance(value, float):
            return {"type": "float", "value": value}
        if isinstance(value, str):
            return {"type": "text", "value": value}
        raise TypeError("Unsupported SQL parameter")

    @staticmethod
    def value(value):
        kind = value["type"]
        if kind == "null": return None
        if kind == "integer": return int(value["value"])
        if kind == "float": return float(value["value"])
        if kind == "text": return value["value"]
        raise ValueError("Unsupported SQL response")

    def session(self):
        return TursoSession(self)

    def execute(self, sql, args=()):
        session = self.session()
        try:
            return session.run([Statement("PRAGMA foreign_keys=ON"), Statement(sql, tuple(args))])[1]
        finally:
            session.close()

    def close(self):
        self.client.close()
