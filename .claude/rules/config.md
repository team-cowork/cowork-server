---
paths:
  - "**/{application,bootstrap}*.{yml,yaml}"
  - "cowork-config/src/main/resources/configs/**"
  - "cowork-*/internal/{config,controlplane}/**"
  - "cowork-chat/src/common/config/**"
  - "cowork-preference/src/main/kotlin/com/cowork/preference/{config/**,CoworkPreferenceMain.kt,ControlPlaneAuthentication.kt}"
  - "cowork-user/{config/**,lib/cowork_user/app_config.ex,lib/cowork_user/control_plane.ex}"
  - "deploy/{config/vault/**,local/services/**,compose/**,prod/slots/**,prod/slots.py,prod/configuration.json,prod/config/**,prod/cd/github-settings.py}"
---

# Configuration

- Keep shared service settings in Config Server. A new Config client needs both `local` and `prod` profiles.
- For production, GitHub Variables and Secrets are the editable source; CD publishes declared inputs to Vault before recreating containers. Put module-only inputs in `Prod-CD(<module>)`; put shared inputs once at repository scope. Vault carries the applied values and versions.
- Derive Config Server accounts from each client's declared username, password and profile. Do not maintain a second copy of client credentials or grant one client another application's settings.
- Keep VM placement, ports, volumes and resource limits in the slot/IaC code. Configuration updates must preserve database and object-storage data.
- Use the same slot environment names across runtimes. Explicit slot inputs override the corresponding remote settings. Keep driver-specific conversion in the app's existing configuration code, not in CD or a bootstrap wrapper.
- Config clients do not all resolve `${VAR}`. Check the receiving loader before adding placeholders; provide a resolved value for clients that only merge property sources.
