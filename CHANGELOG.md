# Changelog

## 2.3.0-easyiga — 2026-10-27 (first public release)

- Coverage context for the activity signal: `developer_members_last_event_projects_scanned`,
  `developer_members_last_event_projects_truncated` and `developer_members_last_event_lookback_days` on groups,
  `master_members_last_event_lookback_days` on projects. The group listing now asks for `maxProjectsPerGroup + 1`
  projects so truncation is detected without listing everything. A consumer can now tell an absent pair in a fully
  scanned group from one in a truncated scan.
- Dedicated integration identity is configurable: `integrationUsername` (default `easyiga-midpoint`, never `root`)
  and `integrationTokenName` (default `easyiga-midpoint-connector`). The protection rules follow the configured value.
- `pom.xml` points at this repository; standalone README; monorepo documentation references removed.
- 85 automated tests (unit + bundle-load IT).

## 2.2.0-easyiga — 2026-10 (lab-measured build, not published)

- Per-entitlement last-event pairs (`developer_members_last_event`, `master_members_last_event`), 90-day window,
  20-project group bound; measured in the article lab against GitLab CE 19.2.0 + midPoint 4.8.12.
