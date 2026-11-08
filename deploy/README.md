# Install mcp-confluence

The Confluence Data Center server as one container (Docker Compose) or one systemd service. It runs behind the MCP gateway; the
gateway, the credential broker and the identity provider are installed elsewhere. Step by step for a VM:
`docs/operator/install-vm.md` in `mcp-docs`. The image is built from the `Dockerfile` in the repository root.

You start the server; the platform operator enters its address in the portal and approves the connection. Nobody issues a certificate
for the server, creates a token or registers anything from a pipeline: the gateway connects to the address, reads the certificate,
pulls the manifest and pushes the policy data itself. The host of the server may be another machine than the platform.

```
deploy/
|-- compose.yaml                 the server as a container (read-only, no capabilities) and a state volume
|-- mcp-confluence.env           settings for Compose: fill in the empty values
|-- certs/                       only for your own certificate; may stay empty (never committed)
`-- systemd/
    |-- mcp-confluence.service       unit (account mcp, systemd sandbox, logs to journald)
    `-- mcp-confluence.env.template  settings -> /etc/mcp-confluence/mcp-confluence.env
```

## Prerequisites

- Docker Engine with Compose v2.20 or newer (or Java 25 and systemd), 1 GiB of memory.
- The public key set of the gateway, `https://<domain of the platform>/.well-known/mcp-jwks.json` (the operator tells you the domain). The
  host must reach that domain on port 443, and the platform must reach the port of the server (8443 by default).
- The addresses of Confluence Data Center (`https`, with the context path if it has one) and of
  the portal page for connecting an account.
- A certificate is optional. Without one the server generates a self-signed certificate on its first start and keeps it in the state
  volume. With your own (PEM files or a PKCS12 keystore, from any CA, signed or not) put it in `certs/`; see `mcp-confluence.env`.

## Install

1. Fill in the empty values in `mcp-confluence.env`: `MCP_GATEWAY_JWKS_URI`, `MCP_CONNECT_URL` and the address of the system. The other lines are optional and
   commented out.
2. Get the image: `docker compose pull`, or after `./mvnw verify` in the repository `docker compose build`.
3. Start:

    ```bash
    docker compose up -d
    docker compose logs mcp-confluence | grep event=tls_certificate
    ```

    The server does not start with a missing setting and says which. The log line has the fingerprint of the certificate it serves:

    ```
    event=tls_certificate source=generated fingerprint=sha256:9f2c...e1 detail="..."
    ```

    Keep it: the operator compares it with the one the portal shows before approving the connection. `source` is `generated`, `pem` or `keystore`.
    The certificate is generated once and reused at every start, as long as the state volume (`state`, mounted at `/var/lib/mcp/state`) stays;
    a new volume is a new certificate and a new fingerprint to approve. With `docker compose down -v` the volume is gone.

4. Give the operator the address: `https://<host>:8443/mcp`, with the host name or address the platform reaches, and the fingerprint.

Until the gateway has pushed policy data (a fresh start, or a restart), the server answers `tools/call` with `409 policy_data_stale`, which the
gateway handles by pushing and repeating the call. The gateway pushes after every change and every minute, so the state lasts seconds. Until an
administrator has approved the policies it refuses every call (`upstream_unavailable`, reason `policies_not_approved` in the audit), which is the state
the next section ends.

## Add the server to the platform

Systems and servers are data of the broker and the gateway, not settings of this directory. Nothing restarts, in either direction:
every instance sees a change within a few seconds. Adding, changing and removing need an administrator token with a fresh MFA login
(`mcp-platform-admin`); the portal (Administration) does all of this, and the calls below are the same operations for scripts.

1. **System in the broker.** Confluence accepts the user's own token or password, so the system has the type `user-secret` only:

    ```bash
    curl --cert bff.crt --key bff.key --cacert ca.crt -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' \
      -H 'If-None-Match: *' -X PUT https://mcp-credential-broker.corp.internal:8443/v1/admin/systems/confluence -d '{
        "baseUrl":"https://confluence.corp.internal","adapter":"atlassian-dc","product":"confluence",
        "supportedTypes":["user-secret"],"defaultType":"user-secret","secretScheme":"Bearer"}'
    ```

2. **Server in the gateway.** The operator enters the address `https://<host>:8443/mcp` in the portal (Administration, Servers, Add), or calls
   `POST /admin/v1/servers` with `{"internalUrl": "..."}` (fresh MFA). The gateway connects, reads the certificate, pulls the manifest and shows
   a card with the system, the certificate (subject, issuer, names, validity, fingerprint) and the tools and policies it found. The `serverId` is
   `mcp-confluence`, derived from the manifest. An address that is loopback or link-local is refused, and `http` needs `allowPlainHttp`, which only an
   administrator may set (for a server inside the Docker network of the platform, started with `MCP_PLAIN_HTTP=true`).
3. **Approve the connection** (fresh MFA), after comparing the fingerprint on the card with the one in the server log:
    - a certificate that a CA trusted by the gateway signed, for the name of the address, is trusted as `ca`, and a renewal needs nothing;
    - any other certificate, a self-signed one included, is pinned (`pinned`) by its fingerprint: when the server later presents another
      certificate it becomes unreachable, and the portal shows the new fingerprint for approval.
4. **Approve** the policies (`GET /admin/v1/registry/servers/mcp-confluence/policies/pending` shows them, `POST .../policies/approve`
   takes their `policiesHash`) and the tools (every tool waits, the read-only ones too; `POST /admin/v1/registry/servers/mcp-confluence/tools/{tool}/approve`), in
   the portal under Administration, Servers. A later release that changes a policy, a declaration or a tool puts the server in
   `pending_approval` for that part until it is approved again; the server refuses calls with `policies_differ` in the meantime.
