# Research: Apache Pekko and Kyo for Meeseeks

Date: 2026-10-31. Language: ASD-STE100 style. Sources: web pages and docs that I read on this date. I did not install or build anything. Items that I did not verify are marked OPEN.

## 1. Pekko versions, Scala, JDK, license

- Pekko Core: newest stable is **1.7.1** (tag on 3 Oct 2026). The preview is 2.0.0-M4 (milestone, not stable). Source: https://pekko.apache.org/download , https://github.com/apache/pekko/tags
- Pekko 1.7.1 is built with **Scala 3.3.7** (Scala 3.3 LTS). Source: https://index.scala-lang.org/apache/pekko/artifacts/pekko-actor/1.7.1 , https://pekko.apache.org/docs/pekko/current/project/scala3.html
- **Scala 3.9:** Pekko does not publish a 3.9 build. A Scala 3.9 program can use libraries built with 3.3 (Scala 3 libraries are forward-usable by newer compilers). Pekko tests 3.9 in its community build. The draft PR passed with 3.9.0-RC6. A maintainer said that 3.9 can become the baseline "at some stage within a year of the 3.9.0 release", probably in Pekko 3. Source: https://github.com/apache/pekko/pull/3303
- **JDK 25:** Pekko merged a fix for ForkJoinPool starvation on JDK 25+ (https://github.com/apache/pekko/pull/2890). Nightly CI on JDK 25 with Scala 3.3 had many stream test timeouts (issue still open, last update March 2026): https://github.com/apache/pekko/issues/2573 . Status after 1.7.x: OPEN.
- Other modules: pekko-persistence-jdbc **1.3.0** (Jun 2026), pekko-persistence-r2dbc **1.2.0** (Jun 2026), pekko-projection **1.1.0** (Feb 2025), pekko-persistence-cassandra (last release Jan 2025). Source: https://pekko.apache.org/download.html , https://release-catalog.apache.org/pekko/index.html
- License: **Apache-2.0** for all modules. No paid license (Akka BSL problem does not apply).

## 2. Kyo and Pekko in one program

- Kyo has an official bridge: **kyo-scheduler-pekko**. One HOCON line puts Pekko dispatchers on the Kyo scheduler, so actors and Kyo fibers share one adaptive thread pool:
  `pekko.actor.default-dispatcher.executor = "kyo.scheduler.KyoExecutorServiceConfigurator"`
  Source: https://getkyo.io/latest/kyo-scheduler-pekko/ , https://github.com/getkyo/kyo/
- There is **no Kyo effect wrapper for Pekko actors** (no "Kyo Pekko" module like kyo-zio). I found none. OPEN: confirm in getkyo/kyo issues.
- Boundary pattern (my design, from Pekko docs): inside an actor, do not block. Run the Kyo effect to a `Future`, then use `context.pipeToSelf` to send the result back to the actor as a message. Source: https://pekko.apache.org/docs/pekko/current/typed/interaction-patterns.html . The exact Kyo call to turn an effect into a `Future` in RC7: OPEN (verify in Kyo docs before code).
- From Kyo to Pekko: `ask` returns `scala.concurrent.Future`. Kyo can await a Future (Kyo has Future interop; exact RC7 name OPEN).
- Risk: two models of concurrency, two models of cancellation. Kyo fiber interruption does not stop an actor, and an actor stop does not interrupt a Kyo fiber unless we code it.

## 3. Does Kyo already have actor features?

Yes. **kyo-actor** (docs 1.0.0-RC7, https://getkyo.io/latest/kyo-actor/):
- Typed actors with a private FIFO mailbox, `send`, `trySend`, `ask`, mailbox capacity (back-pressure).
- Supervision by `Scope`: when the parent scope closes, the child actors close. This matches "kill the tree".
- Since PR #1689 (merged June 2026): `ask` does not hang when an actor dies; `PubSub`, `Hub` bridge. https://github.com/getkyo/kyo/pull/1689
- Kyo also has `kyo-stm` (TRef/TMap) for shared state.

What kyo-actor does **not** have (as far as I found): persistence / event sourcing, remoting across machines, cluster membership, failure detector. These are the reasons to want Pekko.

## 4. Pekko Persistence journals

| Journal | Extra DB? | Maintained | Fit for single-machine start |
|---|---|---|---|
| JDBC (pekko-persistence-jdbc 1.3.0) with H2 (file) | No server (embedded) | Yes | Good. H2 support in Pekko JDBC: confirm dialect list (OPEN) |
| JDBC with SQLite | No server | OPEN (I did not find SQLite in the supported list) | Do not rely on it |
| JDBC / R2DBC with Postgres (r2dbc 1.2.0) | Postgres in Podman | Yes | Good, but one more container |
| LevelDB (built-in) | No | Deprecated in Akka/Pekko docs, test only (OPEN to re-check) | No for production |
| Cassandra | Yes, heavy | Last release Jan 2025 | No |
| In-memory (test) | No | Yes | CI only |

- Snapshots: supported by EventSourcedBehavior (snapshot every N events). Recovery: on start, an actor replays snapshot + events, then runs `RecoveryCompleted` signal. Docs: https://pekko.apache.org/docs/pekko/current/typed/persistence.html
- Mapping to Meeseeks: one persistent actor per Meeseeks (or one "Tree" actor). Events: Spawned, PermitGranted, IterationDone, CheckResult, Killed, Blocked. On `RecoveryCompleted`, the server does adopt-or-kill (Q29). Kill state and fuse state are also persisted, so a restart does not give permits after a kill.

## 5. Pekko Cluster with remote workers

- Transport: Artery TCP; with `transport = tls-tcp` and mutual auth (mTLS) on by default. Needs a key store per node and one CA. https://pekko.apache.org/docs/pekko/current/remoting-artery.html , https://nightlies.apache.org/pekko/docs/pekko/main-snapshot/docs/remote-security.html
- Seed nodes: the server is the seed. Workers join it over the private network (for example WireGuard/Tailscale: OPEN which).
- 2 to 10 nodes: OK for Pekko Cluster. Need a downing provider: Split Brain Resolver (https://pekko.apache.org/docs/pekko/current/split-brain-resolver.html). For "server is the boss", the strategy `keep-oldest` or `lease-majority`/static-quorum must be chosen (OPEN; my proposal: keep-oldest with the server as oldest, down-if-alone off).
- Mapping: Pekko failure detector + "unreachable" event replaces our heartbeat. Worker that is downed runs **Coordinated Shutdown**, and our shutdown task stops Podman containers and brain processes. This replaces the 60-second worker lease (the timeout is a setting in the failure detector + SBR stable-after).
- SSH alternative: no cluster, the server starts worker processes over SSH. Simpler, but we write heartbeat, lease, and messages ourselves.
- Important: Pekko remoting is not designed for untrusted networks. The Meeseeks containers must NOT join the cluster. Only the server and workers join. Meeseeks talk to their worker over a local channel with the token (Q28).

## 6. Neo4j as read model

- Pekko Projection reads persisted events (`EventSourcedProvider.eventsByTag` or eventsBySlices) and stores the offset. https://pekko.apache.org/docs/pekko-projection/current/eventsourced.html
- There is **no Neo4j projection module**. We write a handler. Option A: JdbcProjection with offset in the H2/Postgres journal DB and our handler writes to Neo4j (at-least-once; handler must be idempotent with MERGE on event id). https://pekko.apache.org/docs/pekko-projection/current/jdbc.html
- Exactly-once into Neo4j is not possible this way (offset and Neo4j are different stores). At-least-once + idempotent writes is enough.
- Effect: Neo4j stays single writer (only the projection writes). Neo4j is no longer the source of truth; the journal is. This changes Q28/Q29 text.

## 7. Test seams

- `pekko-actor-testkit-typed` 1.7.1 (ActorTestKit, BehaviorTestKit, TestProbe). https://index.scala-lang.org/apache/pekko/artifacts
- `EventSourcedBehaviorTestKit` and in-memory journal/snapshot plugins for CI (pekko-persistence-testkit). Docs: https://pekko.apache.org/docs/pekko/current/typed/persistence-testing.html (version check OPEN).
- Multi-node tests (cluster) are heavy; for CI use single-JVM cluster tests and fakes for workers (Q34).

## 8. Costs and risks

- Complexity: two runtimes (Kyo fibers + Pekko actors), HOCON config, serialization (must pick Jackson CBOR or a custom serializer for every message and event; Java serialization must stay off).
- Event schema evolution: every event is permanent. Changes need adapters.
- Version pinning: all pekko-* artifacts must have the same version. Projection 1.1.0 is older than core 1.7.1 (compatibility: OPEN, check projection release notes).
- Scala 3.9 + Pekko built on 3.3: should work, but not tested by Pekko as a release. Spike needed (extends Q33).
- JDK 25: open test flakiness in Pekko nightly. Option: run on JDK 21 if the spike shows problems.
- Build: Scala CLI can use Pekko as normal deps; sbt also fine. crap4s CI jobs: effect OPEN (mutation testing and coverage over actors and HOCON; more test time).
- Disk: more jars (tens of MB, not GB). Small effect.

## Comparison

| Topic | Plain Scala 3 + Kyo | Pekko + Kyo | Pekko only |
|---|---|---|---|
| Kyo must-have | Yes | Yes, at the edges | No (breaks rule) |
| Actors / supervision | kyo-actor + Scope | Pekko Typed | Pekko Typed |
| State after crash | Own (Neo4j or own event log) | Pekko Persistence (journal) | Pekko Persistence |
| Remote workers | Own over SSH: heartbeat, lease, messages | Pekko Cluster + mTLS + SBR | Pekko Cluster |
| Kill / lease | Own code | Coordinated shutdown + failure detector + own permit actor | Same |
| Neo4j | Single writer, source of truth | Read model from projection | Read model |
| Complexity | Low to medium | High (two runtimes) | Medium |
| Scala 3.9 | Native (Kyo RC7) | 3.3 libs on 3.9: spike | 3.3 OK, 3.9 spike |
| Effect style | One style | Mixed (Future at boundary) | Future style |

## Recommended design

Pekko + Kyo, with a clear border:
1. Pekko owns **durable state and distribution**: one persistent "Overseer" actor (permits, kill, fuse) and one persistent actor per Meeseeks; Pekko Cluster between server and workers with Artery TLS (mTLS) and Split Brain Resolver.
2. Kyo owns **the work inside one step**: brain calls, Podman calls, check runs, timeouts, retries. Actors call Kyo through one adapter (`runToFuture` + `pipeToSelf`). Dispatcher uses kyo-scheduler-pekko so there is one thread pool.
3. Journal: pekko-persistence-jdbc with an embedded file DB (H2) at start; Postgres later if needed.
4. Neo4j: read model from a Pekko Projection with our own idempotent handler.
5. Meeseeks containers never join the cluster; they talk to their worker with the token.
6. A spike decides first. If the spike fails (Scala 3.9 or JDK 25), fall back to plain Kyo + kyo-actor + own SSH, and keep Neo4j as source of truth.

## Decisions (answer "yes")

1. D1: Run a spike first: Scala 3.9 + Kyo 1.0.0-RC7 + Pekko 1.7.1 (actor-typed, persistence-jdbc with H2, cluster with 2 nodes on one machine, kyo-scheduler-pekko), through all crap4s CI jobs. Yes?
2. D2: If the spike passes, Pekko owns state and distribution, and Kyo owns work inside a step (border = one adapter). Yes?
3. D3: Journal = pekko-persistence-jdbc on embedded H2 for v1. Yes?
4. D4: Neo4j becomes a read model, fed by our own Pekko Projection handler (at-least-once, idempotent). This changes Q28 and Q29. Yes?
5. D5: Remote workers join Pekko Cluster over a private network with Artery TLS (mTLS) and Split Brain Resolver; this replaces SSH transport and the own 60-second lease (Q32, Q24). Yes?
6. D6: Meeseeks containers never join the cluster. Yes?
7. D7: Permit gate and kill switch are state of one persistent Overseer actor, so they survive restart. Yes?
8. D8: Serialization = Jackson CBOR (or one custom serializer), Java serialization off, event adapters for schema changes. Yes?
9. D9: Pin one Pekko version for all pekko-* modules; JDK 21 as fallback if JDK 25 is unstable in the spike. Yes?
10. D10: If the spike fails, fall back to plain Kyo + kyo-actor + own SSH design. Yes?

## OPEN

- Pekko with Scala 3.9.x final and JDK 25: not proven for our use; spike.
- Exact Kyo RC7 API to run an effect as a Future and to await a Future.
- Is there any Kyo-Pekko project or issue beyond kyo-scheduler-pekko? I found none.
- H2 and SQLite support in pekko-persistence-jdbc 1.3.0 (dialect list). LevelDB status in Pekko 1.7.
- Projection 1.1.0 compatibility with Pekko core 1.7.1.
- Split Brain Resolver strategy for a server-centric cluster.
- Private network choice (WireGuard, Tailscale, other).
- Effect on crap4s CI jobs (mutation testing over actors).
- Persistence testkit artifact version and in-memory journal config for 1.7.1.
