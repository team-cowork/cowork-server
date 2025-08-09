# Git 작업 규칙

## 브랜치

| 브랜치 | 용도 |
|---|---|
| `main` | 운영 배포 브랜치. `develop`을 병합할 때만 변경합니다. |
| `develop` | 개발 통합 브랜치 |
| `<type>/<설명>` | 작업 브랜치. `develop`에서 분기하고 `develop`으로 병합합니다. |

작업 브랜치의 `type`은 커밋 유형과 같게 씁니다. 예: `add/team-icon`, `fix/chat-attachment-file-name`, `refactor/msa-preparation`.
CI/CD 작업은 `cicd/`를 씁니다.

## 커밋 메시지

형식은 `type(scope): 설명`입니다.

- `type`: `add`, `update`, `fix`, `refactor`, `docs`, `test`, `ci/cd`
- `scope`: 바뀐 도메인. 여러 도메인에 걸치면 `global`, 배포 파이프라인이면 `ci/cd`
- 설명은 한국어로 쓰고 마침표를 붙이지 않습니다. `~한다`, `~했습니다` 같은 어미는 쓰지 않습니다.

```
add(team): 팀 아이콘 업로드 API 추가
fix(chat): 한글 첨부파일 이름이 밑줄로 바뀌는 문제 수정
```

하나의 커밋에는 하나의 논리적 변경만 담습니다.

## 병합

1. 작업이 끝나면 팀 채널에 리뷰를 요청합니다.
2. CI가 통과하고 리뷰어 한 명 이상이 확인하면 `develop`에 병합합니다.
3. 병합은 `--no-ff`로 하여 작업 브랜치 단위가 이력에 남도록 합니다.
4. 릴리즈는 `develop`을 `main`에 병합해서 진행하며, 병합 후 릴리즈 노트를 작성합니다.
