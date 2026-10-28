# mcp-confluence: serwer MCP dla Confluence Data Center

Serwer MCP dla systemu `confluence` (Confluence Data Center, REST API `/rest/api/content`), zbudowany na
`mcp-platform-starter` 3.0.0-beta-2 (Java 25, Spring Boot 4.1.0, Spring AI 2.0.0, kontrakty `mcp-contracts`
3.1.0). Stoi wyłącznie za gateway'em platformy: przyjmuje ruch tylko po mTLS z wewnętrznym JWT gateway'a (C2),
a do Confluence woła z osobistym tokenem dostępu (PAT) użytkownika, który gateway dołącza do każdego żądania
(C3). Bez poprawnego JWT odpowiada 401, nie ma trybu anonimowego.

Logowania użytkowników, decyzji o dostępie do narzędzi i potwierdzeń serwer nie robi: to zadania gateway'a.
Serwer odpowiada za narzędzia, adapter do Confluence, poprawne błędy (C10) i audyt (C8), który dokłada starter.

## Szybki start

```bash
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@25/25.0.4.1/libexec/openjdk.jdk/Contents/Home
./mvnw verify                 # build i 73 testy; jar: mcp-app/target/mcp-confluence.jar
```

Testy nie potrzebują Dockera ani `mcp-platform-dev` (tam nie ma mocka Confluence): `TestGateway` z
`mcp-platform-test` wystawia JWKS z kluczy testowych kontraktów i podpisuje wewnętrzne JWT, a `StubBackend`
(test) odpowiada jak Confluence DC 8/9 na `/rest/api/content/{id}?expand=...`, `/rest/api/content/search?cql=...`
i `POST /rest/api/content`.

## Narzędzia

| Narzędzie (klient widzi `confluence__<nazwa>`) | Tytuł | Ryzyko | Akcja Cedar | Uprawnienie (grupa) | Zasób (`@ResourceRef`) | Argumenty | Wynik |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `search_pages` | Szukaj stron | `READ_ONLY` | `read` | `mcp-confluence-read` | brak w argumencie; zakres z linku (`QueryScope`), trafienia filtrowane po `id` (`Confluence::Page`) | `space` (`^[A-Za-z0-9]{1,255}$`), `text` (1-200 znaków, jedna linia) | `{spaceKey, pages: [{id, title: untrusted-data, version, lastUpdated}]}`, do 25 stron |
| `get_page` | Pobierz stronę | `READ_ONLY` | `read` | `mcp-confluence-read` | `pageId`: `Confluence::Page` | `pageId` (`^[1-9][0-9]{0,18}$`) | `{id, spaceKey, title, version, lastUpdated, ancestorIds, body: untrusted-data, omittedMacros}` |
| `get_space` | Pobierz przestrzeń | `READ_ONLY` | `read` | `mcp-confluence-read` | `space`: `Confluence::Space` | `space` | `{key, name: untrusted-data}` |
| `create_page` | Utwórz stronę | `UPDATE` | `update` | `mcp-confluence-write` oraz zakres (przestrzeń albo drzewo stron) przyznany grupą | `parentId`: `Confluence::Page` | `space` (musi być przestrzenią rodzica), `parentId`, `title` (1-255, jedna linia), `body` (zwykły tekst, 1-20000) | `{id, spaceKey, title: untrusted-data, version}` |

Wszystkie używają poświadczenia `user-secret` (PAT) jako `Bearer`. `title` w tabeli to polski tytuł z `@ToolTitle`
(część `definitionHash`).

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

- `search_pages`: model nie pisze CQL. Serwer składa go ze `StructuredQuery` startera: `scope.cql(...)` z polami
  `space`, `type`, `text`, wartości zawsze w cudzysłowach i escapowane, zakres linku wirtualnego serwera dołożony
  przez starter (`space in (...)`): `space = "ENG" AND type = "page" AND text ~ "słowa"`. Z `text` zostają litery,
  cyfry, odstępy i `. _ '`, reszta zamienia się w spację. Przestrzeń poza linkiem daje pustą listę bez pytania
  Confluence. Trafienia spoza przestrzeni i inne typy treści są odrzucane po stronie serwera, a każde trafienie
  sprawdza `ResultFilter` (strona jest oceniana po przestrzeni, w której jest teraz, nie po tej z indeksu).
  Przekierowanie (3xx), odpowiedź bez treści, nieczytelna albo bez `id` to `internal_error`, nie pusty wynik.
