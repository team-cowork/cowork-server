# Makefile 사용 가이드

이 문서는 `cowork-server` 루트의 `Makefile`이 제공하는 명령어와 릴리즈 흐름을 정리한다.

기준일:
- 2026-05-10 (초안)

---

## 명령어 목록

| 명령어              | 설명                                          |
|------------------|---------------------------------------------|
| `make version`   | `VERSION` 파일의 현재 버전을 출력                     |
| `make bump`      | `scripts/bump.sh`를 실행해 버전 번호를 증가            |
| `make tag`       | 버전 관련 파일을 커밋하고 git tag 생성                   |
| `make release`   | `tag` 수행 후 `origin/main`에 태그 포함 push        |
| `make init-logs` | `scripts/init-log-dirs.sh`를 실행해 로그 디렉터리 초기화 |

---

## 릴리즈 흐름

```
make bump      # VERSION 파일의 버전 올리기
make release   # commit → tag → git push (--follow-tags)
```

`make release`는 내부적으로 `make tag`를 먼저 실행하므로 따로 호출할 필요 없다.

### make tag 상세

아래 파일을 스테이징한 뒤 커밋과 태그를 생성한다.

| 대상 파일                       | 설명                |
|-----------------------------|-------------------|
| `VERSION`                   | 버전 소스 파일          |
| `cowork-*/build.gradle.kts` | 각 JVM 서비스 빌드 파일   |
| `cowork-chat/package.json`  | NestJS 서비스 패키지 파일 |

커밋 메시지 형식: `chore: release v{VERSION}`

---

## 초기 설정

로컬에서 처음 실행할 때 로그 디렉터리를 먼저 만들어 둔다.

```bash
make init-logs
```
