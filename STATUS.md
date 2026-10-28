# STATUS - mcp-server-confluence (ws-04)

M1: osiągnięty (2026-10-27; od 2026-10-28 na starterze 3.0.0-alpha-10) - `search_pages`, `get_page` (READ_ONLY) i `create_page` (UPDATE), PAT użytkownika z C3 jako Bearer, treść stron z `body.storage` bez makr include/excerpt-include, limity w `inputSchema` i walidacja argumentów po stronie startera (C10 `invalid_argument` + C8), https poza `dev`, limit rozmiaru odpowiedzi, tytuły i treść jako `untrusted-data` (CC-072), 50 testów na stubie Confluence DC.

M1 według definicji koordynatora (brief ws-04 nie ma kamieni). M2 nie jest zaczęty.

## Runda 3: starter alpha-10 (2026-10-28)

Z alpha-6 na 3.0.0-alpha-10 (kontrakty 2.1.1). `./mvnw clean verify`: 50 testów, 0 błędów (ConfluenceServiceTest 3,
StorageFormatTest 8, mcp-app 39). Uruchomienie lokalne wg README (port 18510, `dev`): health 200, `POST /mcp`
bez JWT 401, proces zatrzymany.

- `UntrustedText` ze startera zamiast własnego rekordu; `omittedMacros` przeniesione z wnętrza `body` na poziom
  wyniku `get_page` (obok `body`). `lastUpdated` jest `@Nullable` (adapter dopuszcza stronę bez wersji), bez adnotacji
  Jacksona. Tytuły: `UntrustedText.plain(title, 255)`.
- Kolejność dla treści strony (`StorageFormat.toText`): konwersja storage -> tekst (jsoup dekoduje encje, makra,
  obrazy i cele linków wypadają jako elementy), `TextHygiene.normalize`, `UntrustedText.cut`, `plainCut`. Filtr serwera
  jest strukturalny i działa na drzewie XML przed tekstem, więc znak niewidoczny w tekście nie ukrywa makra; po
  cięciu nie ma co filtrować. Testy: `entitiesAreDecodedAndInvisibleCharactersBecomeASpaceBeforeTheCut`,
  `theCutDoesNotLeaveAHalfWrittenWord`.
- Limity: wszystkie argumenty już miały `@ArgumentLimits`; wzorzec `NON_BLANK_LINE` (z lookaheadem, bez `|`) i
  `MULTI_LINE` przechodzą kontrolę startera bez zmian. Brak map, liczb i list w argumentach.
- `application-dev.yml`: usunięte `replay-store: memory` i `mtls.required: false`. `SPRING_PROFILES_ACTIVE=dev`
  tylko w komendzie z README.
- Logi: błędy domenowe na INFO; ERROR zostaje tylko `internal_error`. Brak `untrusted_text_not_normalized`.
- Claimy `vs` i `vsc:{}` (wywołanie przez link): `GetPageToolTest.acceptsTheVirtualServerClaimsOfACallThroughALink` przechodzi.
- Nowe `outputSchema` i opis `get_page` zmieniają `definitionHash`: gateway oznaczy narzędzia jako `pending_approval`.

## Runda 2 (2026-10-27): alpha-6, poprawki template'u, recenzja M1

