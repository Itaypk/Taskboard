package dev.itayp.tasker.channel.messaging

import dev.itayp.tasker.channel.ChannelType
import dev.itayp.tasker.repository.UserRepository
import dev.itayp.tasker.service.AccountService
import dev.itayp.tasker.service.UserAuthService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import java.util.UUID

/** Links against the real schema: hashing, encryption, the unique constraints, export and deletion. */
@SpringBootTest
@ActiveProfiles("dev")
class ChannelLinkIntegrationTest(
    @Autowired val linkService: ChannelLinkService,
    @Autowired val userAuthService: UserAuthService,
    @Autowired val userRepository: UserRepository,
    @Autowired val accountService: AccountService,
    @Autowired val jdbc: JdbcTemplate,
) {
    private val whatsapp = ChannelType.WHATSAPP

    private fun newUser(): UUID = UUID.randomUUID().also { userAuthService.ensureDevUser(it, System.nanoTime()) }

    private fun sender() = "IL.${System.nanoTime()}"

    private fun link(userId: UUID, sender: String, display: String? = "972500000978"): LinkOutcome {
        val code = linkService.startLink(userId, whatsapp).code
        return linkService.consume(whatsapp, sender, display, ChannelLinkService.extractCode("Link: $code")!!)
    }

    @Test
    fun `a code links the chat account, stores nothing in the clear, and works once`() {
        val userId = newUser()
        val sender = sender()
        val code = linkService.startLink(userId, whatsapp)
        assertThat(code.code).matches("[A-Z2-7]{4}-[A-Z2-7]{4}")

        val normalized = ChannelLinkService.extractCode(code.code)!!
        assertThat(linkService.consume(whatsapp, sender, "972500000978", normalized))
            .isEqualTo(LinkOutcome.Linked(userId, alreadyLinked = false))
        assertThat(linkService.resolve(whatsapp, sender)).isEqualTo(userId)
        assertThat(linkService.list(userId).single().display).isEqualTo("•••• 0978")

        val stored = jdbc.queryForMap("SELECT * FROM channel_link WHERE user_id = ?", userId)
        assertThat(stored.values.map { (it as? ByteArray)?.toString(Charsets.ISO_8859_1) ?: it.toString() })
            .noneMatch { it.contains(sender) || it.contains("972500000978") }

        // Single use: the same code from another account does nothing.
        assertThat(linkService.consume(whatsapp, sender(), null, normalized)).isEqualTo(LinkOutcome.Invalid)
    }

    @Test
    fun `a chat account linked to one user can't be linked to another, and a user's new account replaces the old`() {
        val first = newUser()
        val second = newUser()
        val sender = sender()
        link(first, sender)

        assertThat(link(second, sender)).isEqualTo(LinkOutcome.Conflict(first))
        assertThat(link(first, sender)).isEqualTo(LinkOutcome.Linked(first, alreadyLinked = true))

        val newSender = sender()
        assertThat(link(first, newSender)).isEqualTo(LinkOutcome.Linked(first, alreadyLinked = false))
        assertThat(linkService.resolve(whatsapp, sender)).isNull()
        assertThat(linkService.resolve(whatsapp, newSender)).isEqualTo(first)
    }

    @Test
    fun `an unclaimed account can't ask for a code`() {
        val userId = newUser()
        userRepository.findById(userId).get().apply { claimed = false }.also { userRepository.save(it) }

        assertThatThrownBy { linkService.startLink(userId, whatsapp) }.isInstanceOf(ChannelLinkNotAllowedException::class.java)
    }

    @Test
    fun `unlinking forgets the chat account`() {
        val userId = newUser()
        val sender = sender()
        link(userId, sender)

        assertThat(linkService.unlink(userId, whatsapp)).isTrue()
        assertThat(linkService.resolve(whatsapp, sender)).isNull()
        assertThat(linkService.unlink(userId, whatsapp)).isFalse()
    }

    @Test
    fun `the export names the channel but not the account, and deleting the account removes links and codes`() {
        val userId = newUser()
        link(userId, sender())
        linkService.startLink(userId, whatsapp)

        val export = accountService.exportAccount(userId)
        assertThat(export.user.messagingLinks.map { it.channel }).containsExactly("whatsapp")

        accountService.deleteAccount(userId)
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM channel_link WHERE user_id = ?", Int::class.java, userId)).isZero()
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM channel_link_token WHERE user_id = ?", Int::class.java, userId)).isZero()
    }
}
