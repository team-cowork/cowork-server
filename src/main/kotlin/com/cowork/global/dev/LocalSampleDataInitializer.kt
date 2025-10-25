package com.cowork.global.dev

import com.cowork.channel.domain.Channel
import com.cowork.channel.domain.ChannelViewType
import com.cowork.channel.repository.ChannelRepository
import com.cowork.project.domain.Project
import com.cowork.project.domain.ProjectMember
import com.cowork.project.domain.ProjectMemberRole
import com.cowork.project.repository.ProjectMemberRepository
import com.cowork.project.repository.ProjectRepository
import com.cowork.team.domain.Team
import com.cowork.team.domain.TeamMember
import com.cowork.team.domain.TeamMemberRole
import com.cowork.team.domain.TeamVisibility
import com.cowork.team.repository.TeamMemberRepository
import com.cowork.team.repository.TeamRepository
import com.cowork.user.domain.User
import com.cowork.user.repository.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * local 프로필로 실행할 때 빈 데이터베이스에 화면 개발용 샘플 데이터를 넣는다.
 */
@Profile("local")
@Component
class LocalSampleDataInitializer(
    private val userRepository: UserRepository,
    private val teamRepository: TeamRepository,
    private val teamMemberRepository: TeamMemberRepository,
    private val projectRepository: ProjectRepository,
    private val projectMemberRepository: ProjectMemberRepository,
    private val channelRepository: ChannelRepository,
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    override fun run(args: ApplicationArguments) {
        if (userRepository.count() > 0) return
        val users = userRepository.saveAll(
            listOf(
                User(dataGsmId = 900001, name = "김개발", email = "dev1@gsm.hs.kr", sex = "MAN", grade = 2, classNum = 1, major = "BACKEND"),
                User(dataGsmId = 900002, name = "이디자인", email = "dev2@gsm.hs.kr", sex = "WOMAN", grade = 2, classNum = 2, major = "FRONTEND"),
                User(dataGsmId = 900003, name = "박기획", email = "dev3@gsm.hs.kr", sex = "MAN", grade = 1, classNum = 3, major = "BACKEND"),
            ),
        )
        val owner = users.first()
        val team = teamRepository.save(
            Team(name = "cowork 샘플 팀", description = "로컬 개발용 팀", visibility = TeamVisibility.PUBLIC, ownerId = owner.id),
        )
        teamMemberRepository.saveAll(
            users.map { TeamMember(teamId = team.id, userId = it.id, role = if (it == owner) TeamMemberRole.OWNER else TeamMemberRole.MEMBER) },
        )
        val project = projectRepository.save(
            Project(teamId = team.id, name = "cowork 앱", description = null, githubRepoUrl = null, position = 0, createdBy = owner.id),
        )
        projectMemberRepository.save(ProjectMember(projectId = project.id, userId = owner.id, role = ProjectMemberRole.OWNER))
        channelRepository.saveAll(
            listOf(
                Channel(teamId = team.id, projectId = null, name = "일반", viewType = ChannelViewType.TEXT, description = null, isPrivate = false, position = 0, createdBy = owner.id),
                Channel(teamId = team.id, projectId = project.id, name = "파일", viewType = ChannelViewType.FILE_SHARE, description = null, isPrivate = false, position = 1, createdBy = owner.id),
            ),
        )
        log.info("Insert {} local sample users and team {}", users.size, team.id)
    }
}
