package com.cowork.roadmap.global.audit;

import org.springframework.data.relational.core.mapping.Column;

/**
 * created_by/last_modified_by 컬럼을 가진 테이블용 베이스. X-User-Id는 WebFlux 리액티브 컨텍스트에서
 * 자동 주입하기 까다로워, 서비스 계층에서 명시적으로 설정한다.
 */
public abstract class BaseEntity extends TimestampEntity {

    @Column("created_by")
    private Long createdBy;

    @Column("last_modified_by")
    private Long lastModifiedBy;

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    public Long getLastModifiedBy() {
        return lastModifiedBy;
    }

    public void setLastModifiedBy(Long lastModifiedBy) {
        this.lastModifiedBy = lastModifiedBy;
    }
}
