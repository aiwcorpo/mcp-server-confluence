# mcp-confluence: serwer MCP dla Confluence Data Center

Serwer MCP dla systemu `confluence` (Confluence Data Center, REST API v1 `/rest/api/...`), zbudowany na
`mcp-platform-starter` 3.0.0-beta-14 (Java 25, Spring Boot 4.1.0, Spring AI 2.0.0, kontrakty `mcp-contracts`
6.3.2). Jest produktem do wdrożenia obok gateway'a platformy MCP, nie biblioteką: stoi wyłącznie za gateway'em, przyjmuje ruch
tylko po mTLS z wewnętrznym JWT gateway'a (C2), a do Confluence woła z osobistym tokenem dostępu (PAT) użytkownika,
który gateway dołącza do każdego żądania (C3). Bez poprawnego JWT odpowiada 401, nie ma trybu anonimowego.

Logowania użytkowników, decyzji o dostępie do narzędzi i potwierdzeń serwer nie robi: to zadania gateway'a.
Serwer odpowiada za narzędzia, adapter do Confluence, poprawne błędy (C10) i audyt (C8), który dokłada starter.
Dokumentacja platformy: repozytorium `mcp-docs`.

## Wspierane wersje

| Confluence Data Center | Stan | Co serwer robi |
| --- | --- | --- |
| 8.5 do 10.x (w tym 9.2 LTS i 10.2 LTS) | wspierana | wszystkie narzędzia, zapis włączony |
| starsza niż 8.5 | `degraded` | tylko odczyt, zapis wyłączony (`upstream_unavailable`) |
| 11.0 i nowsza | `degraded` | tylko odczyt, zapis wyłączony do czasu przetestowania |
| wersja nieczytelna albo bez odpowiedzi sondy | `unavailable` | tylko odczyt; zapis tylko po jawnej fladze `MCP_CONFLUENCE_WRITES_ON_UNKNOWN_VERSION=true` i po pierwszej odpowiedzi sondy |

Confluence Server (koniec wsparcia 15.02.2024) i Confluence Cloud nie są wspierane (Cloud: osobna decyzja, wpis `proposed` w
`CONTRACT_CHANGES.md` mcp-contracts, patrz `STATUS.md`). REST v1 `/rest/api/content`, `/space`, `.../label`, `.../child/...` ma
w tym zakresie te same ścieżki; serwer nie używa nic, co zmieniło się między 8.5 a 10.x, dlatego adaptery per wersja nie są
potrzebne, a wersja decyduje tylko o tym, czy zapis jest dozwolony.

Przy starcie i potem co `aiwcorpo.mcp.platform.capability.interval` sonda (`ConfluenceVersionProbe`, bez użytkownika i bez
poświadczenia) czyta wersję: `GET /rest/api/server-information` (pole `version`, w starszych wydaniach `majorVersion`,
`minorVersion`, `patchLevel`), a gdy ta ścieżka jest za logowaniem, manifest `GET /rest/applinks/1.0/manifest` (`version`).
Wynik widać w logu (`backend capability: probe=confluence state=...`), w metryce `mcp_backend_capability` i w
`/actuator/health` (szczegół `backendCapability`, tylko z wewnętrznym JWT). `degraded` i `unavailable` nie zdejmują serwera z
balancera. Operator, którego Confluence ukrywa wersję przed sondą (obie ścieżki odpowiadają, ale bez numeru wersji: stan
`unavailable` po pierwszej odpowiedzi sondy), może włączyć zapis jawnie: `MCP_CONFLUENCE_WRITES_ON_UNKNOWN_VERSION=true`. Flaga nie
działa dla wersji, którą sonda odczytała i nie zna (starsza niż 8.5, 11.x i nowsza: tylko odczyt, niezależnie od flagi), ani przed
pierwszą odpowiedzią sondy (zapis zamknięty).

## Szybki start

```bash
./mvnw verify                 # build i testy; jar: mcp-app/target/mcp-confluence.jar
```

Potrzebny jest JDK 25 (`ci/run.sh` sam go szuka; `./mvnw` wymaga `JAVA_HOME` ustawionego na JDK 25). Testy nie potrzebują Dockera,
Confluence ani `mcp-platform-dev`: `TestGateway` z `mcp-platform-test` wystawia JWKS z kluczy testowych kontraktów i podpisuje
wewnętrzne JWT, a `StubBackend` (katalog testów) odpowiada jak Confluence DC na endpointach opisanych w
`docs/api/tools-catalog.md`. Odpowiedzi sondy wersji są w `mcp-app/src/test/resources/fixtures/confluence-dc-*` (8.5, 9.2,
10.2, 11.0, 7.19 i wersja nieczytelna; opis w `fixtures/README.md`).

