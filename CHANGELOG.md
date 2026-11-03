# Changelog

Format: wersje SemVer, od najnowszej. Do pierwszego wydania publicznego obowiązuje `0.1.0-SNAPSHOT`.

## Niewydane

- Starter 3.0.0-beta-14, kontrakty 6.3.2.
- 17 narzędzi (było 4): listy stronicowane kursorem (`list_pages`, `list_child_pages`, `list_spaces`), `get_page_by_title`,
  `get_page_version`, komentarze, etykiety, załączniki tekstowe, `update_page` z `expectedVersion`, `add_comment`, `add_label`,
  `delete_page` (`DESTRUCTIVE`). `search_pages` ma filtry `title`, `label`, `orderBy` i kursor, wynik w `PagedResult`.
- Sonda wersji (`server-information`, manifest aplikacji): 8.5 do 10.x wspierane, inne wersje tylko do odczytu.
- Listy dopełniane po filtrze polityki (`PageFiller`); `list_labels` z `truncated`; okno komentarzy 10 po 4000 znaków.
- Release workflow czyta pakiety `GITHUB_TOKEN`-em z `packages: read`, bez osobistego tokenu.
- Flaga zapisu tylko dla wersji nieodczytanej; obrazy CI po digeście.
- Sprawdzenie, że id podrzędne należy do ocenionej strony.
- Fixture sondy dla 8.5, 9.2, 10.2, 11.0, 7.19; Apache-2.0, `SECURITY.md`, `CONTRIBUTING.md`, release workflow (obraz ghcr.io, SBOM).
