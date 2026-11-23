# Decision Record: Minecraft Workspace Foundation

**Status: Proposed**  
**Decision scope:** Foundation work for the Minecraft workspace and launcher. This is a design proposal only; implementation has not been authorized.

## What this is

A proposed foundation plan for making the existing Minecraft workspace reliable against a deliberately limited initial matrix:

- Minecraft 1.21.1
- Java 21
- Forge, Fabric, and Paper
- NeoForge and Quilt are explicitly deferred.

The first work phase is about completing and making safe the existing platform contract, not adding new gameplay capabilities. The audited code currently contains a Python MCP workspace, Forge and Fabric server bridges, a Paper bridge, and a standalone Rust launcher/client bridge.

## Why it exists

The audit found that the code and project notes disagree about what is supported and installed:

- The Python workspace registers 46 MCP tools. The Paper bridge registers seven HTTP contexts (`/api/status`, `/api/command`, `/api/chat`, `/api/players`, `/api/chat/history`, `/api/notifications/feed`, `/api/notifications/clear`), while Forge and Fabric expose broader server-side APIs. That means the shared SDK can offer tools whose endpoints do not exist on Paper.
- Paper's Gradle dependency is Paper API 1.20.4, whereas the selected target is 1.21.1. No `plugin.yml` or `paper-plugin.yml` source descriptor was found in the Paper project; no built Paper JAR was available in the inspected `build/libs` directory to verify packaging or loading.
- The Rust daemon binds to `0.0.0.0`, uses permissive CORS, and the audited router does not show authentication on its instance/mod/launch control endpoints. Server bridges also bind all interfaces and log their API keys.
- `InstanceManager::create` embeds Fabric and Forge 1.21.1 bridges, but its fallback writes the Fabric 1.21.1 JAR for unsupported combinations. `Downloader::ensure_bridge_mod` has cache/GitHub retrieval and a Fabric fallback, but its use from the installation path was not established by the audited call sites. Current code also maps Quilt to Fabric and NeoForge to Forge; these mappings are not compatibility proof and are out of this initial target.
- DevNotes describe broader or identical platform support and deployment verification than the currently inspected implementation demonstrates. The standalone launcher and server-side MCP workspace are distinct operational modes and should be documented/tested separately.
- `cargo test --locked --offline` completed successfully but reported zero tests; it is not evidence of loader/API compatibility.

The goal of the foundation phase is therefore to make the chosen matrix truthful, safe, and verifiable before expanding the feature surface.

## What is NOT included

- Implementing any code or changing project files under this proposed decision.
- Adding new Minecraft gameplay tools, AI behavior, or world-interaction features.
- Supporting NeoForge, Quilt, additional Minecraft versions, or older versions in the initial matrix.
- Replacing the current architecture or selecting a new framework, protocol, or authentication provider without a later explicit decision.
- Releasing or deploying artifacts before local and live verification.
- Treating current DevNotes claims as verified runtime behavior when source/build evidence is missing.

## Proposed phased plan

### Phase 0 — Freeze and document the contract

- Keep the initial matrix at Minecraft 1.21.1 / Java 21 / Forge + Fabric + Paper.
- Inventory server mode and client/launcher mode separately; define which operations each mode and loader actually supports.
- Create one versioned API/capability contract from the real handlers and SDK calls. A capability must be advertised only if that bridge implements it.
- Reconcile README/DevNotes/manifest against audited source; mark unverified deployment statements as historical or unverified rather than current guarantees.

**Acceptance:** a reviewed matrix lists each loader, mode, implemented capability set, build artifact, and current verification evidence; SDK does not imply unsupported capabilities.

### Phase 1 — Secure the control plane

- Bind local-only services to loopback by default; require an explicit configuration to expose them beyond the host.
- Add authentication and origin controls to the launcher daemon's state-changing and RPC endpoints; remove permissive CORS unless the intended client origin is explicitly configured.
- Stop logging API keys and session credentials; rotate any values that may have appeared in retained logs before external exposure.
- Validate all filesystem names/paths and request fields at the daemon boundary, and apply bounded request sizes/timeouts.
- For server bridges, document network exposure and use loopback/private binding by default where compatible with the chosen usage.

