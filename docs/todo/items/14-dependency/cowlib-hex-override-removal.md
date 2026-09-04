# cowlib git override 제거와 Hex 릴리스 복귀

- **서비스**: cowork-user, 컨테이너 빌드 인프라
- **우선순위**: 🟠 중간
- **현재 상태**: CVE-2026-43971 픽스가 포함된 upstream 커밋을 git 의존성으로 고정해 취약점은 해소했으나, Hex에 픽스 릴리스가 없어 임시 override가 유지되고 있음

## 문제

`cowork-user`는 `plug_cowboy` → `cowboy`를 거쳐 `cowlib`에 의존한다. `cowboy` 2.18.0이
`cowlib >= 2.19.0 and < 3.0.0`을 요구하지만, Hex의 최신 릴리스 `2.19.0`(2026-07-28 배포)에는
CVE-2026-43971 픽스가 들어 있지 않다. 픽스 커밋 `89da27e`는 2026-08-18에 upstream에 들어갔고
릴리스 태그보다 뒤에 있다.

이 때문에 `cowork-user/mix.exs`는 `cowlib`을 Hex 패키지가 아니라 git 저장소에서 특정 커밋으로
받도록 선언하고 `override: true`로 `cowboy`의 Hex 제약을 우회한다. 취약점 자체는 해소된 상태지만
이 선언은 Hex 릴리스가 나올 때까지만 유지할 임시 조치다.

git 의존성은 Hex 패키지와 동작이 다르다. `mix deps.get`이 tarball을 받는 대신 실제 `git clone`을
수행하므로 `cowork-user/deps/cowlib/.git`이 생기고, IDE가 이를 별도 VCS root로 인식한다. 빌드도
hex.pm 외에 github.com 도달성과 이미지 내 `git` 바이너리에 의존하게 된다. 두 dockerfile 모두
`git`을 설치하고 있어 현재 빌드는 성공하지만, 의존성 확보 경로가 하나 더 늘어난 상태다.

## 취약점과 고정 커밋

| 항목      | 값                                                                                                                  |
|-----------|---------------------------------------------------------------------------------------------------------------------|
| 식별자    | CVE-2026-43971 / [GHSA-gg23-fwhr-prjh](https://github.com/advisories/GHSA-gg23-fwhr-prjh)                           |
| 심각도    | medium (CVSS v4 6.3)                                                                                                |
| 영향 범위 | cowlib 2.9.0 이상 전체, Hex 패치 릴리스 없음                                                                        |
| 내용      | `cow_link:do_link/1`이 target URI·rel 값·속성 키를 이스케이프 없이 `Link:` 헤더에 직렬화해 directive smuggling 허용 |
| 고정 커밋 | `89da27ee4c241f5d649ba7d9b7f2188918af6cea` (2026-08-18, `git describe`: `2.19.0-3-g89da27e`)                        |

고정 커밋은 `2.19.0` 태그에 픽스 관련 커밋 3개를 더한 지점이다. `cowork-user`의 애플리케이션
코드는 `cow_link`를 직접 호출하지 않으므로 실제 노출은 `cowboy`가 `Link:` 헤더를 직렬화하는
경로에 한정되며, 이 범위는 아직 별도로 확인하지 않았다.

## 현재 선언

`cowork-user/mix.exs`:

```elixir
# Remove the override after Hex publishes a Cowlib release containing CVE-2026-43971's fix.
{:cowlib,
 git: "https://github.com/ninenines/cowlib.git",
 ref: "89da27ee4c241f5d649ba7d9b7f2188918af6cea",
 override: true},
```

`cowork-user/mix.lock`:

```text
"cowlib": {:git, "https://github.com/ninenines/cowlib.git", "89da27ee...", [ref: "89da27ee..."]},
```

## 복귀 조건

다음을 모두 만족하는 Hex 릴리스가 나오면 override를 제거한다.

- 릴리스에 `89da27e`가 포함되어 있다. 버전 번호가 아니라 커밋 포함 여부로 판단한다.
- 해당 버전이 `cowboy`의 `cowlib >= 2.19.0 and < 3.0.0` 제약을 만족한다.
- 릴리스가 요구하는 Erlang/OTP 최소 버전이 현재 런타임 이하다. upstream master는 픽스 이후
  OTP-27 이상을 요구하도록 바뀌었고, 두 dockerfile은 `erlang-27.3.4` 이미지를 사용하므로
  현재 기준으로는 문제가 없다.

## 할 일

### Hex 릴리스 추적

- `cowlib`의 Hex 릴리스 목록을 주기적으로 확인한다.

  ```bash
  curl -s https://hex.pm/api/packages/cowlib | jq -r '.releases[0:5][] | "\(.version) \(.inserted_at)"'
  ```

- 새 릴리스가 나오면 해당 태그가 고정 커밋을 포함하는지 확인한다.

  ```bash
  git -C cowork-user/deps/cowlib fetch --tags origin
  git -C cowork-user/deps/cowlib merge-base --is-ancestor 89da27ee4c241f5d649ba7d9b7f2188918af6cea {태그}
  ```

- 릴리스가 장기간 나오지 않으면 upstream 이슈나 릴리스 정책을 확인하고, 계속 고정할지 다른
  대응으로 바꿀지 판단한다.

### override 제거

- `mix.exs`의 `:cowlib` 항목과 그 위의 임시 조치 주석을 함께 제거한다. `cowboy`가 전이 의존성으로
  Hex 버전을 끌어오게 두고, 명시적 버전 고정이 필요한지 별도로 판단한다.
- `mix deps.unlock cowlib` 후 `mix deps.get`으로 `mix.lock` 항목이 `:git`에서 `:hex`로 바뀌고
  체크섬이 기록되는지 확인한다.
- `cowork-user/deps/cowlib`의 git 작업 디렉터리가 사라졌는지 확인하고, IDE에 남은 VCS root 매핑을
  정리한다.

## 검증

- `mix deps.get`과 `mix compile`이 새 의존성 구성에서 성공한다.
- `mix deps` 출력에서 `cowlib`이 Hex 패키지로 표시되고 `override` 표기가 사라진다.
- `mix.lock`의 `cowlib` 항목이 `:hex` 형식이고 체크섬을 포함한다.
- `local.dockerfile`과 `prod.dockerfile` 빌드가 모두 성공한다.
- 핵심 비즈니스 단위 테스트가 통과하고 Plug/Cowboy HTTP 서버의 기동·요청 처리는 수동 smoke로 확인한다.
- 복귀한 Hex 버전에서 `cow_link` 픽스가 실제로 적용되어 있는지 소스로 확인한다.

## 완료 조건

- `mix.exs`에 `cowlib` git 의존성과 `override: true`가 남아 있지 않다.
- `mix.lock`의 `cowlib`이 Hex 패키지와 체크섬으로 고정되어 있다.
- 사용하는 `cowlib` 버전에 CVE-2026-43971 픽스가 포함되어 있다.
- `cowork-user/deps` 아래에 중첩 git 저장소가 생기지 않는다.
- 의존성 확보가 hex.pm만으로 완결되고 빌드가 github.com 도달성에 의존하지 않는다.
