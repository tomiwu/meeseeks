# Research: T3 Code Connection for Overview and Sessions

Date: 2026-11-05. Status: draft.
Issue: #18.

## Purpose

This document researches how T3 Code can show the Meeseeks tree and sessions.
The goal is to satisfy Q14 (T3 Code for overview) and Q23 (T3 Code connection researched first).
The document names the connection method and a test method with the GitHub CLI (`gh`).

---

## 1. What T3 Code is

T3 Code (github.com/pingdotgg/t3code, MIT) is an open-source control plane for coding agents.
It is not an AI model. It wraps existing agent CLIs and shows their sessions in a single dashboard.

**Supported agents (as of 2026):** Claude Code, Codex, Cursor, Grok Build, OpenCode, Google Antigravity, Kiro.

**Architecture:**

| Layer | Description |
|---|---|
| Server | Local Node.js process (`npx t3 serve`) |
| Transport | Effect RPC over WebSocket at `/ws` |
| Claude adapter | `@anthropic-ai/claude-agent-sdk`; sets `CLAUDE_CODE_ENTRYPOINT=t3-code` |
| Clients | Web browser, desktop app (macOS, Windows, Linux), mobile (iOS, Android) |

T3 Code runs the Claude Code CLI you install and authenticate. It never performs inference itself.
Authentication: `claude auth login` on the machine running T3 Code.

---

## 2. Connection method

**Method: T3 Code thread per Meeseeks brain session.**

Each Meeseeks brain backend that uses Claude Code runs as one T3 Code thread.
The Meeseeks worker starts T3 Code on the host machine and creates one thread per Meeseeks actor.
T3 Code's thread list is the session overview Tomasz watches.

Steps:

1. The worker starts the T3 Code server: `npx t3 serve`.
2. When a Meeseeks actor starts a brain iteration, the worker opens a new thread via the T3 Code
   WebSocket API using `@anthropic-ai/claude-agent-sdk`.
3. The thread name encodes the Meeseeks ID and task (for example `task-42/root` or `task-42/child-3`).
   This name is visible in the T3 Code thread list and identifies the actor in the tree.
4. T3 Code streams the live output of the Claude Code subprocess to any connected client.
5. When a Meeseeks actor stops, the worker closes the thread. The thread remains in the T3 Code
   history with its final state.

**Meeseeks tree in T3 Code:**

T3 Code does not have a native hierarchical tree view. The tree is represented through thread naming:

- Root Meeseeks: `task-{id}/root`
- First child: `task-{id}/child-1`
- Second child: `task-{id}/child-2`

Tomasz sorts or filters threads by prefix to see the full tree for a task.
The overseer frustration level and stuck state can be appended to the thread name or printed as the
first line of the session output.

**What T3 Code shows out of the box:**

| View | Content |
|---|---|
| Thread list | All running and recent Meeseeks brain sessions, one row each |
| Thread detail | Live terminal output of the Claude Code subprocess |
| Source control | Branch name, uncommitted changes, diff viewer |
| PR button | One-click commit, push, and PR creation via `gh` |

**What T3 Code does not show:**

- The actor hierarchy as a visual tree. That requires a custom view or a separate overseer dashboard.
- Permit state, circuit breaker state, or frustration level (unless printed to the session output).

**Constraint: Podman sandbox and T3 Code:**

The architecture runs the brain inside a Podman container (Q8, t77u). T3 Code runs on the host.
To let T3 Code track a Claude Code process inside a container, the worker can use one of two approaches:

| Approach | Description |
|---|---|
| A. Host-side brain (preferred for overview) | The brain runs on the host as a T3 Code thread. The Podman container holds only the task files. The worker mounts the task directory into the container for file access. This matches the "locally first" requirement. |
| B. Socket passthrough | The worker passes the T3 Code WebSocket address into the container as an environment variable. The Claude Code process inside the container connects to T3 Code on the host. This is more complex and is not documented by T3 Code. |

**Approach A is recommended for v1** because it is simpler, requires no container networking changes, and satisfies the "locally first" requirement. The sandbox restriction (only the task directory mounted, Q8) is preserved: the container still holds the task files.

---

## 3. Test method with `gh`

T3 Code uses the GitHub CLI (`gh`) for all PR creation. The test below verifies the connection end to end.

**Prerequisites:**

