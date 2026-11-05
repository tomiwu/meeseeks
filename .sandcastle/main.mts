// Meeseeks Sandcastle loop. Host-side orchestrator; agents run in Podman.
// Start: npm run sandcastle   (needs .sandcastle/.env, see .env.example)
import { run, claudeCode } from "@ai-hero/sandcastle";
import { podman } from "@ai-hero/sandcastle/sandboxes/podman";
import { execFileSync } from "node:child_process";

const REPO = "tomiwu/meeseeks";
const MODEL = "claude-sonnet-4-6"; // latest Sonnet known to Sandcastle 0.12.0
const MAX_FIX_TRIES = 3;
const MAX_ISSUES = Number(process.env.SANDCASTLE_MAX_ISSUES ?? "25");
const PRIO: Record<string, number> = { "priority: critical": 0, "priority: high": 1, "priority: medium": 2, "priority: low": 3 };

const gh = (...a: string[]) => execFileSync("gh", a, { encoding: "utf8" }).trim();
const git = (...a: string[]) => execFileSync("git", a, { encoding: "utf8" }).trim();
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));
const REQUIRED = ["format", "lint", "test (3.10.0)", "test (3.9.0)", "crap", "import-check"];

const finished = new Set<number>(); // issues merged in this run; never pick them again

type Issue = { number: number; title: string; body: string; labels: { name: string }[] };

