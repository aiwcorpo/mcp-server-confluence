# mcp-confluence: serwer MCP dla Confluence Data Center

Serwer MCP dla systemu `confluence` (Confluence Data Center, REST API `/rest/api/content`), zbudowany na
`mcp-platform-starter` 3.0.0-alpha-11 (Java 25, Spring Boot 4.1.0, Spring AI 2.0.0, kontrakty `mcp-contracts`
2.1.1). Stoi wyłącznie za gateway'em platformy: przyjmuje ruch tylko po mTLS z wewnętrznym JWT gateway'a (C2),
a do Confluence woła z osobistym tokenem dostępu (PAT) użytkownika, który gateway dołącza do każdego żądania
(C3). Bez poprawnego JWT odpowiada 401, nie ma trybu anonimowego.

Logowania użytkowników, decyzji o dostępie do narzędzi i potwierdzeń serwer nie robi: to zadania gateway'a.
Serwer odpowiada za narzędzia, adapter do Confluence, poprawne błędy (C10) i audyt (C8), który dokłada starter.

## Szybki start

```bash
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@25/25.0.4.1/libexec/openjdk.jdk/Contents/Home
./mvnw verify                 # build i 46 testów; jar: mcp-app/target/mcp-confluence.jar
```

Testy nie potrzebują Dockera ani `mcp-platform-dev` (tam nie ma mocka Confluence): `TestGateway` z
`mcp-platform-test` wystawia JWKS z kluczy testowych kontraktów i podpisuje wewnętrzne JWT, a `StubBackend`
(test) odpowiada jak Confluence DC 8/9 na `/rest/api/content/{id}?expand=...`, `/rest/api/content/search?cql=...`
i `POST /rest/api/content`.

## Narzędzia

| Narzędzie (klient widzi `confluence__<nazwa>`) | Ryzyko | Uprawnienie (C9) | Poświadczenie | Argumenty | Wynik |
| --- | --- | --- | --- | --- | --- |
| `search_pages` | `READ_ONLY` | `mcp-confluence-read` | `user-secret` (PAT), `Bearer` | `space` (klucz przestrzeni `^[A-Za-z0-9]{1,255}$`), `text` (1-200 znaków, jedna linia) | `{spaceKey, pages: [{id, title: {kind: "untrusted-data", format: "text/plain", text, truncated}, version, lastUpdated}]}`, do 25 stron |
| `get_page` | `READ_ONLY` | `mcp-confluence-read` | `user-secret` (PAT), `Bearer` | `pageId` (`^[1-9][0-9]{0,18}$`) | `{id, spaceKey, title: {kind: "untrusted-data", format: "text/plain", text, truncated}, version, lastUpdated, ancestorIds, body: {kind: "untrusted-data", format: "text/plain", text, truncated}, omittedMacros}` |
| `create_page` | `UPDATE` | `mcp-confluence-write` | `user-secret` (PAT), `Bearer` | `space`, `parentId` (strona-rodzic w tej samej przestrzeni), `title` (1-255, jedna linia), `body` (zwykły tekst, 1-20000) | `{id, spaceKey, title: {kind: "untrusted-data", format: "text/plain", text, truncated}, version}` |

Błędy (C10) wspólne dla wszystkich: `credential_required` (brak poświadczenia, 401 z Confluence albo schemat inny
niż `Bearer`), `not_found_or_denied` (404 i 403, ten sam tekst), `invalid_argument`, `upstream_unavailable`,
`internal_error`. `create_page` daje dodatkowo `invalid_argument` przy 400 z Confluence (np. tytuł już istnieje
w przestrzeni), z własnym tekstem serwera; `search_pages` przy 400 z wyszukiwarki (słowa zastrzeżone w CQL).
Argument spoza `inputSchema` (wzorzec, długość, dodatkowe pole) odrzuca starter jako `invalid_argument`, bez
wartości argumentu, i zapisuje zdarzenie audytu C8.

