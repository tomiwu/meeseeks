# Meeseeks: Architecture v2

Date: 2026-10-31. Status: approved by Tomasz t77u.
Source of truth: `DECISIONS.md` (Q1 to Q41, chat t31u to t77u). The newest answer wins.
Diagrams: `diagrams/`. Marks: OPEN = not decided. ASSUMPTION = Tomasz's assumption, not checked.

## One-page summary

- **What:** a long-running Scala 3 server. Tomasz sends a task in chat. Each task has a goal and a check. A Meeseeks works until the check passes, sends one result message, and stops.
- **Chat:** a dedicated Grok bot first, Telegram fallback. T3 Code shows overview and sessions (connection is OPEN).
- **Task types:** `build` (strict external check, run by the server in a fresh read-only container; tests come from a different Meeseeks) and `research` (several independent copies (different prompts, different brains when possible, no shared context), a blind judge, every claim has a verified source URL). A task with no check is refused.
- **State:** Apache Pekko 1.7.1 Typed + Persistence + Cluster. The Pekko event journal is the ONLY store in v1 (H2 through pekko-persistence-jdbc). No Neo4j in v1. A graph read model (CQRS) can come later.
- **Work inside one step:** Kyo, pinned to one exact version, behind one small internal adapter. Kyo is a must-have.
- **Distribution:** all remote work goes through Pekko Cluster with one certificate per worker (mTLS). No SSH. No fallback to SSH.
- **Control:** a permit before every loop iteration. Kill = no more permits + stop order to all workers. Workers stop themselves after 60 s without server contact. A circuit breaker pauses permits on a usage-limit error or on free disk < 10 GB; only Tomasz resumes.
- **Frustration:** five levels. At level 5 a Meeseeks reports "stuck" to the overseer and keeps working. Only upstream (overseer, parent, Tomasz) or a high-confidence oracle "stop" ends it.
- **Overseer:** does no task work. It shows the tree, controls permits, the breaker and restarts, owns stall detection with heartbeats, operates the kill switch.
- **Backends:** pluggable brain and oracle backends: subscription login, API key, local model. Keys only through masked fields. Jev is an optional oracle, off by default.
- **Build:** sbt 2.0 (one exact version pinned). Scala 3.10.0 first; CI tests 3.10.0 and 3.9.0; fallback 3.9.0 LTS. Newest stable versions first, always.
- **Repo:** `tomiwu/meeseeks`, public, MIT, fan project. Code only, no knowledge. Modules `dev` and `runtime`; CI checks that `runtime` never imports `dev`. CI uses fakes only.
- **Disk (Q26):** resolved (t73u). Podman uses the overlay driver; 46% used.

## 1. Purpose and scope (Q1, Q4, Q22)

Meeseeks is a long-running server, not a CLI tool. It accepts a task from chat. The task has a concrete goal and a check that can be run. A Meeseeks process works on the task until the check passes. Then it sends the result and stops.
Version 1 includes everything: children, remote workers, and the overseer. There is no staging.
Development and inference are two separate operations. They stay separate in code, in data, and in plans.

## 2. Components

**Chat channels (Q14).** First channel: a dedicated Grok bot. Fallback: Telegram. Only Tomasz's user ID can send tasks. The server ignores all other senders. The Grok bot channel API is OPEN.

**Gateway (Q20, Q21).** Part of the server. It reads a chat message and makes a `Task` with a goal, a check type (`build` or `research`), and a check or rubric. It refuses a task without a check. It sends exactly one result message per task.

**Overseer actor (Q7, Q19, Q24, Q25, Q30).** One persistent Pekko actor ("Spartan ephor" role). It holds the full tree map, the permit gate, the kill state, and the circuit breaker. All these survive a restart because they are journal state. In v1 it does no task work and makes no task decisions. It shows the tree, controls permits and the breaker, detects stalls with heartbeats, gets heartbeats from every Meeseeks actor (workers report to the Meeseeks actor, the actor forwards to the overseer), restarts a stalled Meeseeks (it kills the old one first), and operates the kill switch. A new Meeseeks asks the overseer first, to reuse solved work.

