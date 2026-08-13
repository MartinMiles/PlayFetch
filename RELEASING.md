# Releasing PlayFetch

Releases are created by the protected **Android release** GitHub Actions workflow. The workflow checks the requested tag against `versionName`, runs tests and release lint, builds a minified signed APK, publishes its SHA-256 checksum, and creates a GitHub build-provenance attestation.

## One-time repository setup

Create a GitHub environment named `release`, add any desired reviewer protection, and configure these environment secrets:

- `ANDROID_SIGNING_KEY_BASE64`: Base64-encoded JKS or PKCS#12 keystore.
- `ANDROID_KEYSTORE_PASSWORD`: Keystore password.
- `ANDROID_KEY_ALIAS`: Signing-key alias.
- `ANDROID_KEY_PASSWORD`: Signing-key password.

Keep an encrypted offline backup of the keystore and credentials. Losing the key prevents compatible updates; disclosing it compromises every release signed with it.

## Release checklist

1. Update `versionName` and `CHANGELOG.md` on a reviewed branch.
2. Merge only after Android CI succeeds.
3. Run **Android release** from the default branch with a matching `vX.Y.Z` tag.
4. Verify the generated attestation and checksum from a clean machine.
5. Install the APK and exercise download, deletion, link routing, and sharing on a physical device.

Do not commit signing material or distribute an APK signed with Android's shared debug key as a production release.
