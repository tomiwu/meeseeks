# Research: How to validate Meeseeks results that have no strict test

Date: 2026-10-31. Status: research for the next grill round. No decisions are approved yet.

## 1. Self-consistency and majority vote
- Self-consistency: sample several answers to the same task, then take the most frequent final answer. It gave large gains on math and reasoning tasks. Source: Wang et al. 2022, https://arxiv.org/abs/2203.11171
- Limit 1: a vote needs answers that you can compare exactly (a number, a choice). Free text research reports cannot be counted directly. "Universal Self-Consistency" uses an LLM to select the most consistent free-form answer. Source: Chen et al. 2023, https://arxiv.org/abs/2311.17311
- Limit 2: more votes do not always help. On hard questions, accuracy can go down when you add more calls. Source: Chen et al. 2024, "Are More LLM Calls All You Need?", https://arxiv.org/abs/2403.02419
- Limit 3: copies of the same model with the same prompt share the same errors. Agreement is then not proof of truth.

## 2. LLM-as-judge and its biases
- Known biases: position bias (prefers the first or second answer), verbosity bias (prefers long answers), self-enhancement bias (prefers its own style). Source: Zheng et al. 2023, MT-Bench, https://arxiv.org/abs/2306.05685
- LLM judges can recognize their own outputs and give them higher scores. Source: Panickssery et al. 2024, https://arxiv.org/abs/2404.13076
- How to reduce bias: hide which Meeseeks wrote each answer; shuffle the order, or judge twice with swapped order and accept only a consistent result (Zheng et al.); score against a fixed rubric; use a judge from a different model family; use a panel of small judges from different families ("jury"), which had less intra-model bias than one large judge. Source: Verga et al. 2024, https://arxiv.org/abs/2404.18796
- Self-review without outside feedback is weak: models often cannot correct their own reasoning. Source: Huang et al. 2023, https://arxiv.org/abs/2310.01798

## 3. How many copies are enough
- Wang et al. 2022: most of the gain comes in the first 5 to 10 samples; after that the curve is flat (figures in the paper).
- Repeated sampling raises the chance that at least one answer is correct ("coverage"), but only if a verifier can find it. Source: Brown et al. 2024, "Large Language Monkeys", https://arxiv.org/abs/2407.21787
- Practical point: 3 copies give a majority only when answers agree; with 3 free-text answers, a tie (1-1-1) is common. Published numbers are for short answers, not research reports. No source gives a number for open research. This is OPEN.

## 4. When answers disagree
- Multi-agent debate: agents read each other's answers and revise; this improved factual accuracy in tests. Source: Du et al. 2023, https://arxiv.org/abs/2305.14325. Risk: agents can converge on a shared wrong answer, so debate does not replace source checks.
- Recommended steps (design, not from one source): (a) split answers into claims; (b) claims that all copies agree on and that have verified sources are accepted; (c) for disputed claims, run a targeted check (one new Meeseeks, a different brain, only that claim, must return sources); (d) if still not resolved, report "no consensus" to the human with both positions and their sources.

## 5. Source-grounded checks
- Split a long answer into atomic facts and check each fact against a source. Source: FActScore, Min et al. 2023, https://arxiv.org/abs/2305.14251
- SAFE: an LLM splits the answer into facts and uses web search to check each one; it agreed with human raters in 72% of cases and was much cheaper. Source: Wei et al. 2024, https://arxiv.org/abs/2403.18802
- For Meeseeks: every claim must have a source URL. A separate verifier (not the writer) fetches each URL and checks that the page supports the claim. A claim with a dead or non-supporting URL fails. This is the nearest thing to a strict check for research.

## 6. Independence between copies
- Errors are only independent if the copies are different. Use: different prompts (wording, order of sub-questions), different brains (for example Claude Code and another CLI), different search start points, no shared context, no shared scratch files, and no view of other copies' answers until judging.
- The judge must not see writer IDs or brain names (blind judging, section 2).

## 7. Rubric defined with the goal
- The task input must contain: goal, check type (build or research), and an acceptance checklist. For research, example items: answers each sub-question; every claim has a URL; at least N independent sources; states what is unknown.
- Rubric-based scoring reduces judge bias compared to open "which is better" questions (Zheng et al. 2023; Verga et al. 2024).

## 8. Validation between loops for build tasks
- Generated tests can rank generated code: CodeT runs code samples against tests written separately and picks code by agreement. Source: Chen et al. 2022, https://arxiv.org/abs/2207.10397
- Mutation testing measures if tests catch real faults: make small changes to the code; good tests fail. Google uses it in code review. Source: Petrovic and Ivankovic 2018, "State of Mutation Testing at Google", https://research.google/pubs/state-of-mutation-testing-at-google/
- Overfitting risk: models can make tests pass by special-casing or by changing tests (reward hacking). Source: OpenAI 2025, "Monitoring reasoning models for misbehavior", https://openai.com/index/chain-of-thought-monitoring/
- Controls: tests written by a separate test Meeseeks from the goal and spec, not from the code; a hidden part of the test set that the code writer never sees (it gets only pass/fail counts); the server runs all tests in a fresh read-only container (Q27); a change to test files is a fail; mutation score as a quality gate for the test set.

