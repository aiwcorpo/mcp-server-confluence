# Backend fixtures per Confluence Data Center version

Each directory holds what `GET /rest/api/server-information` and `GET /rest/applinks/1.0/manifest` answer for that
release. The files were composed from the documented shapes (REST API reference of Confluence Data Center 10.1 for
`server-information`: baseUrl, version, buildNumber, marketplaceBuildNumber, buildDate) and the application-links
manifest; they are not recordings. `confluence-dc-8.5` uses the older shape with `majorVersion`, `minorVersion` and
`patchLevel` and no `version`; `confluence-dc-9.2` hides `server-information` behind a login (the file answers
403) and exposes the version only through the manifest. Replace them with anonymised recordings from a real instance
when one is available; the tests only depend on the version they carry.

Content responses (pages, spaces, comments, labels, attachments) are produced by `StubBackend` in the same shape for all
versions: the endpoints this server uses are the same from 8.5 to 10.x.
