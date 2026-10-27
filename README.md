# mcp-confluence: serwer MCP dla Confluence Data Center

Serwer MCP dla systemu `confluence` (Confluence Data Center, REST API `/rest/api/content`), zbudowany na
`mcp-platform-starter` 3.0.0-alpha-5 (Java 25, Spring Boot 4.1.0, Spring AI 2.0.0, kontrakty `mcp-contracts`
2.0.2). Stoi wyłącznie za gateway'em platformy: przyjmuje ruch tylko po mTLS z wewnętrznym JWT gateway'a (C2),
a do Confluence woła z osobistym tokenem dostępu (PAT) użytkownika, który gateway dołącza do każdego żądania
(C3). Bez poprawnego JWT odpowiada 401, nie ma trybu anonimowego.

Logowania użytkowników, decyzji o dostępie do narzędzi i potwierdzeń serwer nie robi: to zadania gateway'a.
Serwer odpowiada za narzędzia, adapter do Confluence, poprawne błędy (C10) i audyt (C8), który dokłada starter.

## Szybki start

```bash
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@25/25.0.4.1/libexec/openjdk.jdk/Contents/Home
./mvnw verify                 # build i 34 testy; jar: mcp-app/target/mcp-confluence.jar
```

Testy nie potrzebują Dockera ani `mcp-platform-dev` (tam nie ma mocka Confluence): `TestGateway` z
`mcp-platform-test` wystawia JWKS z kluczy testowych kontraktów i podpisuje wewnętrzne JWT, a `StubBackend`
(test) odpowiada jak Confluence DC 8/9 na `/rest/api/content/{id}?expand=...`, `/rest/api/content/search?cql=...`
i `POST /rest/api/content`.

## Narzędzia

| Narzędzie (klient widzi `confluence__<nazwa>`) | Ryzyko | Uprawnienie (C9) | Poświadczenie | Argumenty | Wynik |
| --- | --- | --- | --- | --- | --- |
| `search_pages` | `READ_ONLY` | `mcp-confluence-read` | `user-secret` (PAT), `Bearer` | `space` (klucz przestrzeni `^[A-Za-z0-9]{1,255}$`), `text` (1-200 znaków, jedna linia) | `{spaceKey, pages: [{id, title, version, lastUpdated}]}`, do 25 stron |
| `get_page` | `READ_ONLY` | `mcp-confluence-read` | `user-secret` (PAT), `Bearer` | `pageId` (`^[1-9][0-9]{0,18}$`) | `{id, spaceKey, title, version, lastUpdated, ancestorIds, body: {kind: "untrusted-data", format: "text/plain", text, omittedMacros, truncated}}` |
| `create_page` | `UPDATE` | `mcp-confluence-write` | `user-secret` (PAT), `Bearer` | `space`, `parentId` (strona-rodzic w tej samej przestrzeni), `title` (1-255, jedna linia), `body` (zwykły tekst, 1-20000) | `{id, spaceKey, title, version}` |

Błędy (C10) wspólne dla wszystkich: `credential_required` (brak poświadczenia, 401 z Confluence albo schemat inny
niż `Bearer`), `not_found_or_denied` (404 i 403, ten sam tekst), `invalid_argument`, `upstream_unavailable`,
`internal_error`. `create_page` daje dodatkowo `invalid_argument` przy 400 z Confluence (np. tytuł już istnieje
w przestrzeni), z własnym tekstem serwera.

Szczegóły i uzasadnienia: `docs/api/tools-catalog.md`.

### Co serwer robi z treścią Confluence

- `search_pages`: model nie pisze CQL. Serwer składa `space = "<space>" AND type = page AND text ~ "<słowa>"`;
  z `text` zostają litery, cyfry, odstępy i `. _ '`, reszta (cudzysłowy, operatory Lucene) zamienia się w spację.
  Wyniki spoza przestrzeni i inne typy treści (blog, komentarz) są odrzucane także po stronie serwera.
- `get_page`: treść pochodzi wyłącznie z `body.storage` strony. `body.view` i `body.export_view` Confluence
  renderuje z rozwiniętymi makrami include i excerpt-include, czyli z treścią innych stron i przestrzeni, więc
  adapter nigdy o nie nie prosi. W storage zostają tylko makra z treścią zapisaną na tej stronie (`code`,
  `noformat`, `info`, `note`, `warning`, `tip`, `panel`, `expand`, `excerpt`), bez parametrów; każde inne makro,
  także nieznane, jest usuwane razem z parametrami (np. odwołaniem `ri:space-key`) i zastępowane znacznikiem
  `[macro omitted: <nazwa>]`, a nazwa trafia do `body.omittedMacros`. Wynik to zwykły tekst bez HTML, do 50000
  znaków. Blogi, komentarze, załączniki i strony przestrzeni osobistych (`~user`) dają `not_found_or_denied`.
