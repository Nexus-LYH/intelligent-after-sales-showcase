# Portable CI entry

`.github/workflows/identity-build.yml` builds all current Maven modules and both frontend applications. The existing-service job runs four explicitly listed database-free Java suites. A separate domain job runs **every test** in approval/stock/finance/notification/ai without a global `-Dtest` filter. The frontend job runs the staff application's shared Vitest suite. Both frontends are installed from their own lockfiles before compilation because the customer application reuses staff source.

Run on a disposable clean checkout, using Java 21, Maven and Node 22.14.0:

```sh
mvn --batch-mode --no-transfer-progress -f services/pom.xml -pl trust,identity,asset,ticket,gateway -am '-Dtest=AuthCryptoTest,CompletionTimingTest,SupplementTimingTest,ReopenWindowTest' '-Dsurefire.failIfNoSpecifiedTests=false' clean verify
node ops/ci/verify-portable-reports.mjs
mvn --batch-mode --no-transfer-progress -f services/pom.xml -pl approval,stock,finance,notification,ai -am clean verify
node ops/ci/verify-domain-reports.mjs
npm ci --prefix apps/staff
npm ci --prefix apps/customer
npm test --prefix apps/staff
npm run build --prefix apps/staff
npm run build --prefix apps/customer
```

The existing-service report guard requires all four Java suites to exist, contain tests and have zero failures, errors and skips. `failIfNoSpecifiedTests=false` only permits reactor modules without a selected suite; the guard prevents a fully empty successful run. The domain report guard independently requires nonempty, unskipped, clean suites for each of the five new domains. H2 JDBC and connection proxies exercise local logic; they do not establish MySQL migration/locks or a trusted network transaction.

Do not run `clean verify` in the shared runtime checkout while a role has claimed its artifacts. No MySQL, browser, service process, local credential/configuration file or GitHub secret is used by this workflow. Windows-bound integration harnesses, migration execution, current private trust, real browser flows, independent acceptance, image publication and deployment require separate evidence and are not claimed by this workflow. Artifact upload is limited to selected portable JUnit XML; no runtime logs, private files or unreviewed JARs are uploaded.

First remote execution still requires the reviewed source baseline and this workflow to be pushed together. A local workflow file or a successful package alone is not a successful remote CI run.
