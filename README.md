# midPoint GitLab connector with activity signals

A ConnId connector for [Evolveum midPoint](https://evolveum.com/midpoint/) and self-managed GitLab that, besides
managing accounts and direct Developer/Maintainer memberships, exposes **per-entitlement activity signals** for access
reviews: for each group and project, the latest GitLab event recorded for each managed member within a configurable
window, together with the coverage context a reviewer needs to read an absent value correctly.

Companion code of the We Know Identity article *Activity-Informed Access Reviews: Bringing GitLab Signals into midPoint*
(March 2027). Built and tested against **midPoint 4.8.12** (ConnId 1.6.0.0) and **GitLab CE 19.2**; a safety-focused
derivative of Evolveum's `connector-gitlab-rest` (see [PROVENANCE.md](PROVENANCE.md)). Apache-2.0.

**Design rule the whole thing follows: activity is not entitlement usage.** The connector deliberately does *not* read
GitLab's account-level `last_activity_on` date — a global number next to a specific entitlement row is evidence that
is easy to misread. It carries only project-scoped recorded events, on the group or project being reviewed, and says
in the data how much of that scope was actually covered.

## Activity signals

| Object class | Attribute | Type | Meaning |
|---|---|---|---|
| `__GROUP__` | `developer_members_last_event` | multi-valued string, read-only | one `"<GitLab user id>|<ISO-8601 instant>"` pair per direct **Developer** who authored at least one event, inside the window, on one of the scanned projects. A Developer with no such event is **absent** — nothing is fabricated. |
| `__GROUP__` | `developer_members_last_event_lookback_days` | integer | the window used (`eventLookbackDays`, default 90) |
| `__GROUP__` | `developer_members_last_event_projects_scanned` | integer | how many of the group's projects were actually read |
| `__GROUP__` | `developer_members_last_event_projects_truncated` | boolean | `true` when GitLab listed more projects than `maxProjectsPerGroup`; the scan is then explicitly partial |
| `Project` | `master_members_last_event` | multi-valued string, read-only | one pair per direct **Maintainer** with an event inside the window on this project |
| `Project` | `master_members_last_event_lookback_days` | integer | the window used |

How the values are produced, and what they can and cannot establish:

- **Project.** `GET /projects/:id/members` → Maintainers; `GET /projects/:id/events?after=<today − window>` (all pages);
  newest event per Maintainer wins. No Maintainers → no events call.
- **Group.** GitLab has no group-scoped events endpoint. The connector lists the group's projects with
  `GET /groups/:id/projects` **using GitLab's defaults** — projects shared with the group are included, projects of
  subgroups are not, newest first — asks for `maxProjectsPerGroup + 1` so truncation can be detected, scans at most
  `maxProjectsPerGroup` of them, and keeps the newest event per Developer across those projects. No Developers → no
  listing, no scans, `projects_scanned = 0`.
- **Absent means "no recorded event found in what was scanned"** — not inactivity. For a group, read it together with
  `projects_truncated`. Activity elsewhere does not count: the signal is scoped to the entitlement being reviewed.
- **A membership join is itself an event.** A freshly added member shows a last event equal to the join time.
- **Recorded events ≠ exercised access.** GitLab's event feed is contribution-shaped; reading code, cloning or
  inspecting settings leaves no event. Label the value "recorded events", never "usage", and never auto-revoke on it.
- **The pairs carry the event timestamp, not a collection timestamp.** Values are recomputed on every read of the
  shadow (a reconciliation, or a direct fetch). midPoint 4.8 does not cache resource attributes by default, so a
  certification column or report that works from the repository needs attribute caching or an inbound mapping, and
  must persist or read a retrieval timestamp *and* a collection-failure indication separately — the age of cached data
  does not say whether a later refresh failed.
- **Cost.** Single-page formulas (page size 100): groups `1 + 2·G + Σ min(projects_g, bound)` when every group has at
  least one Developer; projects `1 + P + P_with_Maintainers`. Member lists and event feeds paginate, so a project with
  more than 100 events inside the window costs one request per further page; the bound limits projects, not pages.
  Measured in the lab (3 groups / 33 projects / 14 users): 35 / 38 / 29 requests. There is **no retry or back-off**:
  a `429` surfaces as a connector error and the reconciliation reports it.

## Quick start with midPoint

1. Build (`mvn -B clean verify`, JDK 17+) or take the release jar; copy
   `connector-gitlab-rest-2.3.0-easyiga-connector.jar` into midPoint's `connid-connectors/` and restart, or run the
   Docker installer image against your midPoint home volume (see Build).
2. In GitLab, create a dedicated instance-administrator user (default name `easyiga-midpoint`) and a personal access
   token on it named `easyiga-midpoint-connector`, scope `api`, expiry no more than 30 days ahead. The connector
   refuses `root`, any other identity, a different token name, and longer remaining validity.
3. Import [`examples/midpoint/resource-gitlab-example.xml`](examples/midpoint/resource-gitlab-example.xml) after
   replacing its four placeholders; test the connection; import groups and projects (read-only entitlements).
4. Read a group or project shadow through the resource: the `*_last_event` pairs and the coverage attributes are
   there. For certification columns or reports, see the caching note in the example resource.

## Compatibility and scope

- midPoint 4.8.12 + GitLab CE 19.2 are what was exercised. Not validated on midPoint 4.10/4.11.
- **Not usable unchanged on GitLab.com**: Test Connection requires a dedicated *instance administrator* identity, and
  several endpoints the connector relies on are administrator-only on GitLab's side. Adapting it means reviewing each
  endpoint against the permissions a GitLab.com token can hold, not just the connector's token check.
- Live synchronization is not implemented. Groups and projects are read-only topology.

## Build

```sh
mvn -B clean verify
```

The installable ConnId bundle is:

```text
target/connector-gitlab-rest-2.3.0-easyiga-connector.jar
```

The Docker build runs the same tests and places that bundle in a minimal
installer image:

```sh
docker build -t easyiga-gitlab-connector:local .
```

The installer expects the midPoint home volume at `/midpoint-home` and installs
the bundle as:

```text
/midpoint-home/connid-connectors/connector-gitlab-rest-2.3.0-easyiga.jar
```

Installation is checksum-idempotent and removes only older files matching
`connector-gitlab-rest-*-easyiga.jar`, including when the current bundle was
already installed. The assembled bundle carries `META-INF/LICENSE` and
`META-INF/NOTICE`.

## ConnId identity

- Bundle: `com.evolveum.polygon.connector-gitlab-rest`
- Version: `2.3.0-easyiga`
- Connector class:
  `com.evolveum.polygon.connector.gitlab.rest.GitlabRestConnector`
- ConnId framework: `1.6.0.0`

## Configuration properties

- `baseUrl`: full GitLab URL, for example `http://gitlab`
- `privateToken`: protected administrator PAT with `api` scope
- `connectTimeoutMillis`: positive integer; default `10000`
- `requestTimeoutMillis`: positive integer; default `30000`
- `allowAccountDelete`: must remain `false`; account hard delete is not
  implemented
- `allowTopologyMutations`: must remain `false`; the fork never creates,
  deletes, or changes group/project topology
- `forceRandomPasswordOnCreate`: default `true`; uses GitLab's administrator
  `force_random_password` option when no ConnId password is supplied
- `deprovisionDeveloperGroupPath` and
  `deprovisionMaintainerProjectPath`: optional exact paths configured together;
  these paths also form the allow-list for every membership mutation
- `eventLookbackDays`: positive integer; default `90`; window for the activity
  signals
- `maxProjectsPerGroup`: positive integer; default `20`; bound on projects
  scanned per group for the Developer signal
- `integrationUsername`: default `easyiga-midpoint`; username of the dedicated
  instance-administrator identity the token must belong to (protected from
  mutation; must not be `root`)
- `integrationTokenName`: default `easyiga-midpoint-connector`; exact name the
  token must carry

The configured URL may include a reverse-proxy path. A trailing `/api/v4` is
accepted but not required.

Test Connection requires the PAT to belong to the configured dedicated
instance administrator (`integrationUsername`), to be active and not revoked,
and to carry `api` scope. Its name must be exactly `integrationTokenName`, and
its ISO expiration date must be strictly in the future with no more than 30
days remaining — the check is on *remaining* validity, not total lifetime, so
the token has to be renewed at least monthly. Both `root` and the configured
integration identity are protected from account and membership mutation.

## Object model

- `__ACCOUNT__` (`AccountObjectClass`): username, name, email, password and
  enabled/blocked state; hard delete is permanently unavailable.
- `__GROUP__` (`GroupObjectClass`): read-only topology identified exactly by
  `full_path`; writable direct Developer membership through
  `developer_members`.
- `Project` (`CustomProjectObjectClass`): read-only topology identified exactly
  by `path_with_namespace`; writable direct Maintainer membership through the
  compatibility attribute `master_members`.

Membership values are GitLab numeric user IDs at runtime. They must not be
committed. Group membership deletion always uses
`skip_subresources=true`. Adding a user already present at another access level
uses GitLab's membership `PUT` endpoint only when elevating the role; stronger
externally granted roles are never downgraded.

## Membership ownership

GitLab 19.2 stores `created_at`, `created_by`, `access_level`, and `expires_at`
on each direct group/project membership row. The connector uses that
target-native record as its ownership evidence. It compares both the creator
ID and username to the currently authenticated integration
administrator returned by `GET /user`; no runtime ID is configured, committed,
or cached as ownership state.

A membership is package-created only when all of the following are true:

- the member identity returned by GitLab is the requested identity;
- creator ID and username both match the current integration administrator;
- `created_at` is a valid ISO instant;
- access is exactly Developer (30) or Maintainer (40), as appropriate; and
- `expires_at` is present and null.

An exact membership created by another administrator is pre-existing external
access and is preserved. A package-origin membership whose level or expiry was
changed is treated as external takeover and is preserved. Missing or malformed
creator/timestamp/expiry evidence is unknown legacy state and is also
preserved. A partial creator match, such as the expected username with a
different runtime ID, is conflicting provenance: cleanup fails closed without
deleting or blocking the account.

The same ownership check gates explicit association removal and account-disable
cleanup. Group and project decisions are independent. A membership created by
the package is therefore removed on unassignment, while an exact-level
external membership survives. Stronger roles and all memberships at other
paths are preserved. The user is blocked, never deleted.

While assigned, a lower pre-existing external role is still elevated to the
package minimum so reconciliation converges. Its original external creator
does not change, so unassignment preserves the resulting membership rather
than deleting or attempting to reconstruct the earlier level.

Membership creation and creator provenance are one GitLab database-row
transaction. The connector validates the POST response and refetches once if
the response lacks evidence. It compensates an incomplete creation only when
the POST and authoritative GET carry the same stable creation fingerprint; a
missing or changed fingerprint is preserved for operator reconciliation. A
concurrent HTTP 409 is refetched and classified from the winning row instead of
being claimed. If a row changed hands before validation, cleanup is refused
rather than risking deletion.

There is no automatic legacy backfill. Existing rows are classified from their
GitLab metadata. A null/malformed legacy creator is retained until an operator
explicitly removes and lets the package recreate the membership. The dedicated
integration identity (default `easyiga-midpoint`) is a trust boundary and must not be used for manual
membership administration. A same-level no-op update does not transfer
ownership; an administrator taking over an exact-level membership must delete
and recreate it with a different administrator identity.

Search supports exact, single-valued ConnId Equals and ContainsAllValues filters
and AND conjunctions of those filters. The single-valued ContainsAllValues form
is required by midPoint's post-create shadow refetch. UID or stable-path
predicates use direct GitLab lookups and every remaining predicate is validated
locally. Multi-valued ContainsAllValues, OR, and fuzzy filters are rejected.

## Safety boundary

This connector cannot create, delete, rename, or otherwise mutate groups or
projects. It does not implement live synchronization. API errors expose the
HTTP method, sanitized endpoint and status only; response bodies and
credentials are never included.

GitLab's member DELETE API has no ETag, row version, or conditional-delete
precondition. The connector therefore refetches and requires an unchanged full
membership immediately before deletion, but deployments must serialize
easyIGA unassignment with manual administration of the same user and managed
path. A replacement in the final GET-to-DELETE interval cannot be distinguished
by the target API and is an explicit single-writer limitation.