- `create_page`: `body` to zwykły tekst. Pusta linia rozdziela akapity (`<p>`), pojedynczy koniec linii to
  `<br />`, a `& < > " '` są escapowane, więc z tekstu modelu nie powstanie makro ani element HTML. Przed zapisem
  serwer sprawdza, że `parentId` to widoczna strona przestrzeni `space`; inaczej `not_found_or_denied` i nic nie
  jest tworzone.

## Moduły i miejsce każdej rzeczy

| Moduł | Co w nim jest |
| --- | --- |
| `mcp-core` | domena (`Page`, `PageSummary`, `PageText`, `NewPage`, `PageNotFoundException`, `PageRejectedException`, `ConfluenceIds` z wzorcami identyfikatorów z kontraktu), porty `ConfluenceUseCase` i `ConfluenceResourcePort` |
| `mcp-application` | `ConfluenceService`: zakres wyszukiwania, odrzucenie przestrzeni osobistych, kontrola rodzica |
| `mcp-integration` | `ConfluenceResourceAdapter` (REST, PAT z C3, timeouty, bez przekierowań), `Cql` (zapytanie), `StorageFormat` (storage -> tekst bez makr, tekst -> storage z escapowaniem) |
| `mcp-tools` | `ConfluencePageTools` (trzy narzędzia), `ToolResults` (kształt wyników), `ConfluenceToolErrors` (wyjątki domeny -> C10), `StringArgument` + `ConstrainedToolCallbacks` (limity w `inputSchema`) |
| `mcp-app` | `McpServerApplication`, `McpToolsConfiguration` (jawna lista narzędzi), `ApplicationWiringConfiguration`, testy end-to-end |

## Jak przechodzi wywołanie

```
gateway --mTLS (CN mcp-gateway) + JWT C2 (aud mcp-confluence, tool get_page) + X-MCP-Credential: Bearer <PAT>-->
  starter: InternalJwtFilter (inaczej 401) -> CredentialHeaderFilter -> AuditingToolCallback (C8)
  -> MCP SDK: walidacja argumentów względem inputSchema
  -> ConfluencePageTools.getPage: walidacja pageId
  -> ConfluenceService -> ConfluenceResourceAdapter: CredentialContext.require("confluence"), tylko Bearer
  -> GET {MCP_CONFLUENCE_URL}/rest/api/content/{id}?expand=body.storage,space,version,ancestors
  -> StorageFormat.toText (makra spoza listy usunięte) -> wynik
```

## Konfiguracja

Konfiguracja bazowa (`mcp-app/src/main/resources/application.yml`) jest produkcyjna: mTLS, JWKS gateway'a po
TLS z CA wewnętrznym, `jti` w Redisie. Profil `dev` wyłącza tylko TLS i służy testom; pozostałe ułatwienia
(klucze testowe, `jti` w pamięci, `mtls.required=false`) daje `TestGateway`, a starter odrzuca je poza `dev`.

| Zmienna | Znaczenie | Domyślnie |
| --- | --- | --- |
| `MCP_TLS_KEYSTORE`, `MCP_TLS_TRUSTSTORE`, `MCP_TLS_STORE_PASSWORD` | PKCS12: certyfikat serwera (SAN `mcp-confluence.internal`) i CA wewnętrzne; truststore służy też do JWKS | wymagane |
| `MCP_GATEWAY_JWKS_URI` | JWKS wewnętrznego JWT, np. `https://mcp-gateway.internal:8443/.well-known/jwks.json` | wymagane |
| `MCP_GATEWAY_ISSUER` | `iss` C2 | `https://mcp-gateway.internal` |
| `MCP_CONNECT_URL` | strona portalu do połączenia konta, z `{system}`; trafia do `credential_required` | wymagane |
| `MCP_CONFLUENCE_URL` | adres bazowy Confluence (bez `/rest/api`), np. `https://confluence.corp.internal` albo z kontekstem `.../confluence` | wymagane |
| `MCP_CONFLUENCE_SSL_BUNDLE` | bundle SSL dla HTTPS Confluence, np. `mcp-internal-ca`; puste = zaufanie JDK | puste |
| `MCP_REDIS_HOST`, `MCP_REDIS_PORT`, `MCP_REDIS_USER`, `MCP_REDIS_PASSWORD` | Redis dla jednorazowego `jti` (użytkownik ACL `mcp-confluence`, klucze `jti:mcp-confluence:*`) | `localhost`, `6379`, `mcp-confluence`, puste |
| `MCP_PORT`, `MCP_BIND` | port i adres nasłuchu | `8443`, `0.0.0.0` |

