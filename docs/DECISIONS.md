# Meeseeks: Grill Round 1 record (2026-10-31)

Status: decisions below were reported from a voice call. Tomasz must confirm them.
Open items are marked OPEN. Nothing is invented.

## Decisions
- Q1 Product form: a long-running server service. Users reach it through the Grok bot, Telegram and other chat channels. It is not a CLI tool.
- Q2 Language: Scala 3 with the crap4s defaults.
- Q3 Brain: pluggable. Claude Code or a Cursor agent, in the way T3 Code does it.
- Q4 "Done": the task must have a concrete goal that can be checked, set at the start of the conversation. The check is flexible: for example `sbt test`, or a browser test that a deployed page is reachable. A monitor starts a fresh Meeseeks with clean context when a task stalls or is blocked.
- Q5 Frustration: five levels, from calm to breakdown. It grows from time and failed checks. It never skips the check. Higher frustration splits the task into parts for child Meeseeks.
- Q6 Helpers (merged into Q5): child Meeseeks are separate processes. Depth 3, 10 in total. The parent merges the results. A CLI flag changes the limits. Each helper adds frustration to the parent.
- Q7 Limits: no hard time cap. One overseer (role: Spartan ephor) holds the full map of tasks, branches and results. A new Meeseeks checks the overseer first, to reuse solved work.
- Q8 Container: Podman. Only the task directory is mounted. The Claude token is the only secret.
- Q9 Name and license: `tomiwu/meeseeks`, public, MIT license. Fan project. Only short, original homage lines. The name is held loosely and can change if it causes a problem.

## OPEN items
- RESOLVED (reported, 2026-10-31): see "Knowledge storage" below. Place for goals, research and knowledge is no longer open.
- OPEN: cost cap number.
- OPEN: helper cap numbers (depth 3 and total 10 are the defaults from Q6; confirm).

## Notes from the assistant (not decisions)
- Q1 now differs from the first recommendation (CLI tool). A service needs a chat gateway, a job store and a stop control for runaway helpers. Round 2 must cover these.
- Q3 mentions a Cursor agent. Tomasz's standing rule says no Cursor cloud agents. Confirm that "Cursor agent" means the local Cursor CLI only.
- Kyo was reported as "use from version 1". My advice was to avoid it (RC7, Scala 3.9 only).

## Knowledge storage (added 2026-10-31, reported from voice call)
- Knowledge storage is separate from the implementation of the bot.
- For the future usage phase of the bot, knowledge lives in Obsidian. It is a graph of plain markdown notes with links. It is not a tree.
- No RAG system in version 1.
- Obsidian is chosen over Notion. Reasons: fewer dependencies and no cloud lock-in. Notion can be reviewed again later if a hosted review UI is needed.
- Tomasz can review the graph later in the graph view of Obsidian.
- Still open: the exact folder or vault location, and who writes to it (the overseer or each Meeseeks).

## Additions from 2026-10-31 (reported from voice call, to confirm)
- Vault location is still OPEN. Tomasz wants it outside any repo. He mentioned a dedicated graph database or knowledge graph, and maybe reusing an existing one. No system is chosen.
- New idea: each task folder gets a structured context file, linked to the knowledge graph, with low token use. He floated an Akka actor-model approach to store and share context between agents, but he is not sure.
- Standing rule: development and inference are two separate operations and problems. Keep them separate in all future work.
- Later idea: maybe reuse Meeseeks to rewrite things.
- OPEN: cost cap number, helper cap numbers, vault location, Akka or another context approach.

## Final additions, 2026-10-31 (reported from voice call, to confirm)
- Knowledge: Obsidian graph of markdown notes is for review. The runtime store is Neo4j Community (open source, free for one box). Paid tiers exist for clustering and hot backups. Tomasz can switch later.
- No cost cap for now. Subscription model with tokens. An overseer can be added later if needed.
- No helper caps. Meeseeks spawn freely. Depth and count are unlimited until the overseer steps in. This replaces the Q6 limits (depth 3, 10 in total).
- Everyone writes to Neo4j. Each write is tagged with the writer ID (Meeseeks ID or overseer ID).
- Development and inference are two separate operations and must stay separated.
- Maybe later Meeseeks can be reused to rewrite things.
- Context: structured context linked to the knowledge graph, with low token use. Akka is avoided (paid license). Apache Pekko Typed or similar is considered. The folder-file approach is rejected. A real graph store is used, with references, not copies.
- Vault location: external, not inside a repo.

## Round 2 answers (Tomasz, chat t31u, 2026-10-31)
- Q10: yes to kill switch (stops everything). No rate limit now.
- Q11: cloud agents included; work spreads over agents on any machine. CONFLICT with standing rule "no Cursor cloud agents": asked Tomasz.
- Q12: Kyo from v1, at all costs.
- Q13: Neo4j in Podman on the box, daily backup.
- Q14: dedicated Grok bot first, Telegram fallback; T3 Code for overview and sessions; local first; must be testable with GitHub CLI.
- Q16: no knowledge in the repo; repo holds code only. Knowledge in Neo4j and external vault.
- Q17: yes, create repo tomiwu/meeseeks. Ask before any install.
- Open: Q15 where service runs; cloud agent meaning; Kyo pinning.

## Round 3 (Tomasz, chat t36u, 2026-10-31)
- Q18-Q21, Q23 approved as proposed (graph model Task/Meeseeks/Result/Fact/Source/Event; overseer watch-only; chat task input goal+check; one result message per task; T3 Code connection researched first).
- Q22 changed: everything (children, remote workers, overseer) in v1.
- Podman prune of untagged unused images approved; keep sandcastle images and node image.

