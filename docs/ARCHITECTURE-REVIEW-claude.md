# Meeseeks Architecture Review (Claude, independent pass)

Date: 2026-10-31. Input: `ARCHITECTURE.md` (DRAFT), `DECISIONS.md`, `diagrams/*.png`.

Method: the skills `codebase-design` (module, interface, depth, seam, adapter, the deletion test, "one adapter is a hypothetical seam, two is a real one"), `domain-modeling` (glossary, ADR), and `grilling` (numbered questions, each worded so "yes" accepts the recommended answer). No code exists yet, so this review treats the architecture document itself as the design to grill, not a codebase to scan.

Facts used as given, not verified in this sandbox: the box is 83% full; the Podman vfs storage driver already holds 65 GB. Facts not described in the source documents (Kyo APIs, T3 Code APIs, exact subscription terms) are marked OPEN and not invented.

## Findings

| ID | Sev | Gap | Evidence | Recommendation |
|----|-----|-----|----------|----------------|
| R1 | High | The kill switch has two arrows in the diagram (`KS --> Tree`, `KS --> RW`) and no arrow back. Nothing confirms a kill worked. A Meeseeks is "a separate process" (§2 Meeseeks tree), and the brain runs inside the Podman sandbox, so a container stop is not proven to stop the brain process inside it. | §3 diagram; §8 "How the kill reaches remote workers is OPEN." | Define kill as a protocol, not a signal: the server stops new spawns, sends a stop command to each worker and each container, and each worker reports back. Add a lease: a worker that has no contact with the server for N seconds stops itself. Write an acceptance check: after a kill, 0 containers and 0 brain processes remain, local and remote. |
| R2 | High | No cap, no rate limit, and a shared subscription login (§2, §8) is an open usage and billing risk. The exact subscription terms for automated, multi-process use are not read. | §8 "Usage risk... This is a known risk." | Read the subscription terms for Claude Code and Cursor before v1 (OPEN until done). Keep "no cap" as the stated policy, but add one automatic pause: when the brain returns a usage-limit error, stop new spawns until Tomasz resumes. |
| R3 | High | The stall monitor has no input in the diagram (`MON --> CORE` only) and no owner (§2: "Whether the overseer or a separate part does this is OPEN"). It cannot detect a stall with no data flowing into it. "Stalled" and "blocked" are both used (§2, Q4) but neither is defined. | §2 Stall monitor; §3 diagram; Q4 in `DECISIONS.md`. | Give the overseer ownership of stall detection, since it already "watches the full tree" (§2). Require a heartbeat `Event` from each Meeseeks. Define "stalled" as: no heartbeat, or no new `Event`, for N minutes. Add the missing `Tree --> MON` edge to the diagram. |
| R4 | High | The two diagrams disagree on who writes to T3 Code. The component diagram shows only `OV --> T3` (overseer feeds T3). The sequence diagram shows the Main Meeseeks writing directly: `M-->>X: intermediate step`. One of these is wrong, or T3 Code has two writers with no stated trust rule, the same problem as the Neo4j writer trust gap (see R7). | §3 diagram vs §4 sequence diagram, line "M-->>X: intermediate step". | Pick one path. Recommended: the Meeseeks tree never writes to T3 Code directly. T3 Code reads a status interface that the overseer (or server core) produces from Neo4j. One writer, one seam, matches the Neo4j recommendation in R7. Fix the sequence diagram to route through the overseer. |
| R5 | High | "A Meeseeks never skips the check" (§1, §5), but the check and the brain's writable files share the same mounted directory (§2 Sandbox: "Only the task directory is mounted"). Nothing stops the brain from editing the check command, the test files, or a config the check depends on, so the check can be made to pass without the goal being met. | §2 Sandbox; §5 rules. | Separate the seam the brain writes through from the seam that decides "done." The server, not the Meeseeks, runs the check, from a read-only copy taken at task start. Hash the check files at task start; a changed hash fails the task. Refuse trivially true checks (for example, a bare `true`) at the gateway. |
| R6 | High | "Inference is stateless" (§2 Brain), but the server is "long-running" (§1) and holds the live tree, frustration levels, and leases somewhere. The diagram shows only a one-way write, `Tree -- writes, writerId --> NEO`. Nothing reads the tree back to rebuild it. Deletion test: if the server core process is deleted (a crash), does the running tree reappear, or is it gone? The document does not say. | §1; §2 Brain, Server core; §3 diagram (no read-back edge for tree state). | Make Neo4j the one source of truth for tree state, not just a log. On start, the server reads every open `Meeseeks` node and either adopts it (if its worker still answers) or marks it killed. Record this as an ADR: it is hard to reverse once code depends on in-memory tree state. |
| R7 | High | "All parts write to Neo4j. Each write has a `writerId`" (§2), but `writerId` is supplied by the writer itself, not checked by Neo4j or by any module in front of it. Any process that can reach Neo4j can claim to be any `writerId`. This is a shallow interface: it looks like an audit trail but enforces nothing. | §2 Neo4j; §6 "Every node and every link has a `writerId`." | Route every write through one module in the server core (single writer). The server sets `writerId` from its own spawn record, not from the caller's message. No other process, local or remote, connects to Neo4j directly. |
| R8 | High | The box is 83% full. The Podman vfs storage driver already uses 65 GB. vfs duplicates every image layer per container, so it uses more disk than other drivers. Meeseeks spawn with no cap on count (§2), and each is a fresh Podman sandbox (§2 Sandbox). Neo4j and its daily backup also need disk. Disk exhaustion is a likely failure mode here, not a remote one. | Given facts (83% full, vfs 65 GB); §2 Sandbox, Neo4j; grill round 3 "Podman prune... approved." | Check whether the box supports the overlay storage driver and switch to it (OPEN: confirm support first). Add a disk floor: refuse new spawns under a set free-space number. Remove each container's storage when its Meeseeks ends. Keep a fixed number of Neo4j backups, not all of them. |
| R9 | High | Remote worker transport, authentication, and how a secret (if any) reaches a remote worker are all OPEN (§2, §8), yet the kill switch must reach remote workers (R1) and the Neo4j writer trust model (R7) must also hold for them. Two high-severity findings depend on an undesigned path. | §2 "How the server reaches a remote worker is OPEN"; §8 "Logins: ... No copied session tokens." | OPEN. Do not decide the transport without research, per the existing open item. Recommended direction only, for the grilling round below: the server starts a connection to the worker (not the reverse), the worker uses its own local subscription login, and the worker never writes to Neo4j directly (it reports to the server, matching R7). |
| R10 | Medium | The server core is pinned to Kyo, described as "RC7" needing Scala 3.9 in the open-questions list (§9), with a "small internal layer" so "an API break changes one place only" (§2). That layer has no defined interface yet, and there is only one adapter behind it (Kyo itself). In the codebase-design vocabulary, one adapter is a hypothetical seam, not a proven one. No fact about Kyo's actual API is assumed here. | §2 Server core; §9 item 3. | OPEN on exact version and API facts. Before other work, build a minimal program on the pinned Kyo version and Scala 3.9, run it through the full crap4s CI set (format, lint, test, crap), and confirm the toolchain supports Scala 3.9 (OPEN, not assumed). Treat this as its own issue, separate from all other v1 work. |
| R11 | Medium | The frustration state diagram has self-loops with no exit other than a passed check or the kill switch: `L3 --> L3`, `L4 --> L4`, and `L5_Breakdown --> L5_Breakdown`, each on "split task, spawn children" (§5 diagram). Each child adds frustration to its parent (§2, §5 rules), so a parent that keeps failing can keep spawning, which keeps raising its own frustration. This is a feedback loop with no stated floor, and it feeds directly into R2 (usage) and R8 (disk). | §5 diagram and rules; §2 Meeseeks tree "Each child adds frustration to its parent." | Define a terminal action at L5 with no progress: report "blocked" to Tomasz and stop spawning children. The exact level at which splitting starts, and the level names, stay OPEN per §9 item 5, but the terminal action at L5 should not be OPEN once this is confirmed. |
| R12 | Medium | "A new Meeseeks reads the graph first, to reuse work that is already solved" (§2 Overseer), but no match key defines "already solved." Separately, the stall monitor "starts a new Meeseeks with clean context" when a task stalls (§2), with no statement that the old one is stopped first. Two active Meeseeks can work the same task node at once, each writing results and spawning children. | §2 Overseer, Stall monitor; Q4 in `DECISIONS.md`. | Give every task node one lease: one active worker at a time. A stall restart stops the old Meeseeks before it starts the new one. The exact match key for "reuse" (for example, a hash of goal plus check) stays OPEN; decide it before building reuse. |
| R13 | Medium | A Podman container stop does not prove that every process inside it has exited, and remote workers are not stated to run inside Podman at all: "Remote workers of any kind can run Meeseeks, on any machine that has free capacity" (§2) is separate from the Sandbox paragraph, which only says "Each Meeseeks runs in Podman" without naming remote workers. | §2 Remote workers; §2 Sandbox. | Label every local container with its Meeseeks ID and use `--rm`. Run a periodic sweep that removes containers with no matching live node in Neo4j. For remote workers, the cleanup method is OPEN until R9 is designed; it cannot be assumed to be the same as the local Podman case. |
| R14 | Medium | "Separate tokens for development and for inference" (§7) names the rule but not where each token lives, and the stated CI check only guards source-level imports: "A CI check makes sure that `runtime` never imports from `dev`" (§7). A build or packaging mistake could still place the inference token where a dev job can read it, without breaking any import rule. | §7. | Name the two token stores (OPEN: which vault or secret store) before code. Add a second CI check on the built artifact, not only on source imports: the `runtime` artifact's dependency closure must exclude every `dev` module. |
| R15 | Medium | No fake or in-memory adapter is defined for any external module: Brain, Sandbox (Podman), Chat channel, Neo4j, or remote Worker. Per the testability principle "accept dependencies, don't create them," a Meeseeks that constructs its own Podman or Claude Code connection cannot be tested without a live subscription and a live container runtime. The CI "test" job (§7) has no seam to run against yet. | §7 CI jobs; no testing section in the document. | Define one adapter interface per external module (Brain, Sandbox, Chat, GraphStore, Worker) with a small, in-memory fake for each. CI runs the `test` job against the fakes only. Real Claude Code, real Podman, and real Neo4j runs stay outside CI, on the box. |
| R16 | Medium | The daily Neo4j backup "stops the database for a short time at night" (§2). There is no cap on task duration (§2, §7 "no hard time cap" in `DECISIONS.md` Q7), so a task can still be running, and writing, when the backup window opens. What happens to that write is not stated. | §2 Neo4j; Q7 in `DECISIONS.md`. | Decide one of: the server queues writes during the backup window and flushes after (bounded by disk, see R8), or the server pauses active Meeseeks just before the window and resumes after. Either way, test one restore from a backup before v1, since Community has no hot backup. |
| R17 | Low | The Neo4j-to-Obsidian export direction is one-way in the diagram (`NEO -. export OPEN .-> VAULT`), and the location and ownership of the write are both OPEN (§2 Vault, §9 item 7). If Tomasz edits a note by hand in Obsidian, nothing states whether that edit is ever read back. | §2 Vault; §3 diagram; §9 item 7. | Treat this as a one-way, best-effort export, not a sync. Keep it out of the critical path of a task. Decide the vault location and writer when the R9-style research items are scheduled. |
| R18 | Low | The graph model (§6, PROPOSED) has no `Worker` node, so "how a worker reports its free capacity" (§2) has nowhere to be recorded. It also has no status or lease field on `Meeseeks` or `Task`, though R12 needs one to avoid duplicate work. | §6 Graph model; §2 Remote workers. | Add a `Worker` node (ID, location, free capacity) and a status field on `Task` and `Meeseeks` (for example: open, leased, done, killed) to the PROPOSED model before it is confirmed in Round 3, Q18. |
| R19 | Low | Several domain terms are used without a definition: "context slice," "droppable" / leaf shaking, "wider consult," and the five frustration level names. No `GLOSSARY.md` exists yet (none is expected; there is no repo yet). | §2, §5, §6 throughout `ARCHITECTURE.md`. | Create `GLOSSARY.md` once the repo exists, and define these terms at that point, per the domain-modeling skill. Do this before the terms turn into code identifiers with drifted meanings. |
| R20 | Low | `diagrams/component.png` renders as one very wide, thin row. Several labels (for example, the arrows into and out of the `Tree` subgraph) are hard to read at a normal zoom level. | Direct view of `diagrams/component.png`. | Split the component diagram into two: a control-path diagram (chat, gateway, overseer, kill switch, stall monitor) and a data-path diagram (Meeseeks tree, brain, Neo4j, vault). |