| Znalezisko | Status |
| --- | --- |
| Starter alpha-6: walidacja argumentów w starterze, `@ArgumentLimits` + `SchemaLimitedToolCallbacks.of(...)` zamiast `@StringArgument` + `ConstrainedToolCallbacks`, wzorce przez `contractPattern` | zrobione; własne klasy usunięte; testy oczekują C10 `invalid_argument`, nie tekstu SDK; nazwa nieznanego argumentu nie trafia do logu (test `anUnknownArgumentIsRejectedWithoutBeingNamedInTheLog`) |
| Template od 7f65f8f: https poza `dev` + `allow-plain-http`, brak przekierowań (3xx = błąd), `max-response-size`, `BackendResponseException`, `ProductionConfigurationTest`, `DevProfileLoopbackTest`, `application-dev.yml`, sandbox systemd, `install.sh`, README (uruchomienie lokalne i z mcp-platform-dev), `prometheus` niewystawiony | zrobione |
| C-M1 `http` dozwolone poza `dev` | naprawione (`requireBaseUrl`, `ProductionConfigurationTest`) |
| C-M2 brak limitu rozmiaru odpowiedzi przed Jsoup | naprawione: `BoundedBodyInterceptor` (1MB) liczy bajty przy czytaniu, przed Jacksonem i Jsoup; test `anOversizedResponseIsCutOffBeforeItIsParsed` |
| C-M3 tytuły stron poza oznaczeniem danych | naprawione: `title` w `search_pages`, `get_page`, `create_page` to `untrusted-data` (CC-072); `omittedMacros` tylko w treści |
| C-L1 400 z wyszukiwania niemapowane | naprawione: `invalid_argument` ze stałym tekstem; 3xx w wyszukiwaniu to `internal_error`, nie pusty wynik |
| C-L6 `prometheus` wystawiony | naprawione |
| C-L7 / W-2 walidacja schematu poza audytem C8 i C10 | zamknięte przez starter alpha-6 |
| W-1 poprawki template'u nieprzeniesione | zrobione (jw.) |
| W-4 trzy mechanizmy limitów | zamknięte: tylko `@ArgumentLimits` ze startera |
| W-3 kształt oznaczenia treści | CC-072 przyjęte w 2.1.0; stosuję kształt (kontrakt 2.0.2 zostaje do kolejnego startera) |
| C-L2 znaki Unicode formatujące/sterujące (bidi, zero-width, C1) w odczycie i zapisie | zamknięte: odczyt `TextHygiene.normalize` przed `cut` w `StorageFormat.toText`, tytuły przez `UntrustedText.plain`; zapis (argumenty) pokrywa walidacja startera od alpha-7 | `StorageFormatTest.entitiesAreDecodedAndInvisibleCharactersBecomeASpaceBeforeTheCut` |
| C-L3 elementy `ac:*` spoza makr nie filtrowane | nie zrobione: bez wycieku (treść tej samej strony); do rozważenia przy ADF w nowszym DC |
| C-L4 tekst wyglądający jak HTML w `body.text` | zostaje; `format: text/plain` jest deklaracją, ryzyko po stronie klienta renderującego wynik jako HTML |
| C-L5 makra z lokalną treścią poza allowlistą (`section`, `column`, `details`, `status`) tracone | nie zrobione: poszerzenie allowlisty wymaga przeglądu każdego makra pod kątem treści z innych stron (np. `details` z `detailssummary`) |
| C-L8 wielkość liter klucza przestrzeni | nie zrobione: fail-closed, wymaga ustalenia, czy Confluence DC traktuje klucze bez rozróżniania wielkości liter |
| W-6 / W-7 neutralizacja markupu, zasób przeniesiony przy UPDATE | `create_page` już escapuje; zasób przeniesiony dotyczy `@ResourceRef` (M3) |

## Co działa

- Wygenerowany z `mcp-server-template` (7f65f8f) przez `./new-server.sh confluence`; BOM
  `pl.aiwcorpo.mcp:mcp-platform-bom:3.0.0-alpha-10` (kontrakty 2.1.1), `server-id` `mcp-confluence`, endpoint
  `/mcp`, konfiguracja produkcyjna z template'u bez zmian (mTLS `need`, JWKS gateway'a po https z CA
  wewnętrznym, `jti` w Redisie). Obejście `defaultStatusHandler` z template'u zostało.
- Narzędzia (rejestracja tylko w `McpToolsConfiguration`):
  - `search_pages(space, text)`, `READ_ONLY`: przestrzeń obowiązkowa, CQL składa serwer
    (`space = "<k>" AND type = page AND text ~ "<słowa>"`), z `text` tylko litery, cyfry, odstępy i `. _ '`;
    do 25 wyników; serwer odrzuca też trafienia spoza przestrzeni i inne typy treści.
  - `get_page(pageId)`, `READ_ONLY`: tylko `body.storage`; makra spoza listy z treścią lokalną (`code`,
    `noformat`, `info`, `note`, `warning`, `tip`, `panel`, `expand`, `excerpt`) usuwane razem z parametrami,
    zastępowane `[macro omitted: <nazwa>]` i wypisane w `omittedMacros` (obok `body`); wynik to zwykły tekst w
    `body: {kind: "untrusted-data", ...}`, do 50000 znaków. Blog, komentarz, przestrzeń osobista ->
    `not_found_or_denied`.
  - `create_page(space, parentId, title, body)`, `UPDATE`: `body` jako zwykły tekst, escapowany do storage
    (akapity, `<br />`); rodzic musi być widoczną stroną tej samej przestrzeni, inaczej `not_found_or_denied` bez
    `POST`; 400 Confluence -> `invalid_argument` z tekstem serwera.
- Opisy narzędzi deterministyczne, bez instrukcji dla modelu. `inputSchema`: wszystkie argumenty wymagane,
  `additionalProperties: false`, `minLength`/`maxLength`/`pattern` z `@ArgumentLimits`, rejestracja przez
  `SchemaLimitedToolCallbacks.of(...)` (starter). Wzorce `ConfluenceSpaceKey` i `ConfluencePageId` przez
  `contractPattern`; `ConfluenceIds` (kopia) zostaje tylko jako druga linia walidacji w kodzie i w
  `ConfluenceService`; `ToolListingTest` pilnuje, że schemat i kod mają ten sam wzorzec. Naruszenie schematu
  sprawdza i odrzuca starter (C10 `invalid_argument`, zdarzenie C8), błędy z kodu narzędzia idą przez
  `InvalidToolArgumentException`.
