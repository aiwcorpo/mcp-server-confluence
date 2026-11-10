# Contributing

The server follows the contracts of `mcp-contracts` and the library `mcp-starter-lib`. A change that needs a different
contract (a resource type, a query field, a credential type) starts as a `proposed` entry in `CONTRACT_CHANGES.md` of
`mcp-contracts` and waits for approval.

## Setup

JDK 25, Linux or macOS with bash 3.2 or newer; Windows only through WSL2. The platform artifacts come from Maven Central, or
must already be in `~/.m2`. No container runtime and no Confluence are needed:
the tests run the whole server against a stub backend.

```bash
./mvnw -B verify     # the same command runs in CI
```

`./mvnw -B verify` must pass before a merge. A new tool needs the items of `docs/developer/tools.md` in `mcp-docs`: risk, title,
`@ResourceRef` for every id it names, a closed argument schema, a negative test, and its name in the tools table of the README.

## Policies

The Cedar policies of Confluence are in `mcp-app/src/main/resources/mcp/policies/` and go into the jar and the manifest:

| File | Content |
| --- | --- |
| `schema.cedarschema` | namespace `Confluence`: `Confluence::Space` (classification attribute `labels`) and `Confluence::Page` (parent: its space, plus the ancestor page ids); actions `read`, `update`, `delete`, `admin` |
| `resource-types.json` | the declarations of both types: `idPattern` (space key, page id), `scopePattern`, `earlyScopeCheck` for spaces, the classification and the parent |
| `read.cedar` | reading needs `mcp-confluence-read`; it follows the user's own rights in Confluence |
| `update.cedar` | changing a page, or creating one directly in a space, needs `mcp-confluence-write` and a scope; deleting a page needs `mcp-confluence-destructive` and a scope; a space is never deleted |

A grant on `Confluence::Space/<key>` covers the space and all its pages; a grant on `Confluence::Page/<id>` covers that page and
the tree below it, never the space itself. The resolvers give a page the scope of its space, of every ancestor page
(`ancestorIds`) and of itself, so a page moved out of a granted tree is judged by where it is now. `ConfluencePolicyDecisionsTest`
states the decisions; a change to any file here changes `policiesHash` in the manifest, and an administrator approves it before
the server decides anything.

The gateway pushes the scope mapping, the allowed AI clients and the emergency overrides to `POST /.well-known/mcp-policy-data`
after every change and every minute. A server without fresh data answers `tools/call` with `409 policy_data_stale`, and the
gateway pushes and repeats the call.

## Tool behavior

- `search_content` is the only tool that takes CQL from the caller. It needs a `space` the policy allows; the condition (at most
  500 characters) is scanned and wrapped in parentheses, and the server ANDs `space in (...)` and `type = page` outside it. Each
  hit is looked up by id and kept only if Confluence puts the page in that space now and the policy allows it. It uses
  `/rest/api/search`, not `content/search`, because only that endpoint returns the excerpt.
- `get_space_page_tree` returns at most 200 pages and 10 levels, with `truncated` and `omitted` when it stops short.
- `get_page_restrictions` names users by display name and groups by group name, never an e-mail address. When Confluence does not
  report the lists of an operation, the result says `restrictions_not_reported` instead of showing nobody.
- `diff_page_versions` computes the diff in the server from the plain text of both versions (Data Center has no diff endpoint): at
  most 4 diffs at a time, each limited to about a million line comparisons, `rate_limited` when busy. `list_page_versions` asks for
  versions one by one, 20 per call, because the public REST API has no version list.
- `move_page` is judged on three places (the page, the new parent, the place the page leaves), each with the write scope, so a
  grant on a page tree does not let a page out of that tree. The REST API has no cross-space move and no endpoint to resolve an
  inline comment, so there is no tool for either.

## Confluence versions

At start and then every `aiwcorpo.mcp.platform.capability.interval` the server reads `GET /rest/api/server-information` without
credentials, and `GET /rest/applinks/1.0/manifest` when the first path sits behind a login. The state shows in the log
(`backend capability: probe=confluence state=...`), in the metric `mcp_backend_capability` and in the `backendCapability` detail of
`/actuator/health`. The REST v1 paths are the same across 8.5 to 10.x, so one adapter serves all of them and the version only
decides whether writes are allowed. `MCP_CONFLUENCE_WRITES_ON_UNKNOWN_VERSION=true` has no effect for a version the probe read and
does not know (older than 8.5, 11.x). Probe fixtures for 7.19, 8.5, 9.2, 10.2 and 11.0 are written from the vendor documentation,
not recorded.

## Build details

`./mvnw verify` leaves the manifest in `mcp-app/target/mcp-manifest.json`; the running server serves it at
`/.well-known/mcp-manifest`, and an administrator approves every new or changed tool there. `TestGateway` from
`mcp-platform-test` publishes a JWKS and signs internal JWTs; `StubBackend` answers like Confluence DC on the endpoints the server
uses. The image (`Dockerfile`, `eclipse-temurin:25-jre`, port 8443) needs a Docker daemon. CI runs `./mvnw -B verify`; a `v*` tag
runs `.github/workflows/release.yml`, which builds `ghcr.io/aiwcorpo/mcp-server-confluence` and attaches the manifest and an SBOM
to the GitHub release. `JAVA_HOME` must point to JDK 25, or the build fails with `UnsupportedClassVersionError`.

## Running against a real Confluence

`.run/` has two IntelliJ configurations. Start `LocalGateway` (the gateway of `mcp-platform-test` on
`http://127.0.0.1:18500/mcp`, serving the JWKS on port 18445 and pushing policy data with the policies of this working tree and
the mapping in `.run/scope-mapping.json`), then `McpConfluence (dev, behind LocalGateway)` with `MCP_CONFLUENCE_URL` set to your
Confluence. Connect an MCP client to the gateway with `Authorization: Bearer <your PAT>`. Edit `.run/scope-mapping.json` and the
`--groups` of `LocalGateway` for your space keys and page trees, and restart `LocalGateway` after a change to the policies. The
configurations need the `4.0.0` starter artifacts in `~/.m2`. `MCP_CONFLUENCE_ALLOW_PLAIN_HTTP=true` allows `http` and exists for
the mocks of the development stack.

## Commits

Subject in English, imperative, up to 72 characters. The body describes the change, not the process. Tests go in the
same commit as the code they cover.

## License

Contributions are licensed under Apache-2.0 (see `LICENSE`).