Count: High 9, Medium 7, Low 4. Total 20.

## Questions for Tomasz

Answer "yes" to accept the recommended answer. A "yes" to an OPEN item below only confirms the stated direction; it does not close the OPEN mark where a fact (Kyo API, T3 Code API, subscription terms, Podman driver support) is still unverified.

❓ **Q1 - Kill is a protocol, not a signal:** Should a kill require a confirmation from each worker, and should every worker stop itself if it loses contact with the server for N seconds, even without receiving the kill message?

➡️ Yes. Add an acceptance check: after a kill, 0 containers and 0 brain processes remain, local and remote (R1).

---

❓ **Q2 - Read the subscription terms first:** Should the subscription terms for Claude Code and Cursor be read and reported before v1 starts, given there is no cap and no rate limit on their use?

➡️ Yes (R2).

---

❓ **Q3 - Automatic pause, not a cap:** May the server pause new spawns by itself when the brain returns a usage-limit error, or when disk is low, with only Tomasz able to resume?

➡️ Yes. This is a safety fuse, not a contradiction of "no cap" (R2, R8).

---

❓ **Q4 - Stall monitor owner:** Should the overseer own stall detection, using a heartbeat `Event` from each Meeseeks, with "stalled" defined as no heartbeat for N minutes?

➡️ Yes (R3).

