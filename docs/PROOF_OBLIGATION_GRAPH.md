# Proof Obligation Graph

`ProofGraphStore` is the global typed blackboard for what remains to be proved.
Nodes are main goals, subgoals, lemmas, case branches, constructions,
computation questions, formalization tasks and contradictions. Claim messages
can also be referenced as evidence nodes.

Edges are `depends_on`, `implies`, `refutes`, `equivalent_to`, `strengthens`,
`weakens`, `uses_construction` and `closes`. Edge insertion is cycle-checked by
default and bounded by configured node/edge limits.

## Lifecycle

An obligation moves `open -> tentative -> closed`, `open -> refuted`, or
`open -> blocked -> open`. Closing requires an evidence message and adequate
confidence. A counterexample can refute a claim, reopen transitively dependent
obligations and invalidate facts that used it. Formalization failure creates a
new `formalization_task`; it does not declare the natural-language claim false.

## Proof Debt

Proof debt is a configured weighted sum of open node kind, priority,
centrality, dependency count, shared-route count, repeated failures and
conflict risk. The scheduler values verified debt reduction, prioritizes exact
counterexamples and high-centrality conflicts, and recognizes a shared open
node as a bridge opportunity rather than asking every route to deepen it.

## Equivalence And Sharing

Deterministic normalization is attempted first. Ambiguous matches can be sent
to the configured matching interface. Equivalent obligations from two or more
routes become one bounded bridge task. Mechanism, obligation and verified-fact
overlap drive duplicate-route detection; prose similarity alone cannot merge a
route.

## Modes And Freeze

In `shadow` mode the graph records proposed mutations and scheduler signals but
must not alter legacy scheduling. In `active` mode graph actions may run.
`freeze()` rejects all subsequent graph writes and is called before synthesis
when `final_stage.freeze_graph_before_synthesis` is enabled. Final synthesis is
therefore based on one immutable evidence boundary.

`export_state()` and `from_state()` preserve nodes, claim nodes, edges, events,
mode and freeze state. Machine and Mermaid snapshots are emitted in
`reports/proof_graph.json` and `reports/proof_graph.mmd`.
