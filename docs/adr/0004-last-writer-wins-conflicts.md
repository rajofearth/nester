# 0004 Last-writer-wins conflicts

Date: 2026-09-19
Status: accepted

## Context

The product context report promised nester "resolves conflicts instead of quietly picking one version". Real conflict resolution means either user-facing merge UI or version vectors, both large projects. v1 has at most a couple of devices per host, and the change patterns (camera roll writes on the phone, edits on the host) rarely touch the same file.

## Decision

v1 resolves conflicts last-writer-wins on mtime. When both sides changed a path, the copy with the newer mtime wins and the loser is overwritten. This is a deliberate deviation from the report's promise, recorded here so it does not look like an oversight.

## Consequences

Cheap to build: mtime is already in the index, the comparison is a line of code. The cost is real data loss in the rare case of a true concurrent edit, and no audit trail of the losing version. Revisit triggers: more than two devices paired, user reports of lost edits, or any shared-folder use case. The v2 candidate is a keep-both rename policy: on conflict, save the loser as a renamed copy instead of deleting it. That needs no version vectors and fits the existing index.
