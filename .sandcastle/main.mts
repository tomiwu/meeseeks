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

type Issue = { number: number; title: string; body: string; labels: { name: string }[] };

function pickIssue(): Issue | undefined {
  const open: Issue[] = JSON.parse(gh("issue", "list", "-R", REPO, "--state", "open", "--label", "Sandcastle", "--limit", "200", "--json", "number,title,body,labels"));
  const openNums = new Set(open.map((i) => i.number));
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
  if (!pr) {
    await agent("./.sandcastle/prompt.md", branch, args);
    git("push", "-u", "origin", branch);
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
  gh("pr", "merge", String(pr), "-R", REPO, "--squash", "--delete-branch"); // only after ALL checks pass
  git("fetch", "origin");
}
