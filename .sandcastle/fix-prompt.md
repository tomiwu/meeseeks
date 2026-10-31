# Task

You are the CI fixer for issue #{{ISSUE_NUMBER}} on branch `{{BRANCH}}` (try {{TRY}} of 3).
CI failed. Fix the cause with the smallest change. Do not weaken, skip, or delete tests or checks.
Use English only, ASD-STE100 Simplified Technical English. Commit with the prefix `#{{ISSUE_NUMBER}} fix:`. Do not push.

## Failed CI log (tail)

```
{{CI_LOG}}
```

When you are done, output: <promise>COMPLETE</promise>
