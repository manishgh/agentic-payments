# Verified local implementation toolchain

Bootstrap evidence recorded 2026-11-06 for Windows x64, before application source scaffolding. Tools and database data stay under ignored `.tools/`; no global installation or Windows service registration occurs. The repository's own public licensing remains undecided; the inventory below describes third-party terms only.

## Distribution lock and verification

[Machine-readable distribution lock](../scripts/toolchain-lock.json) pins publisher URLs, checksum URLs, versions and complete hashes. [Bootstrap script](../scripts/Bootstrap-Toolchain.ps1) verifies each cached/downloaded archive against that lock and the publisher checksum before extraction. SHA-256 is used for Temurin/PostgreSQL and Apache's published SHA-512 for Maven. These checks detect corruption and unexpected publisher-content changes; they are checksum verification over HTTPS, not an independent PGP-signature validation claim.

| Distribution | Exact local release | License and selection rationale |
| --- | --- | --- |
| Eclipse Temurin JDK, HotSpot x64 | `21.0.13+9` | GPL-2.0 with Classpath exception, plus bundled notices. Java 21 LTS is the reviewed API target; Adoptium lists availability through at least December 2029. |
| Apache Maven | `3.9.16` | Apache-2.0 with bundled dependency notices. Official maintenance history names 3.9.16 as a maintained release; no Maven 4 prerelease is required. |
| EDB PostgreSQL Windows binaries | PostgreSQL `17.11`, packaging build `17.11-5` | PostgreSQL License for server; bundled third-party notices remain with archive. Official PostgreSQL Windows download page delegates binaries to EDB. Only server/client tools run; bundled pgAdmin/StackBuilder are unused. |

Exact verified archive digests:

```text
Temurin SHA256 a4dbaa05fca4bd89d84584e58c459bd732efebb1722af80ab13c6516832004f9
Maven SHA512 ed41650d42485cfc243fad22158caf9cbb5dc408ce7a09ddb94dd42a019de929ca43065bfa450612cf12bf78b5cafa3884b96c090de326ff590448c933454af3
PostgreSQL SHA256 80379b2c04d51c30225532e0ae04509899141e9957ed096fe749d7fd9df8f82f
```