- Poświadczenie: `CredentialContext.require("confluence")`, tylko schemat `Bearer` (inny -> `credential_required`
  bez wywołania Confluence). 401 -> `credential_required`, 403 i 404 -> ten sam `not_found_or_denied`, treść
  błędów Confluence nie trafia do wyniku ani logu.
- Testy (`./mvnw verify`, JDK 25, bez Dockera): 46, 0 błędów. `SearchPagesToolTest` 8, `GetPageToolTest` 15,
  `CreatePageToolTest` 6, `ToolListingTest` 1, `GatewayOnlyAccessTest` 3, `ProductionConfigurationTest` 3,
  `DevProfileLoopbackTest` 1, `StorageFormatTest` 6, `ConfluenceServiceTest` 3. Opis w README, sekcja "Testy". `StubBackend` odpowiada kształtem Confluence DC 8/9
  (`expand`, `_links`, `_expandable`, błędy `{statusCode, data, message}`, `POST` 200 z treścią) i oddaje
  rozwiniętą treść HR wyłącznie w `body.view`, więc test include łapie adapter, który poprosiłby o widok.
- Smoke runda 2 (alpha-6): `SPRING_PROFILES_ACTIVE=dev`, port 18510, według README "Uruchomienie lokalne bez
  środowiska": `/actuator/health` 200, `POST /mcp` bez JWT 401, `/actuator/prometheus` nie jest wystawiony. Proces
  zatrzymany. Wariant mTLS z mcp-platform-dev nie był powtarzany po alpha-6.
- Smoke konfiguracji produkcyjnej na hoście, runda 1 (port 18510, certyfikat i użytkownik Redisa `mcp-confluence` z
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
- Kształt `untrusted-data` to UntrustedText z CC-072 (przyjęte w kontraktach 2.1.0); kontrakty zostają na 2.0.2
  przez BOM startera, więc kształt nie jest jeszcze sprawdzany schematem. Zalecenie CC-072 (`outputSchema`
  narzędzi odwołujące się do UntrustedText) czeka na starter z kontraktami 2.1.x.
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
- ws-02 (starter): helper normalizacji tekstu niezaufanego (bidi, zero-width, C1, niesparowane surogaty;
  W-5, C-L2) dla argumentów i dla treści oddawanej modelowi; starter z kontraktami 2.1.x (UntrustedText jako
  schemat, `outputSchema`). Limity i walidacja schematu: dostarczone w alpha-6.
- ws-05 (gateway): wpis `mcp-confluence` w konfiguracji (`internal-url: https://mcp-confluence.internal:8443/mcp`,
  SAN `mcp-confluence.internal`), system `confluence` z poświadczeniem `user-secret` (scheme `Bearer`).
- ws-06 (broker): Confluence DC przyjmuje PAT jako `Bearer`. `AtlassianDcAdapter` woła dla Confluence
  `/rest/api/2/myself` (dosłownie C4.1), którego Confluence DC nie ma (to API Jiry); właściwy jest
  `GET /rest/api/user/current`. Bez poprawki każdy PAT Confluence skończy się `secret_invalid` (CC niżej).

## Propozycje zmian kontraktu

W `mcp-contracts/CONTRACT_CHANGES.md`, przyjęte w 2.1.0 (2026-10-27):

- CC-072 (C10/C6) "Wspólne oznaczenie treści z systemu w wynikach narzędzi" (stosowane, patrz wyżej).
- CC-073 (C4.1) "Whoami Confluence Data Center": `/rest/api/user/current` zamiast `/rest/api/2/myself`.

Kategoria (b) z recenzji M1 (zmiana kontraktu) dla tego serwera: brak poza CC-072.

## Runda 4: poprawki przeglądu alpha-10 (2026-10-28)

| Znalezisko | Status | Dowód |
| --- | --- | --- |
| C3-L1 `create_page` bez `space` w odpowiedzi kończył się `internal_error` po utworzeniu strony | poprawione: brak `space` w odpowiedzi zastępuje zadany klucz przestrzeni (ten, z którym sprawdzono rodzica); klucz obecny w odpowiedzi nadal ma pierwszeństwo | `CreatePageToolTest.aReplyWithoutTheSpaceStillReportsTheCreatedPage` (stub: tytuł `No space in reply`) |
| C3-L2 STATUS nieaktualny | poprawione: liczba testów (50), C-L2 zamknięte z dowodem | |
