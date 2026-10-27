module "catalog" { source = "../modules/catalog" }
locals {
  ca_pem   = file(var.platform_ca_file)
  jwks_url = "https://10.0.0.111:4646/.well-known/jwks.json"
  job_ids  = toset(values(module.catalog.service_jobs))
  allowed_sources = { for destination in keys(module.catalog.service_jobs) : destination => sort([
    for source, upstreams in module.catalog.upstreams : source if contains(upstreams, destination)
  ]) }
}

resource "consul_acl_auth_method" "nomad" {
  name           = "nomad-workloads"
  type           = "jwt"
  token_locality = "local"
  description    = "Nomad service and task workload identities"
  # Nomad retires service tokens with allocations; expiring them independently breaks proxies.
  config_json = jsonencode({
    JWKSURL          = local.jwks_url
    JWKSCACert       = local.ca_pem
    JWTSupportedAlgs = ["RS256"]
    BoundAudiences   = ["consul.io"]
    ClaimMappings = {
      nomad_namespace = "nomad_namespace"
      nomad_job_id    = "nomad_job_id"
      nomad_task      = "nomad_task"
      nomad_service   = "nomad_service"
    }
  })
}
resource "consul_acl_binding_rule" "service" {
  for_each    = module.catalog.service_jobs
  auth_method = consul_acl_auth_method.nomad.name
  bind_type   = "service"
  bind_name   = each.key
  selector    = "\"nomad_service\" in value and value.nomad_namespace == \"default\" and value.nomad_job_id == ${jsonencode(each.value)} and value.nomad_service == ${jsonencode(each.key)}"
  description = "Only ${each.value} may register ${each.key}"
}
resource "consul_acl_policy" "task" {
  for_each    = local.job_ids
  name        = "${each.key}-task-read"
  datacenters = ["gsm"]
  rules = join("\n", concat(
    ["node_prefix \"\" { policy = \"read\" }"],
    [for service in sort(distinct(concat(
      [for service, job in module.catalog.service_jobs : service if job == each.key],
      lookup(module.catalog.upstreams, each.key, [])
    ))) : "service ${jsonencode(service)} { policy = \"read\" }"]
  ))
}
resource "consul_acl_role" "task" {
  for_each = local.job_ids
  name     = "${each.key}-tasks"
  policies = [consul_acl_policy.task[each.key].id]
}
resource "consul_acl_binding_rule" "task" {
  for_each    = local.job_ids
  auth_method = consul_acl_auth_method.nomad.name
  bind_type   = "role"
  bind_name   = consul_acl_role.task[each.key].name
  selector    = "\"nomad_service\" not in value and value.nomad_namespace == \"default\" and value.nomad_job_id == ${jsonencode(each.key)}"
}

resource "consul_config_entry" "defaults" {
  for_each = module.catalog.service_jobs
  kind     = "service-defaults"
  name     = each.key
  # Provider 2.23 compares the empty defaults Consul adds to every service entry.
  config_json = jsonencode({ Protocol = "tcp", Expose = {}, MeshGateway = {}, TransparentProxy = {} })
}
resource "consul_config_entry" "intentions" {
  for_each = module.catalog.service_jobs
  kind     = "service-intentions"
  name     = each.key
  # Provider 2.23 compares server-computed precedence; these are Consul's exact/default-namespace values.
  config_json = jsonencode({
    Sources = concat(
      [for source in local.allowed_sources[each.key] : { Name = source, Action = "allow", Type = "consul", Precedence = 9 }],
      [{ Name = "*", Action = "deny", Type = "consul", Precedence = 8 }]
    )
  })
  depends_on = [consul_config_entry.defaults]
}

resource "vault_generic_endpoint" "nomad_jwt" {
  path                 = "auth/jwt-nomad/config"
  disable_delete       = true
  ignore_absent_fields = true
  data_json = jsonencode({
    jwks_url           = local.jwks_url
    jwks_ca_pem        = local.ca_pem
    jwt_supported_algs = ["RS256"]
  })
}
resource "vault_policy" "workload" {
  for_each = module.catalog.job_secrets
  name     = each.key
  policy   = <<-POLICY
path "${var.vault_mount}/data/${each.value}" { capabilities = ["read"] }
path "auth/token/lookup-self" { capabilities = ["read"] }
path "auth/token/renew-self" { capabilities = ["update"] }
path "auth/token/revoke-self" { capabilities = ["update"] }
POLICY
}
resource "vault_jwt_auth_backend_role" "workload" {
  for_each                = module.catalog.job_secrets
  backend                 = "jwt-nomad"
  role_name               = each.key
  role_type               = "jwt"
  user_claim              = "sub"
  bound_audiences         = ["vault.io"]
  bound_claims            = { nomad_namespace = "default", nomad_job_id = each.key }
  token_policies          = [vault_policy.workload[each.key].name]
  token_no_default_policy = true
  token_type              = "service"
  token_period            = 1800
  token_ttl               = 1800
  depends_on              = [vault_generic_endpoint.nomad_jwt]
}
output "job_versions" { value = tomap({}) }
output "expected_jobs" { value = tomap({}) }

output "job_modify_indices" { value = tomap({}) }
