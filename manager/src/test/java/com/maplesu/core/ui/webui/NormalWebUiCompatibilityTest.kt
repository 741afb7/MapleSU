package com.maplesu.core.ui.webui

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NormalWebUiCompatibilityTest {
    @Test fun normalWorkersKeepTheDocumentRestrictions() {
        val policy = moduleDocumentCsp(true)
        assertTrue(policy.contains("worker-src 'self' blob:"))
        assertTrue(policy.contains("frame-src 'self'"))
        assertTrue(policy.contains("object-src 'none'"))
        val ultraPolicy = moduleDocumentCsp(false)
        assertTrue(ultraPolicy.contains("worker-src 'self' blob:"))
        assertTrue(ultraPolicy.contains("frame-src 'self'"))
        assertTrue(ultraPolicy.contains("object-src 'none'"))
        assertEquals(policy, ultraPolicy)
    }

    @Test fun normalSupportsSimpleAndDottedCallbackReferences() {
        val scripts = WebUiCallbackScripts(true)
        assertTrue(scripts.exec("done", 0, "out", "")!!.contains("window[\"done\"]("))
        assertTrue(scripts.exec("app.done", 0, "out", "")!!.contains("window[\"app\"][\"done\"]("))
        assertTrue(scripts.data("window.app.child", "stdout", "out")!!
            .contains("window[\"window\"][\"app\"][\"child\"].stdout.emit"))
    }

    @Test fun normalRejectsExpressionsAndPrototypePaths() {
        val scripts = WebUiCallbackScripts(true)
        for (name in listOf("", ".done", "app..done", "done()", "app['done']", "done;evil()",
            "app.constructor", "app.__proto__.done", "app.prototype.done")) {
            assertNull(name, scripts.exec(name, 0, "", ""))
            assertNull(name, scripts.data(name, "stdout", ""))
            assertNull(name, scripts.result(name, 1, ""))
        }
    }

    @Test fun ultraKeepsTheLiteralWindowPropertyContract() {
        val scripts = WebUiCallbackScripts(false)
        val exec = scripts.exec("app.done", 0, "", "")!!
        assertTrue(exec.contains("window[\"app.done\"]("))
        assertTrue(exec.contains("if (\"app.done\" in window)"))
        assertTrue(exec.contains("else { window[\"app\"][\"done\"]("))
        val data = scripts.data("app.child", "stdout", "line")!!
        assertTrue(data.contains("window[\"app.child\"].stdout.emit"))
        assertTrue(data.contains("else { window[\"app\"][\"child\"].stdout.emit"))
        val result = scripts.result("app.child", 9, "failed")!!
        assertTrue(result.contains("var child = window[\"app.child\"];"))
        assertTrue(result.contains("else { var child = window[\"app\"][\"child\"];"))
        for (name in listOf("done();window.__callbackInjected=1", "app.constructor", "app.__proto__.done", "app.prototype.done")) {
            for (script in listOf(scripts.exec(name, 0, "", "")!!,
                scripts.data(name, "stdout", "")!!, scripts.result(name, 9, "")!!)) {
                assertTrue(script.contains("window[${JSONObject.quote(name)}]"))
                assertFalse("invalid path generated a nested fallback: $name", script.contains("else {"))
            }
        }
    }
}

/** Exports actual production-generated scripts and policy for the Chromium harness. */
object NormalWebUiCompatibilityFixture {
    @JvmStatic fun main(args: Array<String>) {
        val scripts = WebUiCallbackScripts(true)
        val ultra = WebUiCallbackScripts(false)
        val fixture = JSONObject()
            .put("normalCsp", moduleDocumentCsp(true))
            .put("ultraCsp", moduleDocumentCsp(false))
            .put("simpleExec", scripts.exec("done", 0, "stdout", "stderr"))
            .put("dottedExec", scripts.exec("app.done", 7, "out", "err"))
            .put("windowExec", scripts.exec("window.app.done", 8, "window", ""))
            .put("spawn", JSONArray(listOf(
                scripts.data("app.child", "stdout", "line"),
                scripts.data("app.child", "stderr", "warning"),
                scripts.result("app.child", 9, "failed"))))
            .put("ultraExec", ultra.exec("app.done", 0, "literal", ""))
            .put("ultraWindowExec", ultra.exec("window.app.done", 8, "window", ""))
            .put("ultraSpawn", JSONArray(listOf(
                ultra.data("app.child", "stdout", "ultra-line"),
                ultra.data("app.child", "stderr", "ultra-warning"),
                ultra.result("app.child", 13, "ultra-failed"))))
            .put("ultraUnsafeNames", JSONArray(listOf("done();window.__callbackInjected=1", "app.constructor",
                "app.__proto__.done", "app.prototype.done").map { name ->
                JSONObject().put("name", name).put("script", ultra.exec(name, 0, "legacy", ""))
            }))
        File(args.single()).writeText(fixture.toString(2) + "\n")
    }
}
