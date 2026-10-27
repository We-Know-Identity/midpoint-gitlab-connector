# Connector provenance

This repository contains an easyIGA-maintained derivative of Evolveum's
`connector-gitlab-rest`.

- Upstream repository: <https://github.com/Evolveum/connector-gitlab-rest>
- Upstream release/tag: `v1.0`
- Upstream source commit: `42aa0dd0bcac97365a97a61498f0fc6f9af93b2b`
- Upstream tag object: `06190a1de3f95eacedd91955de2cb0da384f600c`
- Upstream license: Apache License 2.0
- Upstream released JAR SHA-256:
  `a6ddb736755e5b3330c156f7acc3bc4edce330288daf339d2394fa93d68058f7`
- Fork version: `2.3.0-easyiga` (2.2.0 = lab-measured build of Oct 2026; 2.3.0 adds the
  coverage attributes and the configurable integration identity)
- Target runtime: midPoint 4.8.12, ConnId 1.6.0.0, Java 17+,
  GitLab CE 19.2

The implementation retains the upstream public connector class and bundle
identity for migration compatibility, but it was rewritten around the Java
HTTP client. The main compatibility and safety changes are:

- a full, configurable HTTP or HTTPS base URL;
- bounded connect and request timeouts;
- GitLab v4 pagination and ConnId offset/page-size handling;
- exact `full_path` and `path_with_namespace` lookup;
- safe exact-Equals conjunctions and the single-valued ContainsAllValues form
  used by midPoint shadow refetch;
- random-password account bootstrap support;
- hard account deletion permanently unavailable;
- group and project topology mutation permanently unsupported;
- only the Task-3 direct membership attributes are writable;
- existing memberships use GitLab `PUT` for access-level changes;
- administrative identities and externally elevated roles are protected from
  membership mutation;
- Test Connection verifies the dedicated administrator identity, active token,
  exact token name, `api` scope, and a future expiration of at most 30 days;
- group-member removal always sends `skip_subresources=true`;
- direct group/project membership rows use GitLab 19.2's target-native
  `created_by` and `created_at` metadata as durable creation provenance;
- both association removal and account disable delete only memberships proven
  to have been created by the current dedicated integration administrator at
  the exact package level with no expiry;
- exact external, externally modified, and unknown legacy memberships are
  preserved; partial creator-identity matches fail closed;
- configured Task-4 business paths allow-list every membership mutation;
- the assembled bundle embeds the upstream Apache license and fork notice;
- API failures never include tokens, request bodies, or response bodies.

The fork does not copy generated runtime data, connector binaries, credentials,
or GitLab identifiers into source control.

GitLab group/project audit events were not selected as provenance: the pinned
Community Edition does not provide a dependable endpoint for this purpose.
ConnId operation options and midPoint 4.8 simple association/shadow state do
not carry durable per-membership creation attribution. A separate connector,
shadow, custom-attribute, or file ledger would require a second non-atomic
write. GitLab's membership row instead persists the membership, creator, and
creation timestamp together and survives connector, midPoint, and GitLab
service restarts.

This mechanism assumes the protected integration administrator (configurable,
default `easyiga-midpoint`) is used only by the connector. Creator ID is resolved at runtime and checked together
with its exact username, so PAT rotation for the same identity is supported and
a same-named replacement identity cannot inherit ownership.