---

❓ **Q5 - One writer to T3 Code:** Should the Meeseeks tree stop writing to T3 Code directly, so that only the overseer (reading from Neo4j) feeds T3 Code?

➡️ Yes. This also fixes the disagreement between the component and sequence diagrams (R4).

---

❓ **Q6 - The check runs outside the brain's reach:** Should the server run the check from a read-only copy taken at task start, so the brain in the sandbox cannot edit the check itself?

➡️ Yes (R5).

---

❓ **Q7 - Neo4j is the source of truth for the tree:** On a server restart, should the server read every open `Meeseeks` node from Neo4j and either adopt it or mark it killed, instead of trusting in-memory state?

➡️ Yes. Worth recording as an ADR, since it is hard to reverse once code is built on the alternative (R6).

---

❓ **Q8 - Single writer to Neo4j:** Should only the server core write to Neo4j, setting `writerId` itself from its own spawn record, with every other process reporting to the server instead of writing directly?

➡️ Yes (R7).

---

❓ **Q9 - Disk guard before v1:** Should the server refuse new spawns under a set free-disk number, and should each Meeseeks's container storage be removed when it ends? Separately, should the overlay Podman storage driver be checked for support on the box, to replace vfs?

➡️ Yes to both. The overlay driver switch stays OPEN until support is confirmed (R8).

