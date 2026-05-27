# Azure Managed Redis — Client-Side Geographic Failover Demo (Spring Boot 3.5 + Jedis 7)

A minimal Spring Boot 3.5 application that demonstrates Jedis 7's
[client-side geographic failover](https://redis.io/docs/latest/develop/clients/jedis/failover/)
(`MultiDbClient`) against an
[Azure Managed Redis (AMR) active geo-replication group](https://learn.microsoft.com/en-us/azure/redis/how-to-active-geo-replication),
authenticated with Microsoft Entra ID.

Reference: [Client-side geographic failover for Redis Active-Active](https://redis.io/blog/client-side-geographic-failover-for-redis-active-active/).
[`CONTEXT.md`](CONTEXT.md) is the glossary used by the scripts and JSON reports.

## Table of contents

- [What the demo shows](#what-the-demo-shows)
- [How it works](#how-it-works)
- [Prerequisites](#prerequisites)
- [Running](#running)
- [REST endpoints](#rest-endpoints)
- [Testing procedures](#testing-procedures)
- [Configuration](#configuration)

## What the demo shows

- A single `MultiDbClient` configured with two weighted AMR endpoints
  (primary `weight=1.0`, secondary `weight=0.5`).
- Per-endpoint health checks (`PingStrategy` by default; `LagAwareStrategy`
  wired and toggleable via config).
- A per-endpoint circuit breaker that trips on failures.
- Automatic failover to the next healthy endpoint, automatic failback when
  the primary recovers.
- `DatabaseSwitchEvent` transitions logged at WARN via `SwitchEventLogger`.
- A background workload (`INCR demo:counter` every second) so failover is
  visible in the logs.
- REST endpoints for manual interaction, status inspection, and forced
  failover via `setActiveDatabase()`.
- Spring Boot Actuator `/actuator/health` reflects the multi-DB state via a
  custom `HealthIndicator`.

## How it works

> **This is not Redis Sentinel or Redis Cluster failover.** Both AMR
> endpoints are fully writable active-active databases kept in sync via
> CRDT. Jedis's job is only to choose which endpoint *this application*
> sends commands to.

With geo-replicated Redis you have two cache endpoints in different
regions, kept in sync by the server. If one goes down, traffic needs to
move to the survivor. Traditional approaches use infrastructure-level
routing — DNS failover, Azure Traffic Manager, or a load balancer — that
operate outside your application. The switch depends on DNS TTL
propagation or health-probe intervals, so recovery is typically measured
in tens of seconds to minutes, and your application has no visibility
into what happened.

**Client-side geographic failover** inverts this. Jedis 7's
`MultiDbClient` holds persistent connections to *every* configured
endpoint simultaneously and makes the routing decision inside the client
library:

```
┌──────────────────────────────────────────────────────────────┐
│  Spring Boot Application                                     │
│                                                              │
│  SessionService ──▶ MultiDbClient ◀── health-check loop      │
│                     ┌────┴────┐       (per endpoint, 5 s)    │
│                     │         │                              │
│                     ▼         ▼                              │
│              ┌──────────┐ ┌──────────┐                       │
│              │ primary  │ │secondary │  ◀─ circuit breaker   │
│              │ w = 1.0  │ │ w = 0.5  │     (per endpoint)    │
│              └────┬─────┘ └────┬─────┘                       │
└───────────────────┼────────────┼─────────────────────────────┘
                    │            │
             ┌──────▼──┐  ┌─────▼───┐
             │AMR East │◄►│AMR West │   ← active geo-replication
             │ (CRDT)  │  │ (CRDT)  │     syncs data bidirectionally
             └─────────┘  └─────────┘
```

**The lifecycle:**

1. **Steady state** — Commands route to the preferred endpoint (the
   healthy one with the highest weight). Connections to the other
   endpoint stay warm and are probed by health checks, but application
   commands go only to the current active endpoint.

2. **Failure detected** — The periodic health check (PING by default) to
   the active endpoint fails. The per-endpoint circuit breaker starts
   counting consecutive failures.

3. **Circuit breaker opens** — After enough consecutive failures the
   breaker trips. `MultiDbClient` immediately elects the next healthy
   endpoint as active and emits a `DatabaseSwitchEvent`. Once the
   breaker opens, the route switch itself is immediate; total detection
   time depends on the health-check interval, timeout, and breaker
   thresholds. No DNS propagation, no Azure infrastructure changes.

4. **Traffic continues** — Commands now go to the new active endpoint.
   Because active geo-replication is eventually consistent, most data
   should already be present on the survivor, subject to replication lag
   and CRDT conflict resolution. Writes acknowledged shortly before the
   regional failure may not yet be visible. This demo measures the
   observed lag.

5. **Recovery and failback** — The health-check loop keeps pinging the
   failed endpoint. Once it responds consistently the circuit breaker
   closes. If the recovered endpoint has a higher weight than the
   current active, `MultiDbClient` waits for a configurable grace period
   (default 5 s in this demo) then switches back automatically.

The result: application code never deals with failover logic.
`SessionService` calls `connection.sync().get(key)` and `MultiDbClient`
decides which physical endpoint handles the command. When a region goes
down and comes back, the switch happens inside the client library with
zero application code changes.

Spring Boot 3.5.x ships with Lettuce 6.x and offers no built-in
`MultiDbClient` support. This project uses **Jedis 7.5.0** directly
(`redis.clients:jedis:7.5.0`) and wires `MultiDbClient` as a Spring bean,
bypassing Spring Data Redis entirely.

## Prerequisites

1. **Java 21** and **Maven 3.9+** (only needed for local development /
   running `mvn verify`; the AKS pipeline builds the image inside
   `mcr.microsoft.com/openjdk/jdk:21-ubuntu` via `az acr build`).
2. **Two Azure Managed Redis caches** in the same active geo-replication
   group. They must be created with:
   - **Clustering policy: Enterprise** (mandatory — `MultiDbClient` is
     standalone-only and cannot handle OSS-clustering MOVED/ASK redirects).
   - **Microsoft Entra Authentication: Enabled** with your identity
     assigned a data-plane access policy (e.g. `Data Owner`).
   - **TLS only** (the default; port 10000).
3. Local Azure CLI / Visual Studio Code / managed identity context that
   `DefaultAzureCredential` can resolve (for local runs).
4. For the AKS deployment path used by the scenarios:
   - An AKS cluster with `--enable-oidc-issuer --enable-workload-identity
     --network-dataplane cilium --enable-acns`. The bootstrap script
     fails loud with the remediation command if any of these are missing.
   - An ACR you can `az acr build` against.
5. **Recommended:** use the bundled Terraform in [`infra/`](infra/README.md)
   to provision both caches + geo-replication in one apply. The scripts in
   [`scripts/`](scripts/README.md) read the cluster names directly from
   `terraform output`, so the testing workflow becomes copy-paste.
   Note: geo-replication must be configured at database-creation time (it
   cannot be added later) and both databases must be created sequentially,
   not in parallel — the Terraform handles this for you.

## Running

### Local development (default profile)

Either set the two hostnames manually:

```bash
export AMR_PRIMARY_HOST=my-primary.eastus.redis.azure.net
export AMR_SECONDARY_HOST=my-secondary.westus.redis.azure.net
mvn spring-boot:run
```

Or, if you used the bundled Terraform:

```bash
eval "$(cd infra && terraform output -raw spring_env)"
mvn spring-boot:run
```

You will see logs like:

```
INCR demo:counter = 42  (active=rediss://my-primary.eastus.redis.azure.net:10000)
```

The default profile uses `DefaultAzureCredential`, which transparently tries
Azure CLI, VS Code / IntelliJ, environment variables, and (lastly) any
Managed Identity available in the environment. It works on your laptop after
`az login` and also works (less explicitly) on Azure.

### On Azure with Managed Identity (`azure` profile)

For production deployments on AKS, Container Apps, App Service, or VMs,
activate the `azure` profile. It swaps the credential source from the
`DefaultAzureCredential` chain to an explicit Azure Managed Identity via the
`redis-authx-entraid` `EntraIDTokenAuthConfigBuilder`. This gives faster
startup (no credential probing) and fail-fast behaviour if the Managed
Identity is missing, unauthorised, or misconfigured.

Reference: [Microsoft Entra ID for authentication on Azure Managed Redis](https://learn.microsoft.com/en-us/azure/redis/entra-for-authentication).

System-assigned Managed Identity (the simplest setup — no extra config needed,
because omitting `app.redis.entra.identity` under the `azure` profile defaults
to system-assigned MI):

```bash
export SPRING_PROFILES_ACTIVE=azure
java -jar target/amr-geo-failover-0.0.1-SNAPSHOT.jar
```

User-assigned Managed Identity (when multiple workloads share the same
identity, or you need explicit RBAC isolation):

```bash
export SPRING_PROFILES_ACTIVE=azure
# Pick one of CLIENT_ID, OBJECT_ID, RESOURCE_ID and supply the matching value
export APP_REDIS_ENTRA_IDENTITY_TYPE=USER_ASSIGNED_CLIENT_ID
export APP_REDIS_ENTRA_IDENTITY_ID=11111111-2222-3333-4444-555555555555
java -jar target/amr-geo-failover-0.0.1-SNAPSHOT.jar
```

The Managed Identity must be assigned a **Data Access Policy** on each AMR
cache (typically `Data Owner` or `Data Contributor`). With the bundled
Terraform, pass the MI's principal object ID to `entra_principal_object_id`
in `terraform.tfvars` instead of your user object ID.

**Important:** `AuthXManager.start()` blocks until the first token is
acquired. If the Managed Identity endpoint (IMDS at `169.254.169.254`) is
unreachable, the identity is not assigned to the workload, or the identity
has no Data Access Policy on the AMR cache, the application will fail to
start. This is intentional and lets you catch misconfiguration before serving
traffic.

### On AKS with Workload Identity (the demo's primary target)

The Cilium FQDN policy used by scenario 2 only works in-cluster, so all
scenarios are designed to run against an AKS deployment. The two helper
scripts in `scripts/` automate everything:

```bash
# Required env vars
export ACR_NAME=myacr AKS_NAME=myaks AKS_RG=myaks-rg

# 1. UAMI + workload-identity federation + AMR access policy + ACR attach
eval "$(./scripts/aks-bootstrap.sh)"

# 2. az acr build the image + render & apply manifests + wait LB IP
eval "$(./scripts/aks-deploy.sh)"

# 3. Hit the LB
curl -s $APP_URL/actuator/health | jq
```

The pod runs with `SPRING_PROFILES_ACTIVE=prod` only. `DefaultAzureCredential`
picks up the workload-identity env vars (`AZURE_CLIENT_ID`,
`AZURE_TENANT_ID`, `AZURE_FEDERATED_TOKEN_FILE`, `AZURE_AUTHORITY_HOST`)
that the mutating webhook injects when the pod carries the
`azure.workload.identity/use: "true"` label, so there is no need to
activate the `azure` Spring profile in-cluster.

Manifests live in [`deploy/k8s/`](deploy/k8s/); the Dockerfile in
[`deploy/Dockerfile`](deploy/Dockerfile) uses the Microsoft Build of OpenJDK
(`mcr.microsoft.com/openjdk/jdk:21-ubuntu` builder + `21-distroless`
runtime, non-root).

## REST endpoints

The app exposes a small **user session store** as its product API, plus a
few operational and test-only endpoints.

| Bucket  | Method | Path                                              | Purpose                                                                                                                          |
|---------|--------|---------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------|
| Product | POST   | `/sessions`                                       | Create a session. Body: `{"userId":"...","attributes":{"k":"v"}}`. Returns 201 + `Location: /sessions/{sid}` and the Session JSON. 400 on blank `userId` or null/non-string attribute values. `userId` is NOT unique — multiple sessions per user are allowed. |
| Product | GET    | `/sessions/{sid}`                                 | Read the session JSON. Slides TTL atomically via `GETEX EX 1800` (no JSON rewrite, `lastSeenAt` not updated on read). 404 if missing. |
| Product | PATCH  | `/sessions/{sid}`                                 | Shallow-merge `attributes`, update `lastSeenAt`, reset TTL via `SET EX 1800` (read-modify-write, last-writer-wins). 400 on null/non-string values; 404 if missing. |
| Product | DELETE | `/sessions/{sid}`                                 | Delete (idempotent, always 204).                                                                                                  |
| Ops     | GET    | `/status`                                         | Active endpoint + per-endpoint health/CB + last switch event.                                                                    |
| Ops     | GET    | `/events?since=<iso8601>&type=&limit=N`           | Recent `DatabaseSwitchEvent` from an in-memory ring buffer (cap 200) — used by scripts.            |
| Ops     | PUT    | `/redis/active-endpoint`                          | Force a switch to `primary` or `secondary`. Body: `{"endpoint":"secondary"}`. Returns `{"requested","from","to"}` (404 unknown name). |
| Ops     | GET    | `/actuator/health`                                | Includes per-endpoint detail map.                                                                                                |
| Test    | GET    | `/admin/pinned/{endpoint}/sessions/{sid}`         | **Test-only**, `@Profile("!prod")`. Reads the session from a specific endpoint, bypassing `MultiDbClient` routing. Used by Scenario 1 for round-trip assertions. |

Errors are returned as JSON via a global `@RestControllerAdvice`:

```json
{ "errorCode": "NOT_FOUND", "message": "...", "timestamp": "..." }
```

Status mapping: missing session / unknown endpoint → 404, bad input → 400,
Redis circuit-breaker / no healthy database / connection / command timeout → 503,
unexpected → 500.

## Testing procedures

The full demo can be exercised end-to-end against real AMR. Work through
the steps in order — each step builds on the previous one.

### Step 0 — Provision infra and deploy the app to AKS

Prerequisites (one-time per cluster):

- An AKS cluster with OIDC issuer + Workload Identity + Cilium dataplane +
  Advanced Container Networking Services (ACNS) **security** enabled:

  ```bash
  az aks update -g $AKS_RG -n $AKS_NAME \
    --enable-oidc-issuer \
    --enable-workload-identity \
    --network-dataplane cilium \
    --enable-acns
  ```

- An ACR you can `az acr build` against.

Run:

```bash
# 1. Provision both caches + active geo-replication (one apply)
cd infra
cp terraform.tfvars.example terraform.tfvars
# Edit terraform.tfvars: set name_prefix + entra_principal_object_id
# (you can set this to any throwaway value — the AKS bootstrap creates a
# UAMI and assigns the real data-plane access policy)
terraform init
terraform apply        # ~25-40 min for two AMR caches + geo-rep link
cd ..

# 2. Wire ACR + UAMI + workload identity federation + AMR access policies.
#    Reads terraform output for AMR; idempotent.
export ACR_NAME=myacr AKS_NAME=myaks AKS_RG=myaks-rg
eval "$(./scripts/aks-bootstrap.sh)"     # exports UAMI_CLIENT_ID etc.

# 3. Build the image in ACR and deploy to the cluster.
eval "$(./scripts/aks-deploy.sh)"        # exports APP_URL=http://<lb-ip>

# 4. Quick sanity check
curl -s $APP_URL/actuator/health | jq
```

The app pods run with `SPRING_PROFILES_ACTIVE=prod` and pick up the UAMI
automatically via `DefaultAzureCredential` (which honours the
`AZURE_*` env vars injected by the workload-identity webhook). No `azure`
Spring profile is needed in-pod.

To stream the app logs while running scenarios:

```bash
kubectl logs -n amr-demo -l app=amr-geo-failover -f
```

### Step 1 — Smoke test the app

```bash
# Both endpoints UP?
curl -s $APP_URL/actuator/health | jq

# Which endpoint is active, and what is the last switch event?
curl -s $APP_URL/status | jq

# Round-trip a session
SID=$(curl -s -X POST $APP_URL/sessions \
       -H 'Content-Type: application/json' \
       -d '{"userId":"alice","attributes":{"theme":"dark"}}' \
     | jq -r .sessionId)
curl -s $APP_URL/sessions/$SID | jq
curl -s -X PATCH $APP_URL/sessions/$SID \
     -H 'Content-Type: application/json' \
     -d '{"attributes":{"theme":"light","cart":"3"}}' | jq
curl -X DELETE $APP_URL/sessions/$SID -i
```

In the pod logs you should see one `INCR demo:counter = N (active=...)`
line per second from `BackgroundWriter`.

### Step 2 — Scenario 1: manual failover (no Azure changes)

Proves the `setActiveDatabase()` path AND that traffic actually follows
the switch — by creating a session on primary, switching to secondary, and
reading it back through `/admin/pinned/secondary/sessions/{sid}` (which
bypasses the failback race). Pure PASS/FAIL.

```bash
./scripts/scenario-1-manual-failover.sh
```

What to look for in the **pod logs**:

```
WARN  SwitchEventLogger - DatabaseSwitchEvent reason=FORCED from=...primary to=...secondary
... BackgroundWriter logs now show active=...secondary...
INFO  SwitchEventLogger - DatabaseSwitchEvent reason=FAILBACK from=...secondary to=...primary
```

Expected runtime: ~15 s. No Azure changes.

### Step 3 — Scenario 2: organic failover via Cilium FQDN policy

Applies a `CiliumNetworkPolicy` that denies egress from the app pods to the
**primary** AMR FQDN. Cilium drops packets in the pod's eBPF datapath for
both new and existing TCP flows — which is what a real outage looks like to
the client. Once `MultiDbClient` fails over to secondary, the policy is
removed and the app fails back to primary.

```bash
# APP_URL + AMR_PRIMARY_HOST + APP_NAMESPACE come from aks-bootstrap.sh +
# aks-deploy.sh. Run them once and eval their output.
./scripts/scenario-2-organic-failover.sh

# Override the timeouts if your region is slow:
FAILOVER_TIMEOUT=120 FAILBACK_TIMEOUT=240 HOLD_S=15 \
  ./scripts/scenario-2-organic-failover.sh
```

What to look for in the **pod logs** (`kubectl logs -n amr-demo -l app=amr-geo-failover -f`):

```
WARN  ... CircuitBreaker primary OPENED ...
WARN  SwitchEventLogger - DatabaseSwitchEvent reason=CIRCUIT_BREAKER from=...primary to=...secondary
... BackgroundWriter keeps INCR'ing against secondary ...
INFO  SwitchEventLogger - DatabaseSwitchEvent reason=FAILBACK from=...secondary to=...primary
```

Why not `az redisenterprise update --public-network-access Disabled`? Because
that's a control-plane firewall change — it blocks NEW handshakes at the
Azure edge but leaves warm Jedis sockets alive, so `PingStrategy` never
trips and the demo misses the failover it's trying to showcase. The Cilium
FQDN deny drops packets inside the pod's network namespace and severs both
new and existing flows. See [`deploy/k8s/policies/block-primary.yaml`](deploy/k8s/policies/block-primary.yaml)
for the policy and ADR/design comments inline.

Cleanup: the script registers an `EXIT` trap that always deletes the
CiliumNetworkPolicy, so it's safe to Ctrl-C.

Expected runtime: 30-60 s.

### Step 4 — Scenario 3: both endpoints down

Verifies the `DatabaseSwitchEvent` error path + the `GlobalExceptionHandler`'s
503 response when nothing is healthy.

```bash
./scripts/scenario-3-all-endpoints-down.sh
```

What to look for in the **pod logs**:

```
WARN  SwitchEventLogger - DatabaseSwitchEvent (no healthy database) ...
ERROR ... RedisNoHealthyDatabaseException ...
... GlobalExceptionHandler returning HTTP 503 errorCode=REDIS_UNAVAILABLE ...
```

What to look for in the **script output**:

- `/actuator/health` flips to `DOWN`.
- `POST /sessions` returns HTTP 503 with `{ "errorCode": "REDIS_UNAVAILABLE", ... }`.
- After re-enable, health returns to `UP`.

Cleanup: trap re-enables both caches on any exit.

Expected runtime: 3-6 min.

### Step 5 — Unit tests (purely local)

The bundled tests cover controller behaviour (mocked service), property
binding, Entra auth profile selection, and switch-event logging:

```bash
mvn -B verify
# → Tests run: 41, Failures: 0, Errors: 0, Skipped: 0
```

Real end-to-end verification is performed by scenarios 1-3 above.

### Step 6 — Destroy infra

When you're done:

```bash
cd infra && terraform destroy
```

This deletes both caches (which auto-removes them from the geo-replication
group) and the resource group.

## Configuration

All Jedis defaults are tuned aggressively for demo visibility. The
in-code defaults are noted in `application.yml`. Restore production values
by overriding the relevant properties (e.g. `app.redis.failback-check-interval=120s`).
The demo defaults to `health-strategy: ping`. A `LagAwareStrategy` is also
included; it queries the Redis Enterprise REST API for replication lag, but
Azure Managed Redis does not expose this API to customers today, so
`lag-aware` is only usable against bring-your-own Redis Enterprise clusters.