- `get_page`: strona jest rozwiązywana przed narzędziem (`ConfluencePageResolver`: strona, `ancestorIds`, etykiety jej
  przestrzeni) i oceniana przez politykę; strona w przestrzeni z etykietą `restricted`, nieistniejąca, ukryta albo
  w przestrzeni osobistej daje to samo `not_found_or_denied`. Dopiero potem czytana jest treść, a strona przeniesiona w
  międzyczasie do innej przestrzeni lub drzewa nie jest zwracana. Treść pochodzi wyłącznie z `body.storage` strony. `body.view` i `body.export_view` Confluence
  renderuje z rozwiniętymi makrami include i excerpt-include, czyli z treścią innych stron i przestrzeni, więc
  adapter nigdy o nie nie prosi. W storage zostają tylko makra z treścią zapisaną na tej stronie (`code`,
  `noformat`, `info`, `note`, `warning`, `tip`, `panel`, `expand`, `excerpt`), bez parametrów; każde inne makro,
  także nieznane, jest usuwane razem z parametrami (np. odwołaniem `ri:space-key`) i zastępowane znacznikiem
  `[macro omitted: <nazwa>]`, a nazwa trafia do `omittedMacros` (obok `body`). Wynik to zwykły tekst bez HTML, do 50000
  znaków. Blogi, komentarze, załączniki i strony przestrzeni osobistych (`~user`) dają `not_found_or_denied`.
- `create_page`: `body` to zwykły tekst. Pusta linia rozdziela akapity (`<p>`), pojedynczy koniec linii to
  `<br />`, a `& < > " '` są escapowane, więc z tekstu modelu nie powstanie makro ani element HTML. Rodzic (`parentId`)
  jest `@ResourceRef` z akcją `update`: polityka wymaga grupy `mcp-confluence-write` i zakresu obejmującego rodzica.
  Serwer sprawdza jeszcze, że `space` to przestrzeń tego rodzica (tak jak ją ocenia polityka); inaczej
  `not_found_or_denied` i nic nie jest tworzone.
- `get_space`: przestrzeń z etykietą `restricted` jest niewidoczna; etykiet w wyniku nie ma.
- Etykiety przestrzeni (`Confluence::Space.labels`) są klasyfikacją i pochodzą z Confluence
  (`/rest/api/space/{key}?expand=metadata.labels`). Brak listy albo lista dłuższa niż jedna odpowiedź to klasyfikacja
  nieznana: zasób jest odrzucany (`internal_error`), nigdy traktowany jako niesklasyfikowany.

## Moduły i miejsce każdej rzeczy

| Moduł | Co w nim jest |
| --- | --- |
| `mcp-core` | domena (`Page`, `PageRecord`, `Space`, `PageSummary`, `PageText`, `NewPage`, `ConfluenceResourceModel`, `PageNotFoundException`, `PageRejectedException`, `ConfluenceIds` z wzorcami identyfikatorów z kontraktu), porty `ConfluenceUseCase` i `ConfluenceResourcePort` |
| `mcp-application` | `ConfluenceService`: filtr przestrzeni w wyszukiwaniu, porównanie strony z tym, co oceniła polityka, kontrola rodzica |
| `mcp-integration` | `ConfluenceResourceAdapter` (REST, PAT z C3, `https` poza `dev`, timeouty, bez przekierowań, `BoundedBodyInterceptor` z limitem rozmiaru odpowiedzi, `BackendResponseException` bez treści), `ConfluencePageResolver` i `ConfluenceSpaceResolver` (`ResourceResolver`, opis zasobu w `ConfluenceResourceDescription`), `Cql` (wyszukanie stron po id dla resolvera), `StorageFormat` (storage -> tekst bez makr, tekst -> storage z escapowaniem) |
| `mcp-tools` | `ConfluencePageTools` (cztery narzędzia), `ToolResults` (kształt wyników), `ConfluenceToolErrors` (wyjątki domeny -> C10), `@ArgumentLimits` na argumentach (wzorce przez `contractPattern`); rejestracja przez `SchemaLimitedToolCallbacks` ze startera w `McpToolsConfiguration` |
| `mcp-app` | `McpServerApplication`, `McpToolsConfiguration` (jawna lista narzędzi), `ApplicationWiringConfiguration`, testy end-to-end |

## Jak przechodzi wywołanie