Każdy tekst napisany przez użytkowników Confluence (tytuł strony, treść) jest w wyniku obiektem `untrusted-data`
(UntrustedText, CC-072), nigdy zwykłym polem obok pól serwera. Klucz przestrzeni i identyfikatory to wartości
sprawdzone wzorcem kontraktu, nie tekst swobodny.

Szczegóły i uzasadnienia: `docs/api/tools-catalog.md`.

### Co serwer robi z treścią Confluence

- `search_pages`: model nie pisze CQL. Serwer składa `space = "<space>" AND type = page AND text ~ "<słowa>"`;
  z `text` zostają litery, cyfry, odstępy i `. _ '`, reszta (cudzysłowy, operatory Lucene) zamienia się w spację.
  Wyniki spoza przestrzeni i inne typy treści (blog, komentarz) są odrzucane także po stronie serwera.
  Przekierowanie (3xx), odpowiedź bez treści, nieczytelna albo bez `id` to `internal_error`, nie pusty wynik.
- `get_page`: treść pochodzi wyłącznie z `body.storage` strony. `body.view` i `body.export_view` Confluence
  renderuje z rozwiniętymi makrami include i excerpt-include, czyli z treścią innych stron i przestrzeni, więc
  adapter nigdy o nie nie prosi. W storage zostają tylko makra z treścią zapisaną na tej stronie (`code`,
  `noformat`, `info`, `note`, `warning`, `tip`, `panel`, `expand`, `excerpt`), bez parametrów; każde inne makro,
  także nieznane, jest usuwane razem z parametrami (np. odwołaniem `ri:space-key`) i zastępowane znacznikiem
  `[macro omitted: <nazwa>]`, a nazwa trafia do `omittedMacros` (obok `body`). Wynik to zwykły tekst bez HTML, do 50000
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
| `mcp-integration` | `ConfluenceResourceAdapter` (REST, PAT z C3, `https` poza `dev`, timeouty, bez przekierowań, `BoundedBodyInterceptor` z limitem rozmiaru odpowiedzi, `BackendResponseException` bez treści), `Cql` (zapytanie), `StorageFormat` (storage -> tekst bez makr, tekst -> storage z escapowaniem) |
| `mcp-tools` | `ConfluencePageTools` (trzy narzędzia), `ToolResults` (kształt wyników), `ConfluenceToolErrors` (wyjątki domeny -> C10), `@ArgumentLimits` na argumentach (wzorce przez `contractPattern`); rejestracja przez `SchemaLimitedToolCallbacks` ze startera w `McpToolsConfiguration` |
| `mcp-app` | `McpServerApplication`, `McpToolsConfiguration` (jawna lista narzędzi), `ApplicationWiringConfiguration`, testy end-to-end |

## Jak przechodzi wywołanie

```
gateway --mTLS (CN mcp-gateway) + JWT C2 (aud mcp-confluence, tool get_page) + X-MCP-Credential: Bearer <PAT>-->
  starter: InternalJwtFilter (inaczej 401) -> CredentialHeaderFilter -> AuditingToolCallback (C8)
  -> starter: walidacja argumentów względem inputSchema (inaczej C10 invalid_argument + C8)
  -> ConfluencePageTools.getPage: walidacja pageId (druga linia)
  -> ConfluenceService -> ConfluenceResourceAdapter: CredentialContext.require("confluence"), tylko Bearer
  -> GET {MCP_CONFLUENCE_URL}/rest/api/content/{id}?expand=body.storage,space,version,ancestors
  -> StorageFormat.toText (makra spoza listy usunięte) -> wynik
```

## Konfiguracja

Konfiguracja bazowa (`mcp-app/src/main/resources/application.yml`) jest produkcyjna: mTLS, JWKS gateway'a po
TLS z CA wewnętrznym, `jti` w Redisie. Profil `dev` (`application-dev.yml`, testy i uruchomienie lokalne)
wyłącza TLS i mTLS, trzyma `jti` w pamięci i dopuszcza klucze testowe; starter odrzuca te ustawienia poza `dev`.

