# Homepage banner and persistent signing

The UI rule recognizes the supplied Simplified Chinese workspace-limit title
and auto-recharge button together, only on `https://chatgpt.com/`. It hides the
small matching top container, not the page/header/composer or conversation text.
It restores marked elements when leaving home or when their contents change.
Quota enforcement and billing are unchanged. Other languages/routes need their
own verified markup. No request is sent and no billing control is clicked.

Validate on the device: fresh home, opening an existing chat, returning home,
new chat, switching workspace, light/dark themes, and a chat quoting the banner.
If the website markup no longer matches, the banner remains visible.
Run `node tools/test_home_banner.cjs` for dependency-free fixture tests.

## Signing setup

Run `python3 tools/configure_signing.py` in Codespaces before publishing this
workflow. It needs `gh` access to manage repository Actions secrets and JDK
`keytool`. It creates one PKCS12 key outside the repository and stores its
encrypted key bytes and random password as `RELEASE_SIGNING_JSON` in Actions.
An existing secret is never replaced automatically. The workflow fails if the
secret is absent; it never falls back to a new debug key.

Back up `~/.local/share/gpt-web-native-signing/signing-private.json` offline in
a secure location before deleting Codespaces. It contains private signing
material: never commit, share in chat, or upload as a build artifact.

The first build with the new key cannot update an installation signed with an
unrelated old key. If the original key cannot be recovered, uninstall/reinstall
once after saving drafts; WebView login/local state will be lost. Subsequent
builds use the fixed key. Version codes start at 1000 plus the Actions run number.
Rerunning the same run retains the same version code, which is not inherently an
Android update failure. Install artifacts from the newest run.

Artifacts include `signing-certificate.txt` (public certificate information,
not the private key). Compare its SHA-256 fingerprint between builds if an
update fails. Android's exact installer error is still needed to distinguish
signature mismatch from downgrade, storage or package parsing errors.

The previous run #6 reported both a missing signing cache and a cache path that
did not exist. The new workflow no longer relies on that cache.
