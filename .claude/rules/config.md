---
paths:
  - "**/{application,bootstrap}*.{yml,yaml}"
  - "cowork-config/src/main/resources/configs/**"
  - "cowork-*/internal/{config,controlplane}/**"
  - "cowork-chat/src/common/config/**"
  - "cowork-preference/src/main/kotlin/com/cowork/preference/{config/**,CoworkPreferenceMain.kt,ControlPlaneAuthentication.kt}"
  - "cowork-user/{config/**,lib/cowork_user/app_config.ex,lib/cowork_user/control_plane.ex}"
  - "deploy/{config/vault/**,local/services/**,compose/**,prod/slots/**,prod/slots.py}"
---

# Configuration

- Keep shared service settings in Config Server. A new Config client needs both `local` and `prod` profiles.
- Supply application secrets through Vault. GitHub Secrets hold deployment credentials; do not duplicate application secrets there.
- Use the same slot environment names across runtimes. Explicit slot inputs override the corresponding remote settings. Keep driver-specific conversion in the app's existing configuration code, not in CD or a bootstrap wrapper.
- Config clients do not all resolve `${VAR}`. Check the receiving loader before adding placeholders; provide a resolved value for clients that only merge property sources.
