# ADR-006: Flyway owns the schema; Hibernate is `ddl-auto: validate`

## Status

Accepted — 2026-09-23

## Context

The requirements gate chose PostgreSQL via Spring Data JPA. It did not say who creates the tables, and the default answer in a Spring Boot project is usually "Hibernate, via `ddl-auto`".

That answer does not work here, for reasons that come from other decisions in this design rather than from general principle:

- **The sequence is not attached to an entity at all.** ADR-001 requires `link_id_seq` to `START WITH 100000`, and `design.md` §5.1 has the application draw from it explicitly rather than through a JPA generator — `Link.id` carries no `@GeneratedValue`. Hibernate only emits sequence DDL for generators it manages, so under `ddl-auto` this sequence would simply never be created, and the application would fail at its first write.
- **The schema has `CHECK` constraints Hibernate will not generate** — the regex constraints on `slug` and `target_url`, which are defence in depth against a `javascript:` target reaching the `Location` header.
- **Rollback depends on schema discipline.** `design.md` §12 makes deployment rollback a JAR swap, which only holds if version N's schema is readable by version N−1's code. That is a rule about *migrations*, and it needs migrations to exist as reviewable artifacts.
- **The integration tests are the migration's test.** ADR-004 runs Flyway against a real Postgres on every integration run. If the schema were generated from entities, the tests would be verifying the entities against themselves.

## Decision

**Flyway owns the schema.** `src/main/resources/db/migration/V1__create_link.sql` (the content of `schema.sql`) is the single definition of the database, applied at application startup and at integration-test startup alike.

**Hibernate is `spring.jpa.hibernate.ddl-auto: validate`** in every profile — production, development, and test. It never creates, never alters, never drops. `validate` is chosen over `none` deliberately: it makes entity/DDL drift a startup failure that names the offending column, rather than a runtime failure at the first query that touches it.

There are **no down-migrations**. Rollback policy is stated in `schema.sql` and `design.md` §12: expand/contract, one version of backward compatibility, no undo scripts.

## Alternatives considered

**`ddl-auto: update`.** Zero setup, and the schema follows the entities automatically — genuinely convenient for the first week of a greenfield project. Rejected on four counts, any one of which would be sufficient here. It cannot express the sequence parameters or the regex `CHECK` constraints this schema needs. It never drops or narrows anything, so the database accumulates the fossil record of every entity field that ever existed and nobody can tell which columns are live. There is no artifact to review — the schema change is implicit in a Java diff, so a column type change gets the same scrutiny as a rename of a local variable. And it applies whatever change it infers at startup, against production, with no plan output and no way to roll back, which makes the deployment rollback path in §12 impossible to reason about.

**`ddl-auto: create-drop` in tests, Flyway in production.** A common split, and it makes the test suite fast and self-contained. Rejected because it means the tests run against a schema that is *not the one that ships*. The regex `CHECK` constraints would be absent from the test database, so the test asserting that a `javascript:` URL cannot be stored would pass for the wrong reason — it would be testing `UrlValidator` alone while believing it was testing both layers. It also leaves the Flyway migration itself completely untested until deployment, which is precisely when a broken migration is most expensive. ADR-004's whole argument is fidelity; this alternative concedes it.

**Liquibase.** Equivalent in capability and arguably better at cross-database portability, with changelogs in XML/YAML/JSON and genuine rollback support — that last point is a real advantage over Flyway, since Flyway's undo is a commercial feature. Rejected on two grounds. Portability is worth nothing here: the target is PostgreSQL and this schema deliberately uses PostgreSQL-specific regex operators and `TIMESTAMPTZ`, so an abstraction over dialects would have to be escaped immediately. And the rollback advantage is largely theoretical for this design — `design.md` §12 deliberately does not rely on schema rollback, because a generated down-migration is a script nobody has ever executed, and executing an untested script during an incident is not a recovery plan. Plain SQL files that read exactly like the DDL a reviewer would write are the better artifact for a schema this small; the ability to *read* the migration is worth more than the ability to theoretically reverse it.

**Hand-applied SQL with no migration tool** — a DBA or an operator runs `schema.sql` before the first deploy. Rejected: it makes the integration-test setup a manual step, which is exactly what ADR-004 exists to eliminate, and it means nothing records which version of the schema a given database is actually at.

**`ddl-auto: none` rather than `validate`.** Marginally faster startup and one less thing that can refuse to boot. Rejected because `validate` is a genuinely valuable check at exactly the moment that matters most: after a rollback, when the old JAR meets the newer schema. `validate` fails immediately and names the column; `none` boots happily and fails later on a request, at which point the diagnosis is much harder. Given that §12's rollback verification starts with a health check, having the application refuse to start with a precise message is the better failure.

## Consequences

**Made easy.** The schema is a reviewable text file with the reasoning in comments, versioned alongside the code that depends on it. Every integration run exercises the real migration on a real PostgreSQL, so a broken migration fails in the test suite rather than in production. The sequence parameters and `CHECK` constraints — neither of which Hibernate can express — are stated directly. `validate` turns entity/DDL drift into a named startup failure.

**Made hard.** Every schema change is now two edits: the migration and the entity. They can disagree, and `validate` is the thing that catches it — which means `validate` must never be weakened to `none` to make a startup problem go away. A migration, once applied anywhere, is immutable: fixing a mistake means writing `V2`, not editing `V1`. That is correct and it is also a discipline people violate when they are in a hurry, and Flyway's checksum failure at startup is the guard.

**Foreclosed.** Switching databases. This schema uses PostgreSQL regex operators and `TIMESTAMPTZ`, and choosing plain-SQL Flyway over Liquibase gives up the (already thin) portability story. That is a deliberate trade — the requirements gate chose PostgreSQL, and designing for a database migration nobody has asked for is speculative generality.

**No schema rollback exists, and that is the design.** Recovery from a bad migration is forward-fix plus the expand/contract rule that keeps the previous JAR runnable against the new schema. If a future release genuinely needs a destructive change, it is a two-release change and must be planned as one at its own design gate — not discovered during an incident.