function pickIssue(): Issue | undefined {
  const open: Issue[] = JSON.parse(gh("issue", "list", "-R", REPO, "--state", "open", "--label", "Sandcastle", "--limit", "200", "--json", "number,title,body,labels"));
  const openNums = new Set(open.map((i) => i.number));
  open.splice(0, open.length, ...open.filter((i) => !finished.has(i.number)));
  const ready = open.filter((i) => ![...(i.body ?? "").matchAll(/Blocked by #(\d+)/gi)].some((m) => openNums.has(Number(m[1]))));
  const prio = (i: Issue) => Math.min(...i.labels.map((l) => PRIO[l.name] ?? 9));
  return ready.sort((a, b) => prio(a) - prio(b) || a.number - b.number)[0];
}

function openPr(branch: string): number | undefined {
  const prs = JSON.parse(gh("pr", "list", "-R", REPO, "--head", branch, "--state", "open", "--json", "number"));
  return prs[0]?.number;
}

async function waitChecks(pr: number): Promise<"pass" | "fail"> {
  await sleep(20_000);
  for (;;) {
    const checks: { bucket: string }[] = JSON.parse(execFileSync("gh", ["pr", "checks", String(pr), "-R", REPO, "--json", "bucket"], { encoding: "utf8" }) || "[]");
    if (checks.length && checks.every((c) => c.bucket === "pass" || c.bucket === "skipping")) return "pass";
    if (checks.some((c) => c.bucket === "fail" || c.bucket === "cancel")) return "fail";
    await sleep(30_000);
  }
}

function failedLog(branch: string): string {
  const runId = gh("run", "list", "-R", REPO, "--branch", branch, "--status", "failure", "--limit", "1", "--json", "databaseId", "--jq", ".[0].databaseId");
  if (!runId) return "No failed run log found.";
  try { return gh("run", "view", runId, "-R", REPO, "--log-failed").slice(-20_000); } catch { return "Could not read the failed log."; }
}

// GitHub closes the issue a few seconds after the merge. Close it ourselves and wait,
// so the next pick can never see the finished issue as open (cause of duplicate PRs).
async function closeIssue(n: number) {
  for (let i = 0; i < 12; i++) {
    const state = gh("issue", "view", String(n), "-R", REPO, "--json", "state", "--jq", ".state");
    if (state === "CLOSED") return;
    if (i === 1) { try { gh("issue", "close", String(n), "-R", REPO, "--reason", "completed"); } catch { /* retry on next loop */ } }
    await sleep(5_000);
  }
}

// Rebase the branch on origin/main before we create or resume its PR.
// On a conflict, abort the rebase and stop the loop with BLOCKER.
function rebaseOnMain(branch: string, issue: number, remote: boolean) {
  git("fetch", "origin");
  const start = remote ? `origin/${branch}` : branch;
  const old = git("rev-parse", start);
  const dir = `/tmp/sandcastle-rebase-${issue}`;
  try { git("worktree", "remove", "--force", dir); } catch { /* no old worktree */ }
  git("worktree", "add", "--detach", dir, start); // temp worktree, so the branch can stay checked out elsewhere
  const wt = (...a: string[]) => execFileSync("git", ["-C", dir, ...a], { encoding: "utf8" }).trim();
  try {
    wt("rebase", "origin/main");
  } catch {
    try { wt("rebase", "--abort"); } catch { /* no rebase in progress */ }
    git("worktree", "remove", "--force", dir);
    console.error(`BLOCKER: issue #${issue}: rebase of ${branch} on origin/main has conflicts. Loop stops.`);
    process.exit(2);
  }
  const head = wt("rev-parse", "HEAD");
  git("worktree", "remove", "--force", dir);
  if (remote) git("push", `--force-with-lease=${branch}:${old}`, "origin", `${head}:refs/heads/${branch}`);
  else git("push", "origin", `${head}:refs/heads/${branch}`);
  git("update-ref", `refs/heads/${branch}`, head);
  try { git("branch", "--set-upstream-to", `origin/${branch}`, branch); } catch { /* fetch sets it later */ }
  git("fetch", "origin");
}

// Wait until all required checks pass on the PR head. "fail" if one fails.
async function waitRequired(pr: number): Promise<"pass" | "fail"> {
  await sleep(20_000);
  for (;;) {
    const checks: { name: string; bucket: string }[] = JSON.parse(execFileSync("gh", ["pr", "checks", String(pr), "-R", REPO, "--json", "name,bucket"], { encoding: "utf8" }) || "[]");
    const req = REQUIRED.map((n) => checks.find((c) => c.name === n));
    if (req.some((c) => c && (c.bucket === "fail" || c.bucket === "cancel"))) return "fail";
    if (req.every((c) => c && c.bucket === "pass")) return "pass";
    await sleep(30_000);
  }
}

// Strict branch protection needs an up-to-date branch. Update it if it is behind, wait for checks, then merge.
async function mergeWhenCurrent(pr: number): Promise<"merged" | "fail"> {
  for (let i = 0; i < 5; i++) {
    const state = gh("pr", "view", String(pr), "-R", REPO, "--json", "mergeStateStatus", "--jq", ".mergeStateStatus");
    if (state === "BEHIND") {
      gh("pr", "update-branch", String(pr), "-R", REPO);
      if ((await waitRequired(pr)) !== "pass") return "fail";
      continue;
    }
    try { gh("pr", "merge", String(pr), "-R", REPO, "--squash", "--delete-branch"); return "merged"; }
    catch { await sleep(15_000); } // state can lag; check again
  }
  return "fail";
}

async function agent(promptFile: string, branch: string, args: Record<string, string>) {
  return run({
    name: `issue-${args.ISSUE_NUMBER}`,
    sandbox: podman(),
    agent: claudeCode(MODEL),
    promptFile,
    promptArgs: args,
    maxIterations: 1,
    branchStrategy: { type: "branch", branch, baseBranch: "origin/main" },
    copyToWorktree: ["node_modules"],
    hooks: { sandbox: { onSandboxReady: [{ command: "npm install" }] } },
  });
}

git("fetch", "origin");
for (let n = 0; n < MAX_ISSUES; n++) {
  const issue = pickIssue();
  if (!issue) { console.log("No unblocked Sandcastle issue. Done."); break; }
  const branch = `sandcastle/issue-${issue.number}`;
  const args = { ISSUE_NUMBER: String(issue.number), ISSUE_TITLE: issue.title, BRANCH: branch };
  let pr = openPr(branch); // resume an open PR instead of a duplicate
  if (pr) {
    rebaseOnMain(branch, issue.number, true);
  } else {
    await agent("./.sandcastle/prompt.md", branch, args);
    rebaseOnMain(branch, issue.number, false);
    gh("pr", "create", "-R", REPO, "--head", branch, "--base", "main", "--title", `${issue.title} (#${issue.number})`, "--body", `Closes #${issue.number}\n\nMade by the Sandcastle loop.`);
    pr = openPr(branch)!;
  }
  let ok = (await waitChecks(pr)) === "pass";
  for (let t = 1; !ok && t <= MAX_FIX_TRIES; t++) {
    await agent("./.sandcastle/fix-prompt.md", branch, { ...args, TRY: String(t), CI_LOG: failedLog(branch) });
    git("push", "origin", branch);
    ok = (await waitChecks(pr)) === "pass";
  }
  if (!ok) {
    gh("issue", "comment", String(issue.number), "-R", REPO, "--body", `Sandcastle: CI still fails after ${MAX_FIX_TRIES} fix tries on PR #${pr}. A person must look.`);
    console.error(`BLOCKER: issue #${issue.number}, PR #${pr}. Loop stops.`);
    process.exit(2);
  }
  // Merge only after ALL checks pass, on a branch that is up to date with main.
  if ((await mergeWhenCurrent(pr)) !== "merged") {
    console.error(`BLOCKER: issue #${issue.number}, PR #${pr}: merge failed after update-branch. Loop stops.`);
    process.exit(2);
  }
  finished.add(issue.number);
  await closeIssue(issue.number);
  try { git("worktree", "prune"); git("branch", "-D", branch); } catch { /* local branch already gone */ }
  git("fetch", "origin");
}
