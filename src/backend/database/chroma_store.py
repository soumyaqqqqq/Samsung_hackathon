"""
FRIDAY - database/chroma_store.py
Vector DB initialisation, semantic memory storage, embedding retrieval.

Uses chromadb with the all-MiniLM-L6-v2 sentence-transformer for
384-dimensional embeddings stored locally.
"""

from __future__ import annotations

import logging
from pathlib import Path
from typing import Any, Optional

logger = logging.getLogger("friday.db.chroma")

# Guard import so the file is importable even without chromadb installed
try:
    import chromadb
    from chromadb.config import Settings as ChromaSettings
    CHROMA_AVAILABLE = True
except ImportError:
    CHROMA_AVAILABLE = False
    logger.warning(
        "chromadb not installed. MemoryAgent will return empty results. "
        "Run: pip install chromadb"
    )


class ChromaStore:
    """
    Wraps a persistent ChromaDB collection for FRIDAY episode memory.

    Collection schema:
      id        : unique message_id from ContextObject
      embedding : 384-dim float vector (all-MiniLM-L6-v2)
      document  : compressed LLM summary (≤ 100 words)
      metadata  : {timestamp, session_id, device_id, emotion,
                   stress_score, location}
    """

    COLLECTION_NAME = "friday_memories"

    def __init__(self, persist_path: str = "./data/chroma_store"):
        self._path   = str(Path(persist_path).expanduser())
        self._client = None
        self._col    = None

        if not CHROMA_AVAILABLE:
            logger.warning("ChromaStore running in stub mode — no persistence.")
            return

        self._client = chromadb.PersistentClient(
            path=self._path,
            settings=ChromaSettings(anonymized_telemetry=False),
        )
        self._col = self._client.get_or_create_collection(
            name=self.COLLECTION_NAME,
            metadata={"hnsw:space": "cosine"},
        )
        logger.info(
            f"ChromaStore initialised: {self._path} "
            f"({self._col.count()} memories)"
        )

    # ──────────────────────────────────────────────────────────────────────────
    # Write
    # ──────────────────────────────────────────────────────────────────────────

    def add_memory(
        self,
        doc_id: str,
        text: str,
        metadata: dict[str, str],
    ) -> bool:
        """
        Store a compressed episode in ChromaDB.
        Returns True on success.
        """
        if self._col is None:
            return False

        # Truncate to 100-word limit (approx)
        words     = text.split()
        truncated = " ".join(words[:100])

        try:
            # upsert so duplicate message_ids overwrite gracefully
            self._col.upsert(
                ids=[doc_id],
                documents=[truncated],
                metadatas=[metadata],
            )
            return True
        except Exception as exc:
            logger.exception(f"ChromaStore.add_memory failed: {exc}")
            return False

    # ──────────────────────────────────────────────────────────────────────────
    # Read
    # ──────────────────────────────────────────────────────────────────────────

    def query_memories(
        self,
        query_text: str,
        n_results: int = 5,
        where: Optional[dict] = None,
    ) -> list[dict[str, Any]]:
        """
        Return the top-n semantically similar memories.
        Each result dict contains: id, text, metadata, distance.
        """
        if self._col is None or self._col.count() == 0:
            return []

        n_results = min(n_results, self._col.count())
        if n_results == 0:
            return []

        try:
            kwargs: dict = {
                "query_texts": [query_text],
                "n_results":   n_results,
                "include":     ["documents", "metadatas", "distances"],
            }
            if where:
                kwargs["where"] = where

            result = self._col.query(**kwargs)

            memories = []
            ids       = result.get("ids",       [[]])[0]
            docs      = result.get("documents", [[]])[0]
            metas     = result.get("metadatas", [[]])[0]
            dists     = result.get("distances", [[]])[0]

            for doc_id, text, meta, dist in zip(ids, docs, metas, dists):
                memories.append({
                    "id":       doc_id,
                    "text":     text,
                    "metadata": meta,
                    "distance": dist,
                })

            return memories

        except Exception as exc:
            logger.exception(f"ChromaStore.query_memories failed: {exc}")
            return []

    def get_by_id(self, doc_id: str) -> Optional[dict]:
        """Retrieve a single memory by its ID."""
        if self._col is None:
            return None
        try:
            result = self._col.get(ids=[doc_id], include=["documents", "metadatas"])
            if not result["ids"]:
                return None
            return {
                "id":       result["ids"][0],
                "text":     result["documents"][0],
                "metadata": result["metadatas"][0],
            }
        except Exception:
            return None

    def count(self) -> int:
        if self._col is None:
            return 0
        try:
            return self._col.count()
        except Exception:
            return 0

    def delete_memory(self, doc_id: str) -> bool:
        if self._col is None:
            return False
        try:
            self._col.delete(ids=[doc_id])
            return True
        except Exception:
            return False
    
   