**Meeseeks actor (Q5, Q6).** One persistent Pekko actor per Meeseeks. It runs a loop. Before every loop iteration it asks for a permit. Each iteration runs one Kyo step: a brain call, Podman calls, and a check. Frustration grows with time and failed checks. It never skips the check. At higher levels it splits the task and starts children.

**Meeseeks tree (Q6 replaced).** Children are separate processes. No depth limit, no count limit, no cost cap, no rate limit in v1. Each child adds frustration to its parent. The parent merges the results.

**Kyo step adapter (Q12, Q39).** Kyo does the work inside one step: brain calls, Podman calls, check runs, timeouts, retries. Actors call Kyo through one adapter (run as Future + `pipeToSelf`). One exact Kyo version is pinned. An API break changes the adapter only. The thread pool is shared through kyo-scheduler-pekko (to verify in the test project).

**Brain and oracle backends (Q3, Q37, t52u).** Pluggable. Three backend kinds: subscription login (for example Claude Code), API key, local model. Keys enter only through masked fields and never go into the repo, the journal, or logs. Inference is stateless: the brain gets only the context slice that the Meeseeks gives it. Cursor cloud agents are excluded (t77u). Remote work runs only on workers that join the Pekko Cluster. ASSUMPTION (Q36): subscription terms allow automated use; nobody checked the terms.

**Oracle (Q31, Q37).** Gives pass / fail / unsure, or stop / continue for a stuck Meeseeks. Jev (TypeSafe AI) is an optional first pass, off by default, with an API key and per-token cost. When Jev is off, a blind LLM judge does the work.

**Workers (Q11, Q15, Q32).** The server runs on Tomasz's computer first. Workers on other machines (Macs and others) join the Pekko Cluster later. Each worker has its own certificate (Artery TLS, mTLS) on a private network. No SSH. A worker starts Podman containers for Meeseeks steps. Containers never join the cluster.

**Sandbox (Q8).** Podman. Only the task directory is mounted. The brain runs as a process inside the container (for example Claude Code with a subscription login); the worker injects only the credential of that backend at start, from masked-field config (approved t77u). No other secret enters the container.

**Event journal (Q29, Q40, Q28).** The Pekko event journal is the only store in v1: pekko-persistence-jdbc with embedded H2, on a volume outside all repos. Only the server writes events. The server sets `writerId` and a timestamp on every event. Processes prove identity with short-lived per-process tokens. A graph read model (CQRS, for example Neo4j) is optional and later. Obsidian notes for review can be exported from events later.

**Serialization.** Events are permanent. Use Jackson CBOR (or one custom serializer). Java serialization is off. Event adapters handle schema changes.

**Vault (knowledge).** Knowledge never lives in the repo. The Obsidian vault is external and for review only. Location is OPEN.

**T3 Code (Q14, Q23).** Overview and session UI. Runs locally first. Must be testable with the GitHub CLI. Connection is OPEN (research first).

**Protocol (Q28).** Our own message protocol, close to A2A, in v1. A2A research is OPEN.

## 3. Task types and checks (Q27, research)

**Build.** The goal names a strict external check (for example `sbt test`, or a browser test of a deployed page). A separate test Meeseeks writes the tests from the goal and spec. The code writer cannot change the tests; a change to test files counts as a fail. The server runs the check between loops and at the end in a fresh read-only container. Self-review never counts as a check.

**Research.** No strict test. The server starts N independent copies (default 3, a setting) with no shared context. Each copy gets a different prompt and, when possible, a different brain (t77u). Every claim needs a source URL; a verifier fetches each URL and marks the claim supported, not supported, or unreachable. A blind judge (no writer ID, no brain name, shuffled order; a different model family when possible) scores against a rubric set with the goal. The report marks each claim verified, agreed only, or disputed.

## 4. Frustration levels (Q5, Q31)

Five levels, from calm (1) to stuck (5). Frustration grows from time and failed checks. Higher levels split work into children.
At level 5 the Meeseeks reports "stuck" to the overseer and keeps working. The overseer asks for the problem details. Only upstream (overseer, parent, Tomasz) can stop it. The oracle can let it stop itself only on a high-confidence "stop". Names and exact limits of levels 1 to 5 are OPEN.