## Review grill (Tomasz, 2026-10-31)
- Q24 (kill): permit design. Each Meeseeks asks the server for a permit before every loop iteration. Kill = server gives no more permits and tells workers to stop. Worker stops itself after 60 s without server contact. Workers confirm the kill. Acceptance test: 0 containers, 0 brain processes, no new iteration after a kill. Permits are automatic (to confirm with Tomasz).
- FUTURE (Tomasz, t41u): the permit gate can later become token-based rate limiting. Not in v1. Write down only.
- Q24 approved by Tomasz (t42u): automatic permits confirmed.
- Q25 approved (t43u): circuit breaker; pause permits on usage-limit error or free disk <10 GB; only Tomasz resumes.
- Q26 (disk): Tomasz says it is not part of this job (t44u), but no Meeseeks code work starts until disk issue is resolved. Blocker. Do not touch disk without his yes.
- Idea (t45u): Meeseeks types. Build = strict external check defined with the goal, run by server, also between loops. Research = no strict check; spawn N identical Meeseeks (e.g. 3), a judge compares and picks. OPEN research: how to compare answers, how many copies, judge on disagreement. Goal must name its check type. Q27 not yet approved.
- Q27 approved for build tasks (t46u): fresh read-only container for checks. New rule: tests are written by a different Meeseeks than the code; code writer cannot change tests. Research on validation of research-type results started before next grill round: /workspace/meeseeks-notes/RESEARCH-validation.md
- Idea (t50u): oracle first pass with a small classifier model (class output: pass/fail/unsure), expensive judge only for unsure. "JEW" unclear (maybe JEPA?), asked Tomasz. Q28 (single writer + token identity, A2A research first) asked, not yet answered.
- Jev (t51u): TypeSafe AI discriminative model, POST https://api.typesafe.ai/v1/systemone, Choice/Score/Noul, $0.042/Mtok input, output free, no free credit for new accounts (docs checked 2026-10-31). Needs API key + per-token billing: conflicts with "no API keys" rule. Proposed Q37: optional oracle first pass, budgeted, off by default. Not yet answered.
- UPDATE (t52u): API keys allowed. Support all approaches: subscription login, API key, local model; pluggable backends; keys only in masked fields. Replaces "no API keys". Q37 approved: Jev optional oracle (assumed off by default).
- Q28 approved (t53u): only server writes to Neo4j and sets writerId; short-lived per-process tokens; own protocol close to A2A in v1; A2A research first.
- Idea (t54u): revisit Pekko (Typed, Persistence, Cluster) for actor state and node communication. Conflicts: Kyo must-have, Neo4j as read model, weight. Proposed Q38: research first; Q29 waits.
- Q39 approved (t56u): Pekko (state, cluster) + Kyo (work inside steps); first issue = test project (Scala 3.9, Kyo RC7, Pekko 1.7.1, H2, 2-node cluster, all CI jobs); fallback Kyo only. Q29 reworded: Pekko journal holds tree state; Neo4j read model. Q24/Q32 may change to Pekko Cluster failure detector. See RESEARCH-pekko-kyo.md.
- Q40 approved (t59u): Neo4j dropped from v1; Pekko journal is the only store; graph read model optional later. Settles Q29; replaces Q13 and Neo4j parts of Q28. Obsidian notes can be exported from journal events.
- Q30 approved (t60u): overseer owns stall detection via heartbeats; restart kills old Meeseeks first.
- Q31 revised (t61u): at L5 Meeseeks reports stuck to overseer but continues; overseer asks for problem details, only upstream (overseer/parent/Tomasz) stops it; oracle (Jev if on, else blind LLM judge) may let it stop itself on high-confidence stop. Awaiting yes.
- Q31 approved (t62u). Q32 proposed: Pekko Cluster with per-worker certificates replaces SSH + 60 s lease, subject to test project; fallback SSH.
- Q32 approved (t63u): all cluster based (Pekko Cluster, per-worker certificate), NO SSH, no SSH fallback. Q33 proposed: test project first.
- Q33 approved as plan (t64u). Build tool change: sbt 2.0 instead of Scala CLI (standing rule override for this project). Q41 proposed; need to verify sbt 2.0 status, Scala 3.9 support, crap4s on sbt.
- Versions checked (t66u): Scala 3.10.0 (2026-10-12, Scala Next, removes legacy syntax), 3.9.0 LTS (2026-09-03), 3.3.9 LTS; sbt 2.0.0 stable 2026-06-14 (summary lists 2.0.11; confirm). Q41 proposed: newest stable default, test both 3.10.0 and 3.9.0, fall back to 3.9.0 if Kyo/Pekko fail. sbt 2.0 approved by Tomasz (t66u "agree with rest").
- Q41 approved (t67u): always try newest versions first; report exact failure and decide; 3.9.0 LTS fallback. Q34 proposed: fakes only in CI.
- Q34 approved (t68u): CI uses fakes only. Q35 proposed: local kill command.
- Q35 dropped (t69u): no local kill command. Q36 proposed: read subscription terms for automated use.
- Q36 closed (t70u): Tomasz says assume subscription backends are allowed for automated use. No terms research. Recorded as his assumption/risk.
- Q26 closed (t73u): disk resolved; Podman uses overlay; 46% used, 70 GB free (2026-10-31 15:15). Open: F3 research copies, F4 cloud agents, F9 brain-in-container design.
- t77u (go): F3 research copies = independent (different prompts, different brains when possible); F4 Cursor cloud agents excluded; F9 brain runs inside container, worker injects only that backend credential. Next: PRD, repo tomiwu/meeseeks, Matt Pocock issues skill, GitHub issues.
