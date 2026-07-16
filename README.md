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

## Actor implementation (`src/investigation`)

The `investigation.*` code in this repo implements a narrower, safer
slice of the vision above: a **case-scheduling / evidence-logging
COORDINATION actor**, not the full robotics-surveillance business
blueprint described elsewhere on this page. Concretely, the shipped
CaseCoordinator-LLM ⊣ RoutingGovernor actor (`investigation.operation`)
exposes exactly four closed-allowlist proposal ops — `:log-case-record`,
`:schedule-investigation-operation`, `:flag-legal-compliance-concern`,
`:coordinate-report-delivery` — and **never renders an investigative
conclusion (guilt/liability/fault) and never authorizes a surveillance
method itself**. Neither capability is a proposal op this actor's
allowlist recognizes at all; the governor's scope-exclusion-gate
(`investigation.policy`) is a permanent, non-overridable hard block
against either, independent of confidence, phase, or human approval.
Every governor-clean commit only ever writes a `:propose`-shaped SSoT
entry (`:status :proposed`/`:logged`) — this actor coordinates, it never
finalizes. See `docs/adr` in the superproject
(`com-junkawasaki/root`, ADR for ISIC 8030) for the full design
rationale, and `test/investigation/scope_exclusion_test.clj` for the
regression test guarding this boundary.

## Capability layer

Resolves via [`kotoba-lang/industry`](https://github.com/kotoba-lang/industry)
(ISIC `8030`). Implemented by:

- [`kotoba-lang/robotics`](https://github.com/kotoba-lang/robotics) — missions, actions, safety-stops, telemetry proofs
- [`kotoba-lang/labor`](https://github.com/kotoba-lang/labor) — investigator contracts, shift timesheets, wages

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md).

## License

AGPL-3.0-or-later.
