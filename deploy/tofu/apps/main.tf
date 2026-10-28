module "catalog" { source = "../modules/catalog" }
locals {
  envoy_image = jsondecode(file("${module.catalog.repository}/deploy/production/versions.json")).envoy.image
  selected    = setsubtract(module.catalog.application_names, ["gateway"])
  jobs = { for name in local.selected : "cowork-${name}" => {
    jobspec = file("${module.catalog.repository}/deploy/nomad/apps/${name}.nomad.hcl")
    variables = {
      image          = var.manifest.applications[name].image
      secret_version = tostring(var.manifest.applications[name].secret_version)
      runtime_env    = jsonencode({ for key, value in module.catalog.runtime_env[name] : key => replace(replace(value, "$${", "$$${"), "%%{", "%%%{") })
      envoy_image    = local.envoy_image
      vault_mount    = var.vault_mount
    }
  } }
}
module "jobs" {
  source = "../modules/jobs"
  jobs   = local.jobs
}
output "job_modify_indices" { value = module.jobs.job_modify_indices }
