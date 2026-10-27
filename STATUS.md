# STATUS - mcp-server-confluence (ws-04)

M1: osiągnięty (2026-10-27) - `search_pages`, `get_page` (READ_ONLY) i `create_page` (UPDATE) na starterze 3.0.0-alpha-5, PAT użytkownika z C3 jako Bearer, treść stron z `body.storage` bez makr include/excerpt-include, 34 testy na stubie Confluence DC.

M1 według definicji koordynatora (brief ws-04 nie ma kamieni). M2 nie jest zaczęty.

## Co działa

- Wygenerowany z `mcp-server-template` (7f65f8f) przez `./new-server.sh confluence`; BOM
  `pl.aiwcorpo.mcp:mcp-platform-bom:3.0.0-alpha-5` (kontrakty 2.0.2), `server-id` `mcp-confluence`, endpoint
  `/mcp`, konfiguracja produkcyjna z template'u bez zmian (mTLS `need`, JWKS gateway'a po https z CA
  wewnętrznym, `jti` w Redisie). Obejście `defaultStatusHandler` z template'u zostało.
- Narzędzia (rejestracja tylko w `McpToolsConfiguration`):
  - `search_pages(space, text)`, `READ_ONLY`: przestrzeń obowiązkowa, CQL składa serwer
    (`space = "<k>" AND type = page AND text ~ "<słowa>"`), z `text` tylko litery, cyfry, odstępy i `. _ '`;
    do 25 wyników; serwer odrzuca też trafienia spoza przestrzeni i inne typy treści.
  - `get_page(pageId)`, `READ_ONLY`: tylko `body.storage`; makra spoza listy z treścią lokalną (`code`,
    `noformat`, `info`, `note`, `warning`, `tip`, `panel`, `expand`, `excerpt`) usuwane razem z parametrami,
    zastępowane `[macro omitted: <nazwa>]` i wypisane w `body.omittedMacros`; wynik to zwykły tekst w
    `body: {kind: "untrusted-data", ...}`, do 50000 znaków. Blog, komentarz, przestrzeń osobista ->
    `not_found_or_denied`.
  - `create_page(space, parentId, title, body)`, `UPDATE`: `body` jako zwykły tekst, escapowany do storage
    (akapity, `<br />`); rodzic musi być widoczną stroną tej samej przestrzeni, inaczej `not_found_or_denied` bez
    `POST`; 400 Confluence -> `invalid_argument` z tekstem serwera.
- Opisy narzędzi deterministyczne, bez instrukcji dla modelu. `inputSchema`: wszystkie argumenty wymagane,
  `additionalProperties: false`, `minLength`/`maxLength`/`pattern` z `@StringArgument` (Spring AI nie czyta
  limitów z parametrów, więc `ConstrainedToolCallbacks` dopisuje je do schematu; callbacki zostają
  `MethodToolCallback`, starter dalej czyta `@ToolRisk`). Wzorce identyfikatorów `ConfluenceSpaceKey` i
  `ConfluencePageId` z kontraktu (`ConfluenceIds`); błędy przez `InvalidToolArgumentException`.
- Poświadczenie: `CredentialContext.require("confluence")`, tylko schemat `Bearer` (inny -> `credential_required`
  bez wywołania Confluence). 401 -> `credential_required`, 403 i 404 -> ten sam `not_found_or_denied`, treść
  błędów Confluence nie trafia do wyniku ani logu.
- Testy (`./mvnw verify`, JDK 25, bez Dockera): 34, 0 błędów. `SearchPagesToolTest` 5, `GetPageToolTest` 10,
  `CreatePageToolTest` 6, `ToolListingTest` 1, `GatewayOnlyAccessTest` 3, `StorageFormatTest` 6,
  `ConfluenceServiceTest` 3. Opis w README, sekcja "Testy". `StubBackend` odpowiada kształtem Confluence DC 8/9
  (`expand`, `_links`, `_expandable`, błędy `{statusCode, data, message}`, `POST` 200 z treścią) i oddaje
  rozwiniętą treść HR wyłącznie w `body.view`, więc test include łapie adapter, który poprosiłby o widok.
- Smoke konfiguracji produkcyjnej na hoście (port 18510, certyfikat i użytkownik Redisa `mcp-confluence` z
  `mcp-platform-dev`): start, `/actuator/health` po mTLS z certyfikatem gateway'a 200, `POST /mcp` bez JWT 401,
  bez certyfikatu klienta handshake zerwany. Proces zatrzymany. Pełnego przebiegu przez gateway nie robiłem
  (gateway zna dziś tylko `mcp-jira`, mocka Confluence nie ma).

## Gdzie wepną się QueryScope i ResultFilter (M3)

Kontrola przestrzeni rozwiązanego zasobu jest w jednym miejscu: `ConfluenceService.spaceInScope(spaceKey)`. Przechodzą
przez nią trafienia `search_pages`, strona z `get_page` (po pobraniu, `page.spaceKey()`) i rodzic w `create_page`.
W M1 odrzuca tylko przestrzenie osobiste; wynik negatywny to zawsze `PageNotFoundException`, czyli ten sam
`not_found_or_denied` co brak strony i 403 (testy `blogPostsAndPersonalSpacesAreNotFound`,
`missingAndForbiddenPagesLookTheSame`). `QueryScope` startera beta zawęzi CQL w `ConfluenceService.searchPages`
przed wywołaniem portu (przestrzenie linku, `vsScopes.confluenceSpaces`), a ocena polityki zasobu (`ResultFilter`,
`@ResourceRef` na `space`, `pageId`, `parentId`; resolver w `mcp-integration` z `ancestorIds` i `labels`
przestrzeni) zastąpi `spaceInScope`.

