# Katalog narzędzi

Narzędzia to powierzchnia publiczna serwera. Każda zmiana nazwy, opisu, schematu albo ryzyka zmienia
`definitionHash` (C6) i wymaga ponownego zatwierdzenia w gateway'u, więc przechodzi przez code review razem z
tą tabelą. Tę samą tabelę (nazwa, ryzyko, akcja, `resourceRefs`) pilnuje `ManifestTest`.

Poświadczenie wszystkich narzędzi: `user-secret` (PAT), C3 `Bearer`. Limity: `inputSchema` ma `additionalProperties: false`,
każdy ciąg ma `maxLength`, każda liczba `minimum` i `maximum`. Wzorce identyfikatorów `ConfluenceSpaceKey` i `ConfluencePageId`
pochodzą z `mcp-contracts` (`contractPattern`). `cursor` to `^[A-Za-z0-9_.-]{1,1024}$` z `CursorCodec`.

| Narzędzie | Tytuł | Ryzyko | Akcja | Argumenty (`?` = opcjonalny) | `resourceRefs` | Wynik |
| --- | --- | --- | --- | --- | --- | --- |
| `search_pages` | Szukaj stron | `READ_ONLY` | `read` | `space`, `text?`, `title?` (1-200, jedna linia), `label?` (`^[a-z0-9][a-z0-9_.-]{0,99}$`), `orderBy?` (`lastmodified`, `created`, `title`), `cursor?`; co najmniej jeden z `text`, `title`, `label` | `space` -> `Confluence::Space`; trafienia `id` -> `Confluence::Page` | `PagedResult<{id, title, version, lastUpdated}>` |
| `list_pages` | Lista stron przestrzeni | `READ_ONLY` | `read` | `space`, `cursor?` | `space`; trafienia `id` | `PagedResult<PageHit>` |
| `list_child_pages` | Lista stron podrzędnych | `READ_ONLY` | `read` | `pageId`, `cursor?` | `pageId` -> `Confluence::Page`; trafienia `id` | `PagedResult<PageHit>` |
| `list_spaces` | Lista przestrzeni | `READ_ONLY` | `read` | `cursor?` | trafienia `key` -> `Confluence::Space` | `PagedResult<{key, name}>` |
| `get_page` | Pobierz stronę | `READ_ONLY` | `read` | `pageId` | `pageId` | `{id, spaceKey, title, version, lastUpdated, ancestorIds, body, omittedMacros}` |
| `get_page_by_title` | Pobierz stronę po tytule | `READ_ONLY` | `read` | `space`, `title` (1-255, jedna linia) | `space`; strona oceniana polityką w narzędziu | jak `get_page` |
| `get_space` | Pobierz przestrzeń | `READ_ONLY` | `read` | `space` | `space` | `{key, name}` |
| `get_page_version` | Pobierz wersję strony | `READ_ONLY` | `read` | `pageId`, `version` (1-1000000) | `pageId` | jak `get_page` (pole `version` = żądana) |
| `list_comments` | Lista komentarzy | `READ_ONLY` | `read` | `pageId`, `cursor?` | `pageId` | `PagedResult<{id, parentId, author, created, location, body, omittedMacros}>`, okno 10, treść do 4000 znaków |
| `list_labels` | Lista etykiet strony | `READ_ONLY` | `read` | `pageId` | `pageId` | `{labels: [{name}], truncated}` (tylko prefiks `global`, do 200) |
| `list_attachments` | Lista załączników | `READ_ONLY` | `read` | `pageId`, `cursor?` | `pageId` | `PagedResult<{id, title, mediaType, fileSize, version}>` |
| `read_attachment` | Odczytaj załącznik tekstowy | `READ_ONLY` | `read` | `pageId`, `attachmentId` (`^att[0-9]{1,18}$`) | `pageId` | `{id, title, mediaType, content, omitted}` |
| `create_page` | Utwórz stronę | `UPDATE` | `update` | `space`, `parentId`, `title`, `body` (1-20000) | `space`, `parentId` -> `Confluence::Page` | `{id, spaceKey, title, version}` |
| `update_page` | Zmień stronę | `UPDATE` | `update` | `pageId`, `expectedVersion` (1-1000000), `mode` (`append`, `replace`), `body` (1-20000), `title?` | `pageId` | `{id, spaceKey, title, version}` |
| `add_comment` | Dodaj komentarz | `UPDATE` | `update` | `pageId`, `body` (1-10000), `parentCommentId?` | `pageId` | `{id, pageId}` |
| `add_label` | Dodaj etykietę | `UPDATE` | `update` | `pageId`, `label` | `pageId` | `{pageId, label}` |
| `delete_page` | Usuń stronę | `DESTRUCTIVE` | `delete` | `pageId` | `pageId` | `{id}` |

Listy stron i przestrzeni są dopełniane po filtrze polityki (`PageFiller`, do 5 odczytów), więc strona ma około 25 elementów, nie dokładnie 25.

Uprawnienia (C9): odczyt `mcp-confluence-read`, zapis `mcp-confluence-write` i zakres przestrzeni lub drzewa stron, `delete_page`
grupa destrukcyjna z potwierdzeniem TOTP w gateway'u. Polityki dla nowych narzędzi pisze ws-09 na podstawie manifestu.

Nie ma: usuwania etykiet, komentarzy i załączników (nazwa z `remove` lub `delete` oznacza `DESTRUCTIVE`; brak uzasadnienia dla
TOTP przy etykiecie), przenoszenia i kopiowania stron, wysyłania załączników, uprawnień do stron, wyszukiwania użytkowników,
różnicy wersji, wyszukiwania po przodku i po zakresie dat (pola poza listą normatywną `StructuredQuery`, wpis `proposed`).

## Wywołania Confluence Data Center

