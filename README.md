# cloud-itonami-8030

Open Business Blueprint for **ISIC Rev.5 8030**: investigation activities
(private investigation, background checks, surveillance and case
research provided under contract).

This repository designs a forkable OSS business for community
investigation services: investigator licensing and case-authorization
management, robotics-assisted surveillance/observation, and evidence
reporting — run by a qualified operator so an investigation firm keeps
its own case and evidence records instead of renting a closed
case-management platform.

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot
performs the physical domain work**. Here robots (stationary/mobile
surveillance observation, document/evidence capture) operate under an
actor that proposes actions and an independent **Private Investigation
Governor** that gates them. The governor never dispatches hardware
itself; `:high`/`:safety-critical` actions (any surveillance of a
private residence, any action touching a minor, any covert recording)
require human sign-off.

## Core Contract

```text
intake + identity + investigator licensing + case authorization
        |
        v
Investigation Advisor -> Private Investigation Governor -> license, dispatch, evidence report, or human approval
        |
        v
robot/investigator actions (gated) + case record + evidence report + audit ledger
```

No automated advice can dispatch a robot/investigator action the governor
refuses, license an investigator outside their verified scope, or publish
an evidence report without governor approval and audit evidence.

## Capability layer

Resolves via [`kotoba-lang/industry`](https://github.com/kotoba-lang/industry)
(ISIC `8030`). Implemented by:

- [`kotoba-lang/robotics`](https://github.com/kotoba-lang/robotics) — missions, actions, safety-stops, telemetry proofs
- [`kotoba-lang/labor`](https://github.com/kotoba-lang/labor) — investigator contracts, shift timesheets, wages

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md).

## License

AGPL-3.0-or-later.
