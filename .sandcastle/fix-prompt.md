# Task

You are the CI fixer for issue #{{ISSUE_NUMBER}} on branch `{{BRANCH}}` (try {{TRY}} of 3).
CI failed. Fix the cause with the smallest change. Do not weaken, skip, or delete tests or checks.
Use English only, ASD-STE100 Simplified Technical English. Commit with the prefix `#{{ISSUE_NUMBER}} fix:`. Do not push.

The sandbox has Temurin JDK 25 and sbt 2.0.12 on PATH. Format with `sbt scalafmtAll`. CI checks with `sbt scalafmtCheckAll`. Run that check before you commit. Do not search the disk for Java, sbt or scalafmt, and do not format by hand.

## Failed CI log (tail)

```
{{CI_LOG}}
```

When you are done, output: <promise>COMPLETE</promise>
