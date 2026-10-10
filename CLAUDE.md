# CLAUDE.md

Guidance for working in this repository.

## What this is

Webhook Notification Provider — an ILM `Connector` implementing the
`Notification Provider` function group for kind `WEBHOOK`. It stores webhook
configurations in PostgreSQL and delivers notifications as HTTP `POST` requests,
either as the raw notification request or rendered through a FreeMarker template.

Spring Boot 3 on Java 21, built with Maven.

## Commands

Build and run the tests:

```bash
mvn -B -U verify
```

Run one test class:

```bash
mvn -B test -Dtest=NotificationInstanceServiceImplTest
```

Coverage report (written by `verify`):

```bash
open target/site/jacoco/index.html
```

Local SonarQube scan:

```bash
mvn -B verify sonar:sonar -Dsonar.token=$SONAR_TOKEN
```

## Quality gates

- Coverage at least 80%. `pom.xml` sets `sonar.coverage.exclusions` for the
  `api`, `dto` and `dao.entity` packages and `ExceptionHandlingAdvice`, so the
  measured set is the service, attribute, util and listener code.
- Duplication under 3%. `sonar.cpd.exclusions` covers `dao.entity` only.
- No Sonar issues, no `TODO` or `FIXME` markers.
- Every third-party GitHub Action is pinned to a full commit SHA with a trailing
  version comment, for example
  `actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1`.

## Layout

| Path | Contents |
|---|---|
| `attribute/` | Attribute definitions (`Attributes`) and the content type enum |
| `api/` | Controllers implementing the connector interfaces, plus the attribute callback |
| `service/` | `AttributeService` and `NotificationInstanceService` with implementations |
| `dao/` | JPA entity and repository |
| `util/TemplateUtils` | FreeMarker rendering and payload-free failure reporting |

## Things worth knowing

**Attribute identifiers are a contract.** The UUIDs and names in `Attributes`
identify attributes in the platform database. Changing one orphans the
configuration of every existing notification instance. The same holds for the
`webhooknp` schema, the `notification_instance` table, kind `WEBHOOK`, and the
attribute callback path.

**Template and log output must stay payload-free.** The notification request can
carry sensitive values, for example the one-time credential of a certificate
registration event. Exception messages from this connector become its HTTP error
response toward the platform, so `TemplateUtils` reports only the template label,
the event and resource identifiers, and a position or exception type. The full
request is available at DEBUG only when `notification.log-request-payload` is
switched on. `TemplateUtilsErrorTest` guards this; keep it that way when
touching the render or send paths.

**A template that will not render is the operator's fault, not ours.** Parse
and render failures raise `ValidationException`, answered 422 with a plain list
of messages; only the data-model failure stays `NotificationException` (500).
Saving an instance and `ContentTemplatePreflight` at startup check parsing with
`TemplateUtils.contentTemplateFailure`, so a parse error reads the same on send,
on save and at startup.

**Delivery headers are vendor-neutral.** `X-Webhook-Timestamp` and
`X-Webhook-Nonce` are named after their function rather than after a product, so
the wire contract survives renames. They are constants in
`NotificationInstanceServiceImpl`.

**The interfaces library splits attributes across `common`, `v2` and `v3`.**
Version-independent types (`AttributeType`, `BaseAttribute`, `DataAttribute`,
properties, constraints, callbacks, content data) live under
`attribute.common`. Concrete attributes and their content are versioned:
`DataAttributeV2`, `StringAttributeContentV2` and so on. Connector interfaces
expect the `common` supertypes, so factories return concrete v2 types and the
controllers hand them over as `BaseAttribute` or `DataAttribute`.

**Attribute content constructors take `(reference, data)`.** The single-argument
form sets both. In the content type drop-down the reference is the wire name
(`json`) and the data is the enum name (`JSON`), which is what the instance
configuration is resolved from.
