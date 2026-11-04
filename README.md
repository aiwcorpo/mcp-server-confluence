# mcp-confluence

MCP server for Confluence Data Center. It is one of the native servers of the MCP platform and runs only behind the
platform gateway: it accepts mutual TLS traffic carrying the gateway's internal JWT and calls Confluence (REST API v1,
`/rest/api/...`) with the user's personal access token (PAT), which the gateway attaches to every request. Without a valid
JWT it answers 401; there is no anonymous mode.

The server does not log users in, decide who may call which tool, or ask for confirmation of destructive actions. The
gateway does that. The server provides the tools, the Confluence adapter, error mapping and the audit trail the starter writes.

Built on `mcp-platform-starter` 3.0.0 (Java 25, Spring Boot 4.1.0, Spring AI 2.0.0, `mcp-contracts` 6.3.2).

## Supported Confluence versions

| Confluence Data Center | State | Effect |
| --- | --- | --- |
| 8.5 to 10.x (including 9.2 LTS and 10.2 LTS) | `supported` | all tools, writes on |
| older than 8.5 | `degraded` | reads only; writes fail with `upstream_unavailable` |
| 11.0 and newer | `degraded` | reads only until the version is tested |
| version unreadable or no answer from the probe | `unavailable` | reads only; writes open only with `MCP_CONFLUENCE_WRITES_ON_UNKNOWN_VERSION=true`, and only after the first probe result |

Confluence Server and Confluence Cloud are not supported. The REST v1 paths the server uses are the same across 8.5 to 10.x,
so there is one adapter and the version only decides whether writes are allowed. The tests use probe fixtures for 8.5, 9.2,
10.2, 11.0 and 7.19 written from the vendor documentation, not recorded from a live instance.

At startup and then every `aiwcorpo.mcp.platform.capability.interval` the probe reads the version without credentials from
`GET /rest/api/server-information`, and from `GET /rest/applinks/1.0/manifest` when the first path sits behind a login. The
state is in the log (`backend capability: probe=confluence state=...`), in the metric `mcp_backend_capability` and in the
`backendCapability` detail of `/actuator/health`. The flag has no effect for a version the probe read and does not know
(older than 8.5, 11.x and newer): those stay read-only.

## Tools

17 tools: 12 `READ_ONLY`, 4 `UPDATE` and 1 `DESTRUCTIVE` (`delete_page`, which needs the confirmation code the gateway checks).
A client sees them as `confluence__<name>`. The generated table with each tool's title, risk, action and required group is in
`docs/reference/tools.md` of the `mcp-docs` repository (link below). It is not repeated here.

Reads cover search, pages, child pages, spaces, page versions, comments, labels and text attachments. Writes create and
update pages (`update_page` takes `expectedVersion` and refuses a stale one), add comments and labels. Search queries are
built by the server from structured arguments; no tool accepts raw CQL.

## Build and test

JDK 25 is required (`JAVA_HOME` must point to it; an older `java` in `PATH` fails with `UnsupportedClassVersionError`).

```bash
export JAVA_HOME=/path/to/jdk-25
./mvnw verify      # build and tests, jar in mcp-app/target/mcp-confluence.jar
make ci            # ci/run.sh verify: source hygiene, mvnw clean verify, check that every test module ran
```

The tests need neither Docker nor a Confluence. `TestGateway` from `mcp-platform-test` publishes a JWKS made from the contract
test keys and signs internal JWTs, and `StubBackend` answers like Confluence DC on the endpoints the server uses. `make ci`
also leaves the manifest in `mcp-app/target/mcp-manifest.json`; register it in the gateway and approve new `UPDATE` and
`DESTRUCTIVE` tools there. `ci/run.sh image` builds the container image (`Dockerfile`, `eclipse-temurin:25-jre`, port 8443)
from `main` only and needs a Docker daemon. A `v*` tag runs `.github/workflows/release.yml`, which builds
`ghcr.io/aiwcorpo/mcp-server-confluence` and attaches the manifest and an SBOM to the GitHub release.

## Configuration

The base configuration (`mcp-app/src/main/resources/application.yml`) is the production one. Outside the `dev` profile the
server does not start without these:

| Variable | Meaning |
| --- | --- |
| `MCP_TLS_KEYSTORE`, `MCP_TLS_TRUSTSTORE`, `MCP_TLS_STORE_PASSWORD` | PKCS12 server certificate and the internal CA |
| `MCP_GATEWAY_JWKS_URI` | JWKS of the gateway's internal JWT, `https` only |
| `MCP_CONNECT_URL` | portal page where a user connects an account; returned in `credential_required` |
| `MCP_CONFLUENCE_URL` | base address of Confluence without `/rest/api`, with the context path if there is one; `https` only |
| `MCP_POLICY_URL`, `MCP_POLICY_KEY_ID`, `MCP_POLICY_PUBLIC_KEY_FILE`, `MCP_POLICY_STATE_DIR` | signed policy bundle and the directory that keeps the last accepted revision |
| `MCP_INTERNAL_URL`, `MCP_ACCEPTED_CREDENTIAL_TYPES` | server address and accepted credential types for the manifest (`user-secret`) |

Also used: `MCP_CONFLUENCE_SSL_BUNDLE` (SSL bundle for a Confluence with an internal CA), `MCP_CURSOR_KEY` (Base64, at least 32
bytes, the same on every instance), `MCP_REDIS_*` (one-time `jti` store, ACL user `mcp-confluence`) and `MCP_PORT`/`MCP_BIND`
(default `8443`, `0.0.0.0`). `MCP_CONFLUENCE_ALLOW_PLAIN_HTTP=true` allows `http` and exists only for the mocks of the development
stack. The full list with defaults is in `docs/reference/env-servers.md` of `mcp-docs`.

A PAT has no scopes and inherits every right of the account. The account needs View on the spaces it reads, Add/Edit pages for
`create_page`, `update_page` and `add_label`, Add comments for `add_comment` and Delete pages for `delete_page`. Use one account per
person. The platform policy can only narrow what the account is allowed to do.

## Documentation

Installation, operation, upgrades, the checks to run on a first real instance and the tool reference live in
[mcp-docs](https://github.com/aiwcorpo/mcp-docs):

- `docs/reference/tools.md`: tools with risk and required group
- `docs/reference/env-servers.md`: environment variables of every server
- `docs/operator/real-systems.md`: supported product versions, read-only checks on a real instance

See also `CONTRIBUTING.md`, `SECURITY.md` and `CHANGELOG.md`. Licensed under Apache-2.0.
