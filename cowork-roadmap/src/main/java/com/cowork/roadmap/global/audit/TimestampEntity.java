package com.cowork.roadmap.global.audit;

import java.time.Instant;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.Setter;

@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
public abstract class TimestampEntity {

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** 생성 시각을 보존하고, 값이 같은 수정도 JPA가 UPDATE하도록 수정 시각을 갱신한다. */
    public void prepareUpdateFrom(TimestampEntity source) {
        this.setCreatedAt(source.getCreatedAt());
        this.setUpdatedAt(Instant.now());
    }
}
