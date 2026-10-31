I have read all required files: `ARCHITECTURE.md`, `DECISIONS.md`, `RESEARCH-pekko-kyo.md`, `RESEARCH-validation.md`, and the five `-v2.mmd` diagrams. Below is my review. I changed no file.

---

# Independent Review: Meeseeks Architecture v2 (DRAFT)

Date: 2026-10-31. Language: ASD-STE100 Simplified Technical English.
Source of truth: `DECISIONS.md` (newest answer wins). Research inputs: `RESEARCH-pekko-kyo.md`, `RESEARCH-validation.md`. Diagrams: `diagrams/*-v2.mmd`.

## Findings

| ID | Severity | Type | Text | Decision reference | Fix |
|----|----------|------|------|--------------------|-----|
| F1 | medium | risk | `ARCHITECTURE.md` says "Pekko 1.7.1 (actor-typed, persistence, persistence-jdbc, cluster), all pekko-* modules on one version." This is not possible. The research shows `pekko-persistence-jdbc` is version **1.3.0**, and `pekko-projection` is **1.1.0**. These modules do not have a 1.7.1 release. The build rule "all pekko-* modules on one version" cannot hold. | `ARCHITECTURE.md` §6 (line 84); `RESEARCH-pekko-kyo.md` §1, §8 | Change the rule. Pin `pekko-*` core modules to 1.7.1. Pin `pekko-persistence-jdbc` to its own newest version (1.3.0). Pin `pekko-projection` to 1.1.0. Add an open item to check version compatibility between these modules. |
| F2 | medium | risk | `ARCHITECTURE.md` makes Scala 3.10.0 the default ("Scala 3.10.0 first"). The grill note says Kyo (RC7) is "Scala 3.9 only". Kyo is a must-have. The research tests Kyo only on Scala 3.9. No source shows Kyo or Pekko on Scala 3.10.0. The default version can make the must-have dependency unavailable. | `ARCHITECTURE.md` §1, §6, Open item 9; `DECISIONS.md` assistant note (Kyo RC7, Scala 3.9 only), Q12 (Kyo at all costs); `RESEARCH-pekko-kyo.md` §1, §2 | Make the test project build Kyo on Scala 3.10.0 first. If Kyo does not build, set Scala 3.9.0 LTS as the default for v1, not only as the fallback. State the Kyo "3.9 only" risk in the main text, not only in the open items. |
| F3 | medium | risk | `ARCHITECTURE.md` says research uses "N identical copies". `RESEARCH-validation.md` says that identical copies share the same errors, so their agreement is not proof. The research needs different prompts and different brains for independence. Identical copies make the blind judge and the vote weak. | `ARCHITECTURE.md` §1, §3; `DECISIONS.md` t45u ("N identical Meeseeks"); `RESEARCH-validation.md` §1 (limit 3), §6, design item 2 | Change "identical copies" to "independent copies". Give each copy a different prompt and, when possible, a different brain. Keep "no shared context". Record this change for Tomasz, because t45u used the word "identical". |
| F4 | medium | gap | `ARCHITECTURE.md` states "Cursor cloud agents are excluded" as a decision. The decision record does not close this point. Q11 said "cloud agents included" and marked a CONFLICT with the standing rule "no Cursor cloud agents"; the note says "asked Tomasz". The Q3 note also asks to confirm the meaning of "Cursor agent". No later answer resolves this. The architecture states a decision that the record does not approve. | `ARCHITECTURE.md` §2 (Brain and oracle backends); `DECISIONS.md` Q11 (t31u), Q3 note, assistant note | Mark "Cursor cloud agents excluded" as OPEN or as ASSUMPTION until Tomasz answers. Add it to the open items list. |
| F5 | low | contradiction | `ARCHITECTURE.md` calls the overseer "watch-only" in the summary and in §2. In the same places it gives the overseer the permit gate, the kill switch, the circuit breaker, and the power to restart a stalled Meeseeks (kill old first). These are active control roles, not "watch-only". The word is misleading. | `ARCHITECTURE.md` §1, §2; `DECISIONS.md` t36u (watch-only), Q24, Q25, Q30 (t60u restart) | Remove or define "watch-only". State that the overseer does no task work itself, but it does control permits, the kill switch, the breaker, and restarts. |
| F6 | medium | contradiction / diagram mismatch | The text says the overseer "owns stall detection with heartbeats". The diagrams show heartbeats only from the worker to the Meeseeks actor (`W-->>M: step result + heartbeat`; `WA -- results, heartbeats --> MA`). No diagram shows a heartbeat that reaches the overseer. The diagrams do not show the path that the text needs for stall detection. | `ARCHITECTURE.md` §1, §2 (Overseer); `DECISIONS.md` Q30 (t60u); `diagrams/sequence-v2.mmd` line 16; `diagrams/cluster-data-v2.mmd` line 15 | Add a heartbeat path from the Meeseeks actor to the overseer in the sequence and cluster diagrams. Or correct the text to show how the overseer gets heartbeat data (for example, from the Meeseeks actor or from the journal). |
| F7 | low | gap | No diagram shows the research task flow. `sequence-v2.mmd` shows only the build flow (one check container, pass/fail in a loop). The research flow (N copies, a source verifier, a blind judge, claim-by-claim result) is a main task type, but no `-v2` diagram shows it. | `ARCHITECTURE.md` §3 (Research), §7; `RESEARCH-validation.md` design items 2–6; `diagrams/sequence-v2.mmd` | Add a diagram for the research flow, or add a branch to the sequence diagram for research tasks. |
| F8 | low | gap | The Pekko journal is the only store in v1 ("event sourcing"). Every event is permanent. The architecture does not name a serialization method. The research says to use Jackson CBOR (or one custom serializer), to turn Java serialization off, and to use event adapters for schema changes. This important point is missing from the architecture. | `ARCHITECTURE.md` §2 (Event journal); `RESEARCH-pekko-kyo.md` §8, D8 | Add a short section on serialization: pick Jackson CBOR or one custom serializer, turn Java serialization off, and plan event adapters for schema changes. |
| F9 | low | diagram mismatch | `component-v2.mmd` shows an edge from the Podman container to the brain backends (`PC --> BR`). The text says the sandbox mounts only the task directory and holds no secret; secrets come only from the backend config. The text also puts brain calls in the server-side Kyo step adapter (`KA --> BR`), with stateless inference fed a context slice by the Meeseeks. A direct container-to-brain call does not match the text, because the container has no key. | `ARCHITECTURE.md` §2 (Kyo step adapter, Brain backends, Sandbox); `diagrams/component-v2.mmd` lines 25, 27–28 | Remove the `PC --> BR` edge, or label it for the local-model case only. Keep brain calls on the server side through the Kyo adapter, so no secret enters the sandbox container. |

