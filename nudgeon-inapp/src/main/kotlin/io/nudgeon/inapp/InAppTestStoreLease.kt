package io.nudgeon.inapp

internal class InAppTestStoreLease(private val key: String) {
    companion object { private val owners = mutableSetOf<String>() }
    private var closed = false
    init { synchronized(owners) { check(owners.add(key)) { "TEST_CLIENT_ALREADY_EXISTS" } } }
    fun close() { synchronized(owners) { if (!closed) { owners.remove(key); closed=true } } }
}