Endpoint MCP to `/mcp` (gateway: `internal-url: https://mcp-confluence.internal:8443/mcp`). Bez JWT odpowiada
tylko `/actuator/health` (i `liveness`, `readiness`), a TLS i tak wymaga certyfikatu klienta z CA wewnętrznego.
Serwer nie wstaje bez `MCP_GATEWAY_JWKS_URI`, `MCP_CONNECT_URL`, `MCP_CONFLUENCE_URL` i plików TLS.

Na hoście ze środowiskiem `mcp-platform-dev` (sprawdzone 2026-10-27; certyfikat i użytkownik Redisa
`mcp-confluence` są w środowisku, mocka Confluence nie ma, więc `MCP_CONFLUENCE_URL` wskazuje na nic):

```bash
dev=../mcp-platform-dev
export MCP_TLS_STORE_PASSWORD="$(grep '^TLS_STORE_PASSWORD=' $dev/.secrets/dev.env | cut -d= -f2-)"
export MCP_REDIS_PASSWORD="$(grep '^REDIS_MCP_CONFLUENCE_PASSWORD=' $dev/.secrets/dev.env | cut -d= -f2-)"
export MCP_TLS_KEYSTORE=$dev/certs/mcp-confluence/keystore.p12 MCP_TLS_TRUSTSTORE=$dev/certs/ca/truststore.p12
export MCP_PORT=18510 MCP_BIND=127.0.0.1 MCP_REDIS_PORT=16379 MCP_CONFLUENCE_URL=http://localhost:18511
export MCP_GATEWAY_JWKS_URI=https://localhost:18445/.well-known/jwks.json
export MCP_CONNECT_URL='http://localhost:18090/connections?system={system}'
java -jar mcp-app/target/mcp-confluence.jar
curl --cacert $dev/certs/ca/ca.crt --cert $dev/certs/mcp-gateway/tls.crt --key $dev/certs/mcp-gateway/tls.key \
     --resolve mcp-confluence.internal:18510:127.0.0.1 https://mcp-confluence.internal:18510/actuator/health  # 200
```

`POST /mcp` bez JWT daje 401, połączenie bez certyfikatu klienta jest zrywane w handshake'u.

## Testy

| Klasa | Co sprawdza |
| --- | --- |
| `SearchPagesToolTest` | CQL budowany przez serwer dla jednej przestrzeni (dokładny `cql`, `limit`, `expand` w żądaniu); CQL w `text` nie poszerza zapytania; blog i trafienia z innej przestrzeni odrzucone; pusta przestrzeń -> pusta lista; `credential_required` (brak, 401); zły `space`/`text` i dodatkowy argument `cql` nie docierają do Confluence |
| `GetPageToolTest` | PAT z C3 dociera do Confluence; makra include/excerpt-include nie dają treści z HR (stub oddaje rozwiniętą treść tylko w `body.view`, adapter prosi o `body.storage`); 404 = 403; blog i przestrzeń osobista -> `not_found_or_denied`; `credential_required` (brak, 401, schemat `Basic`); zły `pageId` nie dociera do Confluence; PAT i treść błędu Confluence ani w wyniku, ani w logu |
| `CreatePageToolTest` | rodzic sprawdzany przed zapisem; treść escapowana (dokładne `body.storage.value`); rodzic z innej przestrzeni, nieistniejący i zabroniony -> ten sam `not_found_or_denied` bez POST; 400 Confluence -> `invalid_argument` bez tekstu Confluence; `credential_required` (brak, 401); `UPDATE` bez `readOnlyHint`; złe argumenty |
| `ToolListingTest` | dokładnie trzy narzędzia; `additionalProperties: false`, `minLength`/`maxLength`/`pattern` w `inputSchema`; wzorce identyfikatorów z kontraktu |
| `GatewayOnlyAccessTest` | bez JWT 401 (`tools/call`, `ping`, `initialize`), JWT innego serwera, innego narzędzia, wariant serwisowy, przeterminowany -> 401; bez JWT tylko health |
| `StorageFormatTest` | storage -> tekst (makra, encje, listy, tabele, obcięcie), tekst -> storage, CQL |
| `ConfluenceServiceTest` | filtr przestrzeni w wyszukiwaniu, przestrzeń osobista, rodzic z innej przestrzeni (bez Springa) |

## Dokumentacja

- `STATUS.md`: stan, odstępstwa, czego potrzebują inne strumienie.
- `docs/api/tools-catalog.md`: rejestr narzędzi.
- `docs/jak-napisac-narzedzie.md`: zasady przy nowym narzędziu.
- `docs/architecture/overview.md`, `docs/security/SECURITY.md`: architektura i niezmienniki.
- `docs/operations/deployment-vm-systemd.md`, `deploy/`, `Dockerfile`: wdrożenie.
