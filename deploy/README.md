# Install mcp-confluence

The Confluence Data Center MCP server as one container. It runs on any host that the platform can reach; the platform (gateway,
broker, portal) is installed separately.

## Requirements

- Docker Engine with Compose v2.20 or newer, 1 GiB of memory.
- The domain of the platform, from its operator. This host must reach `https://<platform>` on port 443.
- The platform must reach this host on port 8443.
- The address of your Confluence Data Center (`https`, with the context path if it has one), version 8.5 to 10.x.

## Install

1. Fill in the three empty values in `mcp-confluence.env`:

    ```bash
    MCP_GATEWAY_JWKS_URI=https://<platform>/.well-known/mcp-jwks.json
    MCP_CONNECT_URL=https://<platform>/connections?system={system}
    MCP_CONFLUENCE_URL=https://<confluence>
    ```

2. If the platform certificate is signed by a company CA, add that CA to a trust store in `certs/`:

    ```bash
    keytool -importcert -noprompt -alias company-ca -file company-ca.crt \
      -keystore certs/truststore.p12 -storetype PKCS12 -storepass changeit
    chmod 644 certs/truststore.p12
    ```

    Then uncomment the `JAVA_TOOL_OPTIONS` line in `mcp-confluence.env`. When Confluence uses a CA that the JVM does not know, import
    that CA into the same file.

3. Start the server:

    ```bash
    docker compose up -d
    ```

4. Read the certificate fingerprint:

    ```bash
    docker compose logs mcp-confluence | grep event=tls_certificate
    ```

    Expected: `event=tls_certificate source=generated fingerprint=sha256:...`

5. Send the platform operator the address `https://<this host>:8443/mcp` and the fingerprint.

## Check that it works

```bash
curl -fsSk https://localhost:8443/actuator/health
```

Expected: `{"status":"UP"...}`. Every other path answers 401 without a token from the gateway.

## Add the server to the platform

The platform operator does this in the portal, Administration, with a fresh login and OTP:

1. Systems: add `confluence`, adapter `atlassian-dc`, product `confluence`, address `https://<confluence>`. Users connect with a personal
   access token (`user-secret`, scheme `Bearer`).
2. Servers, Add server: `https://<this host>:8443/mcp`.
3. Approvals: compare the fingerprint with the one from step 4 and approve the connection, then the policies, then the tools.
4. In Keycloak, create the groups `mcp-confluence-read`, `mcp-confluence-write` and `mcp-confluence-destructive` and add the users.
5. Scope mapping, for writes: a group such as `mcp-confluence-space-ENG` -> `Confluence::Space/ENG` (a space and all its pages), or a group -> `Confluence::Page/1000` (one page and the tree below it).

Each user then pastes a personal access token from Confluence in the portal, Connections.

## Common problems

| Symptom | Cause | Fix |
| --- | --- | --- |
| Server stops at start: cannot fetch the key set | The host does not resolve `<platform>` or does not trust its certificate | Check DNS; do step 2 |
| Portal, Add server: `tls_handshake_failed` or `manifest_unreachable` | Port 8443 closed between the platform and this host | Open it to the platform host |
| Tool call: `upstream_unavailable`, log has `SSLHandshakeException` | The trust store is missing the Confluence CA, or `JAVA_TOOL_OPTIONS` is commented out | Import the CA, then `docker compose up -d --force-recreate` |
| Reads work, every write fails with `upstream_unavailable` | The server could not read the Confluence version, or the version is older than 8.5 or 11.0 and newer (state in `docker compose logs mcp-confluence \| grep "backend capability"`) | Check that `MCP_CONFLUENCE_URL` is reachable; if a proxy hides `/rest/api/server-information`, set `MCP_CONFLUENCE_WRITES_ON_UNKNOWN_VERSION=true` |
| Every call refused with `upstream_unavailable`, reason `policies_not_approved` | The operator has not approved the policies yet | Approve them in the portal |
| Server status `certificate_changed` | The state volume was recreated and the server generated a new certificate | Approve the new fingerprint in the portal |

The state volume keeps the certificate between restarts. `docker compose down -v` deletes it.

## Your own certificate

Put a PEM pair or a PKCS12 keystore in `certs/` and set `MCP_TLS_CERT` and `MCP_TLS_KEY`, or `MCP_TLS_KEYSTORE` and
`MCP_TLS_STORE_PASSWORD`, in `mcp-confluence.env`. A certificate from a CA the gateway trusts, for the host name in the address, is
trusted without pinning, and a renewal needs no new approval.

## Several instances

Instances behind one address need the same values in `mcp-confluence.env`:

| Setting | Why |
| --- | --- |
| `MCP_REDIS_HOST` and the other `MCP_REDIS_*` | one-time token ids shared between instances |
| `MCP_PAGING_CURSOR_KEY` (`openssl rand -base64 32`) | a cursor from one instance works on another |
| `MCP_TLS_CERT` and `MCP_TLS_KEY` | the gateway sees one fingerprint |

## Closed network

Copy the image to your registry and set it in a `.env` file next to `compose.yaml`:

```bash
MCP_REGISTRY=artifactory.corp.test/mcp
```

## Upgrade from 1.x

Remove `MCP_GATEWAY_POLICY_DATA_URL`, `MCP_GATEWAY_TLS_NAME`, `MCP_INTERNAL_URL`, `MCP_TLS_TRUSTSTORE` and the Redis service of the
old compose file, set `MCP_GATEWAY_JWKS_URI`, and start. The operator approves the connection and the policies again; the server
refuses to start while an old setting is left.

## Virtual machine without Docker

`systemd/` has a unit and its environment file for the jar (`mcp-app/target/mcp-confluence.jar` after `./mvnw verify`):

```bash
sudo useradd --system --no-create-home --shell /usr/sbin/nologin mcp
sudo install -d -o root -g mcp -m 0750 /opt/mcp-confluence /etc/mcp-confluence
sudo install -o root -g mcp -m 0640 mcp-confluence.jar /opt/mcp-confluence/mcp-confluence.jar
sudo install -o root -g mcp -m 0640 systemd/mcp-confluence.env.template /etc/mcp-confluence/mcp-confluence.env
sudo install -m 0644 systemd/mcp-confluence.service /etc/systemd/system/
```

Fill in `/etc/mcp-confluence/mcp-confluence.env` as in step 1, then:

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now mcp-confluence
journalctl -u mcp-confluence | grep event=tls_certificate
```
