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
    "cowork-applet" : "nomad/apps/applet",
    "cowork-authorization-mysql" : "nomad/data/authorization",
    "cowork-user-mysql" : "nomad/data/user",
    "cowork-team-mysql" : "nomad/data/team",
    "cowork-project-mysql" : "nomad/data/project",
    "cowork-channel-mysql" : "nomad/data/channel",
    "cowork-roadmap-mysql" : "nomad/data/roadmap",
    "cowork-preference-postgresql" : "nomad/data/preference",
    "cowork-applet-surrealdb" : "nomad/data/applet",
    "cowork-chat-mongodb" : "nomad/data/chat",
    "cowork-voice-mongodb" : "nomad/data/voice",
    "cowork-user-seaweedfs" : "nomad/data/user/seaweedfs",
    "cowork-team-seaweedfs" : "nomad/data/team/seaweedfs",
    "cowork-chat-seaweedfs" : "nomad/data/chat/seaweedfs",
    "cowork-chat-elasticsearch" : "nomad/data/chat/elasticsearch",
    "cowork-kafka" : "nomad/data/kafka",
    "cowork-livekit" : "nomad/data/voice/livekit",
    "cowork-monitoring" : "nomad/apps/monitoring"
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
    "cowork-applet" : "cowork-applet",
    "cowork-authorization-mysql" : "cowork-authorization-mysql",
    "cowork-user-mysql" : "cowork-user-mysql",
    "cowork-team-mysql" : "cowork-team-mysql",
    "cowork-project-mysql" : "cowork-project-mysql",
    "cowork-channel-mysql" : "cowork-channel-mysql",
    "cowork-roadmap-mysql" : "cowork-roadmap-mysql",
    "cowork-preference-postgresql" : "cowork-preference-postgresql",
    "cowork-applet-surrealdb" : "cowork-applet-surrealdb",
    "cowork-chat-mongodb" : "cowork-chat-mongodb",
    "cowork-voice-mongodb" : "cowork-voice-mongodb",
    "cowork-user-seaweedfs" : "cowork-user-seaweedfs",
    "cowork-team-seaweedfs" : "cowork-team-seaweedfs",
    "cowork-chat-seaweedfs" : "cowork-chat-seaweedfs",
    "cowork-chat-elasticsearch" : "cowork-chat-elasticsearch",
    "cowork-kafka" : "cowork-kafka",
    "cowork-livekit" : "cowork-livekit",
    "cowork-monitoring" : "cowork-monitoring",
    "cowork-redis" : "cowork-redis",
    "cowork-authorization-mysql-metrics" : "cowork-authorization-mysql",
    "cowork-user-mysql-metrics" : "cowork-user-mysql",
    "cowork-team-mysql-metrics" : "cowork-team-mysql",
    "cowork-project-mysql-metrics" : "cowork-project-mysql",
    "cowork-channel-mysql-metrics" : "cowork-channel-mysql",
    "cowork-roadmap-mysql-metrics" : "cowork-roadmap-mysql",
    "cowork-preference-postgresql-metrics" : "cowork-preference-postgresql",
    "cowork-applet-surrealdb-metrics" : "cowork-applet-surrealdb",
    "cowork-chat-mongodb-metrics" : "cowork-chat-mongodb",
    "cowork-voice-mongodb-metrics" : "cowork-voice-mongodb"
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
      "cowork-applet",
      "cowork-user-seaweedfs",
      "cowork-team-seaweedfs",
      "cowork-chat-seaweedfs",
      "cowork-redis"
    ],
    "cowork-authorization" : [
      "cowork-authorization-mysql",
      "cowork-redis"
    ],
    "cowork-preference" : [
      "cowork-preference-postgresql",
      "cowork-redis"
    ],
    "cowork-user" : [
      "cowork-user-mysql",
      "cowork-user-seaweedfs",
      "cowork-redis"
    ],
    "cowork-team" : [
      "cowork-team-mysql",
      "cowork-team-seaweedfs"
    ],
    "cowork-channel" : [
      "cowork-channel-mysql"
    ],
    "cowork-notification" : [],
    "cowork-chat" : [
      "cowork-chat-mongodb",
      "cowork-chat-seaweedfs",
      "cowork-redis",
      "cowork-chat-elasticsearch"
    ],
    "cowork-voice" : [
      "cowork-voice-mongodb",
      "cowork-livekit"
    ],
    "cowork-project" : [
      "cowork-project-mysql",
      "cowork-github"
    ],
    "cowork-roadmap" : [
      "cowork-roadmap-mysql"
    ],
    "cowork-github" : [
      "cowork-redis"
    ],
    "cowork-applet" : [
      "cowork-applet-surrealdb"
    ],
    "cowork-livekit" : [
      "cowork-voice"
    ],
    "cowork-monitoring" : [
      "cowork-redis",
      "cowork-authorization-mysql-metrics",
      "cowork-user-mysql-metrics",
      "cowork-team-mysql-metrics",
      "cowork-project-mysql-metrics",
      "cowork-channel-mysql-metrics",
      "cowork-roadmap-mysql-metrics",
      "cowork-preference-postgresql-metrics",
      "cowork-applet-surrealdb-metrics",
      "cowork-chat-mongodb-metrics",
      "cowork-voice-mongodb-metrics"
    ]
  }
}
output "job_secrets" { value = local.job_secrets }
output "service_jobs" { value = local.service_jobs }
output "upstreams" { value = local.upstreams }