1. Install T3 Code: `npm install -g t3` or `npx t3 serve`.
2. Install the GitHub CLI: `gh` available on PATH.
3. Log in to Claude Code: `claude auth login`.
4. Log in to GitHub: `gh auth login`.
5. In T3 Code: open Settings → Source Control and confirm GitHub shows as authenticated.

**Test steps:**

1. Start T3 Code locally: `npx t3 serve`. Open `http://localhost:4000` in a browser.
2. Create a new thread for a test repository. T3 Code starts a Claude Code session.
3. Ask Claude Code (through the T3 Code interface) to make a small change to a file in the repo.
4. Click "Commit & Push" in T3 Code. T3 Code commits the change to a new branch and pushes it.
5. Click "Create PR" in T3 Code. T3 Code calls `gh pr create` with the branch and a generated title.
6. Verify the PR was created:

   ```
   gh pr list --repo <owner>/<repo>
   ```

7. Inspect the PR:

   ```
   gh pr view <number> --repo <owner>/<repo>
   ```

8. Close the test PR:

   ```
   gh pr close <number> --repo <owner>/<repo>
   ```

**What this test proves:**

- T3 Code can track a Claude Code session locally.
- The `gh` CLI integration works end to end.
- A branch-per-session workflow operates correctly.
- The PR button uses `gh` and not a browser OAuth flow.

**Meeseeks-specific test:**

To simulate a Meeseeks session in T3 Code without the full Pekko server:

1. Start T3 Code locally.
2. Open a new thread manually in T3 Code and set the name to `task-test/root`.
3. Send a task goal and check command as the first message in the thread.
4. Verify the thread name appears in the T3 Code thread list.
5. After the session makes a change, use `gh pr list` to confirm the PR is on the correct branch.

This test requires only T3 Code, `gh`, and a Claude Code login. It does not require the Pekko server.

---

## 4. Gaps

| Gap | Detail |
|---|---|
| No native tree view | T3 Code shows a flat thread list. A tree view requires a custom UI or a naming convention. |
| No overseer state | Permit state, breaker state, and frustration level are not visible unless streamed to the session output. |
| Container constraint | Approach A (host-side brain) changes where the brain runs relative to Q8. Decide before implementation. |
| Session naming API | T3 Code does not yet have a stable API to set a thread name from an external caller. Issue #29698 is open. The name must be set at thread creation time. |
| Auth on the worker | Each worker machine that runs T3 Code must have `claude auth login` and `gh auth login` done on that machine. Remote workers (Q32) require this setup per machine. |

---

## 5. Recommendation

**Use approach A (host-side brain, Podman for files) in v1.**

The connection method is: one T3 Code thread per Meeseeks brain session, started by the worker via the
`@anthropic-ai/claude-agent-sdk` and tracked through the T3 Code WebSocket API.
The thread name encodes the Meeseeks actor path. Tomasz filters by task prefix to see the tree.

Defer the native tree view to a later milestone (M4 in PRD §14). For v1, the flat list with named
threads is sufficient to satisfy user story 6 (see the Meeseeks tree) and the "overview" goal.

**Update for ARCHITECTURE.md:** Change "Connection is OPEN (research first)" to "Connection method:
one T3 Code thread per Meeseeks brain session, via `@anthropic-ai/claude-agent-sdk`; tree via naming
convention; tested with `gh`."

---

## 6. Sources

- T3 Code repository: https://github.com/pingdotgg/t3code
- T3 Code source control docs (mirror): https://ithub.global.ssl.fastly.net/Type-Delta/t3code/blob/main/docs/user/source-control.md
- T3 Code git workflow docs (Mintlify mirror): https://www.mintlify.com/pingdotgg/t3code/guides/git-workflow
- T3 Code AGENTS.md: https://github.com/pingdotgg/t3code/blob/main/AGENTS.md
- T3 Code overview (BetterStack): https://betterstack.com/community/guides/ai/t3-code/
- T3 Code review (2026): https://agents.dancingteeth.net/tools/t3-code
- T3 Code architecture deep dive: https://flaviocopes.com/t3-code/
- T3 Code issue #29698 (session naming): https://github.com/pingdotgg/t3code/issues/29698
- T3 Code issue #2863 (bidirectional Claude session): https://github.com/pingdotgg/t3code/issues/2863
- T3 Code issue #15234 (import terminal sessions): https://github.com/pingdotgg/t3code/issues/15234
- T3 Code release notes: https://github.com/pingdotgg/t3code/releases
- GitHub CLI docs: https://cli.github.com/manual/
