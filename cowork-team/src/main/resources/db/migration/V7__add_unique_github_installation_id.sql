-- 같은 installation이 두 팀에 동시에 연결되면 findByGithubInstallationId가 다중 결과를 가정하지 않아
-- disconnect 처리 시 예외가 나고 양쪽 다 영구히 해제되지 않는다. NULL은 서로 다르게 취급되므로
-- 미연결(NULL) 팀끼리는 영향이 없다. 인덱스 부재로 인한 disconnect 시 풀스캔도 함께 해결한다.
ALTER TABLE tb_teams
    ADD UNIQUE INDEX uq_tb_teams_github_installation_id (github_installation_id);