## Narzędzia

17 narzędzi. Klient widzi `confluence__<nazwa>`. Uprawnienie to grupa z paczki polityk (C9); do narzędzi z `@ResourceRef`
polityka dokłada ocenę zasobu (przestrzeń z etykietą `restricted` jest niewidoczna, zapis wymaga grantu na przestrzeni albo
drzewie stron).

| Narzędzie | Tytuł | Ryzyko | Akcja | Uprawnienie | Zasoby (`@ResourceRef`) |
| --- | --- | --- | --- | --- | --- |
| `search_pages` | Szukaj stron | `READ_ONLY` | `read` | `mcp-confluence-read` | `space` (`Confluence::Space`); trafienia po `id` (`Confluence::Page`) |
| `list_pages` | Lista stron przestrzeni | `READ_ONLY` | `read` | `mcp-confluence-read` | `space`; trafienia po `id` |
| `list_child_pages` | Lista stron podrzędnych | `READ_ONLY` | `read` | `mcp-confluence-read` | `pageId` (`Confluence::Page`); trafienia po `id` |
| `list_spaces` | Lista przestrzeni | `READ_ONLY` | `read` | `mcp-confluence-read` | trafienia po `key` (`Confluence::Space`) |
| `get_page` | Pobierz stronę | `READ_ONLY` | `read` | `mcp-confluence-read` | `pageId` |
| `get_page_by_title` | Pobierz stronę po tytule | `READ_ONLY` | `read` | `mcp-confluence-read` | `space`; znaleziona strona oceniana tą samą polityką |
| `get_space` | Pobierz przestrzeń | `READ_ONLY` | `read` | `mcp-confluence-read` | `space` |
| `get_page_version` | Pobierz wersję strony | `READ_ONLY` | `read` | `mcp-confluence-read` | `pageId` |
| `list_comments` | Lista komentarzy | `READ_ONLY` | `read` | `mcp-confluence-read` | `pageId` |
| `list_labels` | Lista etykiet strony | `READ_ONLY` | `read` | `mcp-confluence-read` | `pageId` |
| `list_attachments` | Lista załączników | `READ_ONLY` | `read` | `mcp-confluence-read` | `pageId` |
| `read_attachment` | Odczytaj załącznik tekstowy | `READ_ONLY` | `read` | `mcp-confluence-read` | `pageId` |
| `create_page` | Utwórz stronę | `UPDATE` | `update` | `mcp-confluence-write` + zakres | `space`, `parentId` (`Confluence::Page`) |
| `update_page` | Zmień stronę | `UPDATE` | `update` | `mcp-confluence-write` + zakres | `pageId` |
| `add_comment` | Dodaj komentarz | `UPDATE` | `update` | `mcp-confluence-write` + zakres | `pageId` |
| `add_label` | Dodaj etykietę | `UPDATE` | `update` | `mcp-confluence-write` + zakres | `pageId` |
| `delete_page` | Usuń stronę | `DESTRUCTIVE` | `delete` | grupa destrukcyjna + potwierdzenie TOTP w gateway'u | `pageId` |

Argumenty i wyniki (pełny rejestr w `docs/api/tools-catalog.md`):

- Wyszukiwanie: model podaje `space` i filtry `text`, `title`, `label`, `orderBy` (`lastmodified`, `created`, `title`), nigdy
  zapytania. CQL składa `StructuredQuery`: warunki w jednym nawiasie, zakres przestrzeni po nim. Wymagany jest co najmniej jeden filtr.
- Listy (`search_pages`, `list_pages`, `list_child_pages`, `list_spaces`, `list_comments`, `list_attachments`) zwracają
  `PagedResult`: `items` (do 25, komentarze do 10), `nextCursor`, `truncated`, `omitted`; bez `total`. Kursor jest podpisany i zaszyfrowany (beta-13), związany z
  użytkownikiem, narzędziem i argumentami, ważny 15 minut; obcy, zmieniony albo przeterminowany to ten sam `invalid_argument`.
  Strona po filtrze polityki jest dopełniana (`PageFiller`, do 5 odczytów Confluence na wywołanie), żeby krótka strona z kursorem nie
  zdradzała, że coś ukryto; `list_comments` ma okno 10 komentarzy po najwyżej 4000 znaków (razem do około 40 KB), a `list_labels`
  zwraca do 200 etykiet i `truncated`, gdy jest ich więcej.
