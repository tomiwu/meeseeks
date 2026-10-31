# Meeseeks: Product Requirements Document

Date: 2026-10-31. Status: approved by Tomasz t77u.
Sources: [ARCHITECTURE.md](ARCHITECTURE.md), [DECISIONS.md](DECISIONS.md) (the newest answer wins), [ARCHITECTURE-REVIEW-v2.md](ARCHITECTURE-REVIEW-v2.md), [RESEARCH-validation.md](RESEARCH-validation.md), [RESEARCH-pekko-kyo.md](RESEARCH-pekko-kyo.md).

## 1. Problem

Tomasz has many small tasks that an AI agent can do. Today he must start each agent, watch it, and check its result himself. Agents stop too early, claim success with no proof, or run without control. There is no single safe place to start tasks from chat, see all running work, and stop everything at once.

## 2. Goals

1. Start a task from chat with a goal and a check that can be run.
2. A Meeseeks works until the check passes, then sends one result message and stops.
3. Every result has proof: a passed external check (build) or verified sources (research).
4. One overseer shows the full tree of work and can stop all work at once.
5. Work can spread over remote workers in one Pekko Cluster.
6. Brain backends are pluggable: subscription login, API key, local model.

## 3. Non-goals (v1)

- No Neo4j or other graph store. The Pekko journal is the only store.
- No knowledge in the repo. The repo holds code and design docs only.
- No SSH. No Cursor cloud agents.
- No local kill command (Q35).
- No rate limit and no cost cap. Token-based rate limiting is a later item.
- No public multi-user service. Only Tomasz can send tasks.

## 4. Users

- **Tomasz (owner):** sends tasks, reads results, uses the kill switch, resumes the circuit breaker.
- **Meeseeks processes:** internal actors that do the work.
- **Workers:** machines that join the cluster and run Podman containers.

## 5. User stories

1. As Tomasz, I send a build task with a goal and a check command in chat, and I get one result message when the check passes.
2. As Tomasz, I send a task with no check, and the bot refuses it and tells me why.
3. As Tomasz, I send a research task with a rubric, and I get a report where each claim is marked verified, agreed only, or disputed, with source URLs.
4. As Tomasz, I send "kill" in chat, and all work stops: 0 containers, 0 brain processes, no new iteration.
5. As Tomasz, I get a message when the circuit breaker pauses work, and I resume it with one command.
6. As Tomasz, I see the tree of Meeseeks and their frustration level.
7. As Tomasz, I am told when a Meeseeks is stuck (level 5), and it keeps working until upstream stops it.
8. As Tomasz, I add a remote worker with its own certificate, and it gets work.
9. As Tomasz, I choose a brain backend per task or per setting, and I enter keys only in masked fields.

## 6. Functional requirements

- FR1. The server accepts tasks only from Tomasz's user ID on a chat channel (Grok bot first, Telegram fallback).
- FR2. The gateway makes a `Task` with a goal, a type (`build` or `research`), and a check or rubric. It refuses a task with no check.
- FR3. The gateway sends exactly one result message per task.
- FR4. Each Meeseeks is one persistent Pekko actor. It asks the overseer for a permit before every loop iteration.
- FR5. Each iteration runs one Kyo step through one internal adapter: brain call, Podman calls, check.
- FR6. The overseer holds the tree, the permit gate, the kill state, and the circuit breaker as journal state.
- FR7. Kill: the overseer gives no more permits and sends a stop order to all workers. Workers stop containers and brain processes and confirm.
- FR8. A worker with no server contact for 60 s stops itself (may be replaced by the cluster failure detector).
- FR9. The circuit breaker pauses new permits on a usage-limit error or free disk < 10 GB. Only Tomasz resumes.
- FR10. The overseer detects stalls with heartbeats. It kills a stalled Meeseeks and then restarts it.
- FR11. Frustration has five levels. It grows from time and failed checks. Higher levels split work into children.
- FR12. At level 5 a Meeseeks reports "stuck" to the overseer and keeps working. Only the overseer, the parent, Tomasz, or a high-confidence oracle "stop" ends it.
- FR13. Children are separate actors. Each child adds frustration to its parent. The parent merges results.
- FR14. Build check: the server runs the check in a fresh read-only container. A different Meeseeks writes the tests. A change to test files by the code writer is a fail.
- FR15. Research: N independent copies (default 3) with different prompts and, when possible, different brains, no shared context. A verifier fetches every source URL. A blind judge scores against the rubric.
- FR16. Sandbox: Podman, only the task directory is mounted. The brain runs inside the container. The worker injects only that backend's credential at start.
- FR17. Brain and oracle backends are pluggable: subscription login, API key, local model. Secrets come only from masked fields.
- FR18. Jev is an optional oracle first pass, off by default. When it is off, a blind LLM judge decides.
- FR19. Only the server writes events. It sets `writerId` and a timestamp on every event. Processes prove identity with short-lived per-process tokens.
- FR20. The protocol between processes is our own, close to A2A.
- FR21. Remote workers join the Pekko Cluster with one certificate each (mTLS) on a private network.
- FR22. A new Meeseeks asks the overseer first, to reuse solved work.

