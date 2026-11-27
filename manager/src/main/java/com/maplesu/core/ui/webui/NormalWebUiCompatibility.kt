package com.maplesu.core.ui.webui

import org.json.JSONObject

@Suppress("UNUSED_PARAMETER")
internal fun moduleDocumentCsp(normal: Boolean): String =
    "frame-src 'self'; object-src 'none'; base-uri 'self'; form-action 'self'; worker-src 'self' blob:"

/** Generates callbacks without evaluating caller-supplied JavaScript expressions. */
internal class WebUiCallbackScripts(private val normal: Boolean) {
    private val property = Regex("[A-Za-z_$][A-Za-z0-9_$]*")

    private fun reference(name: String, statement: (String) -> String): String? {
        val parts = name.split('.')
        val nested = if (parts.any { !property.matches(it) || it in setOf("__proto__", "prototype", "constructor") }) null
            else "window" + parts.joinToString("") { "[${JSONObject.quote(it)}]" }
        if (normal) return nested?.let(statement)
        val literal = "window[${JSONObject.quote(name)}]"
        if (nested == null || nested == literal) return statement(literal)
        return "if (${JSONObject.quote(name)} in window) { ${statement(literal)} } " +
            "else { ${statement(nested)} }"
    }

    fun exec(name: String, code: Int, stdout: String, stderr: String): String? {
        val callback = reference(name) {
            "$it($code, ${JSONObject.quote(stdout)}, ${JSONObject.quote(stderr)});"
        } ?: return null
        return "javascript: (function() { try { $callback } catch(e) { console.error(e); } })();"
    }

    fun data(name: String, stream: String, value: String): String? {
        val child = reference(name) { "$it.$stream.emit('data', ${JSONObject.quote(value)});" } ?: return null
        return "javascript: (function() { try { $child " +
            "} catch(e) { console.error('emitData', e); } })();"
    }

    fun result(name: String, code: Int, error: String): String? {
        // The exit listener may delete the global callback before the error event.
        val callback = reference(name) { "var child = $it; " +
            "child.emit('exit', $code); if ($code !== 0) { var err = new Error(${JSONObject.quote(error)}); " +
            "err.exitCode = $code; child.emit('error', err); }" } ?: return null
        return "javascript: (function() { try { $callback } catch(e) { console.error(e); } })();"
    }
}
