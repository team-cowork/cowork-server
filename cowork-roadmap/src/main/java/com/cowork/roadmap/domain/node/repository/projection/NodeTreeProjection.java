package com.cowork.roadmap.domain.node.repository.projection;

public interface NodeTreeProjection {

    Long getId();

    Long getParentId();

    String getTitle();

    String getSourceUrl();

    String getSourceTitle();

    Integer getPosition();
}
