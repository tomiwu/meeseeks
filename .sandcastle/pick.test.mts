// Unit tests for the pure issue-selection logic.
import assert from "node:assert/strict";
import { selectIssue } from "./pick.mts";

const sandcastle = { name: "Sandcastle" };
const critical = { name: "priority: critical" };
const high = { name: "priority: high" };
const medium = { name: "priority: medium" };
const low = { name: "priority: low" };

// Test 1: picks the highest-priority unblocked issue
{
  const open = [
    { number: 10, title: "Low", body: "", labels: [sandcastle, low] },
    { number: 5, title: "High", body: "", labels: [sandcastle, high] },
    { number: 3, title: "Critical", body: "", labels: [sandcastle, critical] },
  ];
  const picked = selectIssue(open, new Set());
  assert.equal(picked?.number, 3, "test 1: picks critical issue first");
}

// Test 2: skips issues blocked by an open issue
{
  const open = [
    { number: 10, title: "Dep", body: "", labels: [sandcastle, medium] },
    { number: 7, title: "Blocked", body: "Blocked by #10\n", labels: [sandcastle, critical] },
    { number: 5, title: "Free", body: "", labels: [sandcastle, high] },
  ];
  const picked = selectIssue(open, new Set());
  assert.equal(picked?.number, 5, "test 2: skips issue blocked by open issue");
}

// Test 3: skips already-finished issues
{
  const open = [
    { number: 1, title: "Done", body: "", labels: [sandcastle, critical] },
    { number: 2, title: "Next", body: "", labels: [sandcastle, high] },
  ];
  const picked = selectIssue(open, new Set([1]));
  assert.equal(picked?.number, 2, "test 3: skips finished issue");
}

// Test 4: returns undefined when all issues are blocked or finished
{
  const open = [
    { number: 10, title: "Open", body: "", labels: [sandcastle, medium] },
    { number: 7, title: "Blocked", body: "Blocked by #10", labels: [sandcastle, high] },
  ];
  const picked = selectIssue(open, new Set([10]));
  assert.equal(picked, undefined, "test 4: returns undefined when no issue is pickable");
}

// Test 5: picks issue whose blocker is closed (not in the open list)
{
  const open = [{ number: 25, title: "Config", body: "Blocked by #24", labels: [sandcastle, medium] }];
  const picked = selectIssue(open, new Set());
  assert.equal(picked?.number, 25, "test 5: picks issue whose blocker is already closed");
}

// Test 6: breaks priority ties by issue number (lower number first)
{
  const open = [
    { number: 20, title: "Later high", body: "", labels: [sandcastle, high] },
    { number: 8, title: "Earlier high", body: "", labels: [sandcastle, high] },
  ];
  const picked = selectIssue(open, new Set());
  assert.equal(picked?.number, 8, "test 6: breaks tie by issue number");
}

console.log("All pick tests passed.");
