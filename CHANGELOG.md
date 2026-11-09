# Changelog

## 2.0.0

Major release: the policies of the server live in this repository. Built on `mcp-platform-starter` 4.0.0 and `mcp-contracts` 7.0.0.

- CC-249, the gateway connects to the server by its address: the server needs no certificate from the internal CA, no mTLS and no policy data
  address. The server serves `GET /.well-known/mcp-manifest` and takes `POST /.well-known/mcp-policy-data`; the gateway pulls the manifest and
  pushes the policy data, so the registration of the manifest from the pipeline is gone. `MCP_GATEWAY_JWKS_URI` is the public
  `https://<domain>/.well-known/mcp-jwks.json`. TLS is the author's own certificate (`MCP_TLS_CERT` and `MCP_TLS_KEY`, or `MCP_TLS_KEYSTORE`), a self-signed
  one generated once into `MCP_STATE_DIR` (its fingerprint is in the log at start), or `MCP_PLAIN_HTTP=true`. The one-time `jti` is kept in memory unless
  `MCP_REDIS_HOST` is set; the cursor key (`MCP_PAGING_CURSOR_KEY`) is kept in the state directory. Removed from the configuration and from `deploy/`:
  `MCP_GATEWAY_POLICY_DATA_URL`, `MCP_GATEWAY_TLS_NAME`, `MCP_INTERNAL_URL`, `MCP_TLS_TRUSTSTORE`, the Redis service of `compose.yaml`; `certs/` is optional.
  `deploy/README.md` has the new procedure (the author starts the server, the operator enters its address in the portal and approves the connection after
  comparing the fingerprint from the log).
  `MCP_CURSOR_KEY` is now `MCP_PAGING_CURSOR_KEY`, the name the starter reads.
- New tools `list_page_versions` (author, time and comment of each stored version, newest first, 20 per call) and `diff_page_versions` (unified line
  diff of the plain text of two versions, computed in the server, capped at 2000 lines per body and 50000 characters of diff). Both read the page under
  the same policy as `get_page`.
- New tool `search_content`: CQL written by the caller, confined to one space the policy allows (the condition is scanned, wrapped in parentheses and
  followed by the server's `space in (...) AND type = page`), at most 500 characters, paged with the signed cursor, with an excerpt as untrusted
  text. Hits are looked up by id and judged by the space they are in now and by the policy, like those of `search_pages`.
- New tool `get_space_page_tree`: id, title, parent id and depth of the pages of a space in tree order, read in windows of 50 and bounded to 200 pages
  and `maxDepth` levels (default 5, at most 10), with `truncated` and the codes `page_limit` and `depth_limit` in `omitted`. Every page is judged by
  the policy; the parent id of a page that is not shown is not returned.
- New tool `get_page_restrictions`: the users (display names) and groups the view and the edit of a page are restricted to on that page, at most 100
  of each per operation; no e-mail address, user name or user key is read from Confluence's answer.
- New tools `reply_to_comment` (a reply under a comment of the same page, same rules as `add_comment`) and `move_page` (a page and its subtree under another
  parent of the same space; the policy judges the page, the new parent and the place the page leaves, and a page already under that parent is not written
  again). `resolve_comment` and cross-space moves are not offered: the documented Data Center REST API has no endpoint for either.
- Cedar policies in `mcp-app/src/main/resources/mcp/policies/` (schema in the namespace `Confluence`, `resource-types.json`,
  `read.cedar`, `update.cedar`), carried in the manifest with `policiesHash`. They replace `policies/confluence/` of the `mcp-policies`
  repository; the rules are the same, except what the platform now generates for every server: the call rules by group, the forbid
  for spaces labelled `restricted` (from `Confluence::Space.labels`, for the space and for its pages) and the rule for the service
  account.
- A deployed server whose policies or declarations changed waits for approval in the gateway: it refuses calls (`policies_not_approved`,
  `policies_differ`) until an administrator approves the new `policiesHash`. Register the new manifest and approve before the first call.
- The resolvers derive the scopes that the entity builder used to derive: a space carries `Confluence::Space/<key>`, a page carries
  the scope of its space, `Confluence::Page/<id>` of every ancestor page Confluence reports and its own, so a page tree grant is
  decided with `principal in resource.scopes`. The space provides the grant probe (`probe(id)`) and the page `scopeTypes()` with
  `probeOutsideScopes(id)`, so a caller without a grant is refused before Confluence is asked. Id arguments take their pattern from the
  declared types (`@ArgumentLimits(resourceType = ...)`).
- Behaviour change: `Confluence::Space` is declared `earlyScopeCheck`, so `create_page` in a space the caller holds no grant on is
  refused by the scope check as `access_denied` before any lookup; it used to be `not_found_or_denied` from the server. What a caller may
  do is the same: creating a page always needed a grant on the space, never only on a page tree.
- Removed: `MCP_POLICY_URL`, `MCP_POLICY_KEY_ID`, `MCP_POLICY_PUBLIC_KEY_FILE`, `MCP_POLICY_STATE_DIR`, the signed policy bundle and the
  `StateDirectory` of the unit. Added: `MCP_GATEWAY_POLICY_DATA_URL` (and `MCP_GATEWAY_TLS_NAME` when the host in it is not a name of the
  gateway certificate). The server connects to the gateway for the policy data with its own certificate, which now needs the usage
  `clientAuth` next to `serverAuth` (CN `mcp-confluence`, SAN `mcp-confluence.internal`).
- `deploy/` has `compose.yaml`, `mcp-confluence.env` and `certs/` next to the systemd files, and the procedure that adds the system
  `confluence` in the broker and the server `mcp-confluence` in the gateway without restarting anything.
- `.run/` has IntelliJ configurations for the server and for the `LocalGateway` of `mcp-platform-test`, with the policies directory and a scope mapping.

## 1.0.0

First public release. MCP server for Confluence Data Center 8.5 to 10.x, run behind the platform gateway. Built on
`mcp-platform-starter` 3.0.0 and `mcp-contracts` 6.3.2.

- 17 tools. Reads: `search_pages`, `list_pages`, `list_child_pages`, `list_spaces`, `get_page`, `get_page_by_title`, `get_space`,
  `get_page_version`, `list_comments`, `list_labels`, `list_attachments`, `read_attachment` (text attachments). Changes:
  `create_page`, `update_page` (with `expectedVersion`), `add_comment`, `add_label`, and `delete_page` (`DESTRUCTIVE`).
- Lists return `PagedResult` with a cursor and are filled up after the policy filter (`PageFiller`); `search_pages` filters by
  `title`, `label` and `orderBy`. `list_labels` reports `truncated`; the comment window is 10 comments of 4000 characters.
- A version probe (`server-information`, application manifest) sets the state: 8.5 to 10.x is supported, other versions are
  read-only. The write flag opens writes only for an unreadable version. A child id is checked to belong to the evaluated page.
- Probe fixtures for 8.5, 9.2, 10.2, 11.0 and 7.19. Tool titles are in English.
- Apache-2.0 `LICENSE` and `NOTICE`, `SECURITY.md`, `CONTRIBUTING.md`; the release workflow builds the image for ghcr.io and
  attaches the manifest and SBOM to the GitHub Release.
