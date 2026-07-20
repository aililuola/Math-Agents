from __future__ import annotations

from collections import deque
from typing import Any

from ...schemas import EvidenceStrength, ExperimentOutcome, ExperimentSpec
from .base import HandlerEvidence


def _edge_key(left: str, right: str, directed: bool) -> tuple[str, str]:
    return (left, right) if directed or left <= right else (right, left)


def run_graph_certificate(spec: ExperimentSpec) -> HandlerEvidence:
    try:
        import networkx as nx  # type: ignore[import-not-found]
    except ImportError as exc:
        raise RuntimeError(
            "graph_certificate requires the optional networkx dependency"
        ) from exc

    graph_data = spec.arguments.get("graph")
    certificate = spec.arguments.get("certificate")
    property_name = str(spec.arguments.get("property", ""))
    if not isinstance(graph_data, dict) or not isinstance(certificate, dict):
        raise ValueError("graph and certificate must be typed mappings")
    nodes = [str(node) for node in graph_data.get("nodes", [])]
    directed = bool(graph_data.get("directed", False))
    edges = [(str(edge[0]), str(edge[1])) for edge in graph_data.get("edges", [])]
    if len(nodes) != len(set(nodes)):
        raise ValueError("graph node identifiers must be unique")
    if any(left not in nodes or right not in nodes for left, right in edges):
        raise ValueError("all edge endpoints must be declared nodes")
    graph = nx.DiGraph() if directed else nx.Graph()
    graph.add_nodes_from(nodes)
    graph.add_edges_from(edges)
    edge_set = {_edge_key(left, right, directed) for left, right in edges}

    independently_valid = False
    details: dict[str, Any]
    if property_name == "proper_coloring":
        colors = {
            str(key): value for key, value in certificate.get("colors", {}).items()
        }
        nx_valid = set(colors) == set(nodes) and all(
            colors[left] != colors[right] for left, right in graph.edges()
        )
        independently_valid = set(colors) == set(nodes) and all(
            colors[left] != colors[right] for left, right in edges
        )
        details = {"colors": colors, "color_count": len(set(colors.values()))}
    elif property_name in {"path", "cycle"}:
        sequence = [str(node) for node in certificate.get("vertices", [])]
        pairs = list(zip(sequence, sequence[1:]))
        if property_name == "cycle" and sequence:
            pairs.append((sequence[-1], sequence[0]))
        edge_valid = all(
            _edge_key(left, right, directed) in edge_set for left, right in pairs
        )
        minimum_vertices = 2 if directed and property_name == "cycle" else 3
        length_valid = (
            len(sequence) >= minimum_vertices
            if property_name == "cycle"
            else bool(sequence)
        )
        unique_valid = (
            length_valid
            and len(sequence) == len(set(sequence))
            and set(sequence).issubset(nodes)
        )
        independently_valid = bool(sequence) and edge_valid and unique_valid
        nx_valid = independently_valid and all(
            graph.has_edge(left, right) for left, right in pairs
        )
        details = {"vertices": sequence, "length": len(pairs)}
    elif property_name == "matching":
        if directed:
            raise ValueError(
                "matching certificates currently require an undirected graph"
            )
        matching = [
            (str(edge[0]), str(edge[1])) for edge in certificate.get("edges", [])
        ]
        used: set[str] = set()
        independently_valid = True
        for left, right in matching:
            if (
                _edge_key(left, right, directed) not in edge_set
                or left in used
                or right in used
            ):
                independently_valid = False
                break
            used.update({left, right})
        nx_valid = independently_valid and nx.is_matching(graph, matching)
        details = {"edges": matching, "size": len(matching)}
    elif property_name == "connected":
        if directed:
            nx_valid = nx.is_strongly_connected(graph)
        else:
            nx_valid = nx.is_connected(graph) if nodes else False
        if nodes:
            adjacency: dict[str, set[str]] = {node: set() for node in nodes}
            reverse: dict[str, set[str]] = {node: set() for node in nodes}
            for left, right in edges:
                adjacency[left].add(right)
                reverse[right].add(left)
                if not directed:
                    adjacency[right].add(left)
                    reverse[left].add(right)
            seen = {nodes[0]}
            queue = deque([nodes[0]])
            while queue:
                current = queue.popleft()
                for neighbor in adjacency[current] - seen:
                    seen.add(neighbor)
                    queue.append(neighbor)
            independently_valid = len(seen) == len(nodes)
            if directed and independently_valid:
                reverse_seen = {nodes[0]}
                reverse_queue = deque([nodes[0]])
                while reverse_queue:
                    current = reverse_queue.popleft()
                    for neighbor in reverse[current] - reverse_seen:
                        reverse_seen.add(neighbor)
                        reverse_queue.append(neighbor)
                independently_valid = len(reverse_seen) == len(nodes)
        details = {"node_count": len(nodes), "edge_count": len(edges)}
    else:
        raise ValueError(f"unsupported graph certificate property: {property_name}")

    if nx_valid != independently_valid:
        return HandlerEvidence(
            outcome=ExperimentOutcome.INCONCLUSIVE,
            evidence_strength=EvidenceStrength.HEURISTIC,
            scope={
                "node_count": len(nodes),
                "edge_count": len(edges),
                "directed": directed,
            },
            verification_notes=[
                "NetworkX and the independent checker disagreed, so no mathematical conclusion was accepted."
            ],
        )
    if not nx_valid:
        return HandlerEvidence(
            outcome=ExperimentOutcome.COUNTEREXAMPLE_FOUND,
            evidence_strength=EvidenceStrength.COUNTEREXAMPLE,
            counterexample={
                "property": property_name,
                "supplied_certificate": certificate,
                "details": details,
            },
            scope={
                "node_count": len(nodes),
                "edge_count": len(edges),
                "directed": directed,
            },
            exact_arithmetic=True,
            cases_checked=1,
            independently_verified=True,
            verification_notes=[
                "Both NetworkX and an independent property-specific checker rejected the supplied certificate."
            ],
        )
    return HandlerEvidence(
        outcome=ExperimentOutcome.CERTIFIED,
        evidence_strength=EvidenceStrength.FORMAL_CERTIFICATE,
        certificate={"property": property_name, **details},
        scope={
            "node_count": len(nodes),
            "edge_count": len(edges),
            "directed": directed,
        },
        exact_arithmetic=True,
        cases_checked=1,
        independently_verified=True,
        verification_notes=[
            "The certificate passed both NetworkX and an independent property-specific checker."
        ],
    )