---

❓ **Q10 - Remote worker direction (research first):** Should the research on remote worker transport start from: the server opens the connection to the worker, the worker uses its own local subscription login, and the worker never writes to Neo4j directly?

➡️ Yes, as a starting direction for research, not as a decision (R9).

---

❓ **Q11 - Kyo spike before other work:** Should the first piece of work be a minimal program on the pinned Kyo version and Scala 3.9, run through the full CI set, before any other server code is written?

➡️ Yes. If it fails, the Kyo decision is reopened (R10).

---

❓ **Q12 - L5 has a floor:** At frustration level 5 with no progress, should the Meeseeks stop, report "blocked" to Tomasz, and spawn no further children?

➡️ Yes (R11).

---

❓ **Q13 - One lease per task:** Should each task node allow only one active Meeseeks at a time, with a stall restart stopping the old one before the new one starts?

➡️ Yes (R12).

---

❓ **Q14 - Test fakes for every external module:** Should an in-memory fake be defined for Brain, Sandbox, Chat, GraphStore, and Worker, with the CI `test` job running only against these fakes?

➡️ Yes (R15).

---

## Summary

The architecture is coherent on the parts that are decided: one task, one check, a frustration ladder, a tree of child Meeseeks, Neo4j as the runtime store, and a single kill switch. Most of the gaps found here are not new ideas but the edges of what is already marked OPEN in `ARCHITECTURE.md` §9, pulled into specific failure scenarios: who stops a Podman child when the kill is sent, what happens the moment the box runs out of disk, what stops the brain from editing its own check, and what happens to the live tree if the server core process dies. Four items stay genuinely OPEN pending outside facts: the Kyo RC7 toolchain, the T3 Code connection, the remote worker transport, and the subscription terms. None of those are invented here. The diagram disagreement on T3 Code's write path (R4) and the self-reported `writerId` in Neo4j (R7) are the two findings most worth fixing before any graph-model decision (Round 3, Q18) is confirmed, since both change the shape of that model.
