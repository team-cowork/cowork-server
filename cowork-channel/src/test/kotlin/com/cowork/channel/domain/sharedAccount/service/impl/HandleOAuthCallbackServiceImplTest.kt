package com.cowork.channel.domain.sharedAccount.service.impl

import com.cowork.channel.domain.channel.entity.Channel
import com.cowork.channel.domain.channel.entity.ChannelType
import com.cowork.channel.domain.channel.entity.ChannelViewType
import com.cowork.channel.domain.channel.service.ChannelAccessGuard
import com.cowork.channel.domain.channel.service.TeamPermissionService
import com.cowork.channel.domain.sharedAccount.entity.AccountProvider
import com.cowork.channel.domain.sharedAccount.entity.SharedAccount
import com.cowork.channel.domain.sharedAccount.repository.SharedAccountRepository
import com.cowork.channel.domain.sharedAccount.service.support.OAuthIdentityResolver
import com.cowork.channel.domain.sharedAccount.service.support.OAuthStateSupport
import com.cowork.channel.global.config.OAuthProperties
import com.cowork.channel.global.config.OAuthProviderConfig
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.DefaultTransactionStatus
import team.themoment.sdk.exception.ExpectedException
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private const val STATE_SECRET = "test-state-secret-key"

class HandleOAuthCallbackServiceImplTest :
    DescribeSpec({

        val objectMapper = jacksonObjectMapper()

        lateinit var sharedAccountRepository: SharedAccountRepository
        lateinit var channelAccessGuard: ChannelAccessGuard
        lateinit var teamPermissionService: TeamPermissionService
        lateinit var oAuthIdentityResolver: OAuthIdentityResolver
        lateinit var transactionManager: PlatformTransactionManager
        lateinit var service: HandleOAuthCallbackServiceImpl

        /** transaction 경계 진입 순서를 기록해 외부 호출이 그 밖에서 일어나는지 확인한다. */
        lateinit var timeline: MutableList<String>

        val channelId = 1L
        val userId = 1L
        val teamId = 100L

        val oAuthProperties = OAuthProperties(
            callbackBaseUrl = "https://example.com",
            allowedReturnOrigins = listOf("https://client.example.com"),
            stateSecret = STATE_SECRET,
            github = OAuthProviderConfig(
                clientId = "gh-id",
                clientSecret = "gh-secret",
                tokenUrl = "https://github.test/token",
                userinfoUrl = "https://github.test/user",
            ),
            notion = OAuthProviderConfig(
                clientId = "no-id",
                clientSecret = "no-secret",
                tokenUrl = "https://notion.test/token",
                userinfoUrl = "https://notion.test/user",
            ),
        )

        fun accountShareChannel() = Channel(
            id = channelId,
            teamId = teamId,
            name = "ch",
            type = ChannelType.TEXT,
            viewType = ChannelViewType.ACCOUNT_SHARE,
            description = null,
            isPrivate = false,
            position = 0,
            createdBy = userId,
            projectId = null,
        )

        /** 실제 서명 규칙으로 state를 만든다. 서명·만료 검증을 진짜로 통과시키기 위함이다. */
        fun buildValidState(provider: AccountProvider = AccountProvider.GITHUB, expOffset: Long = 300L): String {
            val payload = mapOf(
                "channelId" to channelId,
                "userId" to userId,
                "provider" to provider.name,
                "returnOrigin" to oAuthProperties.allowedReturnOrigins.first(),
                "nonce" to UUID.randomUUID().toString(),
                "exp" to (Instant.now().epochSecond + expOffset),
            )
            val encoder = Base64.getUrlEncoder().withoutPadding()
            val payloadB64 = encoder.encodeToString(
                objectMapper.writeValueAsString(payload).toByteArray(Charsets.UTF_8),
            )
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(STATE_SECRET.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            val signature = encoder.encodeToString(mac.doFinal(payloadB64.toByteArray(Charsets.UTF_8)))
            return "$payloadB64.$signature"
        }

        beforeEach {
            timeline = mutableListOf()
            sharedAccountRepository = mockk()
            channelAccessGuard = mockk()
            teamPermissionService = mockk(relaxed = true)
            oAuthIdentityResolver = mockk()
            transactionManager = mockk()

            // begin/commit을 기록하는 최소 transaction manager 대역.
            every { transactionManager.getTransaction(any()) } answers {
                timeline += "tx:begin"
                mockk<DefaultTransactionStatus>(relaxed = true)
            }
            every { transactionManager.commit(any<TransactionStatus>()) } answers { timeline += "tx:commit" }
            every { transactionManager.rollback(any<TransactionStatus>()) } answers { timeline += "tx:rollback" }

            every { channelAccessGuard.findChannelOrThrow(channelId) } answers {
                timeline += "db:findChannel"
                accountShareChannel()
            }
            every { channelAccessGuard.requireTeamChannel(any()) } returns teamId

            service = HandleOAuthCallbackServiceImpl(
                oAuthProperties,
                sharedAccountRepository,
                channelAccessGuard,
                teamPermissionService,
                // state 검증은 실제 구현을 써서 서명·만료 규칙까지 함께 검증한다.
                OAuthStateSupport(oAuthProperties, objectMapper),
                oAuthIdentityResolver,
                transactionManager,
            )
        }

        describe("HandleOAuthCallbackServiceImpl 클래스의 handleCallback 메서드는") {

            context("지원하지 않는 provider 이름을 받으면") {
                it("BAD_REQUEST로 거부한다") {
                    val error = shouldThrow<ExpectedException> {
                        service.handleCallback("UNKNOWN_PROVIDER", "code", "state")
                    }

                    error.statusCode shouldBe HttpStatus.BAD_REQUEST
                }
            }

            context("서명이 변조된 state이면") {
                it("BAD_REQUEST로 거부한다") {
                    val payloadB64 = buildValidState().split(".")[0]

                    val error = shouldThrow<ExpectedException> {
                        service.handleCallback("github", "code", "$payloadB64.invalidsignature")
                    }

                    error.statusCode shouldBe HttpStatus.BAD_REQUEST
                }
            }

            context("만료된 state이면") {
                it("BAD_REQUEST로 거부한다") {
                    val error = shouldThrow<ExpectedException> {
                        service.handleCallback("github", "code", buildValidState(expOffset = -1L))
                    }

                    error.statusCode shouldBe HttpStatus.BAD_REQUEST
                }
            }

            context("provider가 state와 불일치하면") {
                it("BAD_REQUEST로 거부한다") {
                    val githubState = buildValidState(provider = AccountProvider.GITHUB)

                    val error = shouldThrow<ExpectedException> {
                        service.handleCallback("notion", "code", githubState)
                    }

                    error.statusCode shouldBe HttpStatus.BAD_REQUEST
                }
            }

            context("state 검증에 실패하면") {
                it("외부 제공자를 호출하지 않는다") {
                    runCatching { service.handleCallback("github", "code", "tampered.state") }

                    verify(exactly = 0) { oAuthIdentityResolver.resolveIdentifier(any(), any(), any(), any()) }
                }
            }

            context("정상 콜백이면") {
                beforeEach {
                    every {
                        oAuthIdentityResolver.resolveIdentifier(any(), any(), any(), any())
                    } answers {
                        timeline += "http:provider"
                        "octocat"
                    }
                    every {
                        sharedAccountRepository.findByChannelIdAndProviderAndAccountIdentifier(any(), any(), any())
                    } returns null
                    every { sharedAccountRepository.save(any()) } answers {
                        timeline += "db:save"
                        firstArg()
                    }
                }

                it("외부 제공자 호출을 transaction 경계 밖에서 수행한다") {
                    service.handleCallback("github", "code", buildValidState())

                    // 권한 검증 transaction이 commit된 뒤에 외부 호출이 시작되고,
                    // 저장 transaction은 그 이후에 열려야 한다.
                    val httpIndex = timeline.indexOf("http:provider")
                    val firstCommit = timeline.indexOf("tx:commit")
                    val saveIndex = timeline.indexOf("db:save")
                    val lastBegin = timeline.lastIndexOf("tx:begin")

                    (firstCommit < httpIndex) shouldBe true
                    (httpIndex < lastBegin) shouldBe true
                    (lastBegin < saveIndex) shouldBe true
                }

                it("검증과 저장에 각각 별도의 transaction을 사용한다") {
                    service.handleCallback("github", "code", buildValidState())

                    timeline.count { it == "tx:begin" } shouldBe 2
                }

                it("조회한 식별자로 계정을 저장한다") {
                    val saved = slot<SharedAccount>()
                    every { sharedAccountRepository.save(capture(saved)) } answers { firstArg() }

                    service.handleCallback("github", "code", buildValidState())

                    saved.captured.accountIdentifier shouldBe "octocat"
                    saved.captured.channelId shouldBe channelId
                    saved.captured.provider shouldBe AccountProvider.GITHUB
                    saved.captured.connectedViaOAuth shouldBe true
                }
            }

            context("이미 등록된 계정이면") {
                it("새로 저장하지 않고 기존 계정을 반환한다") {
                    val existing = SharedAccount(
                        id = 10L,
                        channelId = channelId,
                        provider = AccountProvider.GITHUB,
                        providerLabel = null,
                        accountIdentifier = "octocat",
                        credential = null,
                        connectedViaOAuth = true,
                        createdBy = userId,
                    )
                    every {
                        oAuthIdentityResolver.resolveIdentifier(any(), any(), any(), any())
                    } returns "octocat"
                    every {
                        sharedAccountRepository.findByChannelIdAndProviderAndAccountIdentifier(any(), any(), any())
                    } returns existing

                    val result = service.handleCallback("github", "code", buildValidState())

                    result.id shouldBe 10L
                    verify(exactly = 0) { sharedAccountRepository.save(any()) }
                }
            }

            context("같은 계정 콜백이 동시에 도착해 unique 제약이 충돌하면") {
                it("재조회로 기존 계정을 반환한다") {
                    val winner = SharedAccount(
                        id = 42L,
                        channelId = channelId,
                        provider = AccountProvider.GITHUB,
                        providerLabel = null,
                        accountIdentifier = "octocat",
                        credential = null,
                        connectedViaOAuth = true,
                        createdBy = userId,
                    )
                    every { oAuthIdentityResolver.resolveIdentifier(any(), any(), any(), any()) } returns "octocat"
                    // 최초 조회는 비어 있지만, 저장 시점엔 다른 요청이 먼저 커밋한 상태.
                    every {
                        sharedAccountRepository.findByChannelIdAndProviderAndAccountIdentifier(any(), any(), any())
                    } returnsMany listOf(null, winner)
                    every {
                        sharedAccountRepository.save(any())
                    } throws DataIntegrityViolationException("duplicate key")

                    val result = service.handleCallback("github", "code", buildValidState())

                    result.id shouldBe 42L
                }

                it("재조회에도 계정이 없으면 예외를 전파한다") {
                    every { oAuthIdentityResolver.resolveIdentifier(any(), any(), any(), any()) } returns "octocat"
                    every {
                        sharedAccountRepository.findByChannelIdAndProviderAndAccountIdentifier(any(), any(), any())
                    } returns null
                    every {
                        sharedAccountRepository.save(any())
                    } throws DataIntegrityViolationException("duplicate key")

                    shouldThrow<DataIntegrityViolationException> {
                        service.handleCallback("github", "code", buildValidState())
                    }
                }
            }

            context("팀 멤버가 아니면") {
                it("외부 제공자를 호출하기 전에 거부한다") {
                    every {
                        teamPermissionService.requireTeamMember(teamId, userId)
                    } throws ExpectedException("팀 멤버만 접근할 수 있습니다.", HttpStatus.FORBIDDEN)

                    shouldThrow<ExpectedException> {
                        service.handleCallback("github", "code", buildValidState())
                    }

                    verify(exactly = 0) { oAuthIdentityResolver.resolveIdentifier(any(), any(), any(), any()) }
                }
            }
        }
    })
