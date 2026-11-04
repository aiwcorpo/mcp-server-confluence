# Changelog

Format: SemVer versions, newest first. Until the first public release the version is `0.1.0-SNAPSHOT`.

## Unreleased

- Starter 3.0.0-beta-14, contracts 6.3.2.
- 17 tools (was 4): lists paged with a cursor (`list_pages`, `list_child_pages`, `list_spaces`), `get_page_by_title`,
  `get_page_version`, comments, labels, text attachments, `update_page` with `expectedVersion`, `add_comment`, `add_label`,
  `delete_page` (`DESTRUCTIVE`). `search_pages` has `title`, `label` and `orderBy` filters and a cursor, and returns `PagedResult`.
- Version probe (`server-information`, application manifest): 8.5 to 10.x supported, other versions read-only.
- Lists are filled up after the policy filter (`PageFiller`); `list_labels` reports `truncated`; comment window of 10 comments of 4000 characters.
- The release workflow reads packages with `GITHUB_TOKEN` and `packages: read`, without a personal token.
- Write flag only for an unreadable version; CI images pinned by digest.
- A check that a child id belongs to the evaluated page.
- Probe fixtures for 8.5, 9.2, 10.2, 11.0 and 7.19; Apache-2.0, `SECURITY.md`, `CONTRIBUTING.md`, release workflow (ghcr.io image, SBOM).