**Acceptance:** unauthenticated external requests cannot list/manage/launch/delete instances or invoke game RPC; credentials are absent from normal logs; intended local client flow still works.

### Phase 2 — Make installation deterministic

- Replace fallback-to-wrong-JAR behavior with explicit compatibility errors.
- Route instance creation/install through one resolver that verifies loader, Minecraft version, bridge artifact identity, and artifact integrity before copying.
- Limit supported loader/version tuples to artifacts actually built and verified. Keep NeoForge/Quilt unavailable until explicitly added to the matrix.
- Make download/cache failure visible and actionable; do not report success after silently substituting an incompatible bridge.
- Add Paper's correct 1.21.1 build target and a valid plugin descriptor, then verify the produced JAR's metadata and server startup.

**Acceptance:** for each supported tuple, the resolver installs the intended artifact and its metadata matches the tuple; unsupported tuples fail without leaving a misleading mod JAR behind.

### Phase 3 — Align SDK, capabilities, and bridge handlers

- Generate or validate SDK tool exposure against the shared capability contract, so Paper-only limitations are visible rather than deferred to endpoint failures.
- Bring Paper into parity only for capabilities needed by the agreed common contract; keep platform-specific operations explicitly gated where APIs differ.
- Ensure all world/server API access from HTTP handlers is marshalled onto the game server thread; use bounded asynchronous request handling and clear timeout/error responses.
- Establish consistent JSON/error/auth behavior and API-version negotiation across Forge, Fabric, Paper, and the launcher client bridge.

**Acceptance:** contract tests compare advertised capabilities to registered handlers for every supported bridge; cross-platform common operations return equivalent documented schemas and errors.

### Phase 4 — Verification and release gate

- Add unit tests for contract parsing, capability negotiation, loader/version selection, path validation, and authentication boundaries.
- Add bridge/SDK contract tests, including negative tests for unsupported calls and loader/version combinations.
- Add reproducible build checks for Forge, Fabric, Paper, launcher, and Python package. Run real server/client smoke tests for each release tuple.
- Record exact tested server/client versions and results in DevNotes; distinguish automated build checks from live server verification.

**Acceptance:** no release is called supported until its builds, contract tests, and required live smoke checks pass; test evidence names the exact matrix tuple.

### Later — Extend loaders or feature surface

Only after the initial matrix is stable, decide separately whether to add NeoForge, Quilt, more Minecraft versions, additional launcher platforms, or gameplay tools. Each addition needs an artifact, compatibility declaration, capability contract, and verification path before it is marked supported.

## Open questions

1. Should daemon and server-bridge binding be loopback-only by default, with remote access supported only via an explicit authenticated reverse proxy/tunnel? **Proposed default:** yes.
2. Which server-side capabilities are required to call the initial Forge/Fabric/Paper contract complete? Audit identifies a 46-tool SDK and differing endpoint sets, but this record deliberately does not assume every SDK tool is needed on every server loader.
3. Should the client/launcher mode be included in the same initial 1.21.1/Java 21 matrix, or should the first acceptance gate cover only server-side Forge/Fabric/Paper? **Open; audit docs mention client mode, but current launcher artifact path is not proven end-to-end.**
4. Which exact Paper server distribution/build should be the verification target (Paper only, or also Spigot)? The requested matrix confirms Paper, not Spigot.
5. Where should this proposed record be placed and what naming convention should this workspace use for ADRs? No existing ADR file was found in this workspace during the current inventory, and the repository's Git metadata is not readable as a Git worktree in this environment.

## Audit boundaries and evidence notes

- Source inspection only; no existing project code or documentation was modified. This standalone proposed record was created as the requested draft.
- The workspace directory reports a `.git` entry, but Git commands fail with “not a git repository” / filesystem-boundary errors, so branch and working-tree state could not be established.
- Cargo's offline test command returned success but ran zero tests.
- The Paper project had no source plugin descriptor in the inspected resource paths and no built JAR in `build/libs` to inspect.
- These points are audit observations, not proof of behavior on a live Minecraft server.
