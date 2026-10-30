"""Turso normalized persistence. Credentials never enter API views or logs."""
from .relational import NormalizedRepository
from .sql_store import StorageUnavailable, SqlFailure, SqlResult, TursoSQL


class TursoRepository(NormalizedRepository):
    backend = "turso"

    def __init__(self, url, token, namespace="camp-room", *, profile_id="default", transport=None):
        super().__init__(TursoSQL(url, token, transport=transport), namespace, profile_id)
