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
- GitHub Actions pinned to immutable commit SHAs;
- Gradle wrapper validation before wrapper execution in CI;
- a published SHA-256 checksum for the pinned Gradle distribution;
- Maven Central as the sole dependency repository, enforced centrally in Gradle settings;
- rejection of dynamic dependency selectors and changing modules;
- committed Gradle dependency lock state for reproducible transitive resolution;
- committed SHA-256 dependency verification metadata;
- explicit strict dependency verification for every CI Gradle invocation;
- automated Gradle and GitHub Actions dependency update checks through Dependabot;
- GitHub Dependency Graph analysis plus pull-request dependency review that rejects known moderate-or-higher vulnerabilities;
- CodeQL static analysis for relevant `main` changes plus a weekly scheduled scan;
- local ignore rules for runtime databases and common secret-bearing files.

Security tooling is additive. A green security workflow does not replace code review, test coverage, evidence validation, or the existing exact-head CI merge gate.

## Updating dependencies safely

Dependency updates must include every related governed artifact in the same reviewed change:

1. update the explicit dependency coordinate;
2. regenerate `bet/bet-cli/gradle.lockfile` with `--write-locks`;
3. regenerate `gradle/verification-metadata.xml` with `--write-verification-metadata sha256`;
4. review the resolved module families and file changes;
5. run the complete build with strict dependency verification.

Do not weaken verification mode, bypass lock state, add an alternate artifact repository, or hand-edit generated checksums to make an update pass.

## Disclosure

Please allow time to validate and remediate a report before public disclosure. Once a fix is available, coordinated disclosure can reference the affected version or commit without exposing unrelated sensitive information.
