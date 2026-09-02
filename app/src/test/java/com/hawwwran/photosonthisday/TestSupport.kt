package com.hawwwran.photosonthisday

/** `kotlin.test.assertFailsWith` without the kotlin-test dependency, and usable from a suspend block. */
inline fun <reified T : Throwable> assertFailsWith(block: () -> Unit): T {
    try {
        block()
    } catch (e: Throwable) {
        if (e is T) return e
        throw AssertionError("expected ${T::class.simpleName}, got ${e.javaClass.simpleName}: ${e.message}", e)
    }
    throw AssertionError("expected ${T::class.simpleName}, nothing was thrown")
}
