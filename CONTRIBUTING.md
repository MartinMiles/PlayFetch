# Contributing to PlayFetch

Thanks for helping improve PlayFetch.

## Development setup

1. Install JDK 21 and Android SDK Platform 37.
2. Fork and clone the repository.
3. Create a focused branch from `main`.
4. Run `./gradlew testDebugUnitTest lintDebug assembleDebug` before opening a pull request.

Use the checked-in Gradle wrapper. Do not commit generated APKs, local SDK paths, credentials, keystores, downloaded third-party packages, or research captures.

## Pull requests

- Keep each pull request focused on one concern.
- Add or update tests for behavior changes.
- Explain security and signer-verification implications for download-path changes.
- Update `README.md` and `CHANGELOG.md` when user-visible behavior changes.
- Ensure CI passes and address review feedback with additional commits.

By contributing, you agree that your contribution is licensed under GPL-3.0-or-later.

## Reporting security issues

Do not disclose vulnerabilities in a public issue. Follow [SECURITY.md](SECURITY.md).
