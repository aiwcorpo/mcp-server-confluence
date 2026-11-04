# Changelog

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