| Zmienna | Znaczenie | Domyślnie |
| --- | --- | --- |
| `MCP_TLS_KEYSTORE`, `MCP_TLS_TRUSTSTORE`, `MCP_TLS_STORE_PASSWORD` | PKCS12: certyfikat serwera (SAN `mcp-confluence.internal`) i CA wewnętrzne; truststore służy też do JWKS | wymagane |
| `MCP_GATEWAY_JWKS_URI` | JWKS wewnętrznego JWT, np. `https://mcp-gateway.internal:8443/.well-known/jwks.json` | wymagane |
| `MCP_GATEWAY_ISSUER` | `iss` C2 | `https://mcp-gateway.internal` |
| `MCP_CONNECT_URL` | strona portalu do połączenia konta, z `{system}`; trafia do `credential_required` | wymagane |
| `MCP_CONFLUENCE_URL` | adres bazowy Confluence (bez `/rest/api`), np. `https://confluence.corp.internal` albo z kontekstem `.../confluence`; poza `dev` tylko `https` | wymagane |
| `MCP_CONFLUENCE_ALLOW_PLAIN_HTTP` | `true` dopuszcza `http` poza `dev`, wyłącznie dla mocków `mcp-platform-dev` (PAT idzie wtedy otwartym tekstem; ostrzeżenie w logu) | `false` |
| `MCP_CONFLUENCE_SSL_BUNDLE` | bundle SSL dla HTTPS Confluence, np. `mcp-internal-ca`; puste = zaufanie JDK | puste |
| `MCP_REDIS_HOST`, `MCP_REDIS_PORT`, `MCP_REDIS_USER`, `MCP_REDIS_PASSWORD` | Redis dla jednorazowego `jti` (użytkownik ACL `mcp-confluence`, klucze `jti:mcp-confluence:*`) | `localhost`, `6379`, `mcp-confluence`, puste |
| `MCP_PORT`, `MCP_BIND` | port i adres nasłuchu | `8443`, `0.0.0.0` |

Endpoint MCP to `/mcp` (gateway: `internal-url: https://mcp-confluence.internal:8443/mcp`). Bez JWT odpowiada
tylko `/actuator/health` (i `liveness`, `readiness`), a TLS i tak wymaga certyfikatu klienta z CA wewnętrznego.
Serwer nie wstaje bez `MCP_GATEWAY_JWKS_URI`, `MCP_CONNECT_URL`, `MCP_CONFLUENCE_URL` i plików TLS.

`java` w `PATH` może być starsza niż 25 (wtedy `UnsupportedClassVersionError`), dlatego poniżej zawsze
`$JAVA_HOME/bin/java`. Limit odpowiedzi Confluence to `confluence.backend.max-response-size` (1MB); większa
odpowiedź jest ucinana w trakcie czytania, przed parsowaniem JSON i Jsoup, i daje `internal_error`.

## Uruchomienie lokalne bez środowiska

Profil `dev` (musi być jedynym aktywnym profilem): zwykłe HTTP na 127.0.0.1, `jti` w pamięci, bez Redisa,
certyfikatów i sekretów. Gateway'a nie ma, więc każde żądanie z JWT kończy się 503 (JWKS niedostępny); ten tryb
służy do sprawdzenia, że serwer wstaje i odrzuca ruch bez JWT. Narzędzia sprawdzają testy (`./mvnw verify`).

```bash
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@25/25.0.4.1/libexec/openjdk.jdk/Contents/Home
./mvnw -B verify
SPRING_PROFILES_ACTIVE=dev MCP_PORT=18510 "$JAVA_HOME/bin/java" -jar mcp-app/target/mcp-confluence.jar
# w drugim terminalu:
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:18510/actuator/health          # 200
curl -s -o /dev/null -w '%{http_code}\n' -X POST -H 'Content-Type: application/json' \
     -H 'Accept: application/json, text/event-stream' \
     -d '{"jsonrpc":"2.0","id":1,"method":"ping"}' http://127.0.0.1:18510/mcp            # 401
```

