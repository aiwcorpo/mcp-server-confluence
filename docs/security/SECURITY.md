# Bezpieczeństwo serwera

Niezmienniki platformy (`mcp-platform-plan/00-overview.md`) i to, gdzie serwer je spełnia.

| Niezmiennik | W serwerze |
| --- | --- |
| Ruch tylko od gateway'a: mTLS + wewnętrzny JWT, inaczej 401 | `server.ssl.client-auth: need` z CA wewnętrznym; `InternalJwtFilter` startera (CN `mcp-gateway`, ES256 z JWKS gateway'a, `iss`, `aud`, czas życia <= 60 s, `jti` jednorazowy w Redisie, `tool` == `params.name`, `rid` == `X-Request-Id`). Bez JWT tylko `/actuator/health*`. Test: `GatewayOnlyAccessTest` |
| Token IdP nigdy do systemu | serwer go nie dostaje; do systemu idzie wyłącznie poświadczenie z C3 (`CredentialContext.require`) |
| Fail-closed | brak JWKS, Redisa, konfiguracji albo poświadczenia -> 401/503/`credential_required`/brak startu, nigdy przepuszczenie; starter odrzuca relaksacje `dev` w innych profilach |
| Sekrety nigdy w logach, audycie, wynikach i błędach | nagłówki poświadczeń ukryte po odczycie, `BackendCredential.toString()` bez wartości, redakcja komunikatów w starterze; adapter nie czyta treści błędów systemu. Test: `theCredentialAndConfluenceErrorsNeverAppearInTheResultOrTheLog` |
| "Nie istnieje" i "brak dostępu" -> `not_found_or_denied` | 404 i 403 Confluence, treść inna niż strona, przestrzeń osobista i rodzic z innej przestrzeni dają ten sam błąd i tekst. Testy: `missingAndForbiddenPagesLookTheSame`, `blogPostsAndPersonalSpacesAreNotFound`, `aParentFromAnotherSpaceIsNotFoundAndNothingIsCreated` |
| Wynik tylko z zasobu wskazanego w argumentach | `get_page` czyta `body.storage` (nigdy `body.view`/`export_view`, w których Confluence rozwija include i excerpt-include treścią innych stron i przestrzeni); makra spoza listy z treścią lokalną są usuwane z parametrami. Test: `contentIncludedFromOtherPagesIsNeverReturned`, `StorageFormatTest` |
| Bez języka zapytań od modelu | CQL składa `Cql`: stałe klauzule, klucz przestrzeni z wzorca kontraktu, z `text` tylko słowa. Test: `cqlInTheTextCannotWidenTheSearch` |
| Zapis nie wprowadza markup | `create_page`, `update_page`, `add_comment` escapują cały tekst do storage; makro ani HTML z argumentu nie powstanie. Test: `createsThePageUnderTheParentWithTheBodyEscaped` |
| Id podrzędne tylko w ocenionym obiekcie | komentarz, załącznik, wersja i strona podrzędna są sprawdzane względem strony ocenionej przez politykę (`container.id`, przodkowie, `id` odpowiedzi); niezgodność to `not_found_or_denied` albo pominięcie. Testy: `anAttachmentOfAnotherPageIsNotFoundAndHiddenPagesStayHidden`, `contentThatIsNotBelowTheJudgedObjectIsNeverHandedOut`, `aReplyToACommentOfAnotherPageOrAMissingOneIsNotFoundAndNothingIsPosted` |
| Zapis tylko na sprawdzonej wersji | bramka wersji (sonda): poza 8.5-10.x tylko odczyt; `update_page` z `expectedVersion`, `replace` tylko dla stron z samych akapitów. Testy: `VersionCoverageTest`, `WriteToolsTest` |
| Audyt z `requestId` | jedno zdarzenie C8 `tool_call` na wywołanie (logger `pl.aiwcorpo.mcp.audit`, stdout, czysta linia JSON), `requestId` z JWT |

## Zasady dla kodu

