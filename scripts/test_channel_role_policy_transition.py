import unittest

from channel_role_policy_transition import check, idempotency_key, operations, read_decision, target_policies


def snapshot(policies=None):
    return {
        "members": {(10, 1): "OWNER", (10, 2): "ADMIN", (10, 3): "MEMBER"},
        "roles": {5: (10, 1), 6: (10, 1), 7: (10, 2)},
        "assignments": {(10, 2): {5, 6}, (10, 3): {7}},
        "policies": policies if policies is not None else {},
        "channels": {100: (10, False), 101: (10, True)},
        "channel_members": {101: {3}},
    }


def manifest(policies, decision="APPLY", actor=1):
    return {"version": "v1", "teams": {"10": {"actorId": actor, "decision": decision, "policies": policies}}}


class ReadDecisionTest(unittest.TestCase):
    def test_owner_bypasses_policy(self):
        self.assertEqual(read_decision(snapshot(), {}, 10, 1, 100), (True, False))

    def test_admin_without_policy_is_denied(self):
        self.assertEqual(read_decision(snapshot(), {}, 10, 2, 100), (False, False))

    def test_deny_wins_at_same_priority(self):
        policies = {(10, 100, 5): True, (10, 100, 6): False}
        self.assertEqual(read_decision(snapshot(), policies, 10, 2, 100), (False, True))

    def test_highest_priority_policy_wins(self):
        state = snapshot()
        state["assignments"][(10, 2)] = {5, 7}
        self.assertEqual(read_decision(state, {(10, 100, 5): False, (10, 100, 7): True}, 10, 2, 100), (True, False))

    def test_absent_higher_priority_inherits_lower(self):
        state = snapshot()
        state["assignments"][(10, 2)] = {5, 7}
        self.assertEqual(read_decision(state, {(10, 100, 5): True}, 10, 2, 100), (True, False))


class CheckTest(unittest.TestCase):
    def test_team_missing_from_manifest_is_error(self):
        errors, _ = check({"version": "v1", "teams": {}}, snapshot())
        self.assertIn("team 10: manifest에 없는 전환 누락 팀입니다", errors)

    def test_actor_must_be_owner(self):
        errors, _ = check(manifest([], "DEFAULT_DENY_APPROVED", actor=2), snapshot())
        self.assertTrue(any("활성 OWNER가 아닙니다" in error for error in errors))

    def test_foreign_channel_and_role_are_errors(self):
        errors, _ = check(manifest([{"channelId": 999, "roleId": 999, "messageRead": True}]), snapshot())
        self.assertEqual(len(errors), 2)

    def test_default_deny_reports_zero_readable_non_owners(self):
        errors, warnings = check(manifest([], "DEFAULT_DENY_APPROVED"), snapshot())
        self.assertEqual(errors, [])
        self.assertEqual(len(warnings), 2)

    def test_allow_policy_clears_zero_readable_warning(self):
        policies = [
            {"channelId": 100, "roleId": 5, "messageRead": True},
            {"channelId": 101, "roleId": 7, "messageRead": True},
        ]
        self.assertEqual(check(manifest(policies), snapshot()), ([], []))


class OperationsTest(unittest.TestCase):
    def test_skips_unchanged_and_deletes_null(self):
        state = snapshot({(10, 100, 5): True, (10, 101, 7): False})
        ops = operations(manifest([
            {"channelId": 100, "roleId": 5, "messageRead": True},
            {"channelId": 101, "roleId": 7, "messageRead": None},
            {"channelId": 100, "roleId": 6, "messageRead": None},
        ]), state)
        self.assertEqual([(op["channelId"], op["roleId"], op["messageRead"]) for op in ops], [(101, 7, None)])

    def test_null_removes_policy_from_target(self):
        state = snapshot({(10, 101, 7): False})
        self.assertEqual(target_policies(manifest([{"channelId": 101, "roleId": 7, "messageRead": None}]), state), {})

    def test_idempotency_key_is_deterministic_and_permission_sensitive(self):
        key = idempotency_key("v1", 10, 100, 5, True)
        self.assertEqual(key, idempotency_key("v1", 10, 100, 5, True))
        self.assertNotEqual(key, idempotency_key("v1", 10, 100, 5, False))
        self.assertNotEqual(key, idempotency_key("v2", 10, 100, 5, True))
        self.assertLessEqual(len(key), 128)


if __name__ == "__main__":
    unittest.main()
