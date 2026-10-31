# Katalog narzędzi

Narzędzia to powierzchnia publiczna serwera. Każda zmiana nazwy, opisu, schematu albo ryzyka zmienia
`definitionHash` (C6) i wymaga ponownego zatwierdzenia w gateway'u, więc przechodzi przez code review razem z
tą tabelą.

| Narzędzie (klient widzi `confluence__<nazwa>`) | Tytuł (`@ToolTitle`) | Ryzyko | Akcja (C6) | Argumenty | Zasób (C7, `resourceRefs`) | Uprawnienie (C9) | Poświadczenie |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `search_pages` | Szukaj stron | `READ_ONLY` | `read` | `space` (`^[A-Za-z0-9]{1,255}$`), `text` (1-200, jedna linia, co najmniej jedna litera lub cyfra) | `space` -> `Confluence::Space`; trafienia: `id` -> `Confluence::Page` (`ResultFilter`) | `mcp-confluence-read` | `user-secret` (PAT), C3 `Bearer` |
| `get_page` | Pobierz stronę | `READ_ONLY` | `read` | `pageId` (`^[1-9][0-9]{0,18}$`) | `pageId` -> `Confluence::Page` | `mcp-confluence-read` | `user-secret` (PAT), C3 `Bearer` |
| `get_space` | Pobierz przestrzeń | `READ_ONLY` | `read` | `space` (`^[A-Za-z0-9]{1,255}$`) | `space` -> `Confluence::Space` | `mcp-confluence-read` | `user-secret` (PAT), C3 `Bearer` |
| `create_page` | Utwórz stronę | `UPDATE` | `update` | `space`, `parentId` (`^[1-9][0-9]{0,18}$`), `title` (1-255, jedna linia, nie same odstępy), `body` (1-20000, bez znaków sterujących poza tab, LF, CR) | `space` -> `Confluence::Space`, `parentId` -> `Confluence::Page` (akcja `update`) | `mcp-confluence-write` + zakres z grupy | `user-secret` (PAT), C3 `Bearer` |

Wszystkie argumenty są wymagane, `inputSchema` ma `additionalProperties: false` i limity
`minLength`/`maxLength`/`pattern` (z `@ArgumentLimits`). Wzorce identyfikatorów to `ConfluenceSpaceKey` i
`ConfluencePageId` z `mcp-contracts` 4.1.0, brane przez `contractPattern` ze startera (kopia w `ConfluenceIds`
służy drugiej linii walidacji w kodzie). Naruszenie schematu daje `invalid_argument` ze startera.

## Wywołania Confluence Data Center

| Narzędzie | Żądania |
| --- | --- |
| `search_pages` | `GET /rest/api/content/search?cql=space = "<space>" AND type = "page" AND text ~ "<słowa>"&limit=25&expand=space,version`; zawężenie do żądanej przestrzeni (`space in (...)`) przed filtrami; potem resolver trafień (niżej) |
| resolver strony | `GET /rest/api/content/{pageId}?expand=space,version,ancestors` (jedna strona) albo `GET /rest/api/content/search?cql=id in ("..", "..") AND type = page&expand=space,version,ancestors` (trafienia), do tego `GET /rest/api/space/{key}?expand=metadata.labels` raz na przestrzeń |
| resolver przestrzeni | `GET /rest/api/space/{key}?expand=metadata.labels` |
| `get_page` | resolver strony, po decyzji polityki `GET /rest/api/content/{pageId}?expand=body.storage,space,version,ancestors` |
| `get_space` | resolver przestrzeni |
| `create_page` | resolver strony dla `parentId`, potem `POST /rest/api/content` z `{type: page, title, space: {key}, ancestors: [{id: parentId}], body: {storage: {value, representation: storage}}}` |

## Mapowanie odpowiedzi

| Confluence | Wynik |
| --- | --- |
| 401 | `credential_required` (z `connectUrl` portalu) |
| 403, 404 | `not_found_or_denied`, ten sam tekst; w wyszukiwaniu pusta lista |
| 400 przy `POST` | `invalid_argument`, tekst serwera (np. duplikat tytułu) |
| 400 przy wyszukiwaniu | `invalid_argument`, stały tekst |
| 5xx, błąd odczytu, 3xx, pusta lub nieczytelna odpowiedź, treść bez `id`, odpowiedź ponad `max-response-size` | `internal_error`, bez treści odpowiedzi |
| treść inna niż `type: page` / `status: current` | `not_found_or_denied`, w wyszukiwaniu pominięta |
| strona w przestrzeni osobistej (`~user`) | `not_found_or_denied` |
| rodzic z innej przestrzeni niż `space` | `not_found_or_denied`, bez `POST` |
| przestrzeń z etykietą `restricted` (strona, przestrzeń, rodzic) | `not_found_or_denied`, w wyszukiwaniu trafienie usunięte |
| etykiety przestrzeni nieznane (brak listy, lista dłuższa niż odpowiedź) | `internal_error`, w wyszukiwaniu trafienie usunięte |

## Treść jako dane

`get_page` zwraca treść strony w `body` z `kind: "untrusted-data"` i `format: "text/plain"`: to tekst
użytkowników Confluence, nie polecenia. Makra spoza listy (`code`, `noformat`, `info`, `note`, `warning`, `tip`,
`panel`, `expand`, `excerpt`) nie są rozwijane, a ich nazwy są w `omittedMacros` (obok `body`). Tytuły stron (`title` w `search_pages`, `get_page`
i `create_page`) mają ten sam kształt, bez `omittedMacros`; `omittedMacros` jest tylko w treści. Poza
kształtem `untrusted-data` w wynikach są wyłącznie identyfikatory sprawdzone wzorcem, klucz przestrzeni, numery
wersji i czas ostatniej zmiany.

Konwencje: nazwy `snake_case`, czasownik i obiekt, bez prefiksu systemu (dokłada go gateway); opis mówi, co
narzędzie zwraca, bez instrukcji dla modelu.