## Recommended design (each answerable by "yes")
1. Every task names a check type: `build` or `research`. A task without a check type and acceptance checklist is refused.
2. Research tasks spawn 3 copies by default (a setting). Copies use different prompts and, when available, different brains, with no shared context.
3. Every research claim has a source URL. A separate verifier Meeseeks fetches each URL and marks the claim supported, not supported, or unreachable.
4. The judge is blind (no writer ID, no brain name), sees answers in shuffled order, scores against the rubric, and is from a different model family than the writers when possible.
5. The result is built claim by claim: claims that are agreed and verified are accepted; disputed claims get one targeted check by a new Meeseeks; if still disputed, the report shows "no consensus" with both positions and their sources.
6. The final research report to the human marks each claim: verified, agreed only, or disputed.
7. For build tasks, a separate test Meeseeks writes tests from the goal and spec only. The code writer never edits tests.
8. A hidden part of the tests is never shown to the code writer; it gets only pass/fail counts.
9. The server runs all tests between loops and at the end in a fresh read-only container. A change to test files counts as a fail.
10. Mutation testing checks the test set before the tests are used as the build check.
11. Self-review by the same Meeseeks never counts as a check.

## OPEN
- Number of copies for research: 3 has no published basis for open research; it may be too few for a majority. Test with real tasks.
- Which second brain is available on Tomasz's subscriptions for "different model family" (judge and copies).
- How to split a research report into claims in a reliable way (claim extraction quality).
- Mutation testing tool for Scala 3.9 + Kyo (for example Stryker4s): compatibility is unknown.
- Cost: verifier and judge calls per claim can be large; need a budget fuse (link to Q25 circuit breaker).
- Whether to allow debate between copies, and its risk of shared wrong answers.
- Subscription terms for automated use (Q36).

## Existing projects (checked 2026-10-31; nothing installed)
"Fit for Scala 3" = can we copy the idea into the Meeseeks server. None of these is a Scala library; all are ideas only.

| Project | What it does | License | Fit for Scala 3 server |
|---|---|---|---|
| karpathy/llm-council, https://github.com/karpathy/llm-council | Same question to several models; each ranks the others' anonymized answers ("Response A/B/C"); a chairman model writes the final answer. Python + OpenRouter API keys. | No LICENSE file found in repo root | Idea fits: 3 stages map to copies, blind judge, synthesis. Code does not (Python, API keys). |
| amiable-dev/llm-council, https://github.com/amiable-dev/llm-council | Extends the council: optional style normalization to hide model identity, randomized labels, self-votes excluded, consensus or debate mode. Python lib / MCP / HTTP. | MIT | Good ideas: style normalization and "exclude self-vote" against self-preference bias. |
| llm-council/llm-council (Language Model Council research code), https://github.com/llm-council/llm-council | Research library: a council of LLMs answers and judges each other; leaderboard. | No LICENSE file found by quick check | Research reference only. |
| kratos619/Aiki, https://github.com/kratos619/Aiki | Runs logged-in CLIs (Claude Code, Codex, Gemini) as child processes, read-only; blind independent answers, cross-exam, judge rules only on disputes; every finding must point to a real file:line. | MIT | Very close to Meeseeks: uses subscription CLIs, no API keys; "judge only disputes" and "deterministic citation check" fit. |
| serdardb/multi-agent, https://github.com/serdardb/multi-agent | Runs the same agent on other model CLIs; rounds of cross-critique until positions stop changing; judge verifies claims against real code. | MIT | Convergence-gated rounds are a good stop rule for disagreement handling. |
| wan-huiyan/agent-review-panel, https://github.com/wan-huiyan/agent-review-panel | Claude Code plugin: 4-6 reviewers with different stances, debate, claim verification, supreme judge, post-judge verification. States openly that same-model reviewers are self-critique, not independent proof. | MIT | Good checklist of verification gates; warning on same-model bias confirms section 6. |
| AltimateAI/claude-consensus, https://github.com/altimateai/claude-consensus | Several models review independently, synthesis of conflicts, APPROVE / CHANGES NEEDED, max 2 rounds, quorum. | MIT (stated in README) | Quorum and max-2-rounds rule fit. |
| d1maash/agentmx, https://github.com/d1maash/agentmx | TUI for many coding CLIs: parallel compare, `vote` with judge, "verified solve" with tests, hard cost stop. | MIT | Shows vote + test-proved patches + budget kill together. |
| soonsoon2/lun, https://github.com/soonsoon2/lun | CLI: same question to several logged-in agent CLIs; compare, consensus summary, or relay discussion. | MIT (README) | Simple reference. |
| BayramAnnakov/agent-tower-plugin, https://github.com/BayramAnnakov/agent-tower-plugin | Claude Code plugin: council with anonymous ranking, debate with judge, producer/reviewer loop with consensus threshold. | Not found by quick check | Idea only. |

### The YouTuber
- A match by topic and the Nordic hint: PewDiePie (Felix Kjellberg, Swedish, speaks English) showed a self-hosted chat UI with a "council" of local models that debate and vote on the best answer, in his video "STOP. Using AI Right now" (about 31 Oct 2025). He also tried a 64-bot swarm and said the bots began to "collude". Sources: https://www.dexerto.com/youtube/pewdiepie-builds-his-own-ai-chat-ui-with-a-council-of-bots-that-vote-on-answers-3278189/ and https://www.uniladtech.com/social-media/pewdiepie-own-ai-platform-after-warning-subscribers-405189-20251104
- I did not find a public repo for his setup. Tomasz must confirm that this is the person he means.
- X search (built-in public search) gave no relevant extra posts.
- Lesson for Meeseeks: his "collusion" story matches the known risk that copies of the same model agree on a wrong answer (sections 1, 4, 6).
