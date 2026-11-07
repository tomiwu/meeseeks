// Tests for the retry helper. Fake commands only; no real GitHub.
import assert from "node:assert/strict";
import { retryOnce, retryOrBlock } from "./retry.mts";

class Exit extends Error { constructor(public code: number) { super("exit"); } }
const mk = (failures: number) => {
  const logs: string[] = [], errs: string[] = [];
  let calls = 0;
  return {
    logs, errs, calls: () => calls,
    hooks: {
      exec: () => { calls++; if (calls <= failures) throw new Error("ETIMEDOUT"); return "ok"; },
      log: (l: string) => logs.push(l),
      err: (l: string) => errs.push(l),
      exit: (c: number): never => { throw new Exit(c); },
    },
  };
};

// Test 1: first call works, no retry
{
  const f = mk(0);
  assert.equal(retryOrBlock("gh", ["issue", "view"], f.hooks), "ok");
  assert.equal(f.calls(), 1);
  assert.equal(f.logs.length, 0);
}
// Test 2: one failure, one log line, one retry, success
{
  const f = mk(1);
  assert.equal(retryOrBlock("gh", ["issue", "view"], f.hooks), "ok");
  assert.equal(f.calls(), 2);
  assert.equal(f.logs.length, 1);
  assert.equal(f.errs.length, 0);
}
// Test 3: two failures, BLOCKER line, exit code 2
{
  const f = mk(2);
  assert.throws(() => retryOrBlock("gh", ["issue", "view"], f.hooks), (e) => e instanceof Exit && e.code === 2);
  assert.equal(f.calls(), 2);
  assert.equal(f.errs.length, 1);
  assert.match(f.errs[0], /^BLOCKER: /);
}
// Test 4: soft variant throws after two failures and does not exit
{
  const f = mk(2);
  assert.throws(() => retryOnce("gh", ["pr", "merge"], f.hooks), /ETIMEDOUT/);
  assert.equal(f.calls(), 2);
  assert.equal(f.errs.length, 0);
}
console.log("All retry tests passed.");
