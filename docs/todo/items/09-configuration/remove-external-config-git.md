# 외부 Config Git 제거 및 prod native 전환

- **서비스**: cowork-config, Config Client 11개, 배포 인프라
- **우선순위**: 🟠 중간
- **현재 상태**: Git backend 제거와 local·prod native 설정 전환은 반영되어 있으며 외부 원본 대조·배포 확인·기존 자격 증명 폐기가 남아 있다.

## 문제

현재 Config는 Vault와 classpath native를 사용한다. 과거 외부 Config Git과 운영 환경에만 있던
속성은 이 저장소에서 확인할 수 없어, 원본의 key가 빠짐없이 이동했는지는 아직 검증하지 않았다.

전환 구현을 다시 수행하는 대신 외부 원본과 현재 응답을 비교해 누락을 확인한다. 공급 경로와
변경·복구 절차는 [배포 문서](../../../deployment.md)를 따른다.

## 코드 근거

- [Config backend 설정](../../../../cowork-config/src/main/resources/application.yml#L65): local·prod 모두 Vault와 classpath native를 사용하며 Git backend 선언은 없다.
- [Vault 설정 공급](../../../../deploy/prod/vault-settings.py): 서비스·프로파일별 속성을 갱신하는 경로가 있다. 외부 Config Git 원본의 누락·폐기 여부는 이 코드로 확인할 수 없다.

## 할 일

- 과거 배포 기록의 Config Git 저장소·label·commit과 운영 override의 key 목록을 값 없이 확보한다.
- 현재 서비스별 native·Vault·bootstrap 공급 경로와 대조하고 제거·이동·누락한 key를 구분한다.
- 노출된 시크릿이 있으면 native로 복사하지 않고 Vault 이동·키 교체·기존 Git 이력 처리 범위를 정한다.
- 분산 검증 환경에서 각 런타임의 설정 병합·실제 주소·기동·Eureka 등록을 확인한다.
- 복구에 필요한 이전 이미지·설정 버전을 확보한 뒤 외부 Config Git과 사용하지 않는 자격 증명을 폐기한다.

## 검증

- 서비스별 응답의 key를 외부 원본과 대조하고 누락 사유를 기록한다.
- 미해결 placeholder·localhost·Compose 전용 주소가 실제 운영 접속값으로 사용되지 않는지 확인한다.
- 설정 교체·Vault 장애·이전 Config 이미지 복구를 수동 운영 점검으로 확인한다.

## 완료 조건

- 외부 원본에서 필요했던 속성이 현재 공급 경로에 반영되어 있다.
- 모든 런타임의 분산 기동과 설정 복구 결과가 기록되어 있다.
- 외부 Config Git과 불필요한 자격 증명이 폐기되어 있다.
