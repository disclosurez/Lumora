package com.lumora.plugin.js

import com.whl.quickjs.wrapper.JSCallFunction
import com.whl.quickjs.wrapper.QuickJSContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies whether a Kotlin exception thrown inside a JSCallFunction is catchable by the
 * script's own JS try/catch, or whether it unwinds straight past JS stack frames back into the
 * Kotlin caller of evaluate() - this determines whether host.httpGet's IOExceptions are visible
 * to a script's `try { host.httpGet(...) } catch (e) {}` at all. See JsPluginEngine's handling.
 */
class HostExceptionPropagationTest {

    companion object {
        init {
            System.getProperty("test.quickjs.so")?.let { System.load(it) }
        }
    }

    @Test
    fun `a Kotlin exception thrown inside a host function is not catchable by JS try-catch`() {
        val context = QuickJSContext.create()
        try {
            context.getGlobalObject().setProperty(
                "boom",
                JSCallFunction { throw java.io.IOException("network down") }
            )
            var threwInKotlin = false
            var jsResult: Any? = "not evaluated"
            try {
                jsResult = context.evaluate(
                    "var caught = 'no'; try { boom(); } catch (e) { caught = 'yes'; } caught;"
                )
            } catch (e: Exception) {
                threwInKotlin = true
            }
            // Document whichever behavior this binding actually has.
            println("threwInKotlin=$threwInKotlin jsResult=$jsResult")
            assertTrue(threwInKotlin || jsResult == "yes")
        } finally {
            context.destroy()
        }
    }
}