Publisher sources inspected: [Adoptium API](https://api.adoptium.net/v3/assets/latest/21/hotspot?architecture=x64&image_type=jdk&os=windows&vendor=eclipse), [Adoptium support](https://adoptium.net/support), [Maven release history](https://maven.apache.org/docs/history.html), [PostgreSQL Windows downloads](https://www.postgresql.org/download/windows/), [EDB binaries](https://www.enterprisedb.com/download-postgresql-binaries). EDB's advertised 17.11 file link redirected to packaging build `17.11-5`; the earlier guessed `17.11-1` artifact was not selected. Checksum links are preserved exactly in the lock.

Distribution notices remain in `.tools/jdk-21.0.13+9/legal/`, `.tools/apache-maven-3.9.16/LICENSE` and `.tools/postgresql-17.11-5/pgsql/server_license.txt` plus adjacent third-party notice files. Downloads are development tools, not repository redistribution assets. No Docker daemon, existing PostgreSQL or runnable Java/Maven on PATH was found during bootstrap.

## Dependency decision and license inventory

The first slice uses JDK HTTP facilities plus JDBC and a JSON boundary library. Domain/application rules do not import JSON/JDBC libraries. A full application framework, ORM and migration framework are unnecessary for this bounded implementation.

| Coordinates / scope | Exact pin | License | Purpose |
| --- | --- | --- | --- |
| `com.fasterxml.jackson.core:jackson-databind` / runtime | `2.22.4` | Apache-2.0 | Strict JSON parsing/serialization at API and persistence boundaries. |
| `com.fasterxml.jackson.core:jackson-core` / transitive runtime | `2.22.4` | Apache-2.0 | Streaming JSON parser. |
| `com.fasterxml.jackson.core:jackson-annotations` / transitive runtime | `2.22` | Apache-2.0 | Jackson metadata; this artifact intentionally has no patch component. |
| `org.postgresql:postgresql` / runtime | `42.7.14` | BSD-2-Clause | Authoritative PostgreSQL JDBC transactions. |
| `org.checkerframework:checker-qual` / transitive runtime | `3.55.1` | MIT | JDBC type annotations. |
| `org.junit.jupiter:junit-jupiter` / test | `6.1.3` | EPL-2.0 | Behavior and database integration tests on Java 21. |
| Jupiter API/params/engine and JUnit Platform commons/engine / transitive test | `6.1.3` | EPL-2.0 | JUnit execution and assertions. |
| `org.opentest4j:opentest4j` / transitive test | `1.3.0` | Apache-2.0 | Test failure types. |
| `org.apiguardian:apiguardian-api` / transitive test | `1.1.2` | Apache-2.0 | API status annotations. |
| `org.jspecify:jspecify` / transitive test | `1.0.0` | Apache-2.0 | Nullness annotations. |

Versions/licenses were read directly from [Maven Central PostgreSQL metadata](https://repo.maven.apache.org/maven2/org/postgresql/postgresql/maven-metadata.xml), [JDBC POM](https://repo.maven.apache.org/maven2/org/postgresql/postgresql/42.7.14/postgresql-42.7.14.pom), [Jackson POM](https://repo.maven.apache.org/maven2/com/fasterxml/jackson/core/jackson-databind/2.22.4/jackson-databind-2.22.4.pom), [Jackson BOM](https://repo.maven.apache.org/maven2/com/fasterxml/jackson/jackson-bom/2.22.4/jackson-bom-2.22.4.pom), [JUnit POM](https://repo.maven.apache.org/maven2/org/junit/jupiter/junit-jupiter/6.1.3/junit-jupiter-6.1.3.pom) and each listed transitive POM. JDBC's Waffle integration is optional and is not selected. Dependency trees from the eventual tested build must confirm the effective inventory; plugin dependencies are separate build-time artifacts.

Maintenance/security review: the [Jackson 2.22 release record](https://github.com/FasterXML/jackson/wiki/Jackson-Release-2.22) lists 2.22.4 as released and the branch open for patches; 2.22 is not advertised as LTS. Keep default typing disabled and enforce input limits. The [pgJDBC security page](https://jdbc.postgresql.org/security/) identifies 42.7.14 as fixing authentication-policy and buffer-data disclosure defects affecting earlier patches. The [JUnit 6.1.3 release notes](https://docs.junit.org/6.1.3/release-notes.html) confirm its published maintenance release. This review does not assert the absence of vulnerabilities or replace future advisory checks.

Verified stable build-plugin releases from official Central metadata: compiler `3.16.0`, Surefire `3.6.1`, resources `3.5.0`, clean `3.5.0`, jar `3.5.1`, dependency `3.11.0`; Apache-2.0 inherited from their Apache parents. Compiler/Surefire prerelease 4.x lines are avoided for Maven 3.9. The build POM is authoritative for plugins actually used.

## Commands and local database

The durable-runtime slice adds Temporal Python SDK `1.35.0` and its resolved dependencies to the existing [environment lock](../workflows/requirements-lock.txt). The [additional dependency inventory](../workflows/temporal-dependency-inventory.json) records official metadata, license declarations, exact wheel/sdist identities and hashes. `pip check` validates the installed dependency relationships. This inventory is traceability for the inspected distributions, not an assertion that every platform wheel is hash locked.

For local runtime tests, `python scripts/bootstrap_temporal.py` installs Temporal CLI `1.10.0` under ignored `.tools/temporal-1.10.0`. The [distribution lock](../scripts/temporal-toolchain-lock.json) pins Windows/Linux x64 archives and the publisher checksum manifest. Bootstrap verifies both before extracting only regular executable/license/notice members into the fixed tool directory. It never starts the service. The verified Windows CLI reports embedded server `1.32.2` and UI `2.56.1`. Keep the server loopback-only; this development setup provides no production authentication, high availability or managed service.

Run from the repository root in PowerShell 7:

```powershell
./scripts/Bootstrap-Toolchain.ps1
. ./scripts/Use-LocalToolchain.ps1
. ./scripts/Start-LocalPostgres.ps1
java -version
mvn -version
postgres --version
```

The environment script affects only the invoking shell and child processes. Maven's dependency cache uses `.tools/m2`. Executables resolve to `.tools/jdk-21.0.13+9/bin/java.exe`, `.tools/apache-maven-3.9.16/bin/mvn.cmd` and `.tools/postgresql-17.11-5/pgsql/bin/`. Other platforms must provision these reviewed releases independently; the supplied binary bootstrap is Windows-only.

The database script initializes a separate `.tools/postgres-data` cluster with SCRAM authentication, binds only `127.0.0.1:55432`, and creates `agentic_payments`. It generates a random synthetic development password in ignored `.tools/local-postgres.json`; do not print, commit or reuse that file for external systems. Dot-sourcing startup sets `PAYMENTS_DB_URL`, `PAYMENTS_DB_USER` and `PAYMENTS_DB_PASSWORD` in the current process. A temporary initdb password file is removed in a `finally` block. Neither credentials nor connection strings containing them belong in logs. The database process runs under the current Windows account, with its window hidden.

```powershell
./scripts/Stop-LocalPostgres.ps1
```

Stop is graceful (`fast`), preserves data and allows restart with the same credentials. Startup does not reset data. Tests should isolate fixtures by tenant/run and use a separate test schema/database as the implementation specifies. This environment performs no live provider calls or money movement. Exact application build/test/run evidence is recorded with the implementation; tool version checks alone do not establish application correctness.

Bootstrap validation completed: all three published archive checksums matched; `java -version`, `mvn -version` and `postgres --version` reported the locked releases; authenticated `psql` returned PostgreSQL 17.11 and database `agentic_payments`; graceful stop/restart and repeated startup succeeded. The temporary initdb password file was absent afterward, and Git ignored the credentials, cluster and binary archives. PowerShell parser checks, documentation validation and `git diff --check` passed. Application integration evidence is separate.
