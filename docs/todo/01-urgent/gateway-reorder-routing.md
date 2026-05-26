# Gateway 프로젝트 reorder 라우팅 누락

- **서비스**: cowork-gateway
- **우선순위**: 🔴 즉시 필요

## 문제

`PATCH /api/teams/{teamId}/projects/reorder` 요청이 Gateway에서 `cowork-project` 서비스로 라우팅되지 않는다.

## 해결

`cowork-gateway` 라우팅 설정에 아래 항목 추가:

```yaml
- id: cowork-project-reorder
  uri: lb://cowork-project
  predicates:
    - Path=/api/teams/*/projects/reorder
```
