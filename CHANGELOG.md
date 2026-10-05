# Changelog

## 1.0.2 — 2026-10-05

### Fixed
- Hooks inside app processes now read their settings from a small remote file written by the
  app on every change, instead of only the LSPosed remote preferences. Those changes were not
  always delivered to an app process that was already running, so it kept the state from its
  own start (frozen route, fake point staying after stop) until it was restarted.
  system_server is unchanged.
- Update check: a release is offered only when its version is newer than the installed one
  (the old check offered any release whose tag differed, including older ones). Release notes
  are shown without Markdown marks; the release page link is correct.

Earlier versions: see the GitHub releases.
