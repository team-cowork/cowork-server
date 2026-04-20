package com.cowork.team.repository

import com.cowork.team.domain.Team
import org.springframework.data.jpa.repository.JpaRepository

interface TeamRepository : JpaRepository<Team, Long>