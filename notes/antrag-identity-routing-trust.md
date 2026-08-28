# Identity, Kammer-Routing & Trust Level in an Antrag

FIT-Connect is a transport layer, not an identity or routing authority. Here's exactly
what it gives you for each of these three questions, and what you still have to build
yourself.

## 1. Getting user information (ELSTER-ID / BundID) from an Antrag

There is no native "applicant identity" field. It surfaces in one of two places, and
only if the sender chose to put it there.

**Where it lives:**

- **The submission payload itself** — the `content` validated against the service's
  `dataSchema`. Applicant-entered contact data, company info, tax numbers live here
  because the Fachverfahren put them there — FIT-Connect never parses or validates it
  for you.
- **Metadata `dataSets` (schema v2.x/v3.x) or `authenticationInformation` (v1.x)** —
  the optional, standardized slot for authentication *proofs*. If the sending
  Formular-Server authenticated the user via BundID or ELSTER, it can attach a
  `dataSet` using Governikus's `IdentificationReport` schema. Its `subjectRef` carries
  the pseudonymous subject identifier from whichever trust framework was used.

Both slots are available off `ReceivedSubmission`/`ReceivedAntrag.getMetadata()` (the
SDK exposes `dataSets`/`authenticationInformation` as part of the parsed metadata) -
no extra lookup needed.

**The catch:** attaching an `IdentificationReport` is entirely opt-in by whoever built
the sending Formular. FIT-Connect doesn't enforce it and doesn't check that it matches
the form data. Plenty of OZG-Formulare — especially simple company-data forms — never
attach one.

**Handling Anträge without it, or with insufficient identity:**

1. **Contract it at onboarding.** When a Formular-Anbieter registers to send to one of
   your destinations, agree what they must attach — an `IdentificationReport` dataSet,
   a minimum level of assurance. This is a business SLA, not something the protocol
   enforces.
2. **Validate on receipt.** Before auto-processing, check whether the expected
   identification proof is present and meets your bar.
3. **Define a fallback** for when it isn't: reject via the submission API with
   `DataSchemaViolation` and a problem detail naming what's missing; route to a manual
   review/Nacherfassung queue; or accept but flag the case as identity-unverified for
   the caseworker.
4. **Don't treat self-declared payload fields as identity proof** — only the
   `IdentificationReport`, when present, is an authenticated assertion. Everything
   else is just form input.

## 2. Finding the responsible Kammer for an Antrag

FIT-Connect doesn't know what a Kammer is — it only routes by **LeiKa-Schlüssel +
region** (ARS / AGS / Area).

**Decision: the portal routes, we don't re-check on receipt.** The Antragsteller
picks the Leistung through the portal's Leistungsfinder, the portal (or its
Formular-Server) resolves the destination — typically via the Router API on the
*sender* side — and submits straight to that IHK's registered destination. We take
that at face value: no `findAreas`/`findDestinations` re-check against the payload's
Sitz on our end, and no assumption that the Antragsteller picked wrong.

**What we still have to do, once, up front:**

- Register one `Destination` per IHK tenant, `Service.regions` set to the ARS codes
  of every Kreis in that IHK's Bezirk — fully scriptable via `DestinationClient`, no
  manual SSP work.
- Source that region list from a static ARS→IHK-Nummer table. Every
  Landkreis/kreisfreie Stadt belongs to exactly one IHK, never split, but there's no
  ready-made public machine-readable CSV — build it once from DIHK/IHK-Finder data.
- This table is what makes the portal's own routing land correctly in the first
  place; it's setup work, not a per-request lookup.

**If it later turns out the selected IHK isn't responsible** (discovered during case
processing, e.g. the Sitz doesn't fall in this IHK's Bezirk after all): reject the
submission, with a `Problem.detail` pointing the applicant/portal at the correct
Kammer — not a forced handover to the other Kammer, and not a receipt-time re-route.

**Fallback:** ambiguous or missing Sitz data → default to a catch-all intake
destination and a manual triage queue, rather than guessing.

## 3. Getting the trust level (Vertrauensniveau) from an Antrag

Same mechanism as user identity in §1 — no dedicated field, it rides along in the
`IdentificationReport` dataSet's `levelOfAssurance`:

| Framework | Values |
|---|---|
| eIDAS | `http://eidas.europa.eu/LoA/low` · `.../substantial` · `.../high` |
| BSI eID | `http://bsi.bund.de/eID/LoA/normal` · `.../substanziell` · `.../hoch` |
| Not-notified eIDAS | `http://eidas.europa.eu/NotNotified/LoA/*` |
| Unresolved | `unknown` |

**Reading it on receipt:** find the `dataSet` (v2/v3) or `authenticationInformation`
entry (v1) whose schema matches `IdentificationReport`, parse its JSON `content`, read
`levelOfAssurance`.

**Attaching it on send:** build a `DataSetToSend` (fitko-spring) or the SDK's
`DataSet` type directly - either auto-computes the required sha512 hash - to attach
a real `IdentificationReport` payload via `AntragToSend.builder(...).dataSet(...)`.

**Same caveat as §1:** it's opt-in by the sender. If a process needs a guaranteed
minimum LoA — e.g. "must be 2FA-verified" for a given operation — that's a rule you
enforce yourself at receipt time (reject or step-up if absent or too low), not
something FIT-Connect guarantees end to end.