- Tekst z Confluence (tytuły, treść, komentarze, autor, nazwy etykiet i załączników) jest w wynikach `untrusted-data`.
  Treść jest czytana wyłącznie z `body.storage`; makra spoza krótkiej listy nie są rozwijane, ich nazwy są w `omittedMacros`,
  a w `omitted` listy komentarzy pojawia się `unsupported_macro`.
- Załączniki: `read_attachment` zwraca tekst plików `text/*`, JSON i XML do 200000 bajtów (po 50000 znaków ucięcie z
  `truncated`); inne pliki dają pusty `content` i `omitted`: `binary_attachment` albo `attachment_too_large`. Pobranie idzie
  tylko ścieżką `/download/attachments/<id strony>/...` zwróconą przez Confluence dla tego załącznika.
- Identyfikator podrzędny (komentarz, załącznik, wersja, strona podrzędna) jest zawsze sprawdzany względem strony, którą
  polityka oceniła; niezgodność daje `not_found_or_denied` (albo element jest pomijany na liście).

### Zapis i powtórzenie wywołania

`UPDATE` jest dostarczane co najmniej raz (CC-204), więc narzędzia zapisu są zaprojektowane pod powtórzenie:

| Narzędzie | Powtórzenie |
| --- | --- |
| `create_page` | Confluence odmawia drugiej strony o tym samym tytule w przestrzeni: `invalid_argument`, bez duplikatu |
| `update_page` | `expectedVersion` (wersja odczytana przez wywołującego) i blokada optymistyczna: edycja idzie tylko na dokładnie tę wersję, powtórzenie po udanym zapisie kończy się konfliktem, nie drugim zapisem |
| `add_label` | etykieta, którą strona już ma, zostaje bez zmian (stan docelowy) |
| `add_comment` | powtórzenie dubluje komentarz (Confluence nie daje klucza); opis narzędzia to mówi |
| `delete_page` | strona w koszu nie jest już stroną bieżącą: powtórzenie to `not_found_or_denied`; wymaga nowego kodu TOTP |

`update_page(pageId, expectedVersion, mode, body, title?)`: `mode=append` dopisuje akapity po istniejącej treści i zachowuje
wszystko, co jest na stronie (makra, tabele, obrazy); `mode=replace` zastępuje treść i jest dozwolone tylko dla strony złożonej
z samych akapitów tekstu (taką tworzy `create_page`), inaczej `invalid_argument` z podpowiedzią, by użyć `append`. Treść to zwykły
tekst, escapowany do storage format: model nie wprowadzi makra ani HTML. Konflikt wersji i `replace` na stronie z formatowaniem to
`invalid_argument` z własnym tekstem serwera. `delete_page` przenosi stronę do kosza przestrzeni (Confluence `DELETE
/rest/api/content/{id}`); strony podrzędne zostają.

### Błędy (C10)

`credential_required` (brak poświadczenia, 401 z Confluence albo schemat inny niż `Bearer`), `not_found_or_denied` (404, 403,
przestrzeń `restricted`, przestrzeń osobista, id podrzędne spoza strony: ten sam tekst), `invalid_argument` (także 400 z
Confluence, konflikt wersji, kursor), `upstream_unavailable` (502-504, zapis na niezweryfikowanej wersji),
`internal_error`. Odpowiedzi Confluence nie trafiają do komunikatów ani logów.

### Manifest i metryki

Manifest do rejestracji w gateway'u to artefakt budowy `mcp-app/target/mcp-manifest.json`. Powstaje w fazie `package`
(`exec-maven-plugin`, klasa `ManifestExport`), więc jest też po `./mvnw -DskipTests package`. Klasa nie startuje serwera: składa
manifest tym samym `ManifestGenerator` z konfiguracji produkcyjnej i listy narzędzi z `McpToolsConfiguration`, więc manifest
odrzucany przez gateway zatrzymuje budowę. Tylko `READ_ONLY` jest zatwierdzane automatycznie; `UPDATE` i `DESTRUCTIVE`
czekają na `approve` administratora. `ManifestTest` porównuje narzędzia z `ManifestExport` z `tools/list` działającego serwera.

