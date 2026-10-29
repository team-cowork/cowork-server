# Kafka 중단 이후 애플리케이션 로그 급증

## 확인된 원인

2026-10-29 08:59:37 KST에 Kafka JVM이 Linux의 전역 OOM으로 종료되었습니다(exit 137).
이후 `project`, `roadmap`, `channel`, `preference`의 Kafka AdminClient가
`Rebootstrapping with Cluster(...)`를 반복 출력하면서 각 VM의 `app.log`가 시간당 약 7~8GiB 증가하였습니다.
기존 일별 회전은 활성 파일의 크기를 제한하지 못하였습니다.

Kafka VM은 온라인 메모리 블록이 4GiB였지만, 장애 당시 게스트의 `MemTotal`은 약 1.87GiB였습니다.
`virtio_balloon` 장치와 메모리 감소 이력이 확인되어 약 2GiB가 회수된 것으로 추정하였습니다.
가상화 호스트의 balloon 목표값과 메모리 압박은 확인하지 못하였으므로, VM 설정 자체가 2GiB였다고 해석하면 안 됩니다.
Kafka 재기동 전에는 호스트의 실제 메모리 할당과 게스트 가용량을 확인해야 합니다.

## 현장에서 적용한 조치

- 위 네 VM의 활성 `app.log`에서 증거를 확보한 뒤 내용을 비워 합계 약 37.6GiB를 회수하였습니다.
- `cowork-emergency-logrotate.timer`를 설치하여 15초마다 50MiB 초과 로그를 회전하고 최근 2개를 보관하도록 하였습니다.
- Docker stdout 수집과 데이터베이스·Kafka·Loki의 영속 데이터는 유지하였습니다.

타이머는 점검 사이에 발생하는 증가량까지 차단하는 하드 한도가 아닙니다.
`copytruncate`는 복사와 비우기 사이의 파일 로그 일부를 잃을 수 있으므로, 장애 중 중복 파일이 디스크를 채우지 않게 하는 임시조치로 사용하였습니다.
운영 로그 수집은 기존 Docker stdout 경로를 계속 사용합니다.

## 배포 시 반영할 조치

애플리케이션 변경은 현장 조치 당시 배포하지 않았습니다.
네 애플리케이션을 다시 배포하면 파일당 10MB 회전, 보관 파일 합계 100MB 정리,
Kafka 연결 재시도 기본 1초·최대 30초 및 요청 재시도 기본 1초·최대 10초 설정이 적용됩니다.
재시도 간격에는 Kafka 클라이언트의 지터가 적용됩니다.
고정 브로커 주소를 사용하는 AdminClient는 `metadata.recovery.strategy=none`으로 반복 재부트스트랩을 막고,
해당 로거는 WARN으로 제한하였습니다. 기존 브로커에 대한 재연결은 계속 수행합니다.

Ansible은 현장 타이머를 같은 이름과 상태 파일로 관리합니다. 컨테이너 교체 시에는 실행 중인 앱의 PID를 다시 찾아 적용합니다.
10MB·100MB 애플리케이션 설정도 회전·정리 시점에 적용되므로, 활성 파일과 직전 기록 한 건 등을 포함한 전체 사용량의 엄격한 하드 한도는 아닙니다.

## 확인과 임시조치 해제

배포 후 네 VM에서 다음을 확인합니다.

1. 실행 이미지가 변경된 버전인지 확인하고 Kafka의 연결 및 요청 재시도 설정을 확인합니다.
2. Kafka 중단·복구 이력이 있는 기간의 로그 발생량과 파일 크기, 디스크 증가 추이를 확인합니다.
3. `systemctl status cowork-emergency-logrotate.timer`와
   `systemctl show cowork-emergency-logrotate.service -p Result -p ExecMainStatus`로 타이머 상태를 확인합니다.

새 애플리케이션의 파일 회전이 안정적으로 동작하는 것을 확인한 후,
네 VM의 타이머를 중지·비활성화하고 Ansible의 임시조치 설치도 함께 제거합니다.
설치를 남겨 두면 다음 호스트 구성 적용 때 다시 활성화됩니다.
Kafka 복구, balloon 설정 변경, 디스크 증설·이동은 이번 현장 조치에 포함하지 않았습니다.
