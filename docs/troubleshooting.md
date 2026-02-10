# 장애 대응 기록

운영과 개발 중에 겪은 문제와 해결 방법을 남긴다. 같은 증상이 다시 나오면 여기부터 확인한다.

## 한글 첨부파일 이름이 밑줄로 바뀜 (2025-11)

- 증상: `회의록.pdf`를 올리면 `___.pdf`로 저장되었다.
- 원인: 파일 이름 정리 정규식이 영문, 숫자, 일부 기호만 허용했다.
- 해결: 허용 문자에 한글(`가-힣`)을 추가했다. 경로 구분자와 제어 문자는 계속 `_`로 바꾼다.

## 배포 직후 헬스 체크 실패로 배포 중단 (2025-12)

- 증상: 새 이미지를 올리면 `cowork-server did not become healthy`로 배포가 실패했다.
- 원인: Flyway 마이그레이션과 Firebase 초기화로 시작 시간이 60초를 넘었는데, 헬스 체크 대기는 60초였다.
- 해결: 컨테이너 `start_period`를 40초로 늘리고 배포 스크립트의 대기 횟수를 30회(150초)로 늘렸다.
- 확인 방법: `docker logs --tail 200 cowork-server`에서 `Started CoworkApplication` 로그 시각을 본다.

## 사용자 검색 응답 직렬화 경고 (2026-02)

- 증상: 사용자 검색을 호출하면 `Serializing PageImpl instances as-is is not supported` 경고가 반복되었다.
- 원인: Spring Data 3.3부터 `PageImpl`을 그대로 JSON으로 만드는 방식이 권장되지 않는다.
- 해결: `@EnableSpringDataWebSupport(pageSerializationMode = VIA_DTO)`를 켰다. 응답의 페이지 정보는 `page` 항목으로 바뀌었으므로 프론트엔드에도 공유했다.