Alerty: zdarzenia `authentication` ponad limit zlicza `mcp_audit_authentication_suppressed_total`.

## Wymagane uprawnienia konta i token

Serwer woła Confluence jako użytkownik, więc dostęp wyznaczają jego prawa. PAT Data Center nie ma zakresów (scope): dziedziczy
wszystkie prawa konta. Dlatego zalecane jest osobne konto użytkownika na osobę, nie konto współdzielone, i prawa nie szersze, niż
ma wynikać z narzędzi.

| Narzędzia | Prawo konta w przestrzeni |
| --- | --- |
| odczyt (wszystkie `READ_ONLY`) | Widok przestrzeni i stron (View), dla komentarzy i załączników odpowiednio ich odczyt |
| `create_page`, `update_page`, `add_label` | Dodawanie i edycja stron (Add/Edit pages) |
| `add_comment` | Dodawanie komentarzy (Add comments) |
| `delete_page` | Usuwanie stron (Delete pages) |

PAT musi być włączony na instancji (domyślnie jest, administrator może go wyłączyć; kontrolka w administracji Confluence,
sekcja Personal Access Tokens). Użytkownik tworzy token w profilu (Settings, Personal Access Tokens) i podaje go w portalu
platformy; token ma czas życia (domyślnie do 365 dni) i trzeba go odnowić przed wygaśnięciem, bo wygasły daje
`credential_required`. Brak PAT na instancji klienta to blokada wdrożenia tego typu poświadczenia.

## Instalacja

Wymagania, układ plików, kroki dla VM z systemd i typowe problemy: `docs/operations/deployment-vm-systemd.md`
(`deploy/scripts/install.sh`, `update.sh`, `healthcheck.sh`, szablon środowiska `deploy/systemd/mcp-confluence.env.template`).
Wariant kontenerowy: `Dockerfile` (po `./mvnw verify`; obraz `eclipse-temurin:25-jre`, glibc, bo `cedar-java` ładuje bibliotekę
natywną, użytkownik `mcp`, port 8443, mTLS) i `ci/run.sh image`, który buduje `mcp-server-confluence:m6-<sha7>`; kontener dostaje
tę samą konfigurację ze zmiennych środowiska i plików TLS montowanych tylko do odczytu.

Artefakty platformy (`mcp-platform-starter`, `mcp-contracts`) są w GitHub Packages organizacji `aiwcorpo`. Przy budowie ze
źródeł wystarczy `~/.m2` z tymi artefaktami albo rejestr w `MCP_MAVEN_REPOSITORY_URL` (+ `_USER`, `_PASSWORD`); układ
`settings.xml` z dwoma serwerami `github-starter` i `github-contracts` opisuje README `mcp-starter-lib`, rozdział "Instalacja".

## Codzienna obsługa

- Zdrowie: `deploy/scripts/healthcheck.sh` (wymaga certyfikatu klienta); szczegół `backendCapability` pokazuje wersję
  Confluence, stan sondy i zestaw cech.
- Aktualizacja: `deploy/scripts/update.sh <nowy.jar>`, wycofanie: przywrócić `.bak`. Po aktualizacji zarejestrować w gateway'u
  świeży manifest z `mcp-app/target/mcp-manifest.json`.
- Klucz kursorów `MCP_CURSOR_KEY` (Base64, co najmniej 32 bajty) jest taki sam na wszystkich instancjach; zmiana klucza unieważnia
  kursory w locie (żyją 15 minut), nic poza tym.
- Wygasłe tokeny: użytkownicy dostają `credential_required` z odnośnikiem do portalu; po stronie serwera nic nie trzeba robić.
- Audyt C8 idzie na stdout (journald), logger `pl.aiwcorpo.mcp.audit`. Linie operacyjne: `event=page_created_outside_judged_tree`
  (strona utworzona poza oceniony zakres, wymaga ręcznego sprawdzenia), `backend capability: ... state=degraded`.
- Zmiana wersji Confluence (upgrade): sonda sama zauważy nową wersję w ciągu interwału; po przejściu na 11.x serwer przechodzi
  w odczyt do czasu wydania serwera przetestowanego na tej wersji (flaga zapisu tego nie zmienia).

## Pierwsze uruchomienie u klienta (tylko GET)

Kolejność sprawdzeń, każde to odczyt; nic nie zmienia danych:

