# Research: A2A Protocol, Private Network, Split Brain Resolver

Date: 2026-11-05. Status: draft.
Issue: #19.

## Purpose

This document covers three open items from ARCHITECTURE.md section 8:

- Item 7: A2A research for our protocol.
- Item 3: Private network for the cluster (WireGuard, Tailscale, other).
- Item 4: Split Brain Resolver strategy for a server-centric cluster.

Each section ends with one recommendation.

---

## 1. A2A Protocol

### 1.1 What A2A is

A2A (Agent2Agent) is an open protocol. Google open-sourced it in April 2025.
It defines how agents discover each other and exchange work.
Version 0.2.4 is the current stable release.

The protocol has five core concepts:

| Concept | Description |
|---|---|
| Agent Card | A JSON file at `/.well-known/agent.json`. It lists the agent's name, capabilities, and endpoint. |
| Task | The unit of work. A client sends a task to an agent. The agent returns one result. |
| Message | One turn in a task. A message holds one or more parts. |
| Part | The content unit. Types: `TextPart`, `FilePart`, `DataPart`. |
| Artifact | The output of a completed task. Same part types as a message. |

Transport is HTTP/REST. Streaming uses Server-Sent Events (SSE).
Authentication uses standard HTTP headers: Bearer token (JWT or API key) or OAuth2.
The spec does not mandate a specific token format.

### 1.2 What A2A gives Meeseeks

The architecture requires a protocol "close to A2A" (Q28, FR20).
A2A maps well to the Meeseeks design:

| Meeseeks concept | A2A equivalent |
|---|---|
| Task sent to Overseer | A2A Task |
| Meeseeks to Overseer permit request | A2A Task (synchronous, one reply) |
| Worker reporting result | A2A Artifact in a completed Task |
| Brain call result (text, file) | A2A TextPart or FilePart |
| Per-process token | A2A Bearer token in the HTTP header |

The Pekko Cluster carries messages internally over Artery (TCP/TLS).
A2A is not a replacement for Artery. A2A applies to the external boundary:
the protocol between the server and any process that is not a Pekko cluster member.
In v1 that boundary is small. It grows when the Grok bot or external tools connect.

### 1.3 What to adopt and what to skip

**Adopt:**

- Task and Message structure (goal, parts, artifacts). Use the A2A field names.
- Part types: `TextPart`, `FilePart`, `DataPart`. These cover all Meeseeks payloads.
- Task state machine: `submitted`, `working`, `input-required`, `completed`, `failed`, `canceled`.
  Map to Meeseeks states directly.
- Agent Card for each worker. The server reads the card to know what a worker can do.
- Bearer token for process identity. Matches FR19 (per-process short-lived tokens).

**Skip in v1:**

- Push notifications (A2A optional feature). The Pekko journal handles event delivery.
- Multi-turn `input-required` state. Meeseeks actors do not pause for external input mid-task.
- The A2A discovery registry. The server knows its workers directly.

### 1.4 Message format

Use JSON over HTTP for the external boundary. Use Jackson CBOR inside the Pekko Cluster
(consistent with the serialization decision in ARCHITECTURE.md section 2).

A minimal task message in JSON follows the A2A structure:

```json
{
  "id": "task-42",
  "sessionId": "session-7",
  "message": {
    "role": "user",
    "parts": [{ "type": "text", "text": "goal: add failing test for X" }]
  }
}
```

The server adds `writerId` and a timestamp to every event before it writes to the journal (FR19).
These fields are outside the A2A payload and are not visible to workers.

### 1.5 Recommendation

**Adopt the A2A Task and Message schema for all external-boundary messages.**
Use A2A field names and the A2A task state machine.
Use Bearer tokens for process identity.
Skip push notifications and multi-turn input in v1.
Keep Artery TLS for all intra-cluster Pekko messages.

---

## 2. Private Network for the Cluster

### 2.1 Requirements

The cluster needs a private network for three reasons:

