package com.cowork.roadmap.domain.node.repository.view;

public interface NodeTreeView {

    Long getId();

    Long getParentId();

    String getTitle();

    String getSourceUrl();

    String getSourceTitle();

    Integer getPosition();
}
