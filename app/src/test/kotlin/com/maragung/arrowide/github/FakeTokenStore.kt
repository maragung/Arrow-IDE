package com.maragung.arrowide.github

/**
 * In-memory [TokenStore] that records every [TokenStore.set] call so
 * tests can assert when (and whether) a token was stored.
 */
class FakeTokenStore(initialToken: String? = null) : TokenStore {

    var token: String? = initialToken
        private set

    val setCalls = mutableListOf<String?>()

    override fun get(): String? = token

    override fun set(value: String?) {
        setCalls += value
        token = value
    }
}
