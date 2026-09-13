package com.maragung.arrowide.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [ModelRouter] classification heuristics and route lookups (plan #57):
 * a configured kind returns its model id, an unconfigured kind returns
 * null (manual/default), never a fabricated model. Pure logic.
 */
class ModelRouterTest {

    @Test
    fun classifiesRefactorKeywords() {
        val router = ModelRouter()

        assertEquals(AiTaskKind.REFACTOR, router.classify("please refactor this mess"))
        assertEquals(AiTaskKind.REFACTOR, router.classify("Rename saveFile to write"))
    }

    @Test
    fun classifiesDebugKeywords() {
        val router = ModelRouter()

        assertEquals(AiTaskKind.DEBUG, router.classify("help me debug this"))
        assertEquals(AiTaskKind.DEBUG, router.classify("It throws an error on line 3"))
        assertEquals(AiTaskKind.DEBUG, router.classify("Here is the stack trace"))
        assertEquals(AiTaskKind.DEBUG, router.classify("Why does the build fail?"))
    }

    @Test
    fun classifiesCodingRequests() {
        val router = ModelRouter()

        assertEquals(AiTaskKind.CODING, router.classify("write a parser for this"))
        assertEquals(AiTaskKind.CODING, router.classify("implement the save function"))
        assertEquals(AiTaskKind.CODING, router.classify("please add test for parse"))
        assertEquals(AiTaskKind.CODING, router.classify("what about a data class here"))
        assertEquals(AiTaskKind.CODING, router.classify("look:\n```kotlin\nfun x(){}\n```"))
    }

    @Test
    fun classifiesEverythingElseAsChat() {
        val router = ModelRouter()

        assertEquals(AiTaskKind.CHAT, router.classify("what is a monad?"))
        assertEquals(AiTaskKind.CHAT, router.classify("summarize this design"))
    }

    @Test
    fun routeForUnconfiguredKindIsNull() {
        val router = ModelRouter()

        assertNull(router.routeFor(AiTaskKind.CHAT))
        assertNull(router.route("write a function"))
    }

    @Test
    fun routeReturnsConfiguredModelForClassifiedKind() {
        val router = ModelRouter()
        router.routes = mapOf(
            AiTaskKind.CHAT to "anthropic/claude-haiku",
            AiTaskKind.CODING to "anthropic/claude-sonnet",
        )

        assertEquals("anthropic/claude-sonnet", router.route("write a function for me"))
        assertEquals("anthropic/claude-haiku", router.route("what is a monad?"))
        assertNull(router.route("refactor this")) // REFACTOR left manual
        assertEquals(
            "anthropic/claude-sonnet",
            router.routeFor(AiTaskKind.CODING),
        )
    }
}
