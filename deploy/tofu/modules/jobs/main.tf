terraform {
  required_providers {
    nomad = { source = "hashicorp/nomad", version = "= 2.6.1" }
  }
}
variable "jobs" {
  type = map(object({ jobspec = string, variables = map(string) }))
}
resource "nomad_job" "this" {
  for_each = var.jobs
  jobspec  = each.value.jobspec
  detach   = true
  hcl2 {
    allow_fs = false
    vars     = each.value.variables
  }
}
output "job_modify_indices" {
  value = { for id, job in nomad_job.this : id => job.modify_index }
}
