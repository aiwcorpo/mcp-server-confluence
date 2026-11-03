# Contributing

The server follows the contracts of `mcp-contracts` and the library `mcp-starter-lib`. A change that needs a different
contract (a resource type, a query field, a credential type) starts as a `proposed` entry in `CONTRACT_CHANGES.md` of
`mcp-contracts` and waits for approval.

## Setup

JDK 25, Linux or macOS with bash 3.2 or newer; Windows only through WSL2. The platform artifacts must be in `~/.m2` or in
the registry named by `MCP_MAVEN_REPOSITORY_URL` (README, "Instalacja"). No container runtime and no Confluence are needed:
the tests run the whole server against a stub backend.

```bash
ci/run.sh verify     # text checks, then clean verify; the same script runs in CI
```

`ci/run.sh verify` must pass before a merge. A new tool needs the items of `docs/jak-napisac-narzedzie.md`: risk, title,
`@ResourceRef` for every id it names, a closed argument schema, a negative test, and a row in the README table.

## Commits

Subject in English, imperative, up to 72 characters. The body describes the change, not the process. Tests go in the
same commit as the code they cover.

## License

Contributions are licensed under Apache-2.0 (see `LICENSE`).
