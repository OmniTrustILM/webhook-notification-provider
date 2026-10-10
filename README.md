# Webhook Notification Provider

> This repository is part of the commercial open-source project ILM. You can find more information about the project at the [ILM](https://github.com/OmniTrustILM/ilm) repository, including the contribution guide.

Webhook Notification Provider `Connector` is the implementation of the following `Function Groups` and `Kinds`:

| Function Group          | Kind      |
|-------------------------|-----------|
| `Notification Provider` | `WEBHOOK` |

It is compatible with the `Notification Provider` interface. This connector provides the following features:
- Send webhook notifications

## Database requirements

Webhook Notification Provider `Connector` requires the PostgreSQL database to store the data about the configured webhooks.

## Interfaces

Webhook Notification Provider implements `Notification Provider` interfaces. To learn more about the interfaces and end points, refer to the [Interfaces](https://github.com/OmniTrustILM/interfaces).

For more information, please refer to the [documentation](https://docs.otilm.com).

## Docker container

Webhook Notification Provider `Connector` is provided as a Docker container. Use the `hub.omnitrustregistry.com/ilm/webhook-notification-provider:tagname` to pull the required image from the repository. It can be configured using the following environment variables:

| Variable        | Description                                              | Required                                           | Default value |
|-----------------|----------------------------------------------------------|----------------------------------------------------|---------------|
| `JDBC_URL`      | JDBC URL for database access                             | ![](https://img.shields.io/badge/-YES-success.svg) | `N/A`         |
| `JDBC_USERNAME` | Username to access the database                          | ![](https://img.shields.io/badge/-YES-success.svg) | `N/A`         |
| `JDBC_PASSWORD` | Password to access the database                          | ![](https://img.shields.io/badge/-YES-success.svg) | `N/A`         |
| `DB_SCHEMA`     | Database schema to use                                   | ![](https://img.shields.io/badge/-NO-red.svg)      | `webhooknp`   |
| `PORT`          | Port where the service is exposed                        | ![](https://img.shields.io/badge/-NO-red.svg)      | `8080`        |
| `JAVA_OPTS`     | Customize Java system properties for running application | ![](https://img.shields.io/badge/-NO-red.svg)      | `N/A`         |
| `NOTIFICATION_LOG_REQUEST_PAYLOAD` | Include the notification request in DEBUG logs. See [How to enable DEBUG logs](#how-to-enable-debug-logs) | ![](https://img.shields.io/badge/-NO-red.svg) | `false` |

## Attributes to configure

Configuring an instance of this Webhook Notification Provider requires the following attributes:

| Attribute        | Description                                            | Content Type |
|------------------|--------------------------------------------------------|--------------|
| Webhook URL      | URL the event data is sent to                          | `STRING`     |
| Content type     | Format of the request body, see below                  | `STRING`     |
| Content template | Template rendering the request body, per content type  | `CODEBLOCK`  |

The following content types are supported:

| Content type | Request body                                                      | `Content-Type` header |
|--------------|-------------------------------------------------------------------|-----------------------|
| `RAW_JSON`   | The notification request serialized as-is, no template involved   | `application/json`    |
| `JSON`       | Rendered from the content template                                | `application/json`    |
| `XML`        | Rendered from the content template                                | `application/xml`     |

`RAW_JSON` takes no content template. For `JSON` and `XML` the content template is rendered with
FreeMarker against the notification request, so its fields are available as variables — for example
`${event}`, `${resource}` and `${notificationData.serialNumber}`.

A content template is checked when the instance is saved, and one that will not parse is refused
with the position of the error. Whether a reference such as `${notificationData.serialNumber}`
resolves depends on the event, so that is only found when a notification reaches the template. A
notification that reaches a template which will not render is answered with an unprocessable-entity
status, since the template is configuration rather than a fault of the connector, and a parse error
reads the same there as on save. Every stored template that will not parse is named in the log at
startup.

## Delivery request

Notifications are delivered as an HTTP `POST` to the configured webhook URL. Alongside the
`Content-Type` of the selected content type, each request carries:

| Header                | Description                                                                  |
|-----------------------|------------------------------------------------------------------------------|
| `X-Webhook-Timestamp` | Delivery time in milliseconds since the epoch, for replay detection          |
| `X-Webhook-Nonce`     | Per-delivery nonce, letting the receiver discard duplicate deliveries        |

> **Note**
> These headers were named `X-CZERTAINLY-Timestamp` and `X-CZERTAINLY-Nonce` before version 1.1.0.
> Receivers that validate the header names need to be updated.

## How to enable DEBUG logs

To enable DEBUG logs for the implementation of the webhook notification provider, you need to set the following environment variable:
```shell
LOGGING_LEVEL_COM_OTILM=DEBUG
```

DEBUG logs describe each notification by its identifiers only — event, resource, recipient count, and whether notification data is present. The notification request itself is never written to the logs at any level unless payload logging is switched on explicitly:

```shell
NOTIFICATION_LOG_REQUEST_PAYLOAD=true
```

> **Warning**
> With payload logging enabled, DEBUG logs contain the complete notification request, including data that can be sensitive — for example the one-time credential of certificate registration events, or object data enabled on the notification profile. Enable it only while troubleshooting, and treat the logs accordingly.

Template failures do not need it: they are reported at ERROR level with the template identifier, the event and resource, and the position of the failing expression in the template, without exposing any payload.
