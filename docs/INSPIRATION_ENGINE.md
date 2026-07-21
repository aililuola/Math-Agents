# Inspiration Engine

The 0.7 Inspiration Engine is a first-class, checkpointed search subsystem. It
does not mean "run widen again". Every trigger names an observable failure,
every proposal identifies a mechanism change and open obligation, and every
accepted proposal remains unverified until independent review.

```text
Trigger -> bounded task selection -> independent proposals
        -> Novelty Gate -> Inspiration Referee -> fast falsification
        -> InsightMemory -> obligation/route materialization
        -> later strict verification -> optional FactMemory
```

## Triggers

`TriggerPolicy` detects configured stagnation without verified gain, proof-debt
plateau, shared bottlenecks, repeated first-error fingerprints, all routes
failed, high mechanism redundancy, a route consuming too much budget without
progress, failed final repair and manual requests. It selects at most
`max_inspiration_tasks_per_round`; it never opens every mechanism at once.

Trigger detection and task construction are deterministic and cheap. Before
any generator, referee or quick-skeptic model call, every task is converted to
a typed scheduler action with a three-call cost estimate and passed through
`SoftBudgetAllocator.admit_decision()`. Rejected tasks emit an admission record
and make no provider call.

A cheap exact falsification is still allowed early. Reasoning-first forbids
computation as the default route generator, not inexpensive rejection of a
precise false premise.

## Representation Switchboard

The switchboard selects applicable representations by domain and unused
mechanism signature. Its vocabulary includes direct algebra, modular and
p-adic views, recurrence/finite state, graph or hypergraph, extremal/minimal
counterexample, invariant/monovariant, double counting, generating functions,
probabilistic, linear algebra/polynomial, synthetic geometry, coordinates,
complex plane and inversion/projective views.

Each `RepresentationCandidate` includes a rewritten view, object mapping,
preserved properties, potentially lost conditions, tools, expected advantage,
failure risks, fast failure tests and a targeted novelty signature. Geometry
representations are tags and extension points, not a claim of a complete
geometry solver.

## Analogy Agent

The first implementation searches verified local JSONL records using
object/operation tags, graph tags, mechanism tags and deterministic BM25. The
default seed library is `benchmarks/analogy_library.jsonl`. No external vector
database or web retrieval is used.

`AnalogyMapping` must state object and operation correspondence, transferable
lemmas, non-transferable conditions, transfer risks and new bridge lemmas. An
unverified or incomplete record is ignored. A missing library yields an empty
result plus a diagnostic, never an invented source.

## Auxiliary Construction Inventor

This agent proposes domain-appropriate auxiliary sequences, extremal objects,
graphs, colorings, potentials, generating functions, polynomials, coordinate
systems, geometric auxiliaries, equivalence relations, finite states or
quotients. `ConstructionProposal` gives an explicit definition, constructed
objects, target obligations, expected relation and proof-debt reduction,
falsification tests and failure conditions. A proposal without a definition or
open target is rejected.

## Invariant And Monovariant Agent

`InvariantHypothesisAgent` declares the state, allowed operations, candidate
expression and proposed behavior. It records a nontrivial boundary check and
requests an independent skeptic or exact tool to falsify the idea. It never
announces the invariant as true. Only a separately proved derived lemma can
close an obligation.

## Reverse Goal Analyzer

`ReverseGoalAnalyzer` asks which intermediate claims would imply the selected
goal, which are already supported by facts, and what smallest gaps remain. It
creates `ReverseGoalPlan` bridge requests and attaches those requests to the
Proof Obligation Graph. It receives a minimal graph slice, not route
transcripts.

## Persistent Meta-Strategist

The strategist persists across rounds and reads only observable metrics: route
scores, proof-debt history, verified gain, repeated errors, redundancy, message
utility, bridge/conflict state and protected budget. It may continue, repair,
rewrite, switch representation, search analogies, invent a construction,
launch surprise search or recommend route merge/cooldown. Per-mechanism
cooldowns prevent repeated bets on the same failed mechanism.

## Surprise Budget Explorer

Surprise calls are reserved separately from synthesis, high-risk final audit,
at least one configured revision cycle and the finish-transition buffer. A
route is created only in active mode, below `max_paths`, above the novelty
threshold and within `max_new_routes_per_trigger`. Consecutive rejected
proposals enter cooldown. Shadow mode records the same decision without
spending calls or changing routes.

## Novelty And Referee

`NoveltySignature` combines representation tags, mechanism tags, core objects,
key transformations, proof principles and targeted obligations with configured
weights. Reworded prose with the same mechanism is a duplicate. Novelty means
different, not correct.

`InspirationReferee` must differ from the proposal author. It checks semantic
distinctness, relevance, coherence, hidden assumptions and immediate
counterexamples, then chooses reject, store insight, attach, create route,
request computation or request bridge verification. Self-reviewed inspiration
cannot be broadcast.

With `require_inspiration_referee: true`, missing, local-deterministic or
self-review can at most store an Insight; it cannot attach or create a route.
With the switch explicitly disabled, deterministic local admission is allowed.
`max_new_routes_per_trigger` is counted across all mechanisms sharing the same
trigger, not only Surprise proposals.

## Modes, Checkpoint And Activity

`off` disables the engine. `shadow` runs trigger, generation, novelty and review
for diagnostics but makes no scheduling, graph, memory or budget mutation.
`active` may store Insights, attach obligations or create routes after all
gates. Proposal/materialization IDs are stable, so resume cannot materialize
the same proposal twice.

State is stored in the stage checkpoint and
`inspiration/inspiration_checkpoint.json`: triggers, tasks, proposals, reviews,
materializations, derived strategies, verified outcomes, strategist cooldowns,
surprise budget and the last observable snapshot. Activity includes all
required trigger, representation, analogy, construction, invariant, meta,
surprise, rejection, materialization and verification events without private
reasoning.

## Benchmark

Run the offline 11-variant benchmark with:

```powershell
python -m benchmarks.topology.run_mock_benchmark
```

It covers active/shadow graph and inspiration, plus ablations without analogy,
representation switching, surprise budget and the persistent strategist. It
makes no provider calls.