```
gateway --mTLS (CN mcp-gateway) + JWT C2 (aud mcp-confluence, tool get_page) + X-MCP-Credential: Bearer <PAT>-->
  starter: InternalJwtFilter (inaczej 401) -> CredentialHeaderFilter -> AuditingToolCallback (C8)
  -> starter: walidacja argumentów względem inputSchema (inaczej C10 invalid_argument + C8)
  -> policy: Action call (Cedar z paczki), potem ConfluencePageResolver (GET strony i przestrzeni) i Action read
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
| `MCP_POLICY_URL`, `MCP_POLICY_KEY_ID`, `MCP_POLICY_PUBLIC_KEY_FILE`, `MCP_POLICY_STATE_DIR` | podpisana paczka polityk (C7): adres `latest.json`, id klucza, klucz publiczny Ed25519 (SPKI PEM), katalog na ostatnio przyjętą rewizję (bez niego restart przyjąłby starszą paczkę); serwer nie wstaje bez pierwszej paczki | wymagane |
| `MCP_INTERNAL_URL`, `MCP_ACCEPTED_CREDENTIAL_TYPES` | dane manifestu (`GET /mcp-manifest`): adres serwera dla gateway'a i typy poświadczeń | wymagane, `user-secret` |
| `MCP_PORT`, `MCP_BIND` | port i adres nasłuchu | `8443`, `0.0.0.0` |

Endpoint MCP to `/mcp` (gateway: `internal-url: https://mcp-confluence.internal:8443/mcp`). Bez JWT odpowiada
tylko `/actuator/health` (i `liveness`, `readiness`), a TLS i tak wymaga certyfikatu klienta z CA wewnętrznego.
Serwer nie wstaje bez `MCP_GATEWAY_JWKS_URI`, `MCP_CONNECT_URL`, `MCP_CONFLUENCE_URL`, paczki polityk i plików TLS.
Obraz oparty o glibc (`eclipse-temurin:25-jre`, nie Alpine): `cedar-java` ładuje bibliotekę natywną.

`java` w `PATH` może być starsza niż 25 (wtedy `UnsupportedClassVersionError`), dlatego poniżej zawsze
`$JAVA_HOME/bin/java`. Limit odpowiedzi Confluence to `confluence.backend.max-response-size` (1MB); większa
odpowiedź jest ucinana w trakcie czytania, przed parsowaniem JSON i Jsoup, i daje `internal_error`.

## Uruchomienie lokalne bez środowiska

Profil `dev` (musi być jedynym aktywnym profilem): zwykłe HTTP na 127.0.0.1, `jti` w pamięci, bez Redisa,
certyfikatów i sekretów. Paczkę polityk bierze z repozytorium paczek (`http://127.0.0.1:18747/policies/main/latest.json`, klucz
`../mcp-policies/keys/dev/`, uruchamiaj z katalogu repozytorium); bez niego serwer nie wstaje. Gateway'a nie ma, więc każde żądanie z JWT kończy się 503 (JWKS niedostępny); ten tryb
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
| `ResourcePolicyTest` | strona i przestrzeń `restricted` = brak (ten sam tekst), nieznane etykiety -> `internal_error`, brak grupy -> `access_denied` bez wywołania Confluence, link zawężony do ENG ukrywa strony i przestrzenie poza nim, wyszukiwanie poza linkiem to pusta lista bez zapytania, trafienia oceniane po aktualnej przestrzeni (indeks vs. rzeczywistość), awaria resolvera usuwa trafienie, `create_page`: zakres przestrzeni albo drzewa, rodzic z innej lub zastrzeżonej przestrzeni |
| `ManifestTest` | `GET /mcp-manifest` przechodzi reguły gateway'a, definicje równe `tools/list` (`definitionHash`), tytuły, `resourceRefs` i ryzyko |
| `ToolListingTest` | dokładnie cztery narzędzia; `additionalProperties: false`, `minLength`/`maxLength`/`pattern` w `inputSchema`; wzorce identyfikatorów z kontraktu |
| `ProductionConfigurationTest` | konfiguracja bazowa: `client-auth: need`; backend `http` poza `dev` odrzucony (chyba że jawnie dopuszczony); `dev` na 127.0.0.1 |
| `DevProfileLoopbackTest` | `dev` z adresem innym niż pętla zwrotna nie wstaje |
| `GatewayOnlyAccessTest` | bez JWT 401 (`tools/call`, `ping`, `initialize`), JWT innego serwera, innego narzędzia, wariant serwisowy, przeterminowany -> 401; bez JWT tylko health |
| `StorageFormatTest` | storage -> tekst (makra, encje, listy, tabele, obcięcie), tekst -> storage, zapytanie po id |
| `ConfluenceServiceTest` | filtr przestrzeni w wyszukiwaniu, przestrzeń osobista, rodzic z innej przestrzeni (bez Springa) |

## Dokumentacja

- `STATUS.md`: stan, odstępstwa, czego potrzebują inne strumienie.
- `docs/api/tools-catalog.md`: rejestr narzędzi.
- `docs/jak-napisac-narzedzie.md`: zasady przy nowym narzędziu.
- `docs/architecture/overview.md`, `docs/security/SECURITY.md`: architektura i niezmienniki.
- `docs/operations/deployment-vm-systemd.md`, `deploy/`, `Dockerfile`: wdrożenie.