1. Wersja: `curl -s https://HOST/rest/api/server-information` (pole `version`). Jeśli 401/403, `curl -s https://HOST/rest/applinks/1.0/manifest`
   (element `version`). Wynik musi się mieścić w 8.5 do 10.x; po starcie serwera to samo w logu `backend capability`.
2. PAT włączone: użytkownik testowy tworzy token (Settings, Personal Access Tokens); brak tej pozycji w profilu znaczy, że PAT
   jest wyłączony przez administratora.
3. Token działa: `curl -s -H "Authorization: Bearer $PAT" https://HOST/rest/api/space?limit=1` daje 200.
4. Etykiety przestrzeni: `curl -s -H "Authorization: Bearer $PAT" "https://HOST/rest/api/space/KLUCZ?expand=metadata.labels"` pokazuje
   `metadata.labels` z `size`, `limit` i bez `_links.next`; klasyfikacja `restricted` jest brana z tych etykiet.
5. Strona: `.../rest/api/content/ID?expand=body.storage,space,version,ancestors` zwraca `body.storage.value`, `space.key`,
   `ancestors`; to pola, na których polega serwer.
6. Komentarze i załączniki: `.../rest/api/content/ID/child/comment?expand=body.storage,version,ancestors,container` oraz
   `.../child/attachment` (w `extensions` `mediaType` i `fileSize`, w `_links` `download`).
7. Wyszukiwanie: `.../rest/api/content/search?cql=space%3D%22KLUCZ%22%20AND%20text~%22slowo%22&limit=1` daje 200.
8. Dopiero potem `tools/call` przez gateway na `get_space` i `list_pages` kontem testowym.

Odpowiedzi z pkt 1, 3-6 warto zapisać (zanonimizowane) jako fixture w `mcp-app/src/test/resources/fixtures/confluence-dc-<wersja>/`,
żeby testy odwzorowywały instancję klienta.

## Moduły i miejsce każdej rzeczy

| Moduł | Co w nim jest |
| --- | --- |
| `mcp-core` | domena (`Page`, `PageRecord`, `Space`, `PageSummary`, `PageText`, `Slice`, `Comment`, `Attachment`, `PageEdit`, `NewPage`, wyjątki domeny, `ConfluenceIds` z wzorcami identyfikatorów z kontraktu), porty `ConfluenceUseCase`, `ConfluenceResourcePort` i `BackendFeatures` |
| `mcp-application` | `ConfluenceService`: filtr przestrzeni w wyszukiwaniu, porównanie strony i elementów podrzędnych z tym, co oceniła polityka, kontrola rodzica, bramka zapisu wg wersji |
| `mcp-integration` | `ConfluenceResourceAdapter` (REST v1, PAT z C3, `https` poza `dev`, timeouty, bez przekierowań, `BoundedBodyInterceptor` z limitem rozmiaru odpowiedzi, `BackendResponseException` bez treści), `ConfluencePageResolver` i `ConfluenceSpaceResolver` (`ResourceResolver`, opis zasobu w `ConfluenceResourceDescription`), `ConfluenceVersionProbe` i `CapabilityBackendFeatures` (sonda wersji i bramka zapisu), `Cql` (wyszukanie stron po id dla resolvera), `StorageFormat` (storage -> tekst bez makr, tekst -> storage z escapowaniem) |
| `mcp-tools` | `ConfluencePageTools` (17 narzędzi), `ToolResults` (kształt wyników), `ConfluenceToolErrors` (wyjątki domeny -> C10), `@ArgumentLimits` na argumentach (wzorce przez `contractPattern`); rejestracja przez `SchemaLimitedToolCallbacks` ze startera w `McpToolsConfiguration` |
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
| `MCP_CONFLUENCE_WRITES_ON_UNKNOWN_VERSION` | `true` dopuszcza zapis, gdy sonda nie mogła odczytać wersji (stan `unavailable`); nie zmienia nic dla wersji starszej niż 8.5 i dla 11.x (patrz Wspierane wersje) | `false` |
| `MCP_CURSOR_KEY` | klucz HMAC kursorów list (Base64, co najmniej 32 bajty), taki sam na wszystkich instancjach; bez niego każdy proces losuje własny | losowy |
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
export JAVA_HOME=...   # JDK 25
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
export JAVA_HOME=...   # JDK 25
export MCP_PLATFORM_DEV=...   # ścieżka bezwzględna do katalogu mcp-platform-dev
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

