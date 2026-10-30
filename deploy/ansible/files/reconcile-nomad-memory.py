#!/usr/bin/env python3
"""Reload a running Nomad client when Linux reports a different memory total."""
from pathlib import Path
import subprocess


def main():
    if subprocess.run(['systemctl', 'is-active', '--quiet', 'nomad.service']).returncode != 0:
        return
    total_mib = next(int(line.split()[1]) for line in Path('/proc/meminfo').read_text().splitlines()
                     if line.startswith('MemTotal:')) // 1024
    if total_mib <= 0:
        raise ValueError('MemTotal must be at least 1 MiB')
    marker = Path('/run/cowork-nomad-memory/total-mib')
    total = str(total_mib)
    if marker.exists() and marker.read_text().strip() == total:
        return
    # Nomad 2.0.7 re-runs its static memory fingerprinter on SIGHUP.
    subprocess.run(['systemctl', 'reload', 'nomad.service'], check=True)
    marker.write_text(total + '\n')
    print(f'Reload Nomad memory fingerprint for MemTotal={total_mib} MiB', flush=True)


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, StopIteration, subprocess.CalledProcessError) as error:
        raise SystemExit(f'Nomad memory reconciliation failed: {error}') from None