1. Pekko Artery (TCP) must reach all cluster nodes. Nodes bind to a stable IP or hostname.
2. mTLS (one certificate per worker, Q32) secures the channel. The network must not expose ports publicly.
3. Workers run on different machines (Tomasz's computer + remote Macs, Q15).

### 2.2 Options

#### Option A: WireGuard (self-managed)

WireGuard is a kernel-level VPN. It uses ChaCha20-Poly1305.
The codebase is under 5000 lines. Audit surface is small.

**How it works:**
Each node gets a key pair. The server holds a list of peer public keys and their allowed IP ranges.
Nodes connect peer-to-peer over UDP. Packets are encrypted at the kernel level.
On Linux the kernel module is built in since 5.6. On macOS the userspace tool `wireguard-go` runs instead.

**Configuration for Meeseeks:**
- Assign each node a fixed IP in a private subnet (for example `10.100.0.0/24`).
- The server holds the full peer list. Workers hold only the server's public key.
- Each worker uses its fixed IP as the Pekko `bind-hostname` and `canonical-hostname`.
- Key exchange is manual (scp or a small script). No control plane.

**Pros:** No external dependency. Full control. No data leaves the machines.
**Cons:** Manual key management. No automatic NAT traversal. Requires root on each machine.

#### Option B: Tailscale

Tailscale builds a mesh network on top of WireGuard.
It adds a managed control plane (Tailscale's servers) for key exchange and NAT traversal.
The open-source control plane alternative is Headscale.

**How it works:**
Each machine runs `tailscaled`. Machines authenticate to the Tailscale control plane once.
The control plane distributes public keys. Machines connect directly (peer-to-peer) when possible,
or relay through DERP servers when NAT traversal fails.
MagicDNS assigns stable hostnames (for example `worker-1.tail12ab.ts.net`).

**Configuration for Meeseeks:**
- Install `tailscaled` on the server and each worker.
- Use Tailscale ACLs to allow only the Pekko Artery port between cluster nodes.
- Use MagicDNS hostnames as `canonical-hostname` in Pekko config.
- One Tailscale auth key per machine. Rotate with the Tailscale API.

**Pros:** Automatic NAT traversal. Stable hostnames without a DNS server.
No manual key exchange. Works through firewalls. Free for small teams (up to 100 devices).
**Cons:** Key exchange depends on Tailscale's control plane (or Headscale for self-hosted).
One extra process per machine.

#### Option C: Nebula

Nebula is an open-source overlay network from Slack.
It uses a central "lighthouse" node for peer discovery and certificate-based identity.

**How it works:**
A certificate authority (CA) issues a certificate to each node.
Nodes register with the lighthouse. The lighthouse shares peer addresses.
Nodes then connect directly over UDP.

**Configuration for Meeseeks:**
- Run the lighthouse on the server (same machine).
- Issue one certificate per worker with `nebula-cert sign`.
- Certificates encode allowed IP ranges and groups.

**Pros:** Self-hosted. Certificate-based identity aligns with the per-worker certificate requirement.
No external dependency after setup.
**Cons:** More setup than Tailscale. CA management adds operational burden.
Smaller community than WireGuard or Tailscale.

### 2.3 Comparison

| Criterion | WireGuard | Tailscale | Nebula |
|---|---|---|---|
| External dependency | None | Tailscale control plane (or Headscale) | None after setup |
| NAT traversal | Manual | Automatic | Automatic (via lighthouse) |
| Key management | Manual | Automatic | CA-based (manual sign) |
| Stable hostname | No (IP only) | Yes (MagicDNS) | No (IP only) |
| Per-node cert | No | No (key pairs) | Yes |
| Setup effort | Medium | Low | High |
| Audit surface | Very small | Medium | Medium |

### 2.4 Recommendation

**Use Tailscale for v1.**

Tailscale reduces setup effort and handles NAT traversal automatically.
This matters because Tomasz's computer is the server and workers are remote Macs
that may sit behind NAT (Q15).
MagicDNS gives stable hostnames. The Pekko config uses these hostnames directly.
The free tier covers the cluster size for v1 (one server plus a few workers).

If the dependency on Tailscale's control plane is not acceptable, use Headscale
(self-hosted control plane, Apache 2.0 license) as a drop-in replacement.
The Tailscale client on each machine works unchanged with Headscale.

mTLS (Q32) is separate from the network layer. Pekko Artery handles mTLS with its own
certificate store. Tailscale provides network isolation and NAT traversal only.

---

## 3. Split Brain Resolver

### 3.1 What split brain is

A Pekko Cluster uses a gossip protocol. If the network between nodes breaks,
two groups may each think the other group has failed.
Both groups may then try to become the full cluster.
This is a split-brain scenario. It leads to conflicting state and lost events.

Pekko Cluster does not resolve split brain by itself.
`pekko-management` provides a Split Brain Resolver (SBR) module.
SBR watches for unreachable nodes and applies a strategy to decide which side survives.

### 3.2 Meeseeks cluster topology

The Meeseeks cluster has one server and N workers (Q15, Q32).
The server holds the overseer actor and the event journal.
Workers run Podman containers and brain processes.

This topology is server-centric:
- The server is the seed node. It is always started first.
- Workers join and leave. The server stays up for the life of the application.
- Losing the server means losing all state (until the journal recovers on restart).
- Losing a worker means losing one or more running tasks (the overseer restarts them).

### 3.3 Strategies

`pekko-management` 1.6.0 (compatible with Pekko core 1.7.1) provides these strategies:

| Strategy | Rule |
|---|---|
| `keep-majority` | Keep the partition with more nodes. Down the minority side. |
| `static-quorum` | Keep the partition that has at least N nodes. N is set in config. |
| `keep-oldest` | Keep the partition that contains the oldest node. Down the other side. |
| `keep-referee` | Keep the partition that contains a named node (by address or role). Down the other side. |
| `down-all` | All nodes that detect unreachability down themselves immediately. The cluster stops. |
| `lease-majority` | Keep-majority, but only the winning side acquires an external lease first. |

### 3.4 Analysis for a server-centric cluster

**keep-oldest** fits the Meeseeks topology.

The server is always started first. It is always the oldest node.
If the network splits, the partition that contains the server keeps running.
Workers on the other side down themselves. The server continues with the remaining workers.

This matches the Meeseeks safety requirements:
- The overseer and journal are on the server. They must survive the split.
- Workers are stateless relative to the journal. A downed worker loses only its running tasks.
  The overseer detects the stall and restarts those tasks on surviving workers (FR10, Q30).
- No external lease or quorum size configuration is needed.

**Why not keep-majority:**
With one server and two workers (the minimum realistic cluster), a network split may produce
a 1-node and a 2-node partition. If the server is alone (1 node), it downs itself.
The journal is then unavailable. That is worse than losing two workers.

**Why not static-quorum:**
The quorum size must be set in config. If workers come and go, the quorum may be wrong.
A cluster with fewer nodes than the quorum gets stuck.

**Why not keep-referee:**
`keep-referee` names a node by its configured address.
It is functionally equivalent to `keep-oldest` for this topology
but requires the server address to be stable and set in config.
`keep-oldest` requires no such config.

**Why not lease-majority:**
Requires an external lease store (for example etcd or a database).
Adds an external dependency. Not needed for a single-server design.

### 3.5 Configuration

Add `pekko-management` to the build:

```
// build.sbt
"org.apache.pekko" %% "pekko-management-cluster-bootstrap" % "1.6.0",
"org.apache.pekko" %% "pekko-management-cluster-http"      % "1.6.0"
```

Enable the SBR in `application.conf`:

```hocon
pekko {
  cluster {
    downing-provider-class = "org.apache.pekko.cluster.sbr.SplitBrainResolverProvider"
  }

  cluster.split-brain-resolver {
    active-strategy = keep-oldest

    keep-oldest {
      # Down the oldest node too if it is the only node in its partition.
      # Set to no so the server never downs itself when isolated.
      down-if-alone = no
    }

    # Time to wait before the resolver acts after a node becomes unreachable.
    # 10 s is a safe default for a small cluster on a stable network.
    stable-after = 10s
  }
}
```

Setting `down-if-alone = no` means the server stays up even when isolated.
Workers that cannot reach the server down themselves.
The server then heals or waits for reconnection.

### 3.6 Interaction with the 60-second self-stop lease (FR8)

FR8 says a worker with no server contact for 60 s stops itself.
The SBR acts after `stable-after` (10 s by default).
The SBR downs a worker before the 60-second self-stop fires.
The two mechanisms are complementary. The SBR handles the cluster membership layer.
The 60-second self-stop handles the application layer (containers and brain processes).
Both must be active.

### 3.7 Recommendation

**Use `keep-oldest` with `down-if-alone = no` and `stable-after = 10s`.**

This keeps the server and its journal alive in every network partition scenario.
Workers on the unreachable side down themselves.
The overseer detects the lost tasks and restarts them on surviving workers.
No external lease store or quorum configuration is needed.

---

## 4. Summary of recommendations

| Topic | Recommendation |
|---|---|
| A2A protocol | Adopt the A2A Task and Message schema for external-boundary messages. Use A2A field names and the A2A task state machine. Use Bearer tokens for process identity. Keep Artery TLS for intra-cluster messages. |
| Private network | Use Tailscale. Use Headscale as the self-hosted control plane if external dependency is not acceptable. |
| Split Brain Resolver | Use `keep-oldest` with `down-if-alone = no` and `stable-after = 10s`. |

---

## 5. Sources

- A2A specification v0.2.4: https://google.github.io/A2A/specification/
- A2A GitHub repository: https://github.com/google/A2A
- WireGuard documentation: https://www.wireguard.com/
- Tailscale documentation: https://tailscale.com/kb/
- Headscale (self-hosted Tailscale control plane): https://github.com/juanfont/headscale
- Nebula documentation: https://github.com/slackhq/nebula
- Pekko Cluster Split Brain Resolver: https://pekko.apache.org/docs/pekko-management/current/split-brain-resolver.html
- pekko-management releases: https://github.com/apache/pekko-management/releases
- Pekko Cluster documentation: https://pekko.apache.org/docs/pekko/current/typed/cluster.html
