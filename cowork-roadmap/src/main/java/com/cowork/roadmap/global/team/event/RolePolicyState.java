package com.cowork.roadmap.global.team.event;

import java.time.Instant;

import tools.jackson.databind.JsonNode;

public record RolePolicyState(String kind, Long roleId, Long accountId, boolean isDefault, String permissions,
        boolean deleted, Instant occurredAt) {
    public static RolePolicyState parse(String key, JsonNode event) {
        if (event == null || !event.isObject()) {
            throw new IllegalArgumentException("Expected a role policy object");
        }
        String kind = event.path("eventType").asString("");
        Instant version = Instant.parse(event.path("occurredAt").asString(""));
        boolean deleted = bool(event, "deleted");
        long roleId = id(event, "roleId");
        if ("ASSIGNMENT".equals(kind)) {
            long accountId = id(event, "accountId");
            checkKey(key, "assignment:" + accountId + ":" + roleId);
            return new RolePolicyState(kind, roleId, accountId, false, "{}", deleted, version);
        }
        if (!"ROLE".equals(kind)) {
            throw new IllegalArgumentException("Invalid account policy type");
        }
        checkKey(key, "role:" + roleId);
        JsonNode permissions = event.path("permissions");
        if (!permissions.isObject()) {
            throw new IllegalArgumentException("Missing permissions");
        }
        for (JsonNode value : permissions) {
            if (!value.isBoolean()) {
                throw new IllegalArgumentException("Invalid permission value");
            }
        }
        return new RolePolicyState(kind,
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
