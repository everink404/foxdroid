# FoxDroid shared contracts

This directory contains runtime-neutral fixtures and protocol snapshots intended
to be consumed independently by Web and Android implementations. It must not
depend on JavaScript-, Python-, or Kotlin-specific serialization behavior.

`test-vectors/chart-notes.json` is the first shared chart semantics fixture. Its
expected values describe the logical chart only; mutable gameplay state such as
judgment, combo, and held-key state does not belong in this contract.

`test-vectors/judgment-sequences.json` defines deterministic input sequences for
Hold, Roll, and Mine behavior. Rule values are explicit because StepMania permits
theme-level differences for grace windows and whether a mine requires a fresh step.

`golden-library/` contains original, media-free `.sm` and `.ssc` samples for scanner
and parser integration tests. It covers fixed timing, positive and negative Offset,
BPM changes, Stop, Delay, Warp, chart-level timing override, `.ssc` preference,
a missing title, and a missing music asset. All sample content was written for
FoxDroid and contains no third-party songs or recordings. The chart timing cases
in `test-vectors/chart-notes.json` give the Web client fixed expected note times.

`api/openapi-v1.json` is the checked-in draft contract for content-service
clients. Server tests fail when the generated schema changes without an explicit
snapshot update.
