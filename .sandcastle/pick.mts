// Pure issue-selection logic, no side effects.

export const PRIO: Record<string, number> = {
  "priority: critical": 0,
  "priority: high": 1,
  "priority: medium": 2,
  "priority: low": 3,
};

export type Issue = { number: number; title: string; body: string; labels: { name: string }[] };

/** Return the highest-priority unblocked issue, or undefined. */
export function selectIssue(open: Issue[], finished: Set<number>): Issue | undefined {
  const openNums = new Set(open.map((i) => i.number));
  const ready = open
    .filter((i) => !finished.has(i.number))
    .filter((i) => ![...(i.body ?? "").matchAll(/Blocked by #(\d+)/gi)].some((m) => openNums.has(Number(m[1]))));
  const prio = (i: Issue) => Math.min(...i.labels.map((l) => PRIO[l.name] ?? 9));
  return ready.sort((a, b) => prio(a) - prio(b) || a.number - b.number)[0];
}
