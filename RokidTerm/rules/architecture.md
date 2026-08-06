# RokidTerm rules: Architecture

Loaded on demand from `CLAUDE.md`. Covers the network/session model and the
two-account SSH architecture.

## Network and session model

- The glasses connect directly to the public server. They do not depend on the Mac after provisioning.
- Do not introduce a cloud-to-Mac tunnel, VPN, reverse SSH route, or Mac endpoint without an explicit architecture change from the user.
- Wi-Fi connectivity is managed by Rokid system components, not this app. The app has no `CHANGE_WIFI_STATE` permission.
- `DREAMS_CAST` may temporarily lose its Android default route while the companion/CXR service rebuilds Wi-Fi. Treat `ENETUNREACH` as a network state, not an SSH key failure.
- Display `CONNECTING`, `CONNECTED`, `DISCONNECTED`, and sanitized `ERROR` states. Error-state center/confirm should retry the active endpoint.
- Each endpoint owns a fixed tmux session name. The remote command first creates a missing session in detached mode with the fixed Claude launcher and startup flags, then applies a 46-column-friendly compact status configuration and attaches:

```bash
(tmux has-session -t <session> 2>/dev/null || tmux new-session -d -s <session> -c <workspace> /home/rokid/bin/rokid-claude --effort max --dangerously-skip-permissions) && tmux set-option ... && exec tmux attach-session -t <session>
```

- Reconnecting attaches to the existing session without restarting Claude. Status options are refreshed on every connection so inherited server defaults cannot truncate the session name or emit tmux scroll markers such as `<lau>`. An APK reinstall disconnects SSH but must not kill the server tmux session.
- tmux sessions belong to the `rokid` Linux user. Inspect them from an admin shell with `sudo -iu rokid tmux ls`, not plain `tmux ls` as `ubuntu`.

## Two-user architecture: terminal (`rokid`) vs ASR (`asr-fwd`)

The glasses use **two separate SSH connections to the same server**, each with
its own Linux account, identity, and permission envelope. This is deliberate:
the `rokid` account is locked down so it can never be used as a jump host or
reach internal services, while a dedicated `asr-fwd` account exposes exactly
one thing — the ASR port.

```
Rokid glasses (RokidTerm APK)
  │
  ├─ SSH #1: rokid@server (terminal)
  │    ├─ tmux + Claude Code PTY
  │    └─ AllowTcpForwarding no  → cannot forward to ANY port
  │
  └─ SSH #2: asr-fwd@server (ASR, same host)
       ├─ permitopen="127.0.0.1:8765"  → can only forward to ASR
       ├─ local forward 127.0.0.1:18765 → 127.0.0.1:8765
       └─ HTTP POST /v1/transcribe (WAV) → recognized text
```

Why two accounts instead of letting `rokid` forward:

- `rokid`'s `no-port-forwarding` / `AllowTcpForwarding no` is a security
  boundary: a compromised `rokid` session must not be able to reach the
  server's loopback services (`myWorldOpen:3000`, Caddy:2019, DNS) or become a
  SOCKS proxy / pivot into the cloud VPC.
- `asr-fwd` is the minimal exception: it is restricted by `permitopen` to a
  single forward target, has `/usr/sbin/nologin` (no shell), no groups, no
  sudo. Even a leaked `asr-fwd` key can only transcribe audio — it cannot
  execute anything.

Server-side setup (already deployed, documented in
`third_party/asr-server/CLAUDE.md`):

- `asr-fwd` user + `~asr-fwd/.ssh/authorized_keys` with
  `no-agent-forwarding,no-X11-forwarding,no-user-rc,no-pty,permitopen="127.0.0.1:8765"`.
- The ASR service itself binds only to `127.0.0.1:8765`, never publicly.
- ASR is on-demand: a PAM `pam_exec` hook starts it when `rokid` logs in and
  stops it ~60 s after the last `rokid` session closes.

App-side integration (this repo):

- `AsrProfile.fromEndpoint(endpoint)` reuses the endpoint's host/knownHost but
  switches the user to `asr-fwd` (default).
- The ASR identity is a separate `DeviceKeyStore` entry keyed by profile id
  (`ssh_identity_asr-<id>.enc`); it must be provisioned with the `asr-fwd`
  public key, not the `rokid` one.
- `ServerAsrClient` opens the second SSH session and the local forward;
  `AsrController` wires it to the composer (record → transcribe → draft).
- The composer's speech button uses the ASR path when the `asr-fwd` channel is
  up, falling back to the local Android `SpeechRecognizer` (rarely available on
  this firmware).

Operational notes:

- The two connections are independent: the `asr-fwd` session does not depend on
  the `rokid` session staying up, and vice versa. A reconnect of one does not
  affect the other.
- The `asr-fwd` connection must be recreated after the app or the forward dies;
  `AsrController.connect(endpoint)` is called together with the terminal
  connect.
- Never put the `asr-fwd` private key in the APK or in git; it is provisioned
  per device via the same app-private mechanism as the `rokid` identity.
