import unittest

from vault_unsealer import unseal


class UnsealTest(unittest.TestCase):
    def test_submits_shares_until_vault_is_unsealed(self):
        submitted = []

        def submit(key):
            submitted.append(key)
            return {'sealed': len(submitted) < 2}

        self.assertTrue(unseal({'initialized': True, 'sealed': True}, ['a', 'b', 'c'], submit))
        self.assertEqual(submitted, ['a', 'b'])

    def test_does_nothing_when_vault_is_unsealed_or_uninitialized(self):
        def submit(key):
            raise AssertionError('must not submit')

        self.assertFalse(unseal({'initialized': True, 'sealed': False}, ['a'], submit))
        self.assertFalse(unseal({'initialized': False, 'sealed': True}, ['a'], submit))


if __name__ == '__main__':
    unittest.main()
