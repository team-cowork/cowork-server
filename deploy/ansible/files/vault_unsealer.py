#!/usr/bin/env python3
"""Unseal the platform Vault with locally held Shamir key shares after it restarts sealed."""
import json
import ssl
import sys
import time
import urllib.request


def unseal(status, keys, submit):
    """Submit key shares while Vault is sealed. Return True when this call unsealed it."""
    if not status['initialized'] or not status['sealed']:
        return False
    for key in keys:
        if not submit(key)['sealed']:
            return True
    return False


def main(address, ca_file, key_file):
    context = ssl.create_default_context(cafile=ca_file)

    def call(method, path, body=None):
        data = None if body is None else json.dumps(body).encode()
        request = urllib.request.Request(f'{address}/v1/{path}', data=data, method=method)
        with urllib.request.urlopen(request, context=context, timeout=10) as response:
            return json.load(response)

    while True:
        try:
            keys = json.load(open(key_file))['keys_base64']
            if unseal(call('GET', 'sys/seal-status'), keys, lambda key: call('PUT', 'sys/unseal', {'key': key})):
                print('Vault unsealed', flush=True)
        except Exception as error:
            # Never print the error text: a rejected unseal request can echo request details.
            print(f'Vault unseal check failed: {type(error).__name__}', flush=True)
        time.sleep(10)


if __name__ == '__main__':
    main(*sys.argv[1:4])