## Odstępstwa i znane ograniczenia

- `get_page(pageId)` nie sprawdza przestrzeni strony względem zakresu użytkownika ani linku (zgłoszone jako
  MEDIUM w automatycznym przeglądzie). W M1 wywołanie idzie z PAT użytkownika, więc Confluence egzekwuje jego
  własne uprawnienia; zawężenie do przestrzeni przyznanych grupami C9 i do przestrzeni linku nie działa, bo
  gateway nie zna przestrzeni z samego `pageId`. Sygnatura zostaje zgodna z briefem (bez argumentu `space`).
  Warunek zamknięcia: starter beta z `@ResourceRef(Confluence::Page)` i oceną polityki na serwerze, wpiętą w
  `ConfluenceService.spaceInScope` (M3).
- Walidacja schematu w MCP SDK: wartości spoza `inputSchema` (wzorzec, długość, dodatkowy argument) odrzuca MCP SDK
  przed narzędziem tekstem `Tool (...) input validation failed: ...` (`isError: true`), nie obiektem C10
  `invalid_argument` (ograniczenie startera, jego STATUS). Treść tego tekstu to wzorzec ze schematu, bez wartości
  argumentu. Wartości zgodne ze schematem, a odrzucane przez serwer (np. `text` bez litery i cyfry), dają C10.
  Testy akceptują obie formy (`ServerTestSupport.assertRejectedArgument`) i zawsze sprawdzają, że Confluence nie
  dostał żądania. Zrezygnowanie z limitów w schemacie przywróciłoby C10, ale M1 wymaga limitów w schemacie.
- Oznaczenie treści jako dane: kształt własny (`kind: "untrusted-data"`), bo kontrakt go nie ma; propozycja CC
  niżej. Tytuły stron są zwykłymi polami.
- `parentId` jest wymagany (tabela briefu): narzędzie nie tworzy stron najwyższego poziomu przestrzeni.
- `search_pages` bez stronicowania (stałe 25) i bez fragmentów treści (`excerpt` z wyszukiwarki może pochodzić z
  renderowanej treści z makrami include, więc nie jest zwracany).
- Brief: testy na mockach z ws-01 i przypadki "zasób przeniesiony do Restricted", "link do zasobu spoza zakresu".
  Mocka Confluence nie ma, testy są na `StubBackend`. "Restricted" wymaga polityki zasobów na serwerze (M3);
  odpowiednikiem "linku spoza zakresu" w M1 są rodzic z innej przestrzeni i makra include z innej przestrzeni
  (testy wyżej).
- Manifest C6, `@ResourceRef`, `QueryScope`, `ResultFilter`: M2/M3, nie zaczęte.
- Tekst strony: wcięcia w blokach kodu są zdejmowane przy porządkowaniu linii; tabele jako `a | b |`.

## Czego potrzebują inni

- ws-01 (mcp-platform-dev): brak mocka Confluence DC. Potrzebny mock z PAT-ami użytkowników (Bearer, 401 dla
  cudzego/wygasłego), endpointami `GET /rest/api/content/{id}?expand=body.storage,space,version,ancestors`,
  `GET /rest/api/content/search?cql=...&limit&expand=space,version`, `POST /rest/api/content`,
  `GET /rest/api/user/current` (whoami Confluence DC; patrz CC niżej), z danymi co najmniej dwóch przestrzeni
  globalnych i jednej osobistej oraz stroną z makrem include/excerpt-include wskazującym inną przestrzeń
  (`body.view` z rozwiniętą treścią). Do tego usługa `mcp-confluence` w profilu `full` z `MCP_CONNECT_URL`
  (uwaga z STATUS template'u). Kształt odpowiedzi: `mcp-app/src/test/java/.../StubBackend.java`.
- ws-02 (starter): (1) odpowiedź walidacji schematu MCP SDK jako C10 `invalid_argument` (już w STATUS startera,
  beta); (2) limity argumentów w `inputSchema`: dziś każdy serwer musi to obejść sam (`@StringArgument` +
  `ConstrainedToolCallbacks` tutaj); dobrze byłoby mieć to w starterze, razem z walidacją z tego samego źródła.
- ws-03 (template): wzorzec limitów w `inputSchema` (jak wyżej), jeśli nie trafi do startera.
- ws-05 (gateway): wpis `mcp-confluence` w konfiguracji (`internal-url: https://mcp-confluence.internal:8443/mcp`,
  SAN `mcp-confluence.internal`), system `confluence` z poświadczeniem `user-secret` (scheme `Bearer`).
- ws-06 (broker): Confluence DC przyjmuje PAT jako `Bearer`. `AtlassianDcAdapter` woła dla Confluence
  `/rest/api/2/myself` (dosłownie C4.1), którego Confluence DC nie ma (to API Jiry); właściwy jest
  `GET /rest/api/user/current`. Bez poprawki każdy PAT Confluence skończy się `secret_invalid` (CC niżej).

## Propozycje zmian kontraktu

Dopisane na końcu `mcp-contracts/CONTRACT_CHANGES.md`, status open:

- CC-??? (C10/C6) "Wspólne oznaczenie treści z systemu w wynikach narzędzi".
- CC-??? (C4.1) "Whoami Confluence Data Center": `/rest/api/user/current` zamiast `/rest/api/2/myself`.
