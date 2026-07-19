package com.cowork.team.domain.team.repository

import com.cowork.team.domain.team.entity.Team
import org.springframework.data.jpa.repository.JpaRepository

interface TeamRepository : JpaRepository<Team, Long>