Wartości domyślne profilu są w `mcp-app/src/main/resources/application-dev.yml` (Confluence
`http://localhost:18081`, portal `http://localhost:18090`); każdą można nadpisać zmienną z tabeli wyżej.

Ryzyko: `application-dev.yml` jest w jarze produkcyjnym, a profil `dev` przyjmuje JWT podpisane opublikowanymi
kluczami testowymi kontraktów i nie wymaga certyfikatu klienta. Dlatego `dev` słucha wyłącznie na adresie pętli
zwrotnej: starter odmawia startu z `dev` przy innym `server.address`, a test `DevProfileLoopbackTest` pilnuje
tego w serwerze. Nie ustawiaj `SERVER_ADDRESS` ani `MCP_BIND` przy `dev` i nigdy nie włączaj `dev` na hoście ani
w kontenerze, do którego łączy się gateway: tam działa wyłącznie konfiguracja bazowa z mTLS.

## Uruchomienie z mcp-platform-dev (mTLS)

Konfiguracja produkcyjna na hoście, z certyfikatami i Redisem środowiska `mcp-platform-dev` (`make up` już
uruchomione). Środowisko ma certyfikat `certs/mcp-confluence` (SAN `mcp-confluence.internal`) i użytkownika
Redisa `mcp-confluence`. Magazyny PKCS12 i Redis są chronione hasłami z `$MCP_PLATFORM_DEV/.secrets/dev.env`, więc
ten wariant wymaga ich odczytu. Mocka Confluence środowisko nie ma, więc `MCP_CONFLUENCE_URL` wskazuje na
nic, a `http` wymaga jawnej zgody:

```bash
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@25/25.0.4.1/libexec/openjdk.jdk/Contents/Home
export MCP_PLATFORM_DEV=<workspace>/mcp-platform-dev           # ścieżka bezwzględna
export MCP_TLS_STORE_PASSWORD="$(grep '^TLS_STORE_PASSWORD=' "$MCP_PLATFORM_DEV/.secrets/dev.env" | cut -d= -f2-)"
export MCP_REDIS_PASSWORD="$(grep '^REDIS_MCP_CONFLUENCE_PASSWORD=' "$MCP_PLATFORM_DEV/.secrets/dev.env" | cut -d= -f2-)"
export MCP_TLS_KEYSTORE="$MCP_PLATFORM_DEV/certs/mcp-confluence/keystore.p12"
export MCP_TLS_TRUSTSTORE="$MCP_PLATFORM_DEV/certs/ca/truststore.p12"
export MCP_PORT=18510 MCP_BIND=127.0.0.1 MCP_REDIS_PORT=16379
export MCP_CONFLUENCE_URL=http://localhost:18511 MCP_CONFLUENCE_ALLOW_PLAIN_HTTP=true
export MCP_GATEWAY_JWKS_URI=https://localhost:18445/.well-known/jwks.json
export MCP_CONNECT_URL='http://localhost:18090/connections?system={system}'
"$JAVA_HOME/bin/java" -jar mcp-app/target/mcp-confluence.jar
# w drugim terminalu (te same zmienne MCP_PLATFORM_DEV), certyfikat klienta gateway'a:
TLS=(--cacert "$MCP_PLATFORM_DEV/certs/ca/ca.crt" --resolve mcp-confluence.internal:18510:127.0.0.1
     --cert "$MCP_PLATFORM_DEV/certs/mcp-gateway/tls.crt" --key "$MCP_PLATFORM_DEV/certs/mcp-gateway/tls.key")
curl -s -o /dev/null -w '%{http_code}\n' "${TLS[@]}" https://mcp-confluence.internal:18510/actuator/health   # 200
curl -s -o /dev/null -w '%{http_code}\n' "${TLS[@]}" -X POST -H 'Content-Type: application/json' \
     -d '{"jsonrpc":"2.0","id":1,"method":"ping"}' https://mcp-confluence.internal:18510/mcp                     # 401
```

