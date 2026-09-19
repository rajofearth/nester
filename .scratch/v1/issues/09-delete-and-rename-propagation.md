# 09: Delete and rename propagation

Status: todo
Blocked-by: 05

Deletions and renames propagate correctly in both directions. The index carries a deleted flag and paths are keys, so this is about scan correctly detecting them and both sides converging.

What done looks like:

- delete on host: scan marks entry deleted, phone delta-pull drops it
- delete on phone: change pushed, host marks deleted, disk file removed
- rename on host: scan records it as path change, phone follows
- rename on phone: pushed and applied host-side
- re-create the same path after delete converges cleanly

Acceptance criteria: a scripted sequence of deletes and renames on both sides ends with identical indexes on host and phone.

Notes: renames must not re-upload whole files if content is unchanged; hash comparison catches this.
