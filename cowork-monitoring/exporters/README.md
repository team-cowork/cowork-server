# Exporters

This directory documents optional infrastructure exporters that can be added to the monitoring stack.

Typical exporters for the Cowork platform:

- `node-exporter` for host CPU, memory, disk, and filesystem metrics
- `cAdvisor` for container metrics
- `mysqld-exporter` for MySQL metrics
- `postgres-exporter` for PostgreSQL metrics
- `redis-exporter` for Redis metrics
- `kafka-exporter` for Kafka broker and consumer metrics

Rule of thumb:

- service runtime metrics stay with the service
- infrastructure metrics come from exporters
- dashboards and alerts stay in `cowork-monitoring`
