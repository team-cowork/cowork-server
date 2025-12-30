# DB 백업과 복구

## 백업

- RDS 자동 스냅샷(7일 보관)과 별도로 매일 새벽 3시에 논리 백업을 만든다.
- EC2의 crontab에 다음을 등록한다.

  ```
  0 3 * * * /home/ec2-user/cowork/deploy/scripts/backup-db.sh >> /home/ec2-user/cowork/deploy/backups/backup.log 2>&1
  ```

- 덤프 파일은 S3 백업 버킷의 `mysql/` 아래에 올라가며, 버킷 수명 주기 규칙으로 30일 뒤 삭제한다.
- EC2에는 최근 7일 치만 남긴다.

## 복구

1. 복구할 파일을 내려받는다.

   ```bash
   aws s3 cp s3://<백업 버킷>/mysql/cowork-20260110-030000.sql.gz .
   ```

2. 서비스를 점검 모드로 바꾸고 애플리케이션 컨테이너를 멈춘다.
3. 덤프를 적용한다.

   ```bash
   gunzip -c cowork-20260110-030000.sql.gz | docker run --rm -i -e MYSQL_PWD="$DB_PASSWORD" mysql:8.0 \
     mysql --host="$DB_HOST" --user="$DB_USERNAME" cowork
   ```

4. 애플리케이션을 다시 실행하고 Flyway 기록과 주요 API를 확인한다.
