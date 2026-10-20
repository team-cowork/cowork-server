#!/bin/bash
# An administrator runs this from a root-owned staging directory that also holds ca.key and ca.crt.
set -euo pipefail
export PATH=/usr/sbin:/usr/bin:/sbin:/bin
umask 077
if [ "$EUID" -ne 0 ] || [ "$#" -ne 0 ]; then
  echo '[vault-tls] Bootstrap requires root and accepts no arguments' >&2
  exit 1
fi
source_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
for path in "$source_dir" bootstrap-vault-tls.sh vault-tls.py cowork-vault-tls.service cowork-vault-tls.timer settings.json ca.crt ca.key; do
  [ "$path" = "$source_dir" ] || path="$source_dir/$path"
  [ ! -L "$path" ] && [ "$(stat -c %u "$path")" = 0 ] && [ $(( 0$(stat -c %a "$path") & 022 )) = 0 ] ||
    { echo "[vault-tls] $path must be root-owned and not writable by others" >&2; exit 1; }
done
[ $(( 0$(stat -c %a "$source_dir/ca.key") & 077 )) = 0 ] || { echo '[vault-tls] ca.key must be private' >&2; exit 1; }
openssl x509 -in "$source_dir/ca.crt" -noout
[ "$(openssl pkey -in "$source_dir/ca.key" -pubout)" = "$(openssl x509 -in "$source_dir/ca.crt" -noout -pubkey)" ] ||
  { echo '[vault-tls] ca.key does not belong to ca.crt' >&2; exit 1; }

install -d -m 0755 -o root -g root /usr/local/libexec
install -d -m 0700 -o root -g root /etc/cowork/vault-tls
install -m 0600 -o root -g root "$source_dir/ca.key" /etc/cowork/vault-tls/ca.key
install -m 0644 -o root -g root "$source_dir/ca.crt" /etc/cowork/vault-tls/ca.crt
install -m 0644 -o root -g root "$source_dir/settings.json" /etc/cowork/vault-tls/settings.json
install -m 0755 -o root -g root "$source_dir/vault-tls.py" /usr/local/libexec/cowork-vault-tls.py
install -m 0644 -o root -g root "$source_dir/cowork-vault-tls.service" /etc/systemd/system/cowork-vault-tls.service
install -m 0644 -o root -g root "$source_dir/cowork-vault-tls.timer" /etc/systemd/system/cowork-vault-tls.timer

volume=$(python3 -I -c 'import json,sys; print(json.load(open(sys.argv[1]))["volume"])' /etc/cowork/vault-tls/settings.json)
docker volume inspect "$volume" >/dev/null 2>&1 || docker volume create "$volume" >/dev/null
/usr/bin/python3 -I /usr/local/libexec/cowork-vault-tls.py renew
systemctl daemon-reload
systemctl enable --now cowork-vault-tls.timer
echo '[vault-tls] CA installed, certificate issued and daily renewal enabled'