## Count by severity

| Severity | Count |
|----------|-------|
| High | 0 |
| Medium | 5 (F1, F2, F3, F4, F6) |
| Low | 4 (F5, F7, F8, F9) |
| **Total** | **9** |

## Notes on scope

- I report only items that are real against the newest decisions, the research files, or the diagrams.
- I did not report points that the architecture already marks OPEN and handles correctly (for example: Split Brain Resolver strategy, H2 dialect list, JDK 25 flakiness, sbt 2.0 exact version, T3 Code connection). These stay open, but the text and the record agree on them.
- I did not report superseded items where the architecture follows the newest answer correctly (for example: no helper caps replaces Q6; Neo4j dropped from v1 by Q40; "Claude token only" replaced by masked-field backends in t52u).

---

## Reviewer and resolution (added by Meeseeks Bot)

- Reviewer: Claude Code CLI, `--model opus` (newest Opus alias), print mode, read-only tools (Read, Glob, Grep). Opus was available.
- Second pass (different model family): tried Cursor CLI (local, not cloud) with `gpt-5.6-sol-high`. It failed: "You've reached your $20 monthly additional usage limit" (resets 1 November UTC). No second pass was done.
- Fixed in ARCHITECTURE.md / diagrams: F1, F2 (risk text added; 3.10.0 first kept per Q41), F5, F6, F7 (new research-v2 diagram), F8, F9.
- Open, needs Tomasz: F3 (identical vs independent research copies), F4 (Cursor cloud agents, Q11 conflict).
