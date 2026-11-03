# Security policy

## Reporting a vulnerability

Report vulnerabilities privately through GitHub: Security tab of this repository, "Report a vulnerability"
(private vulnerability reporting). Do not open a public issue for a security problem.

Include the affected version (`pom.xml`), the tool involved, the Confluence release (the probe logs it as
`backend capability: probe=confluence`) and steps to reproduce. In scope: a tool result that contains a page, comment or
attachment the caller's policy denies, an id of a child object (comment, attachment, version) that is served for a page
it does not belong to, a write that lands outside the judged space or tree, a way to make the server send a model-written
query to Confluence, and any path by which the backend credential reaches a log, a result or an error.

## Scope

In scope: the code of this repository. Out of scope: the platform library (`mcp-starter-lib`), the gateway, the credential
broker and the policies, which are separate repositories with their own policies; Confluence itself.

The invariants of the server and the tests that hold them are in `docs/security/SECURITY.md`.

## Supported versions

Only the latest released version receives fixes. Published versions are immutable; a fix ships as a new release.
