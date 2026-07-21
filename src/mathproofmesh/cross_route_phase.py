from __future__ import annotations

from collections.abc import Iterable
from typing import Any


def distinct_agent_exclusions(*agent_ids: str | None) -> set[str]:
    return {agent_id for agent_id in agent_ids if agent_id is not None}


def team_reviews_allow_global_share(
    reviews: Iterable[dict[str, Any]], *, teams_enabled: bool
) -> bool:
    records = list(reviews)
    if not records:
        return not teams_enabled
    return all(
        bool(record.get("global_share_allowed"))
        and bool(record.get("validation_passed"))
        for record in records
    )
