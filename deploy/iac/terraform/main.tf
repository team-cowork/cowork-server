terraform {
  required_version = ">= 1.10, < 2.0"
  required_providers {
    grafana = {
      source  = "grafana/grafana"
      version = "4.46.0"
    }
  }
  backend "local" {}
}

# GRAFANA_URL and GRAFANA_AUTH are process environment inputs, never TF variables.
provider "grafana" {}

resource "grafana_folder" "cowork" {
  title = "cowork"
  uid   = "cowork"
}

resource "grafana_data_source" "prometheus" {
  name       = "Prometheus"
  uid        = "cowork-prometheus"
  type       = "prometheus"
  url        = "http://prometheus:9090"
  is_default = true
  json_data_encoded = jsonencode({
    timeInterval = "15s"
    httpMethod   = "POST"
  })
}

resource "grafana_data_source" "loki" {
  name              = "Loki"
  uid               = "cowork-loki"
  type              = "loki"
  url               = "http://loki:3100"
  json_data_encoded = jsonencode({ maxLines = 1000 })
}

resource "grafana_data_source" "alertmanager" {
  name = "Alertmanager"
  uid  = "cowork-alertmanager"
  type = "alertmanager"
  url  = "http://alertmanager:9093"
  json_data_encoded = jsonencode({
    implementation             = "prometheus"
    handleGrafanaManagedAlerts = false
  })
}

locals {
  dashboards_directory = "${path.module}/../../config/monitoring/grafana/dashboards"
  # Common operational views do not select or infer a service implementation language.
  dashboard_files = toset(["overview.json", "infrastructure.json", "cowork-logs.json", "service-slots.json"])
}

resource "grafana_dashboard" "cowork" {
  for_each    = local.dashboard_files
  folder      = grafana_folder.cowork.uid
  config_json = file("${local.dashboards_directory}/${each.key}")
  overwrite   = true
  depends_on  = [grafana_data_source.prometheus, grafana_data_source.loki]
}
