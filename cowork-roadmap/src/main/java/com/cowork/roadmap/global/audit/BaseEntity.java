package com.cowork.roadmap.global.audit;

import org.springframework.data.relational.core.mapping.Column;

import lombok.Getter;
import lombok.Setter;

/**
 * created_by/last_modified_by 컬럼을 가진 테이블용 베이스. X-User-Id는 WebFlux 리액티브 컨텍스트에서
 * 자동 주입하기 까다로워, 서비스 계층에서 명시적으로 설정한다.
 */
@Getter
@Setter
public abstract class BaseEntity extends TimestampEntity {

    @Column("created_by")
    private Long createdBy;

    @Column("last_modified_by")
    private Long lastModifiedBy;

    /** lastModifiedBy는 호출자가 setLastModifiedBy()로 새로 갱신하므로 일부러 복사 대상에서 제외한다. */
    public void copyAuditFrom(BaseEntity source) {
        super.copyAuditFrom(source);
        this.setCreatedBy(source.getCreatedBy());
    }
}
