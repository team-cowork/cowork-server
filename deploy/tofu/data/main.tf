module "catalog" { source = "../modules/catalog" }
locals {
  root        = module.catalog.repository
  envoy_image = jsondecode(file("${local.root}/deploy/production/versions.json")).envoy.image
  database_scripts = { for owner in module.catalog.database_owners : owner => (
    contains(["chat", "voice"], owner) ? ["mongodb-init.sh", "mongodb-init.js"] :
    owner == "preference" ? ["postgres-init.sh"] : ["mysql-init.sh"]
  ) }
  database_files = { for owner, scripts in local.database_scripts : owner => {
    for name in scripts : name => file("${local.root}/deploy/runtime/databases/${name}")
  } }
  search_files     = { for name in toset(["init.sh", "role.json"]) : name => file("${local.root}/deploy/runtime/search/${name}") }
  storage_files    = { for name in toset(["configure-bucket.sh", "render-cors.sh"]) : name => file("${local.root}/deploy/runtime/storage/${name}") }
  kafka_files      = { for name in toset(["server.properties", "start-broker.sh", "policy.json"]) : name => file("${local.root}/deploy/runtime/kafka/${name}") }
  monitoring_files = { for name in fileset("${local.root}/deploy/runtime/monitoring", "**/*.{yml,yaml,json,conf}") : name => file("${local.root}/deploy/runtime/monitoring/${name}") if name != "environment.json" }
  monitoring_env   = jsondecode(file("${local.root}/deploy/runtime/monitoring/environment.json"))
  storage_env = { for owner in module.catalog.storage_owners : owner => merge(
    { PUBLIC_WEB_ORIGINS = module.catalog.runtime_env.gateway.PUBLIC_WEB_ORIGINS },
    module.catalog.runtime_env[owner]
  ) }
  common = { envoy_image = local.envoy_image, vault_mount = var.vault_mount }
  jobs = merge(
    { for owner in module.catalog.database_owners : "cowork-${owner}-db" => {
      jobspec = file("${local.root}/deploy/nomad/data/${owner}-db.nomad.hcl")
      variables = merge(local.common, {
        secret_version = tostring(var.manifest.data_secret_versions[owner])
        runtime_env    = jsonencode({ for key, value in module.catalog.runtime_env[owner] : key => replace(replace(value, "$${", "$$${"), "%%{", "%%%{") if contains(["DB_NAME", "DB_USERNAME"], key) })
        config_files   = jsonencode({ for key, value in local.database_files[owner] : key => replace(replace(value, "$${", "$$${"), "%%{", "%%%{") })
      })
    } },
    { for owner in module.catalog.storage_owners : "cowork-${owner}-storage" => {
      jobspec = file("${local.root}/deploy/nomad/data/${owner}-storage.nomad.hcl")
      variables = merge(local.common, {
        secret_version = tostring(var.manifest.data_secret_versions["${owner}/storage"])
        runtime_env    = jsonencode({ for key, value in local.storage_env[owner] : key => replace(replace(value, "$${", "$$${"), "%%{", "%%%{") })
        config_files   = jsonencode({ for key, value in local.storage_files : key => replace(replace(value, "$${", "$$${"), "%%{", "%%%{") })
      })
    } },
    {
      cowork-chat-search = {
        jobspec = file("${local.root}/deploy/nomad/data/chat-search.nomad.hcl")
        variables = merge(local.common, {
          image          = var.manifest.infrastructure_images["chat-search"]
          secret_version = tostring(var.manifest.data_secret_versions["chat/search"])
          config_files   = jsonencode({ for key, value in local.search_files : key => replace(replace(value, "$${", "$$${"), "%%{", "%%%{") })
        })
      }
      cowork-kafka = {
        jobspec = file("${local.root}/deploy/nomad/data/kafka.nomad.hcl")
        variables = merge(local.common, {
          init_image     = var.manifest.infrastructure_images["kafka-init"]
          secret_version = tostring(var.manifest.data_secret_versions.kafka)
          config_files   = jsonencode({ for key, value in local.kafka_files : key => replace(replace(value, "$${", "$$${"), "%%{", "%%%{") })
          runtime_env    = "{}"
        })
      }
      cowork-redis = {
        jobspec = file("${local.root}/deploy/nomad/data/redis.nomad.hcl")
        # Redis has no application credentials: the loopback listener is behind Consul mTLS.
        variables = merge(local.common, { secret_version = "1", runtime_env = "{}" })
      }
      cowork-livekit = {
        jobspec = file("${local.root}/deploy/nomad/platform/livekit.nomad.hcl")
        variables = merge(local.common, {
          secret_version = tostring(var.manifest.data_secret_versions["voice/livekit"])
          runtime_env    = "{}"
        })
      }
      cowork-monitoring = {
        jobspec = file("${local.root}/deploy/nomad/platform/monitoring.nomad.hcl")
        variables = merge(local.common, {
          secret_version = tostring(var.manifest.platform_secret_versions.monitoring)
          runtime_env    = jsonencode({ for key, value in local.monitoring_env : key => replace(replace(value, "$${", "$$${"), "%%{", "%%%{") })
          config_files   = jsonencode({ for key, value in local.monitoring_files : key => replace(replace(value, "$${", "$$${"), "%%{", "%%%{") })
        })
      }
    }
  )
}
module "jobs" {
  source = "../modules/jobs"
  jobs   = local.jobs
}
output "job_modify_indices" { value = module.jobs.job_modify_indices }
