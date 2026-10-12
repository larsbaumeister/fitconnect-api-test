# fitko-swing-client

A Swing desktop **dev tool** for sending FIT-Connect submissions to **any**
Zustellpunkt and following what happens to them. Built directly on the
FIT-Connect Java SDK (`dev.fitko.fitconnect.sdk:client`, no Spring).

- **Connections**: named sender-client credential sets (client id/secret per
  environment TEST/STAGE/PROD or a custom one). The secret can stay in
  environment variables.
- **Zustellpunkte**: saved destinations. *Fetch info* reads a destination's
  services, schemas, regions, metadata versions and reply channels from the
  submission API. The compose form then offers exactly those values.
- **Presets**: save the compose form and reuse it. The payload can contain
  placeholders that are resolved freshly on every send.
- **History**: every submission sent, or attempted, is kept in a searchable,
  filterable table. You can filter by status, Zustellpunkt, preset and time
  period, and free-text search covers ids and the payload. It also has an
  event-log viewer, *Resend*, *Follow-up in same case*, notes and CSV export.
- **Status tracking**: pending submissions are re-checked in the background
  (every 30 s by default). Rows flash when their status changes, and a desktop
  notification fires on *accepted* or *rejected*.

## Build & run

```bash
cd fitko-swing-client
mvn package                          # runs the tests, builds target/fitko-swing-client.jar
java -jar target/fitko-swing-client.jar
# or: mvn -q compile exec:java
```

Requires JDK 17+. On a bare Linux or WSL you need an X server (or WSLg) plus
fontconfig and fonts (`libx11`, `libxext`, `libxrender`, `libxtst`,
`fontconfig`, any TTF fonts). Without fonts, FlatLaf can't start, and the app
falls back to Swing's built-in look.

## First start

1. *Welcome* → **Add connection**. Enter the client id and secret of a sender
   client from the [Self-Service-Portal](https://docs.fitko.de/fit-connect/docs/getting-started/account),
   then click **Test connection**. It fetches an OAuth token.
2. **Add Zustellpunkt**. Paste the destination ID, then click **Fetch info**.
3. In the *New submission* tab, the service, schema and region lists are now
   filled from the destination. A built-in example preset (Gewerbeanmeldung,
   XZuFi) is there to start from. Click **Send**.

The *Sent submissions* tab shows the new row as `SUBMITTED`. Once a receiver
accepts or rejects it, the row turns `ACCEPTED` or `REJECTED`. Rejection
reasons (problems) appear in the detail pane and in the event log.

## Payload placeholders

| Placeholder  | Resolved to                                       |
|--------------|---------------------------------------------------|
| `${uuid}`    | a new random UUID (one per occurrence)            |
| `${now}`     | current timestamp, ISO-8601 with offset           |
| `${date}`    | current date                                      |
| `${counter}` | running number, persisted across restarts         |
| `${caseId}`  | the case the submission is appended to (or empty) |

`$${x}` gives a literal `${x}`. **Preview** shows the payload the next send
will produce. **Send ×N** (the *Copies* spinner) resolves the placeholders
separately for each copy.

## Data

Everything is stored as JSON in `~/.fitconnect-ui/`. Override the location
with `-Dfitconnect.ui.home=<dir>`, e.g. to keep one setup per project.

| File                 | Content                                                        |
|----------------------|----------------------------------------------------------------|
| `connections.json`   | credentials. Owner read/write only (`rw-------`) on POSIX file systems |
| `zustellpunkte.json` | saved destinations + fetched destination info                  |
| `presets.json`       | presets                                                        |
| `submissions.json`   | history, including the auth tags needed to check status later  |
| `settings.json`      | poll interval, theme, window layout, `${counter}`              |

Saves are atomic. A file that can't be parsed is moved aside as
`<name>.corrupt-<timestamp>` and reported at startup.

*Zustellpunkte → Export* writes Zustellpunkte + presets to one file that a
teammate can *Import*. Connections, and so secrets, are never exported.

## Design notes

- `service/SubmissionFactory` builds the SDK's `SendableSubmission`. It does
  the same builder walk as `fitko-spring`'s `DefaultSubmissionSender`.
- The status of a submission is checked with the connection it was **sent**
  with, so switching connections doesn't break older rows.
- *Fetch info* calls `GET {submission-api}/v2/destinations/{id}` with its own
  OAuth token (`service/DestinationInfoService`). It's the same endpoint the
  SDK reads before every send, but `ClientFactory` doesn't expose that
  service.
- Every network call runs off the Swing event thread. Data is only touched on
  the event thread, and files are written on a background thread.
