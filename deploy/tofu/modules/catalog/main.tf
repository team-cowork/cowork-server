locals {
  job_secrets = {
    "cowork-gateway" : "nomad/apps/gateway",
    "cowork-authorization" : "nomad/apps/authorization",
    "cowork-preference" : "nomad/apps/preference",
    "cowork-user" : "nomad/apps/user",
    "cowork-team" : "nomad/apps/team",
    "cowork-channel" : "nomad/apps/channel",
    "cowork-notification" : "nomad/apps/notification",
    "cowork-chat" : "nomad/apps/chat",
    "cowork-voice" : "nomad/apps/voice",
    "cowork-project" : "nomad/apps/project",
    "cowork-roadmap" : "nomad/apps/roadmap",
    "cowork-github" : "nomad/apps/github",
    "cowork-authorization-db" : "nomad/data/authorization",
    "cowork-user-db" : "nomad/data/user",
    "cowork-team-db" : "nomad/data/team",
    "cowork-project-db" : "nomad/data/project",
    "cowork-channel-db" : "nomad/data/channel",
    "cowork-roadmap-db" : "nomad/data/roadmap",
    "cowork-preference-db" : "nomad/data/preference",
    "cowork-chat-db" : "nomad/data/chat",
    "cowork-voice-db" : "nomad/data/voice",
    "cowork-user-storage" : "nomad/data/user/storage",
    "cowork-team-storage" : "nomad/data/team/storage",
    "cowork-chat-storage" : "nomad/data/chat/storage",
    "cowork-chat-search" : "nomad/data/chat/search",
    "cowork-kafka" : "nomad/data/kafka",
    "cowork-livekit" : "nomad/data/voice/livekit",
    "cowork-monitoring" : "nomad/apps/monitoring",
    "cowork-user-migrations" : "nomad/data/user"
  }
  service_jobs = {
    "cowork-gateway" : "cowork-gateway",
    "cowork-authorization" : "cowork-authorization",
    "cowork-preference" : "cowork-preference",
    "cowork-user" : "cowork-user",
    "cowork-team" : "cowork-team",
    "cowork-channel" : "cowork-channel",
    "cowork-notification" : "cowork-notification",
    "cowork-chat" : "cowork-chat",
    "cowork-voice" : "cowork-voice",
    "cowork-project" : "cowork-project",
    "cowork-roadmap" : "cowork-roadmap",
    "cowork-github" : "cowork-github",
    "cowork-authorization-db" : "cowork-authorization-db",
    "cowork-user-db" : "cowork-user-db",
    "cowork-team-db" : "cowork-team-db",
    "cowork-project-db" : "cowork-project-db",
    "cowork-channel-db" : "cowork-channel-db",
    "cowork-roadmap-db" : "cowork-roadmap-db",
    "cowork-preference-db" : "cowork-preference-db",
    "cowork-chat-db" : "cowork-chat-db",
    "cowork-voice-db" : "cowork-voice-db",
    "cowork-user-storage" : "cowork-user-storage",
    "cowork-team-storage" : "cowork-team-storage",
    "cowork-chat-storage" : "cowork-chat-storage",
    "cowork-chat-search" : "cowork-chat-search",
    "cowork-kafka" : "cowork-kafka",
    "cowork-livekit" : "cowork-livekit",
    "cowork-monitoring" : "cowork-monitoring",
    "cowork-user-migrations" : "cowork-user-migrations",
    "cowork-redis" : "cowork-redis",
    "cowork-authorization-db-metrics" : "cowork-authorization-db",
    "cowork-user-db-metrics" : "cowork-user-db",
    "cowork-team-db-metrics" : "cowork-team-db",
    "cowork-project-db-metrics" : "cowork-project-db",
    "cowork-channel-db-metrics" : "cowork-channel-db",
    "cowork-roadmap-db-metrics" : "cowork-roadmap-db",
    "cowork-preference-db-metrics" : "cowork-preference-db",
    "cowork-chat-db-metrics" : "cowork-chat-db",
    "cowork-voice-db-metrics" : "cowork-voice-db"
  }
  upstreams = {
    "cowork-gateway" : [
      "cowork-authorization",
      "cowork-preference",
      "cowork-user",
      "cowork-team",
      "cowork-channel",
      "cowork-notification",
      "cowork-chat",
      "cowork-voice",
      "cowork-project",
      "cowork-roadmap",
      "cowork-github",
      "cowork-user-storage",
      "cowork-team-storage",
      "cowork-chat-storage",
      "cowork-redis"
    ],
    "cowork-authorization" : [
      "cowork-authorization-db",
      "cowork-redis"
    ],
    "cowork-preference" : [
      "cowork-preference-db",
      "cowork-redis"
    ],
    "cowork-user" : [
      "cowork-user-db",
      "cowork-user-storage",
      "cowork-redis"
    ],
    "cowork-team" : [
      "cowork-team-db",
      "cowork-team-storage"
    ],
    "cowork-channel" : [
      "cowork-channel-db"
    ],
    "cowork-notification" : [],
    "cowork-chat" : [
      "cowork-chat-db",
      "cowork-chat-storage",
      "cowork-redis",
      "cowork-chat-search"
    ],
    "cowork-voice" : [
      "cowork-voice-db",
      "cowork-livekit"
    ],
    "cowork-project" : [
      "cowork-project-db",
      "cowork-github"
    ],
    "cowork-roadmap" : [
      "cowork-roadmap-db"
    ],
    "cowork-github" : [
      "cowork-redis"
    ],
    "cowork-livekit" : [
      "cowork-voice"
    ],
    "cowork-user-migrations" : [
      "cowork-user-db"
    ],
    "cowork-monitoring" : [
      "cowork-redis",
      "cowork-authorization-db-metrics",
      "cowork-user-db-metrics",
      "cowork-team-db-metrics",
      "cowork-project-db-metrics",
      "cowork-channel-db-metrics",
      "cowork-roadmap-db-metrics",
      "cowork-preference-db-metrics",
      "cowork-chat-db-metrics",
      "cowork-voice-db-metrics"
    ]
  }
}
output "job_secrets" { value = local.job_secrets }
output "service_jobs" { value = local.service_jobs }
output "upstreams" { value = local.upstreams }
