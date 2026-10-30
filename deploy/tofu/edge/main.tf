module "jobs" {
  source = "../modules/jobs"
  jobs   = var.jobs
}
output "job_modify_indices" { value = module.jobs.job_modify_indices }