## 7. Non-functional requirements

- NFR1. Language: Scala 3.10.0 first, CI also on 3.9.0 LTS; fallback 3.9.0.
- NFR2. Build: sbt 2.0, one exact version pinned (newest stable at start: 2.0.12). JDK 25 Temurin; JDK 21 if 25 fails.
- NFR3. Newest stable versions first, always. Pin exact versions.
- NFR4. Pekko 1.7.1 core modules on one version; Kyo pinned to one exact version (1.0.0-RC7 at start).
- NFR5. CI jobs: format, lint, test, crap (crap4s rules). CI uses fakes only.
- NFR6. Modules `dev` and `runtime`; CI fails if `runtime` imports `dev`.
- NFR7. No secret in the repo, the journal, or logs. Java serialization off; Jackson CBOR with event adapters.
- NFR8. Journal: H2 through pekko-persistence-jdbc, on a volume outside all repos.
- NFR9. Code follows [CODING_STANDARDS.md](CODING_STANDARDS.md).
- NFR10. All text in English (ASD-STE100 Simplified Technical English).

## 8. Task types

| Type | Check | Proof |
|---|---|---|
| `build` | Strict external check (for example `sbt test`), run by the server in a fresh read-only container | Check passes; tests from a different Meeseeks |
| `research` | Rubric set with the goal | Independent copies, verified source URLs, blind judge |

## 9. Architecture summary

Chat -> gateway -> `Task` -> Meeseeks actor -> permit from overseer -> Kyo step -> brain and Podman on a worker -> check -> event to journal -> result message. See [ARCHITECTURE.md](ARCHITECTURE.md) and the diagrams:
[component](diagrams/component-v2.png), [sequence](diagrams/sequence-v2.png), [frustration](diagrams/frustration-v2.png), [permit and kill](diagrams/permit-kill-v2.png), [cluster and data](diagrams/cluster-data-v2.png), [research](diagrams/research-v2.png).

## 10. Decisions (short form; full text in [DECISIONS.md](DECISIONS.md))