## 5. Permit gate, kill switch, circuit breaker (Q10, Q24, Q25, Q35)

- A Meeseeks asks for a permit before every loop iteration. Permits are automatic.
- Kill (one chat command, no settings): the overseer gives no more permits and sends a stop order to all workers. Workers stop containers and brain processes and confirm. A worker with no server contact for 60 s stops itself (lease). The Pekko Cluster failure detector may replace the own lease after the test project.
- Acceptance test: after a kill, 0 containers, 0 brain processes, no new iteration.
- No local kill command (Q35 dropped). The chat kill and the cluster stop order are the only kill paths.
- Circuit breaker: on a usage-limit error or free disk < 10 GB, the overseer pauses new permits. Only Tomasz resumes.
- FUTURE (not v1): the permit gate can become token-based rate limiting.

## 6. Build, versions, repo, CI (Q2, Q16, Q17, Q33, Q34, Q41)

- Build tool: sbt 2.0, one exact version pinned (2.0.0 or newer stable; confirm the newest). Replaces Scala CLI for this project.
- Scala 3.10.0 first (Q41). RISK: Kyo RC7 was known on Scala 3.9 only; the test project must prove Kyo and Pekko on 3.10.0. CI tests 3.10.0 and 3.9.0. If Kyo or Pekko fail on 3.10.0, report the exact failure and fall back to 3.9.0 LTS.
- Rule: always try the newest stable versions first.
- Pekko core 1.7.1 (actor-typed, persistence, cluster): all core modules on one version. pekko-persistence-jdbc 1.3.0 and pekko-projection 1.1.0 (later) have their own pinned versions; compatibility with core 1.7.1 is OPEN. JDK 25 Temurin; JDK 21 if JDK 25 fails in the test project.
- crap4s defaults (munit, strict compiler flags) where they work on sbt.
- Modules: `dev` and `runtime`. A CI check fails if `runtime` imports `dev`.
- CI uses fakes only: no real brain, no real subscription, no real API key.
- Repo `tomiwu/meeseeks`: public, MIT, fan project, code only.
- First issue: test project (sbt 2.0, Scala 3.10.0/3.9.0, pinned Kyo, Pekko 1.7.1, H2 journal, 2-node cluster on one machine, all CI jobs).

## 7. Diagrams

- `diagrams/component-v2.png`: components.
- `diagrams/sequence-v2.png`: one task, from chat to result.
- `diagrams/frustration-v2.png`: frustration levels and states.
- `diagrams/permit-kill-v2.png`: permit gate, kill, breaker.
- `diagrams/cluster-data-v2.png`: cluster, workers, journal data flow.
- `diagrams/research-v2.png`: research task flow (copies, verifier, blind judge).

## 8. Open items

1. T3 Code connection (research first).
2. Grok bot channel API.
3. Private network for the cluster (WireGuard, Tailscale, other).
4. Split Brain Resolver strategy for a server-centric cluster.
5. Names and limits of the five frustration levels.
6. Obsidian vault location.
7. A2A research for our protocol.
8. crap4s on sbt 2.0.
9. Kyo and Pekko on Scala 3.10.0.
10. H2 journal support in pekko-persistence-jdbc.
11. JDK 25 with Pekko 1.7.1.
12. Research copies count and claim extraction quality; mutation testing tool on sbt 2.0.
13. Replace the own 60 s lease with the cluster failure detector (decide after the test project).
14. Exact sbt 2.0 version to pin.
15. pekko-persistence-jdbc 1.3.0 and pekko-projection 1.1.0 compatibility with Pekko core 1.7.1.

## 9. Decided (t73u, t77u)

- Research copies are independent: different prompts, different brains when possible, no shared context (t77u, replaces "identical" in t45u).
- Cursor cloud agents are excluded. Remote workers join the Pekko Cluster (t77u).
- The brain runs inside the Podman container. The worker injects only that backend's credential at start (t77u).
- Disk blocker Q26 is resolved: 46% used, Podman uses overlay (t73u).