5. **Groups.** The server needs the groups `mcp-confluence-read`, `mcp-confluence-write` and `mcp-confluence-destructive` in the
   identity provider (the gateway derives them from the risk of a tool). Changes in Confluence are allowed only inside spaces and
   page trees that a group is mapped to; map them in Administration, Scope mapping, or with `PUT /admin/v1/policy/scope-mapping`
   (the whole document, `If-Match` with the `ETag` of the last `GET`):

    ```json
    {"scopeMapping": [
      {"group": "mcp-confluence-space-ENG", "scopes": ["Confluence::Space/ENG"]},
      {"group": "mcp-confluence-handbook-editors", "scopes": ["Confluence::Page/1000"]}
    ]}
    ```

    A scope `Confluence::Space/ENG` covers the space and every page in it, and creating a page directly in the space. A scope
    `Confluence::Page/1000` covers that page and the whole tree below it, wherever in the space it lies; it does not cover the space,
    so a user with only a page grant cannot create a page at the top of the space. A user in `mcp-confluence-write` and
    `mcp-confluence-space-ENG` may then change pages of ENG and nothing else; reading follows the user's rights in Confluence.
    Spaces labelled `restricted` are closed to everyone, whatever the groups.

### Remove the server

Delete the server in the gateway (`DELETE /admin/v1/servers/mcp-confluence`) and the system in the broker
(`DELETE /v1/admin/systems/confluence`). Its tools leave every `tools/list`, calls are refused as for an unknown tool, and the entries
of the scope mapping for `Confluence::Space` and `Confluence::Page` go with it. Then stop the container or the service.

## Policy data

The gateway pushes the scope mapping, the allowed AI clients (`claude-code`, `claude-ai` by default) and the emergency overrides to
`POST /.well-known/mcp-policy-data` of the server after every change and every minute; the server fetches nothing and holds the data in
memory. A server that was restarted, or missed a push, answers `tools/call` with `409 policy_data_stale` and the gateway pushes and repeats the
call once. The policies themselves are in the jar (`mcp/policies/`); there is nothing to configure for them and no key to distribute.

## Several instances

Behind a balancer the instances share one address, so they need what one instance keeps in its own memory or state directory: Redis for the
one-time `jti` (`MCP_REDIS_HOST` and the lines under it in `mcp-confluence.env`; without it a token could be used once on each instance), the same
`MCP_PAGING_CURSOR_KEY` (otherwise a cursor from one instance is refused by another) and the same certificate (`MCP_TLS_CERT` and `MCP_TLS_KEY`,
otherwise each instance generates its own and the gateway sees the fingerprint change from one call to the next). Compose runs one instance; Redis is
yours.

## Closed network

Copy the image to your registry and point Compose at it in a `.env` file next to `compose.yaml`:

```bash
MCP_REGISTRY=artifactory.corp.test/mcp
```

## Upgrade from 1.x

Version 2.0.0 gets the policy data pushed by the gateway and the policies from the jar, and refuses to start while a setting of the earlier
policy bundles, `policy-data.*` or `mtls.*` is left. Remove `MCP_GATEWAY_POLICY_DATA_URL`, `MCP_GATEWAY_TLS_NAME`, `MCP_INTERNAL_URL`,
`MCP_TLS_TRUSTSTORE`, the Redis service of the earlier compose file and its `MCP_REDIS_*` lines (one instance needs none), and set
`MCP_GATEWAY_JWKS_URI` to the public key set of the gateway. The certificate from the internal CA is not needed any more: keep it
(`MCP_TLS_CERT`, `MCP_TLS_KEY` or `MCP_TLS_KEYSTORE` with `MCP_TLS_STORE_PASSWORD`) or let the server generate one, and have the operator approve
the connection. The registration of the manifest from the pipeline is gone: the gateway pulls it. Approve the policies as above before the first call.

## Virtual machine without Docker

`systemd/` has a unit and its environment file for the jar (`mcp-app/target/mcp-confluence.jar` after `./mvnw verify`). The variables are
the ones of `mcp-confluence.env`; the unit's `StateDirectory=` keeps the certificate in `/var/lib/mcp-confluence`.

```bash
sudo useradd --system --no-create-home --shell /usr/sbin/nologin mcp
sudo install -d -o root -g mcp -m 0750 /opt/mcp-confluence /etc/mcp-confluence
sudo install -o root -g mcp -m 0640 mcp-confluence.jar /opt/mcp-confluence/mcp-confluence.jar
sudo install -o root -g mcp -m 0640 systemd/mcp-confluence.env.template /etc/mcp-confluence/mcp-confluence.env   # then fill it in
sudo install -m 0644 systemd/mcp-confluence.service /etc/systemd/system/
sudo systemctl daemon-reload && sudo systemctl enable --now mcp-confluence
journalctl -u mcp-confluence | grep event=tls_certificate
```

Health (any certificate; no token is needed for this path):

```bash
curl -fsSk https://localhost:8443/actuator/health
```

Update: keep the old jar for a rollback, replace it and restart.

```bash
sudo cp -a /opt/mcp-confluence/mcp-confluence.jar /opt/mcp-confluence/mcp-confluence.jar.bak
sudo install -o root -g mcp -m 0640 new-mcp-confluence.jar /opt/mcp-confluence/mcp-confluence.jar
sudo systemctl restart mcp-confluence
```
