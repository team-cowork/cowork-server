# PR 테스트 커버리지

Stage CI는 모든 PR에서 전체 코드 모듈을 측정한다. `coverage-plan`이 모듈 목록을 구성하고, `coverage`가 모듈별로 대상 브랜치(`base SHA`)와 PR 병합 결과(`merge SHA`)를 실행한다. `coverage-comment`는 결과를 모아 PR 댓글 한 개를 갱신한다. 변경 파일 필터를 적용하지 않으며, 동시에 최대 8개 측정을 실행한다.

## 댓글의 수치

- 모듈별·전체 커버리지: `covered / total × 100`을 소수점 첫째 자리까지 표시한다.
- 증감: 반올림하기 전 두 커버리지의 차이를 계산한 뒤 `+1.2%p`처럼 표시한다. 상대 증가율이 아닌 퍼센트포인트 차이다.
- 전체: 모듈별 백분율의 평균 대신 covered와 total을 각각 합산한다. Go의 구문과 다른 언어의 라인이 섞인 종합 지표이며, 순수 라인 커버리지는 아니다.
- 감소: GitHub의 `diff` 코드 블록에서 빨간색으로 표시한다. 증가한 행은 초록색으로 표시한다.
- 도넛: 전체 covered/missed를 QuickChart로 시각화한다. 이미지가 표시되지 않아도 표에서 같은 수치를 확인할 수 있다.

낮은 커버리지와 커버리지 하락은 CI 실패 조건이 아니다. 측정 실패·보고서 누락은 0% 대신 상태를 표시하고, 불완전한 결과로 전체 수치나 증감을 확정하지 않는다. 해당 리비전에 아직 없거나 삭제된 모듈과 실행 코드가 없는 monitoring은 N/A다.

## 측정 범위와 도구

| 모듈 | 도구 | 단위 |
|---|---|---|
| gateway, config, channel, team, roadmap | Gradle + JaCoCo 0.8.14 | 생산 클래스 라인 |
| project | Maven + JaCoCo 0.8.14 | 생산 클래스 라인 |
| preference | Amper 0.11.0 테스트 JVM + JaCoCo 0.8.14 CLI | 생산 클래스 라인 |
| authorization, notification, voice | `go test -coverpkg=./...` | 구문 |
| chat | 기존 Jest coverage | `src` 생산 코드 라인 |
| user | Mix/OTP 내장 `cover` | 생산 Elixir 모듈 라인 |
| promotion | c8 12.0.0 + Node test runner | `src`, `scripts`의 JavaScript 라인 |
| monitoring | 설정 전용 | 대상 없음 |

Go의 생성된 Swagger `docs/docs.go`는 제외한다. Promotion은 실행되지 않은 파일도 포함하며 HTML/CSS는 분모에 넣지 않는다. 각 언어가 실행 가능한 코드를 계산하는 방식이 다르므로 같은 비율이라도 언어 간 테스트 품질이 같다는 뜻은 아니다.

## 유지보수

도구는 PR의 `scripts/coverage`에서 가져오고 측정 소스는 별도 checkout에 둔다. 기준 브랜치에 커버리지 설정이 없어도 두 리비전에 같은 도구를 적용한다. 언어와 의존성은 각각의 빌드 파일을 그대로 사용한다. 기존 서비스 빌드 설정에 커버리지 플러그인을 상시 적용하지 않는다.

새 모듈을 추가하면 `scripts/coverage/modules.json`에 runner, 단위, 보고서 형식을 등록한다. 등록되지 않은 `cowork-*` 디렉터리는 계획 단계에서 검출한다. 모듈 삭제 시 기준 브랜치와의 비교가 필요한 동안 목록을 유지하면 삭제 상태도 표시된다.

원본 보고서와 정규화된 `result.json`은 같은 workflow run 안에서 모듈·리비전별 artifact로 7일간 보관한다. 일부 job만 재실행해도 다른 모듈의 결과를 함께 집계한다. 측정 job이 시작되면 기존 artifact를 미완료 상태로 먼저 교체하므로, 재실행 중 setup 실패나 시간 초과가 발생해도 이전 성공 수치를 사용하지 않는다.

측정 job은 읽기 권한만 갖고, 댓글 job만 PR 쓰기 권한을 요청한다. fork PR에서 댓글 권한이 거부되면 Actions summary와 artifact로 결과를 확인한다. 새 커밋 또는 대상 브랜치 이동으로 오래된 실행이 된 경우에는 댓글 갱신을 건너뛴다.

## 로컬 실행

각 모듈의 CI와 같은 JDK, Go, Node, Elixir 또는 Kotlin CLI가 필요하다. 어댑터가 테스트와 의존성 설치를 수행하므로 소스 checkout과 결과 디렉터리를 별도로 준비하는 것이 좋다.

```sh
python3 scripts/coverage/run.py measure \
  --module cowork-gateway --snapshot pr \
  --source /absolute/path/to/source-checkout \
  --output /absolute/path/to/coverage/gateway-pr

python3 -m unittest discover -s scripts/coverage -p 'test_*.py'
node --test scripts/coverage/comment.test.cjs
```

참고: [JaCoCo Gradle 플러그인](https://docs.gradle.org/current/userguide/jacoco_plugin.html), [Go 커버리지](https://go.dev/blog/cover), [Mix 내장 커버리지](https://hexdocs.pm/mix/1.18.0/Mix.Tasks.Test.html#module-coverage), [QuickChart 도넛](https://quickchart.io/documentation/chart-types/#doughnut-chart).
