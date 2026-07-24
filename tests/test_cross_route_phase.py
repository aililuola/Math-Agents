from mathproofmesh.cross_route_phase import team_reviews_allow_global_share


def test_global_share_gate_uses_the_claims_source_delta_review() -> None:
    reviews = [
        {
            "delta_id": "accepted-delta",
            "global_share_allowed": True,
            "validation_passed": True,
        },
        {
            "delta_id": "rejected-delta",
            "global_share_allowed": False,
            "validation_passed": False,
        },
    ]

    assert team_reviews_allow_global_share(
        reviews,
        teams_enabled=True,
        delta_id="accepted-delta",
    )
    assert not team_reviews_allow_global_share(
        reviews,
        teams_enabled=True,
        delta_id="rejected-delta",
    )
    assert not team_reviews_allow_global_share(
        reviews,
        teams_enabled=True,
        delta_id="missing-delta",
    )
