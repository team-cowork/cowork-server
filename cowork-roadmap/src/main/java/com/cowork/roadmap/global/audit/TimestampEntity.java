package com.cowork.roadmap.global.audit;

import java.time.LocalDateTime;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.relational.core.mapping.Column;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public abstract class TimestampEntity {

    @CreatedDate
    @Column("created_at")
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column("updated_at")
    private LocalDateTime updatedAt;

    /**
     * Lombok {@code @Builder}는 상속 필드를 인식하지 못해 {@code toBuilder()}로 만든 새 인스턴스에는
     * createdAt/updatedAt이 비어 있다. 불변 엔티티를 부분 수정할 때 이 메서드로 원본 감사 필드를 옮겨야 UPDATE 시
     * created_at이 NULL로 덮어써지는 것을 막을 수 있다.
     */
    public void copyAuditFrom(TimestampEntity source) {
        this.setCreatedAt(source.getCreatedAt());
        this.setUpdatedAt(source.getUpdatedAt());
    }
}
