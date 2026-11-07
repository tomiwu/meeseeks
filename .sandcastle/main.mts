// Meeseeks Sandcastle loop. Host-side orchestrator; agents run in Podman.
// Start: npm run sandcastle   (needs .sandcastle/.env, see .env.example)
import { run, claudeCode } from "@ai-hero/sandcastle";
import { podman } from "@ai-hero/sandcastle/sandboxes/podman";
import { execFileSync } from "node:child_process";
import { rmSync } from "node:fs";
import { retryOnce, retryOrBlock } from "./retry.mts";
import { selectIssue, PRIO, type Issue } from "./pick.mts";

const REPO = "tomiwu/meeseeks";
const MODEL = "claude-sonnet-4-6"; // latest Sonnet known to Sandcastle 0.12.0
const MAX_FIX_TRIES = 3;
const MAX_ISSUES = Number(process.env.SANDCASTLE_MAX_ISSUES ?? "25");
const DRY_RUN = Boolean(process.env.SANDCASTLE_DRY_RUN);

// 60 s timeout, so a network stall cannot hang the loop.
// Hard call: retry once, then BLOCKER and exit 2. Soft call: retry once, then throw (caller handles it).
const gh = (...a: string[]) => retryOrBlock("gh", a);
const ghSoft = (...a: string[]) => retryOnce("gh", a);
const git = (...a: string[]) => execFileSync("git", a, { encoding: "utf8" }).trim();
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));
const REQUIRED = ["format", "lint", "test (3.10.0)", "crap", "import-check"];

const finished = new Set<number>(); // issues merged in this run; never pick them again

function pickIssue(): Issue | undefined {
  const open: Issue[] = JSON.parse(gh("issue", "list", "-R", REPO, "--state", "open", "--label", "Sandcastle", "--limit", "200", "--json", "number,title,body,labels"));
  return selectIssue(open, finished);
}

function openPr(branch: string): number | undefined {
  const prs = JSON.parse(gh("pr", "list", "-R", REPO, "--head", branch, "--state", "open", "--json", "number"));
  return prs[0]?.number;
}

// Wait for CI. If CI is pending or failed and main moved, rebase on main and wait for the new run.
async function waitChecks(pr: number, branch: string, issue: number): Promise<"pass" | "fail"> {
  await sleep(20_000);
  for (;;) {
    const checks: { bucket: string }[] = JSON.parse(execFileSync("gh", ["pr", "checks", String(pr), "-R", REPO, "--json", "bucket"], { encoding: "utf8" }) || "[]");
    if (checks.length && checks.every((c) => c.bucket === "pass" || c.bucket === "skipping")) return "pass";
    const failed = checks.some((c) => c.bucket === "fail" || c.bucket === "cancel");
    netGit(`[issue-${issue}] CI wait:`, "fetch", "--quiet", "origin");
    const mainMoved = Number(git("rev-list", "--count", `origin/${branch}..origin/main`)) > 0;
    if (mainMoved && rebaseOnMain(branch, issue, "remote", failed ? "CI failed and main moved" : "CI pending and main moved")) {
      await sleep(20_000);
      continue;
    }
    if (failed) return "fail";
    await sleep(30_000);
  }
}

function failedLog(branch: string): string {
  const runId = gh("run", "list", "-R", REPO, "--branch", branch, "--status", "failure", "--limit", "1", "--json", "databaseId", "--jq", ".[0].databaseId");
  if (!runId) return "No failed run log found.";
  try { return ghSoft("run", "view", runId, "-R", REPO, "--log-failed").slice(-20_000); } catch { return "Could not read the failed log."; }
}

// GitHub closes the issue a few seconds after the merge. Close it ourselves and wait,
// so the next pick can never see the finished issue as open (cause of duplicate PRs).
async function closeIssue(n: number) {
  for (let i = 0; i < 12; i++) {
    const state = gh("issue", "view", String(n), "-R", REPO, "--json", "state", "--jq", ".state");
    if (state === "CLOSED") return;
    if (i === 1) { try { ghSoft("issue", "close", String(n), "-R", REPO, "--reason", "completed"); } catch { /* retry on next loop */ } }
    await sleep(5_000);
  }
}