Atrapa backendu: `StubBackend` (serwer HTTP na pętli zwrotnej, prawdziwy klient, timeouty i mapowanie błędów) odpowiada jak Confluence DC
na wszystkich endpointach używanych przez serwer, a odpowiedzi sondy wersji pochodzą z plików `fixtures/confluence-dc-<wersja>/`
(8.5, 9.2, 10.2, 11.0, 7.19, nieczytelna). Kształt treści jest wspólny dla wersji, bo ścieżki REST v1 są te same; różnice
dotyczą sondy. Fixture nie są nagraniami (patrz `fixtures/README.md`). Bez pobierania obrazów produktów.

| Klasa | Co sprawdza |
| --- | --- |
| `VersionCoverageTest` | sonda dla 8.5 (major/minor/patch), 9.2 (przez manifest), 10.2 (`version`), 7.19 i 11.0 (`degraded`, tylko odczyt), wersja nieczytelna (`unavailable`); sonda bez poświadczenia; na 11.0 odczyt działa, a każdy zapis kończy się `upstream_unavailable` bez żądania zapisu do Confluence |
| `CredentialTypeTest` | `user-secret` przechodzi; `user-oauth`, `token-exchange`, `service-account`, brak nagłówka typu i błędna pisownia -> `internal_error`, zero żądań do Confluence |
| `SearchPagesToolTest` | CQL budowany przez serwer ze `StructuredQuery` (dokładny `cql`); CQL w `text` nie poszerza zapytania; blog i trafienia z innej przestrzeni odrzucone; `credential_required`; zły `space`/`text`, dodatkowy argument -> `invalid_argument` bez wywołania Confluence; 400 wyszukiwarki -> `invalid_argument` bez jej tekstu; przekierowanie to błąd, nie pusty wynik |
| `ReadToolsTest` | stronicowanie kursorem (25 + 5, bez `total`), kursor obcego użytkownika, innego narzędzia i zmienionych argumentów odrzucony; filtry `title`, `label`, `orderBy` i ich walidacja; `list_child_pages`, `list_spaces` (przestrzenie `restricted` i bez znanych etykiet pominięte, jedno wywołanie zbiorcze dla polityki); `get_page_by_title` (ten sam błąd dla braku i zakazu); `get_page_version`; komentarze (rodzic, lokalizacja, `unsupported_macro`, treść HR nie przecieka); etykiety tylko globalne; załączniki tekstowe, binarne i zbyt duże; załącznik innej strony i strona ukryta -> ten sam `not_found_or_denied`; treść spoza sprawdzonego obiektu nigdy nie jest oddana |
| `GetPageToolTest` | PAT z C3 dociera do Confluence; makra include/excerpt-include nie dają treści z HR; 404 = 403; blog i przestrzeń osobista -> `not_found_or_denied`; `credential_required`; zły `pageId`; nieczytelna odpowiedź, przekierowanie, odpowiedź ponad limit -> `internal_error` bez treści w logu; PAT ani treść błędu nie trafiają do wyniku i logu |
| `CreatePageToolTest` | rodzic sprawdzany przed zapisem; treść escapowana; rodzic z innej przestrzeni, nieistniejący i zabroniony -> ten sam błąd bez POST; 400 -> `invalid_argument` bez tekstu Confluence; `credential_required`; złe argumenty |
| `WriteToolsTest` | `update_page` append i replace (dokładny PUT: wersja + 1, tytuł, storage), replace odmówione dla strony z makrami, wersja nieaktualna i 409 z Confluence -> konflikt bez drugiego zapisu, strony poza grantem -> `not_found_or_denied` bez zapisu, czytelnik -> `access_denied`, złe argumenty; `add_comment` (escapowanie, odpowiedź pod komentarzem tej strony, komentarz innej strony lub brak -> `not_found_or_denied` bez POST); `add_label` (powtarzalne, wzorzec etykiety); `delete_page` jest `DESTRUCTIVE` i bez grantu `access_denied` |
| `AdapterWritesTest` | `DELETE` strony jako użytkownik (usunięcie, 403/404 -> to samo `PageNotFoundException`), z poświadczeniem związanym przez filtr startera |
| `ResourcePolicyTest` | strona i przestrzeń `restricted` = brak (ten sam tekst), nieznane etykiety, brak grupy -> `access_denied` bez wywołania Confluence, trafienia oceniane po aktualnej przestrzeni, awaria resolvera usuwa trafienie, `create_page`: zakres przestrzeni albo drzewa |
| `ManifestTest` | narzędzia z `ManifestExport` równe `tools/list`; `GET /mcp-manifest` przechodzi reguły gateway'a; `definitionHash`, tytuły, `resourceRefs` i ryzyko wszystkich 17 narzędzi |
| `ToolListingTest` | dokładnie 17 narzędzi, wskazówki `readOnlyHint`/`destructiveHint`; `additionalProperties: false`, limity każdego argumentu; wzorce identyfikatorów z kontraktu; pola opcjonalne nie są wymagane |
| `ProductionConfigurationTest`, `DevProfileLoopbackTest`, `GatewayOnlyAccessTest` | konfiguracja bazowa (mTLS, https backendu), `dev` tylko na pętli zwrotnej, bez JWT 401 |
| `StorageFormatTest`, `PlainParagraphsTest`, `CapabilityBackendFeaturesTest` | storage <-> tekst, test strony złożonej z samych akapitów (warunek `replace`), bramka zapisu |
| `ConfluenceServiceTest` | filtry przestrzeni, porównanie z tym, co oceniła polityka, komentarz i trash tylko w miejscu oceny, bramka zapisu bez dotykania Confluence (bez Springa) |

