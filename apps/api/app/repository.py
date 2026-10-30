from pathlib import Path
from .relational import NormalizedRepository
from .sql_store import SQLiteDatabase


class Repository(NormalizedRepository):
    """Local normalized SQLite with the same constraints as Turso."""
    backend = "sqlite"

    def __init__(self, path: Path, namespace="camp-room", profile_id="default"):
        self.path = path
        super().__init__(SQLiteDatabase(path), namespace, profile_id)


def repository_for(settings):
    if settings.turso_url and not settings.force_local:
        from .turso_repository import TursoRepository
        return TursoRepository(settings.turso_url, settings.turso_token, settings.db_namespace, profile_id=settings.profile_id)
    return Repository(settings.db_path, settings.db_namespace, settings.profile_id)
