# Security policy

## Supported versions

Security fixes are applied to the latest released version.

## Report a vulnerability

Please use GitHub's private **Report a vulnerability** form on the repository's **Security** tab. Include the affected version, reproduction steps, impact, and any suggested remediation. Do not include sensitive details in a public issue.

You should receive an acknowledgement within seven days. A fix timeline depends on severity and reproducibility. Please allow time for a coordinated release before public disclosure.

## Download trust model

PlayFetch does not re-sign downloaded packages. Google-delivered files are checked against Play metadata; fallback APKs must pass Android signature verification and the app's signer-consistency checks. These controls reduce risk but do not replace independent verification for high-value or sensitive applications.