Połączenie bez certyfikatu klienta jest zrywane w handshake'u. Pełne wywołanie narzędzia wymaga gateway'a z
hosta (`mcp-gateway/scripts/run-dev-host.sh`, JWKS na `localhost:18445`) skonfigurowanego na ten serwer.

## Testy

| Klasa | Co sprawdza |
| --- | --- |
| `SearchPagesToolTest` | CQL budowany przez serwer dla jednej przestrzeni (dokładny `cql`, `limit`, `expand` w żądaniu); CQL w `text` nie poszerza zapytania; blog i trafienia z innej przestrzeni odrzucone; pusta przestrzeń -> pusta lista; `credential_required` (brak, 401); zły `space`/`text` i dodatkowy argument `cql` -> `invalid_argument` bez wywołania Confluence; 400 wyszukiwarki -> `invalid_argument` bez jej tekstu; przekierowanie to błąd, nie pusty wynik; tytuły jako `untrusted-data` |
| `GetPageToolTest` | PAT z C3 dociera do Confluence; makra include/excerpt-include nie dają treści z HR (stub oddaje rozwiniętą treść tylko w `body.view`, adapter prosi o `body.storage`); 404 = 403; blog i przestrzeń osobista -> `not_found_or_denied`; `credential_required` (brak, 401, schemat `Basic`); zły `pageId` i nieznany argument (nazwa nie trafia do logu) -> `invalid_argument` bez wywołania Confluence; nieczytelna odpowiedź, strona bez `id` albo bez treści, przekierowanie (bez podążania) i odpowiedź ponad limit -> `internal_error` bez treści w logu; PAT i treść błędu Confluence ani w wyniku, ani w logu |
| `CreatePageToolTest` | rodzic sprawdzany przed zapisem; treść escapowana (dokładne `body.storage.value`); rodzic z innej przestrzeni, nieistniejący i zabroniony -> ten sam `not_found_or_denied` bez POST; 400 Confluence -> `invalid_argument` bez tekstu Confluence; `credential_required` (brak, 401); `UPDATE` bez `readOnlyHint`; złe argumenty |
| `ToolListingTest` | dokładnie trzy narzędzia; `additionalProperties: false`, `minLength`/`maxLength`/`pattern` w `inputSchema`; wzorce identyfikatorów z kontraktu |
| `ProductionConfigurationTest` | konfiguracja bazowa: `client-auth: need`; backend `http` poza `dev` odrzucony (chyba że jawnie dopuszczony); `dev` na 127.0.0.1 |
| `DevProfileLoopbackTest` | `dev` z adresem innym niż pętla zwrotna nie wstaje |
| `GatewayOnlyAccessTest` | bez JWT 401 (`tools/call`, `ping`, `initialize`), JWT innego serwera, innego narzędzia, wariant serwisowy, przeterminowany -> 401; bez JWT tylko health |
| `StorageFormatTest` | storage -> tekst (makra, encje, listy, tabele, obcięcie), tekst -> storage, CQL |
| `ConfluenceServiceTest` | filtr przestrzeni w wyszukiwaniu, przestrzeń osobista, rodzic z innej przestrzeni (bez Springa) |

## Dokumentacja

- `STATUS.md`: stan, odstępstwa, czego potrzebują inne strumienie.
- `docs/api/tools-catalog.md`: rejestr narzędzi.
- `docs/jak-napisac-narzedzie.md`: zasady przy nowym narzędziu.
- `docs/architecture/overview.md`, `docs/security/SECURITY.md`: architektura i niezmienniki.
- `docs/operations/deployment-vm-systemd.md`, `deploy/`, `Dockerfile`: wdrożenie.
