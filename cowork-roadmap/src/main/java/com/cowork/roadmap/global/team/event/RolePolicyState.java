package com.cowork.roadmap.global.team.event;

import java.time.Instant;

import tools.jackson.databind.JsonNode;

public record RolePolicyState(long teamId, String kind, Long roleId, Long accountId, boolean isDefault,
        String permissions, boolean deleted, Instant occurredAt) {
    public static RolePolicyState parse(String key, JsonNode event, boolean teamScoped) {
        if (event == null || !event.isObject()) {
            throw new IllegalArgumentException("Expected a role policy object");
        }
        long teamId = teamScoped ? id(event, "teamId") : 0;
        String scope = teamScoped ? teamId + ":" : "";
        String type = event.path("eventType").asString("");
        Instant version = Instant.parse(event.path("occurredAt").asString(""));
        String kind = teamScoped ? switch (type) {
            case "ROLE_UPSERTED", "ROLE_DELETED" -> "ROLE";
            case "ASSIGNMENT_UPSERTED", "ASSIGNMENT_DELETED" -> "ASSIGNMENT";
            case "MEMBER_ASSIGNMENTS_DELETED" -> "MEMBER";
            default -> throw new IllegalArgumentException("Invalid team policy type");
        } : type;
        boolean deleted = teamScoped ? type.endsWith("_DELETED") : bool(event, "deleted");
        if (teamScoped && "MEMBER".equals(kind)) {
            long accountId = id(event, "accountId");
            checkKey(key, "member:" + scope + accountId);
            return new RolePolicyState(teamId, kind, null, accountId, false, "{}", true, version);
        }
        long roleId = id(event, "roleId");
        if ("ASSIGNMENT".equals(kind)) {
            long accountId = id(event, "accountId");
            checkKey(key, "assignment:" + scope + accountId + ":" + roleId);
            return new RolePolicyState(teamId, kind, roleId, accountId, false, "{}", deleted, version);
        }
        if (!"ROLE".equals(kind)) {
            throw new IllegalArgumentException("Invalid account policy type");
        }
        checkKey(key, "role:" + scope + roleId);
        if (teamScoped && deleted) {
            return new RolePolicyState(teamId, kind, roleId, null, false, "{}", true, version);
        }
        JsonNode permissions = event.path("permissions");
        if (!permissions.isObject()) {
            throw new IllegalArgumentException("Missing permissions");
        }
        for (JsonNode value : permissions) {
            if (!value.isBoolean()) {
                throw new IllegalArgumentException("Invalid permission value");
            }
        }
        return new RolePolicyState(teamId,
                kind,
                roleId,
                null,
                bool(event, "isDefault"),
                permissions.toString(),
                deleted,
                version);
    }

    private static void checkKey(String actual, String expected) {
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException();
        }
    }

    private static long id(JsonNode event, String field) {
        JsonNode value = event.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() <= 0) {
            throw new IllegalArgumentException();
        }
        return value.asLong();
    }

    private static boolean bool(JsonNode event, String field) {
        if (!event.path(field).isBoolean()) {
            throw new IllegalArgumentException();
        }
        return event.path(field).asBoolean();
    }
}
