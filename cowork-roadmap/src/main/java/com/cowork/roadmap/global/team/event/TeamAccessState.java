package com.cowork.roadmap.global.team.event;

import java.time.Instant;

import tools.jackson.databind.JsonNode;

public record TeamAccessState(long teamId, long accountId, boolean manageRoadmaps, Instant membershipVersion,
        Instant authorizationVersion, Instant occurredAt) {
    public static TeamAccessState parse(String key, JsonNode event) {
        if (event == null || !"UPSERT".equals(event.path("eventType").asString(""))) {
            throw new IllegalArgumentException("Invalid team access event");
        }
        long teamId = positiveId(event.path("teamId"));
        long accountId = positiveId(event.path("accountId"));
        JsonNode permissions = event.path("permissions");
        if (!(teamId + ":" + accountId).equals(key) || !permissions.isObject()
                || !permissions.path("manage_roadmaps").isBoolean() || !event.has("sourceAuthorizationVersion")) {
            throw new IllegalArgumentException("Invalid team access state");
        }
        Instant membership = Instant.parse(event.path("sourceMembershipVersion").asString(""));
        Instant authorization = event.hasNonNull("sourceAuthorizationVersion")
                ? Instant.parse(event.path("sourceAuthorizationVersion").asString(""))
                : null;
        if (membership.isBefore(Instant.EPOCH) || (authorization != null
                && (!authorization.isAfter(Instant.EPOCH) || authorization.isAfter(membership)))) {
            throw new IllegalArgumentException("Invalid membership version");
        }
        for (JsonNode value : permissions) {
            if (!value.isBoolean() || (authorization == null && value.asBoolean())) {
                throw new IllegalArgumentException("Invalid team permission");
            }
        }
        return new TeamAccessState(teamId,
                accountId,
                permissions.path("manage_roadmaps").asBoolean(),
                membership,
                authorization,
                Instant.parse(event.path("occurredAt").asString("")));
    }

    private static long positiveId(JsonNode value) {
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() <= 0) {
            throw new IllegalArgumentException("Invalid team access identity");
        }
        return value.asLong();
    }
}