// Rebase the branch on origin/main before we create or resume its PR.
// The rebase runs in a temp worktree. Then the result goes to the remote branch, the local
// branch ref, and any Sandcastle worktree that has the branch checked out, so the agent starts from it.
// On a conflict, abort the rebase and stop the loop with BLOCKER.
function quietGit(...a: string[]) {
  try { execFileSync("git", a, { encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] }); } catch { /* cleanup only */ }
}
function dropTemp(dir: string) {
  quietGit("worktree", "remove", "--force", dir);
  rmSync(dir, { recursive: true, force: true });
  quietGit("worktree", "prune");
}
function worktreeOf(branch: string): string | undefined {
  let path: string | undefined;
  for (const line of git("worktree", "list", "--porcelain").split("\n")) {
    if (line.startsWith("worktree ")) path = line.slice(9);
    else if (line === `branch refs/heads/${branch}`) return path;
  }
  return undefined;
}
// Network git call with a 60 s timeout. On timeout or error, retry once, then BLOCKER (no hang).
function netGit(tag: string, ...a: string[]): string {
  for (let i = 1; ; i++) {
    try { return execFileSync("git", a, { encoding: "utf8", timeout: 60_000, killSignal: "SIGKILL" }).trim(); }
    catch (e) {
      if (i >= 2) {
        console.error(`BLOCKER: ${tag} git ${a[0]} failed or timed out 2 times. Loop stops.`);
        process.exit(2);
      }
      console.log(`${tag} git ${a[0]} failed or timed out, retry once`);
    }
  }
}
function sha(ref: string): string | undefined {
  try { return execFileSync("git", ["rev-parse", "--verify", "--quiet", ref], { encoding: "utf8", stdio: ["ignore", "pipe", "ignore"] }).trim() || undefined; }
  catch { return undefined; }
}
// from = "local": start from the local branch (the agent just wrote to it).
// from = "remote": start from origin/<branch> (resume, or CI wait).
// Only call this between agent runs, never while the agent works in the checkout.
// Returns true if the branch moved.
function rebaseOnMain(branch: string, issue: number, from: "local" | "remote", why: string): boolean {
  const tag = `[issue-${issue}] ${why}:`;
  netGit(tag, "fetch", "--quiet", "origin");
  const remoteSha = sha(`origin/${branch}`);
  const start = from === "remote" ? `origin/${branch}` : branch;
  const old = sha(start);
  if (!old) { console.log(`${tag} rebase skipped, ${start} does not exist yet`); return false; }
  const push = (to: string) => {
    const now = netGit(tag, "ls-remote", "origin", `refs/heads/${branch}`).split(/\s/)[0] || undefined;
    if (now === to) { console.log(`${tag} push skipped, remote already has ${to.slice(0, 7)}`); return; }
    if (now) netGit(tag, "push", "--quiet", `--force-with-lease=${branch}:${remoteSha ?? now}`, "origin", `${to}:refs/heads/${branch}`);
    else netGit(tag, "push", "--quiet", "origin", `${to}:refs/heads/${branch}`);
  };
  const behind = Number(git("rev-list", "--count", `${old}..origin/main`));
  if (behind === 0) {
    console.log(`${tag} ${branch} already up to date with origin/main`);
    if (old !== remoteSha) push(old);
    return false;
  }
  const used = worktreeOf(branch);
  if (used && execFileSync("git", ["-C", used, "status", "--porcelain"], { encoding: "utf8" }).trim()) {
    console.log(`${tag} rebase skipped, ${used} has changes that are not committed`);
    return false;
  }
  const dir = `/tmp/sandcastle-rebase-${issue}`;
  dropTemp(dir);
  execFileSync("git", ["worktree", "add", "--quiet", "--detach", dir, start], { stdio: ["ignore", "pipe", "pipe"] });
  const wt = (...a: string[]) => execFileSync("git", ["-C", dir, ...a], { encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] }).trim();
  try {
    wt("rebase", "origin/main");
  } catch {
    try { wt("rebase", "--abort"); } catch { /* no rebase in progress */ }
    dropTemp(dir);
    console.error(`BLOCKER: ${tag} rebase of ${branch} on origin/main has conflicts. Loop stops.`);
    process.exit(2);
  }
  const head = wt("rev-parse", "HEAD");
  dropTemp(dir);
  push(head);
  if (used) {
    // The agent reuses this worktree. Move its files too, not only the ref.
    execFileSync("git", ["-C", used, "reset", "--hard", "--quiet", head], { stdio: ["ignore", "pipe", "pipe"] });
  } else {
    git("update-ref", `refs/heads/${branch}`, head);
  }
  quietGit("branch", "--set-upstream-to", `origin/${branch}`, branch);
  netGit(tag, "fetch", "--quiet", "origin");
  console.log(`${tag} rebased ${branch} on origin/main (was ${behind} commits behind), now ${head.slice(0, 7)}`);
  return true;
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
    try { ghSoft("pr", "merge", String(pr), "-R", REPO, "--squash", "--delete-branch"); return "merged"; }
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
if (DRY_RUN) {
  const issue = pickIssue();
  console.log(issue ? `dry-run: next issue #${issue.number}: ${issue.title}` : "dry-run: no unblocked Sandcastle issue");
  process.exit(0);
}
for (let n = 0; n < MAX_ISSUES; n++) {
  const issue = pickIssue();
  if (!issue) { console.log("No unblocked Sandcastle issue. Done."); break; }
  const branch = `sandcastle/issue-${issue.number}`;
  const args = { ISSUE_NUMBER: String(issue.number), ISSUE_TITLE: issue.title, BRANCH: branch };
  let pr = openPr(branch); // resume an open PR instead of a duplicate
  if (pr) {
    rebaseOnMain(branch, issue.number, "remote", "resume open PR");
  } else {
    rebaseOnMain(branch, issue.number, sha(branch) ? "local" : "remote", "before first agent run");
    await agent("./.sandcastle/prompt.md", branch, args);
    rebaseOnMain(branch, issue.number, "local", "after agent run");
    gh("pr", "create", "-R", REPO, "--head", branch, "--base", "main", "--title", `${issue.title} (#${issue.number})`, "--body", `Closes #${issue.number}\n\nMade by the Sandcastle loop.`);
    pr = openPr(branch)!;
  }
  let ok = (await waitChecks(pr, branch, issue.number)) === "pass";
  for (let t = 1; !ok && t <= MAX_FIX_TRIES; t++) {
    await agent("./.sandcastle/fix-prompt.md", branch, { ...args, TRY: String(t), CI_LOG: failedLog(branch) });
    rebaseOnMain(branch, issue.number, "local", `after fixer try ${t}`);
    ok = (await waitChecks(pr, branch, issue.number)) === "pass";
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
