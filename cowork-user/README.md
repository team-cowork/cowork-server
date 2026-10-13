# cowork-user

사용자 계정과 공개 프로필의 원본을 소유한다.

운영 검색은 `utf8mb4_unicode_ci`와 `NO_BACKSLASH_ESCAPES`가 없는 세션에 의존한다.
Docker stdout 회전은 컨테이너 내부 파일 로그에 적용되지 않는다. 파일 회전은 `config/config.exs`에서 관리한다.

[로컬 실행](../docs/local-run-guide.md) · [운영 배포](../docs/deployment.md)
