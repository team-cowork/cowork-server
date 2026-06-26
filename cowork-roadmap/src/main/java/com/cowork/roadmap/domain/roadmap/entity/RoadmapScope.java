package com.cowork.roadmap.domain.roadmap.entity;

public enum RoadmapScope {
    /** 전공/포지션별 고정 글로벌 로드맵 (ADMIN 관리) */
    GLOBAL,
    /** 팀이 직접 등록·구성하는 커스텀 로드맵 */
    TEAM,
    /** 프로젝트 단위 커스텀 로드맵 */
    PROJECT
}