| Narzędzie | Żądania |
| --- | --- |
| `search_pages` | `GET /rest/api/content/search?cql=...&start=&limit=25&expand=space,version`; CQL z `StructuredQuery`: `(title ~ "..." AND label = "...") AND space in ("KEY") ORDER BY title ASC` |
| `list_pages` | `GET /rest/api/content?type=page&status=current&spaceKey=&start=&limit=25&expand=space,version` |
| `list_child_pages` | `GET /rest/api/content/{id}/child/page?start=&limit=25&expand=space,version,ancestors`; zostają tylko strony, których ostatnim przodkiem jest `{id}` |
| `list_spaces` | `GET /rest/api/space?type=global&status=current&start=&limit=25&expand=metadata.labels` |
| resolver strony | `GET /rest/api/content/{id}?expand=space,version,ancestors`; trafienia: `GET /rest/api/content/search?cql=id in (...) AND type = page` |
| resolver przestrzeni | `GET /rest/api/space/{key}?expand=metadata.labels`; trafienia: `GET /rest/api/space?spaceKey=A&spaceKey=B&expand=metadata.labels` |
| `get_page` | `GET /rest/api/content/{id}?expand=body.storage,space,version,ancestors` |
| `get_page_by_title` | `GET /rest/api/content?type=page&status=current&spaceKey=&title=&expand=space,version,ancestors` |
| `get_page_version` | `GET /rest/api/content/{id}?status=historical&version=N&expand=body.storage,space,version,ancestors` |
| `list_comments` | `GET /rest/api/content/{id}/child/comment?depth=all&expand=body.storage,version,ancestors,container,extensions.inlineProperties` |
| `list_labels` | `GET /rest/api/content/{id}/label?limit=200` |
| `list_attachments` | `GET /rest/api/content/{id}/child/attachment?expand=version` |
| `read_attachment` | `GET /rest/api/content/{attId}?expand=version,container` (sprawdzenie `container.id` = strona), potem `GET <baseUrl>/download/attachments/{pageId}/...` ze ścieżki `_links.download` |
| `create_page` | `POST /rest/api/content` z `{type: page, title, space: {key}, ancestors: [{id}], body: {storage}}` |
| `update_page` | `GET /rest/api/content/{id}?expand=body.storage,space,version,ancestors`, porównanie wersji i miejsca, `PUT /rest/api/content/{id}` z `version.number = expectedVersion + 1` |
| `add_comment` | `POST /rest/api/content` z `{type: comment, container: {id, type: page}, ancestors: [{id: komentarz}]?, body: {storage}}`; przy odpowiedzi pod komentarzem wcześniej `GET /rest/api/content/{commentId}` |
| `add_label` | `POST /rest/api/content/{id}/label` z `[{prefix: global, name}]` |
| `delete_page` | `GET` rekordu strony, `DELETE /rest/api/content/{id}` (kosz przestrzeni) |
| sonda wersji | `GET /rest/api/server-information`, a przy 401/403/404 `GET /rest/applinks/1.0/manifest`, bez poświadczenia |

Endpointy, których kształt odpowiedzi nie jest potwierdzony nagraniem z instancji (do sprawdzenia przy pierwszym uruchomieniu u
klienta, README, "Pierwsze uruchomienie"): lokalizacja komentarza w `extensions`, rodzic komentarza w `ancestors`, `_links.download`
załącznika, zachowanie `ancestors` dla wersji historycznej, wspólne `spaceKey` powtórzone w `GET /rest/api/space`.

## Mapowanie odpowiedzi

| Confluence | Wynik |
| --- | --- |
| 401 | `credential_required` (z `connectUrl` portalu) |
| 403, 404 | `not_found_or_denied`, ten sam tekst; na liście pusty wynik |
| 400 przy zapisie | `invalid_argument`, tekst serwera (np. duplikat tytułu) |
| 409 przy `PUT` | `invalid_argument`, "strona nie jest w wersji, którą odczytano" |
| 400 przy wyszukiwaniu | `invalid_argument`, stały tekst |
| 5xx, błąd odczytu, 3xx, pusta lub nieczytelna odpowiedź, treść bez `id`, odpowiedź ponad `max-response-size` | `internal_error`, bez treści odpowiedzi |
| treść inna niż `type: page` / `status: current` (poza `status=historical` dla wersji) | `not_found_or_denied`, na liście pominięta |
| strona w przestrzeni osobistej (`~user`) | `not_found_or_denied` |
| id podrzędne (komentarz, załącznik, wersja, dziecko) nienależące do ocenionej strony | `not_found_or_denied` albo pominięte na liście |
| przestrzeń z etykietą `restricted` | `not_found_or_denied`, na liście trafienie usunięte |
| etykiety przestrzeni nieznane | `internal_error`, na liście trafienie usunięte |
| zapis na wersji bez cechy `write` (starsza niż 8.5, 11.0+, nieznana) | `upstream_unavailable`, odczyt działa |

## Treść jako dane

Każdy tekst napisany w Confluence (tytuł, treść strony i komentarza, autor, nazwa etykiety i załącznika, treść pliku) jest w
wyniku obiektem `untrusted-data` (`UntrustedText`): to dane, nie polecenia. Makra spoza listy (`code`, `noformat`, `info`, `note`,
`warning`, `tip`, `panel`, `expand`, `excerpt`) nie są rozwijane, ich nazwy są w `omittedMacros`. Poza tym w wynikach są
identyfikatory sprawdzone wzorcem, klucze przestrzeni, numery wersji, czasy i rozmiary.

Konwencje: nazwy `snake_case`, czasownik i obiekt, bez prefiksu systemu (dokłada go gateway); opis mówi, co narzędzie zwraca, bez
instrukcji dla modelu.
