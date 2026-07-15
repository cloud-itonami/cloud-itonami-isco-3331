# cloud-itonami-isco-3331

Open Occupation Blueprint for **ISCO-08 3331**: Clearing and Forwarding Agents.

This repository designs a forkable OSS business for an independent customs clearing and freight forwarding practice: a document-handling and cargo-tracking robot manages clearance documentation under a governor-gated actor, so the practice keeps its own clearance records instead of renting a closed logistics SaaS.

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot performs
the physical domain work**. Here a document-handling and cargo-tracking robot performs customs-form printing, cargo-manifest filing and physical archival under an actor that proposes
actions and an independent **Customs Clearing Governor** that gates them. The governor never
dispatches hardware itself; `:high`/`:safety-critical` actions (such as
customs declaration above the client's registered duty-value ceiling) require human sign-off.

A live sample of the operator console (robotics safety console, shared template) is rendered in [docs/samples/operator-console.html](docs/samples/operator-console.html) — pure-data HTML output of `kotoba.robotics.ui`.

## Core Contract

```text
shipment manifest + customs declaration + duty schedule
        |
        v
Clearing Advisor -> Customs Clearing Governor -> clear shipment/file declaration, or human sign-off
        |
        v
robot actions (gated) + operating records + audit ledger
```

No automated advice can dispatch a robot action the governor refuses, suppress
an operating record, or disclose sensitive data without governor approval and
audit evidence.

## Capability layer

Resolves via [`kotoba-lang/occupation`](https://github.com/kotoba-lang/occupation)
(ISCO-08 `3331`). Required capabilities:

- :robotics
- :identity
- :forms
- :audit-ledger

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md).

## License

AGPL-3.0-or-later.
