# cowork-project

## 역할

팀 내 프로젝트·멤버 권한과 GitHub 저장소 연동을 관리합니다.

- 프로젝트 생성·조회·수정·삭제와 순서 변경
- 팀 기본·사용자 지정 역할과 프로젝트별 정책에 따른 접근 권한 검증
- GitHub 저장소 연결, PR 조회·승인·병합과 라벨 정책 변경 요청

프로젝트 조회·콘텐츠 작성·설정 관리·재정렬은 각각 `project_read`, `project_write`, `project_manage`, `project_reorder`로 구분합니다. 생성·삭제에는 `manage_projects`, GitHub 연결 설정에는 `manage_github`가 필요합니다.

새 프로젝트는 기본적으로 공개이며 `isPrivate`로 변경할 수 있습니다. 기존 프로젝트는 비공개로 유지하며, 비공개 프로젝트는 프로젝트 역할에서 읽기를 명시적으로 허용해야 합니다. 팀 OWNER/ADMIN만 활성 멤버십을 확인한 뒤 이 정책을 우회합니다. 생성자가 팀을 탈퇴해도 프로젝트는 유지됩니다.

## 스택

- Kotlin / Java 26 / Spring Boot
- Maven
- Spring Data JPA / MySQL / Flyway
- Spring Kafka / Eureka / Config Client / OpenFeign

## 포트

| 용도 | 컨테이너 포트 | Compose 기본 호스트 포트 |
|------|---------------|--------------------------|
| HTTP | `8084`        | `8084`                   |

## 환경변수

아래 값은 [로컬 Compose 설정](../deploy/compose/stack.yaml)의 컨테이너 입력입니다.

| 변수                     | 기본값                      | 설명                                                     |
|--------------------------|-----------------------------|----------------------------------------------------------|
| `APP_CONFIG_PROFILE`     | `local`                     | 설정 프로파일                                            |
| `CONFIG_SERVER_URL`      | `http://cowork-config:8761` | 필수 Config Server 연결                                  |
| `CONFIG_CLIENT_USERNAME` | 없음                        | 필수. `.env`의 `CONFIG_CLIENT_PROJECT_USERNAME`에서 전달 |
| `CONFIG_CLIENT_PASSWORD` | 없음                        | 필수. `.env`의 `CONFIG_CLIENT_PROJECT_PASSWORD`에서 전달 |
