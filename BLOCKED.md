# Blocked: Issue #24 — Branch protection and merge settings

## Status

The CI workflow and placeholder build are complete. All five required jobs pass on `main`:

- `format` — success
- `lint` — success
- `test (3.10.0)` — success
- `test (3.9.0)` — success
- `crap` — success

## Blocker

The `GH_TOKEN` in this environment does not have the `administration: write` permission.
Both of the following GitHub API calls return HTTP 403:

```
gh api --method PUT repos/tomiwu/meeseeks/branches/main/protection
gh api --method PATCH repos/tomiwu/meeseeks
```

Error: `Resource not accessible by personal access token`

## Remaining steps (require administration permission)

1. Set squash merge only:
   ```
   gh api --method PATCH repos/tomiwu/meeseeks \
     --field allow_squash_merge=true \
     --field allow_merge_commit=false \
     --field allow_rebase_merge=false
   ```

2. Set branch protection on `main`:
   ```
   gh api --method PUT repos/tomiwu/meeseeks/branches/main/protection \
     --raw-field 'required_status_checks={"strict":true,"contexts":["format","lint","test (3.10.0)","test (3.9.0)","crap"]}' \
     --raw-field 'enforce_admins=false' \
     --raw-field 'required_pull_request_reviews={"required_approving_review_count":0}' \
     --raw-field 'restrictions=null' \
     --field allow_force_pushes=false \
     --field allow_deletions=false
   ```

## Fix

Add `administration: write` to the token, or run the two commands above from an account with admin access to `tomiwu/meeseeks`.
