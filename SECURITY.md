# Security Policy

## Supported version

Butler is under active development. Security fixes are applied to the current `main` branch. Older historical policy versions retained in the repository are compatibility and audit surfaces, not separately maintained release lines.

## Reporting a vulnerability

Please do **not** publish exploit details, credentials, tokens, personal data, or proof-of-concept attack steps in a public issue.

Preferred reporting path:

1. Open the repository's **Security** tab.
2. Use **Report a vulnerability** / private vulnerability reporting when that option is available.
3. Include the affected component, impact, reproduction conditions, and the minimum detail needed to validate the issue safely.

If private vulnerability reporting is not available, open a public issue containing only a short request for a private security contact channel. Do not include vulnerability details in that public issue.

## What to include

Useful reports include:

- affected file, command, workflow, or dependency;
- expected versus observed security behavior;
- prerequisites needed to reproduce the issue;
- impact and realistic attack scenario;
- whether credentials, user data, filesystem access, network access, or code execution are involved;
- suggested remediation, if known.

Do not include real secrets. Use redacted or synthetic values.

## Repository security controls

The repository's baseline security controls include:

- least-privilege GitHub Actions permissions;
- GitHub Actions checkout without persisted credentials where workflow pushes are not required;
- automated Gradle and GitHub Actions dependency update checks through Dependabot;
- CodeQL static analysis for relevant `main` changes plus a weekly scheduled scan;
- local ignore rules for runtime databases and common secret-bearing files.

Security tooling is additive. A green security workflow does not replace code review, test coverage, evidence validation, or the existing exact-head CI merge gate.

## Disclosure

Please allow time to validate and remediate a report before public disclosure. Once a fix is available, coordinated disclosure can reference the affected version or commit without exposing unrelated sensitive information.
