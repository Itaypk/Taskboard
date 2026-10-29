package dev.itayp.tasker.util

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MarkdownChecklistTest {

    @Test
    fun `unticks every item, partly ticked or not`() {
        assertThat(MarkdownChecklist.uncheckAll("- [x] inbox\n- [ ] calendar\n- [X] backlog"))
            .isEqualTo("- [ ] inbox\n- [ ] calendar\n- [ ] backlog")
    }

    @Test
    fun `covers ordered, nested and quoted items and keeps the rest of the note`() {
        val note = "Intro with [x] inline\n\n1. [x] one\n  * [x] nested\n> - [x] quoted\n2) [x] paren"
        assertThat(MarkdownChecklist.uncheckAll(note))
            .isEqualTo("Intro with [x] inline\n\n1. [ ] one\n  * [ ] nested\n> - [ ] quoted\n2) [ ] paren")
    }

    @Test
    fun `skips markers inside fenced code blocks`() {
        val note = "```\n- [x] not a task\n```\n~~~~\n- [x] nor this\n~~~\n~~~~\n- [x] real"
        assertThat(MarkdownChecklist.uncheckAll(note))
            .isEqualTo("```\n- [x] not a task\n```\n~~~~\n- [x] nor this\n~~~\n~~~~\n- [ ] real")
    }

    @Test
    fun `ignores markers that do not render as checkboxes`() {
        val note = "[x] no bullet\n- [x]\n- [x]   \n-[x] squashed"
        assertThat(MarkdownChecklist.uncheckAll(note)).isEqualTo(note)
    }

    @Test
    fun `preserves CRLF line endings`() {
        assertThat(MarkdownChecklist.uncheckAll("- [x] a\r\n- [x] b\r\n"))
            .isEqualTo("- [ ] a\r\n- [ ] b\r\n")
    }
}
