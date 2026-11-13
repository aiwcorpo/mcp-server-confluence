# mcp-confluence

Part of [MCP Platform](https://github.com/aiwcorpo/mcp-platform), a self-hosted MCP gateway for companies. Documentation: [mcp.aiwcorpo.pl](https://mcp.aiwcorpo.pl/).

MCP server for Confluence Data Center. AI clients such as Claude Code reach it through the gateway of the aiwcorpo MCP
platform; the server calls Confluence with each user's own personal access token.

## Quick start

```bash
cd deploy
# fill in MCP_GATEWAY_JWKS_URI, MCP_CONNECT_URL and MCP_CONFLUENCE_URL in mcp-confluence.env
docker compose up -d
docker compose logs mcp-confluence | grep event=tls_certificate
```

Then the platform operator adds `https://<this host>:8443/mcp` in the portal. Full steps: [deploy/README.md](deploy/README.md).

## Tools

24 tools, shown to a client as `confluence__<name>`. Reading needs the group `mcp-confluence-read`, writing
`mcp-confluence-write`. One tool deletes: `delete_page` moves a page to the trash and needs the group
`mcp-confluence-destructive` and a confirmation code from the Authenticator, marked in the Delete column.

| Area | Read | Write | Delete |
| --- | --- | --- | --- |
| Search | `search_pages`, `search_content` | | |
| Pages | `get_page`, `get_page_by_title`, `list_pages`, `list_child_pages`, `get_space_page_tree`, `get_page_restrictions` | `create_page`, `update_page`, `move_page` | `delete_page` |
| Versions | `list_page_versions`, `get_page_version`, `diff_page_versions` | | |
| Comments | `list_comments` | `add_comment`, `reply_to_comment` | |
| Labels | `list_labels` | `add_label` | |
| Attachments | `list_attachments`, `read_attachment` | | |
| Spaces | `list_spaces`, `get_space` | | |

`search_pages` builds its query from structured arguments (words, title, label) in one space. `search_content` takes a CQL
condition of up to 500 characters; it needs a space the policy allows, and the server adds the space and `type = page` around
your condition, so it cannot reach other spaces. `update_page` takes `expectedVersion` and refuses a stale one. `move_page` works
inside one space and needs write rights on the page, the new parent and the place the page leaves. `read_attachment` returns text
files only.

Users connect with a personal access token (credential type `user-secret`). A token has the rights of its account: View on the
spaces it reads, Add and Edit pages for `create_page`, `update_page`, `move_page` and `add_label`, Add comments for comments,
Delete pages for `delete_page`. Writes are allowed only inside spaces and page trees that the platform operator maps to the
user's group (scope mapping). Spaces labelled `restricted` are closed to everyone.

## Supported Confluence versions

Confluence Data Center 8.5 to 10.x (including the 9.2 and 10.2 LTS), REST API v1. Confluence Server and Cloud are not supported.

The server reads the version at start and then at a fixed interval:

| State | When | Effect |
| --- | --- | --- |
| `supported` | Data Center 8.5 to 10.x | all tools |
| `degraded` | older than 8.5, or 11.0 and newer | reads only, writes fail with `upstream_unavailable` |
| `unavailable` | the version is unreadable | reads only; writes open with `MCP_CONFLUENCE_WRITES_ON_UNKNOWN_VERSION=true` |

The server has not been tested against a live Confluence. The tests use responses written from the vendor documentation. Run
read-only calls on your instance first.

## Configuration

Required, in `deploy/mcp-confluence.env`:

| Variable | Description |
| --- | --- |
| `MCP_GATEWAY_JWKS_URI` | key set of the gateway: `https://<platform>/.well-known/mcp-jwks.json` |
| `MCP_CONNECT_URL` | portal page where a user connects Confluence: `https://<platform>/connections?system={system}` |
| `MCP_CONFLUENCE_URL` | address of Confluence without `/rest/api`, with the context path if there is one, `https` only |

`MCP_ACCEPTED_CREDENTIAL_TYPES` is preset to `user-secret`. Optional settings (own certificate, Redis for several instances,
trust store for a Confluence with a company CA) are commented in the same file.

## Build

```bash
export JAVA_HOME=<path to JDK 25>
./mvnw verify
```

The jar is `mcp-app/target/mcp-confluence.jar`; `docker build -t mcp-confluence:dev .` builds the image. Tests need neither
Docker nor Confluence. Working on the code: [CONTRIBUTING.md](CONTRIBUTING.md).

## More

- [deploy/README.md](deploy/README.md): installation, troubleshooting, upgrade from 1.x
- [CHANGELOG.md](CHANGELOG.md), [SECURITY.md](SECURITY.md)

Apache License 2.0.