## Dokumentacja

- `STATUS.md`: stan, odstępstwa, czego potrzebują inne strumienie.
- `docs/api/tools-catalog.md`: rejestr narzędzi.
- `docs/jak-napisac-narzedzie.md`: zasady przy nowym narzędziu.
- `docs/architecture/overview.md`, `docs/security/SECURITY.md`: architektura i niezmienniki.
- `docs/operations/deployment-vm-systemd.md`, `deploy/`, `Dockerfile`: wdrożenie.

## CI

Cała logika jest w `ci/run.sh` (`make ci` woła `ci/run.sh verify`); `.gitlab-ci.yml`, `Jenkinsfile` i `.github/workflows/ci.yml`
to cienkie nakładki, więc to samo działa lokalnie, w obrazie `maven:3.9.12-eclipse-temurin-25` i w każdym z trzech systemów.

| Krok | Co robi |
| --- | --- |
| `ci/run.sh verify` (domyślny) | `hygiene`, `mvnw clean verify`, potem kontrola, że każdy moduł z `CI_TEST_MODULES` uruchomił testy (pominięte nie liczą się) i że powstał `mcp-app/target/mcp-manifest.json` |
| `ci/run.sh hygiene` | cudzysłowy, spacje i wielokropek spoza ASCII oraz ślady narzędzi w źródłach (perl czyta przez `<<>>`) |
| `ci/run.sh image` | `docker build` jaru z `verify` jako `mcp-server-confluence:<prefiks>-<sha7>` (prefiks z `CI_IMAGE_TAG_PREFIX`, domyślnie `m6`); tylko gałąź `main`, wymaga demona Dockera |

Inny argument kończy się kodem 2. Merge request i pull request nie dostają sekretów ani gniazda Dockera: testy nie
potrzebują kontenerów, a rejestr Maven z artefaktami platformy (`MCP_MAVEN_REPOSITORY_URL`, zwykła zmienna) jest wtedy
czytany anonimowo (`.ci/settings-anonymous.xml`); w GitHub Actions pull request czyta GitHub Packages tokenem przebiegu z
`packages: read`. Obrazy buildu w CI są przypięte po digeście. Dane dostępu (`MCP_MAVEN_REPOSITORY_USER`, `_PASSWORD`; w GitLabie
zmienne Protected i Masked, w Jenkinsie poświadczenie `mcp-maven-registry`) widzi tylko gałąź `main`, na której działa też
krok `image` (runner z gniazdem Dockera; w GitLabie tag `docker-socket` musi należeć do runnera chronionego (protected),
inaczej gniazdo trafiłoby w ręce merge requestu; w GitHubie sekrety są w Environment `ci-main`). Moduły z wymaganymi
testami: `CI_TEST_MODULES` (domyślnie `mcp-application mcp-integration mcp-tools mcp-app`), dolna granica dla `mcp-app`: `CI_APP_MIN_TESTS` (104).
Rejestracja manifestu w gateway'u (`PUT` z certyfikatem `mcp-registry-ci-mcp-confluence`) jest osobnym krokiem pipeline'u
serwera, bo wymaga sekretu, którego repo nie zawiera.
