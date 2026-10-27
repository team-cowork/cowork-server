package com.cowork.roadmap.global.audit;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.Setter;

/**
 * created_by/last_modified_by 컬럼을 가진 테이블용 베이스. 요청자의 X-User-Id를 서비스 계층에서 명시적으로
 * 설정한다.
 */
@MappedSuperclass
@Getter
@Setter
public abstract class BaseEntity extends TimestampEntity {

    @Column(name = "created_by", updatable = false)
    private Long createdBy;

    @Column(name = "last_modified_by")
    private Long lastModifiedBy;

    /** lastModifiedBy는 호출자가 setLastModifiedBy()로 새로 갱신하므로 일부러 복사 대상에서 제외한다. */
    public void prepareUpdateFrom(BaseEntity source) {
        super.prepareUpdateFrom(source);
        this.setCreatedBy(source.getCreatedBy());
    }
}
