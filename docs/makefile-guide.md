# Makefile 사용 가이드

이 문서는 `cowork-server` 루트의 `Makefile`이 제공하는 명령어와 릴리즈 흐름을 정리한다.

기준일:
- 2026-05-10 (초안)
- 2026-07-23 (현재 Makefile 기준 갱신)

---

## 명령어 목록

| 명령어              | 설명                                          |
|------------------|---------------------------------------------|
| `make version`   | `VERSION` 파일의 현재 버전을 출력                     |
| `make bump`      | `scripts/bump.sh`를 실행해 버전 번호를 증가            |
| `make tag`       | 버전 관련 파일을 커밋하고 git tag 생성                   |
| `make release`   | `tag` 수행 후 `origin/main`에 태그 포함 push        |
| `make init-logs` | `scripts/init-log-dirs.sh`로 legacy 호스트 로그 디렉터리 생성 |
| `make setup`     | authorization·notification·voice의 Go Swagger 도구와 문서 생성 |

---

## 릴리즈 흐름

```
make bump      # VERSION 파일의 버전 올리기
make release   # commit → tag → git push (--follow-tags)
```

`make release`는 내부적으로 `make tag`를 먼저 실행하므로 따로 호출할 필요 없다.

### make tag 상세

아래 패턴에 해당하는 파일을 스테이징한 뒤 커밋과 태그를 생성한다. 변경되지 않았거나 존재하지 않는 패턴은 Git이 그대로 건너뛴다.

| 대상 파일 | 설명 |
|---|---|
| `VERSION`, `MODULE.bazel` | 공통 버전·Bazel 모듈 파일 |
| `cowork-*/build.gradle.kts` | Gradle 모듈 버전 |
| `cowork-*/pom.xml` | Maven 모듈 버전 |
| `cowork-*/package.json` | Node 모듈 버전 |
| `cowork-user/mix.exs` | Elixir 모듈 버전 |
| `cowork-authorization/cmd/main.go` | authorization Swagger 버전 |
| `cowork-notification/cmd/server/main.go` | notification Swagger 버전 |
| `cowork-voice/cmd/server/main.go` | voice Swagger 버전 |

커밋 메시지 형식: `chore: release v{VERSION}`

---

## 초기 설정

호스트에서 애플리케이션을 직접 실행하며 `/var/log/cowork` 경로를 명시적으로 사용할 때 legacy 로그 디렉터리를 만든다.

```bash
make init-logs
```

Docker Compose는 `cowork_logs` named volume을 사용하므로 이 명령이 필요 없다. 현재 스크립트의 `gateway`, `authorization` 같은 디렉터리명과 일부 로거의 `cowork-gateway`, `cowork-authorization` 경로가 다르므로, 호스트 파일 로깅에 사용하기 전 대상 서비스의 실제 로그 경로를 확인한다.

Go API 문서 생성 도구와 의존성을 준비할 때는 다음을 실행한다.

```bash
make setup
```
