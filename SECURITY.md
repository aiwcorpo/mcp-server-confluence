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

What the server must keep (policy decisions, the ids of child objects, the scope of writes) is described in the "Policies" and
"Tool behavior" sections of [CONTRIBUTING.md](CONTRIBUTING.md); what the platform guarantees is on
https://mcp.aiwcorpo.pl/2.0/admin/security/.

## Supported versions

Only the latest released version receives fixes. Published versions are immutable; a fix ships as a new release.