| ID | Decision |
|---|---|
| Q1 | Long-running server, reached through chat channels |
| Q2 | Scala 3, crap4s defaults |
| Q3 | Pluggable brain |
| Q4 | Every task has a checkable goal |
| Q5 | Five frustration levels; never skip the check |
| Q6 | Children are separate processes; no depth or count limit (replaced) |
| Q7 | No hard time cap; one overseer |
| Q8 | Podman; only the task directory mounted |
| Q9 | `tomiwu/meeseeks`, public, MIT, fan project |
| Q10 | Kill switch stops everything; no rate limit |
| Q11 | Remote workers allowed; Cursor cloud agents excluded (t77u) |
| Q12 | Kyo from v1, pinned, behind one adapter |
| Q13 | Neo4j dropped (replaced by Q40) |
| Q14 | Grok bot first, Telegram fallback, T3 Code for overview |
| Q15 | Server on Tomasz's computer first; workers later |
| Q16 | No knowledge in repo; `dev` and `runtime` modules with CI import check |
| Q17 | Repo and issues may be created; ask before any install |
| Q18 | Graph model approved, only for a later read model |
| Q19 | Overseer watch-only plus kill switch (extended by Q24, Q25, Q30) |
| Q20 | Chat input with goal and check; refuse no check |
| Q21 | One result message per task |
| Q22 | Everything in v1 |
| Q23 | T3 Code connection researched first |
| Q24 | Permit before every iteration; kill = no permits + stop order; 60 s self-stop |
| Q25 | Circuit breaker: usage-limit error or free disk < 10 GB; only Tomasz resumes |
| Q26 | Disk blocker resolved (t73u): Podman overlay, 46% used |
| Q27 | Build check in fresh read-only container; separate test writer |
| Q28 | Only server writes; writerId; per-process tokens; protocol close to A2A |
| Q29 | Pekko journal holds the tree state (settled by Q40) |
| Q30 | Overseer owns stall detection; restart kills old first |
| Q31 | Level 5: report stuck, continue; only upstream or confident oracle stops |
| Q32 | All cluster based, per-worker certificate, no SSH |
| Q33 | Test project first; sbt 2.0 |
| Q34 | CI uses fakes only |
| Q35 | No local kill command |
| Q36 | Assume subscription backends may be used for automation (Tomasz's assumption) |
| Q37 | Jev optional oracle, off by default |
| Q38 | Research Pekko and Kyo together (done) |
| Q39 | Pekko for state and cluster, Kyo inside steps |
| Q40 | No Neo4j in v1; journal is the only store |
| Q41 | Newest versions first; Scala 3.10.0, fallback 3.9.0 |
| t31u..t36u | Round 2 and 3 answers (Q10..Q23) |
| t42u..t46u | Permits, breaker, disk gate, task types |
| t49u..t53u | Authentication, Jev, API keys allowed, Q28 |
| t54u..t59u | Pekko + Kyo, Neo4j dropped |
| t60u..t69u | Q30..Q35, sbt 2.0, newest versions |
| t70u | Q36 assumption |
| t73u | Disk resolved |
| t77u | Independent research copies; no Cursor cloud agents; brain inside container; approved |

## 11. Risks

- Kyo 1.0.0-RC7 and Pekko on Scala 3.10.0 are not proven.
- pekko-persistence-jdbc 1.3.0 and H2 with Pekko core 1.7.1 are not proven.
- crap4s rules and a mutation testing tool on sbt 2.0 are not proven.
- JDK 25 with Pekko 1.7.1 is not proven.
- Subscription terms for automated use were not checked (Q36 assumption).
- No cost cap: a loop can use much quota. The circuit breaker is the only guard.
- Grok bot channel API is not known yet.

## 12. Open items

See [ARCHITECTURE.md section 8](ARCHITECTURE.md#8-open-items): T3 Code connection, Grok bot channel API, private network, Split Brain Resolver strategy, names and limits of frustration levels, vault location, A2A research, crap4s on sbt 2.0, Kyo and Pekko on 3.10.0, H2 journal, JDK 25, research copy count and claim extraction, cluster failure detector versus own lease, exact sbt pin, Pekko module compatibility.

## 13. Acceptance tests

- AT1 Kill: start tasks on two workers, send kill. After the confirm timeout: 0 containers, 0 brain processes, no new iteration event in the journal.
- AT2 Lease: cut a worker from the server. Within 60 s it has 0 containers and 0 brain processes.
- AT3 No check: a task with no check is refused with one message.
- AT4 Build: a fake brain makes a failing test pass; the read-only check container passes; one result message.
- AT5 Test guard: a code writer that changes a test file gets a fail.
- AT6 Breaker: a fake usage-limit error pauses permits; only the resume command restarts them.
- AT7 Disk: a fake free-disk value < 10 GB pauses permits.
- AT8 Stall: a Meeseeks with no heartbeat is killed and restarted.
- AT9 Research: three independent fake copies, a verifier, and a blind judge make a report with claim marks.
- AT10 Restart: after a server restart, the tree, permits, kill state, and breaker state come back from the journal.

## 14. Milestones

1. M0 Test project: sbt 2.0, Scala 3.10.0/3.9.0, Kyo, Pekko, H2 journal, 2-node cluster, CI.
2. M1 Core: modules, fakes, task model, gateway, Meeseeks actor, overseer, journal.
3. M2 Work: Kyo adapter, Podman sandbox, build check, frustration, children.
4. M3 Research and oracles: research flow, backends, Jev.
5. M4 Distribution and channels: cluster workers, protocol and tokens, Grok bot and Telegram, T3 Code.
6. M5 Safety: kill end to end, disk breaker, branch protection, Sandcastle config.
