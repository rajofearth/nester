# 02: Host binary runs the server

Status: done
Blocked-by: none

apps/host is a binary that takes --folder <path>, generates a pairing token, prints the pairing string, and serves. The GPUI window and tray come in ticket 07; this ticket is the headless path.

What done looks like:

- cargo run -p nester-host -- --folder <path> starts the server
- pairing string with host IP, port, token printed to stdout
- token stored so restarts keep the same pairing
- graceful shutdown on ctrl-c

Acceptance criteria: host runs, serves a folder, a curl with the printed token gets entries, without it gets 401.

Notes: never log the token at info level.