- Każdy argument narzędzia to dane od modelu: wzorzec, długość, zamknięte listy, przed wywołaniem systemu.
  Szczegóły: `docs/jak-napisac-narzedzie.md`.
- Adresy: tylko `MCP_<SYSTEM>_URL` z konfiguracji, ścieżki API z szablonu URI z argumentami jako zmiennymi
  (kodowane przez `RestClient`), bez przekierowań (3xx to błąd); poza `dev` tylko `https`, `http` jawnym
  `MCP_CONFLUENCE_ALLOW_PLAIN_HTTP` dla mocków środowiska dev; żadnych adresów z argumentów (SSRF).
- Odpowiedź Confluence: najwyżej `max-response-size` (1 MB), liczone przy czytaniu, więc ani Jackson, ani Jsoup nie
  dostają więcej; nieczytelna odpowiedź 2xx nie trafia do komunikatu ani logu (`BackendResponseException`).
- Profil `dev` (klucze testowe, bez mTLS) wstaje tylko na adresie pętli zwrotnej (test
  `DevProfileLoopbackTest`); serwer, do którego łączy się gateway, działa wyłącznie w konfiguracji bazowej.
- Serwer nie ma konta w systemie. Typ `service-account` (np. service desk, D10) przychodzi też przez C3, a
  wtedy serwer jest główną granicą autoryzacji i musi sprawdzać zakres zasobu sam (starter beta).
- Hinty MCP (`readOnlyHint`, `destructiveHint`) są informacją dla klienta, nie zabezpieczeniem.

## Wdrożenie

Konto bez uprawnień, sandbox systemd (`deploy/systemd/mcp-confluence.service`), sekrety tylko w pliku
środowiska (0640, root:mcp) albo z magazynu sekretów, certyfikat serwera z SAN `mcp-confluence.internal` z CA
wewnętrznego. Redis: użytkownik ACL `mcp-confluence` z kluczami `jti:mcp-confluence:*` i komendą `SET`.

## Ryzyka przyjęte i granice

- **Zajęty tytuł jako wyrocznia.** `create_page` i zmiana tytułu w `update_page` dają `invalid_argument` przy zajętym tytule, także gdy
  stronę o tym tytule ukrywają restrykcje Confluence albo polityka. Wywołujący ma w tej przestrzeni prawo zapisu, a odpowiedź pochodzi z
  Confluence (400). Ryzyko przyjęte; tekst błędu nie zawiera tytułu ani treści odpowiedzi.
- **Usunięcie strony zmienia przodków dzieci.** `delete_page` przenosi stronę do kosza, a jej dzieci Confluence podpina pod rodzica
  usuwanej strony, więc zmieniają się ich `ancestorIds`. Przy polityce opartej o poddrzewo (`forbid` dla poddrzewa X, grant
  `Confluence::Page/X`) usunięcie korzenia X przeklasyfikuje dzieci. Wymaga akcji `delete` i kodu TOTP; do uwzględnienia w politykach (ws-09).
- **`ancestorIds` w `get_page`.** Wynik zawiera id przodków (same id, bez tytułów), także takich, które polityka mogłaby zabraniać.
- **Wersje historyczne.** Confluence DC nie podaje przodków wersji historycznej, więc `get_page_version` porównuje tylko przestrzeń i id.
  Wersja sprzed przeniesienia strony z zabronionego poddrzewa jest zwracana, o ile strona jest teraz oceniona jako dozwolona.
- **Krótka strona listy.** Dopełnianie (`PageFiller`) ogranicza liczbę odczytów do 5; po ich wyczerpaniu strona może być krótka z kursorem.
  Serwer nigdy nie zwraca liczby ani komunikatu o ukrytych elementach.
- **Bramka zapisu.** Flaga `MCP_CONFLUENCE_WRITES_ON_UNKNOWN_VERSION` działa tylko dla wersji nieodczytanej przez sondę (`unavailable`
  po jej pierwszej odpowiedzi). Wersje starsze niż 8.5 i 11.x oraz czas przed pierwszą odpowiedzią sondy są tylko do odczytu.
