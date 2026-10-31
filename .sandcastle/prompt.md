# Task

You are a coding agent for the Meeseeks project (Scala 3, sbt 2.0, Pekko, Kyo).
Work ONLY on issue #{{ISSUE_NUMBER}}: {{ISSUE_TITLE}}. You are on branch `{{BRANCH}}`.

## Issue

!`gh issue view {{ISSUE_NUMBER}} --json title,body,comments --jq '"# " + .title + "\n\n" + .body + "\n\n## Comments\n" + ([.comments[].body] | join("\n---\n"))'`

## Rules

- Use English only, ASD-STE100 Simplified Technical English, in code comments, commits and docs.
- Read `docs/PRD.md` and `docs/ARCHITECTURE.md` before you write code.
- Use the newest stable versions of tools and libraries, pinned.
- Write a failing test first, then the code (Red, Green, Refactor).
- Never put secrets or knowledge data in the repo. The `runtime` module never imports `dev`.
- Do not change `.github/` protection settings. Do not merge, close issues, or push; the host does that.
- Make one or more commits with the prefix `#{{ISSUE_NUMBER}}:`.
- If you are blocked, write the reason in a file `BLOCKED.md`, commit it, and stop.

When you are done, output: <promise>COMPLETE</promise>
