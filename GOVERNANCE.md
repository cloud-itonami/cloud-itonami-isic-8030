# Governance

`cloud-itonami-8030` is an OSS open-business blueprint for community
investigation services, robotics-premised.

## Maintainers
Maintainers may merge changes that preserve these invariants:
- a robot/investigator action the governor refuses is never dispatched.
- the Private Investigation Governor remains independent of the advisor.
- hard policy violations (out-of-scope license, unauthorized surveillance, evidenceless finding) cannot be overridden by human approval.
- every dispatch, sign-off, license and evidence-report path is auditable.
- sensitive case and personal data stays outside Git.

## Decision Records
Architecture decisions live in `docs/adr/`. Changes to the trust model, storage contract, public business model, operator certification or license should add or update an ADR.

## Operator Governance
Anyone may fork and operate independently. itonami.cloud certification is a separate trust mark and should require security, robot-safety, audit and data-flow review.

Certified operators can lose certification for:
- bypassing robot-safety or license-scope checks
- mishandling case or personal data
- misrepresenting certification status
- failing to respond to security or safety incidents
