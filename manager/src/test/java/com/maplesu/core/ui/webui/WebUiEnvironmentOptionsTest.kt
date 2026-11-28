package com.maplesu.core.ui.webui

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files

/** Exercises the production option builder in an actual POSIX shell, without Root. */
class WebUiEnvironmentOptionsTest {
    private val inherited = "/inherited path/'quoted':/system/bin:/data/adb/maplesu/bin"
    private val inheritedHome = "/inherited home/'quoted'"
    private val inheritedLibraryPath = "/inherited library/'quoted'"

    private fun bridge() = WebViewInterface(WebUIState()) { error("No Root shell is used") }

    private fun options(key: String, value: String, cwd: String? = null): String = JSONObject().apply {
        put("env", JSONObject().put(key, value))
        cwd?.let { put("cwd", it) }
    }.toString()

    private fun command(options: String, body: String = "printf '%s' \"\$PATH\""): String {
        val method = WebViewInterface::class.java.getDeclaredMethod("processOptions", StringBuilder::class.java, String::class.java)
            .apply { isAccessible = true }
        val result = StringBuilder()
        try { bridge().use { method.invoke(it, result, options); "" } }
        catch (error: InvocationTargetException) { throw error.targetException }
        return result.append(body).toString()
    }

    private fun spawn(options: String, argument: String): String {
        val method = WebViewInterface::class.java.getDeclaredMethod("buildSpawnCommand", String::class.java,
            String::class.java, String::class.java).apply { isAccessible = true }
        return bridge().use {
            method.invoke(it, "printf", JSONArray().put("%s").put(argument).toString(), options) as String
        }
    }

    private fun WebViewInterface.use(action: (WebViewInterface) -> String): String = try { action(this) } finally { destroy() }

    private fun shell(command: String): Pair<Int, String> {
        val candidates = listOfNotNull(System.getProperty("maplesu.test.shell"), "/bin/sh", "C:/Program Files/Git/bin/bash.exe")
        val executable = candidates.firstOrNull { File(it).isFile }
        assumeTrue("A POSIX shell is required for environment option regression", executable != null)
        // A script file also keeps Windows command-line quoting outside the
        // shell-language test: the child reads the exact generated UTF-8 bytes.
        val script = Files.createTempFile("webui-options-", ".sh")
        try {
            val initialPath = "'" + inherited.replace("'", "'\"'\"'") + "'"
            val initialHome = "'" + inheritedHome.replace("'", "'\"'\"'") + "'"
            val initialLibraryPath = "'" + inheritedLibraryPath.replace("'", "'\"'\"'") + "'"
            Files.writeString(script, "PATH=$initialPath; export PATH; HOME=$initialHome; export HOME; " +
                "LD_LIBRARY_PATH=$initialLibraryPath; export LD_LIBRARY_PATH; " +
                "KSU_COMPAT_SET=prefixvalue; KSU_COMPAT_EMPTY=; unset PATH_SUFFIX PATH2 KSU_WEBUI_ABSENT; " + command + "\n")
            val process = ProcessBuilder(executable!!, script.toString().replace('\\', '/')).apply {
                redirectErrorStream(true)
            }.start()
            val output = process.inputStream.bufferedReader().readText()
            return process.waitFor() to output
        } finally { Files.deleteIfExists(script) }
    }

    @Test fun dollarPathInheritsPlatformAndMapleTools() {
        val result = shell(command(options("PATH", "/module/bin:\$PATH")))
        assertEquals(0, result.first)
        assertEquals("/module/bin:$inherited", result.second)
    }

    @Test fun bracedPathInheritsWithoutSplittingQuotesAndSpaces() {
        assertEquals(0 to "/module path/'quoted':$inherited", shell(command(options("PATH", "\"/module path/'quoted':\${PATH}\""))))
    }

    @Test fun multipleTokensUseTheOriginalPath() {
        assertEquals(0 to "$inherited:/between:$inherited:/end", shell(command(options("PATH", "\$PATH:/between:\${PATH}:/end"))))
    }

    @Test fun otherEnvironmentValuesFollowShellCommandSubstitution() {
        val value = "\$HOME:\${LD_LIBRARY_PATH}:\$(printf computed):`printf backtick`"
        assertEquals(0 to "$inheritedHome:$inheritedLibraryPath:computed:backtick",
            shell(command(options("PROBE", value), "printf '%s' \"\$PROBE\"")))
    }

    @Test fun pathUsesTheSameShellExpressionContractAsOtherValues() {
        val value = "\"\$(printf computed):`printf backtick`:\$PATH\""
        assertEquals(0 to "computed:backtick:$inherited", shell(command(options("PATH", value))))
    }

    @Test fun variableNamesUseTheirWholeTokenAndParameterOperatorsExpand() {
        val value = "\$PATH_SUFFIX:\${PATH_SUFFIX}:\$PATH2:\${PATH:-fallback}:\$PATH"
        assertEquals(0 to ":::$inherited:$inherited", shell(command(options("PATH", value))))
    }

    @Test fun posixParameterOperatorsFollowTheShellContract() {
        val cases = mapOf(
            "\${KSU_WEBUI_ABSENT:-fallback}" to "fallback",
            "\${KSU_COMPAT_SET:+present}" to "present",
            "\${KSU_COMPAT_SET#prefix}" to "value",
            "\${KSU_COMPAT_SET%value}" to "prefix",
            "\${KSU_WEBUI_ABSENT-default}" to "default",
            "\${KSU_COMPAT_EMPTY:-default}" to "default",
            "\${KSU_COMPAT_EMPTY-default}" to ""
        )
        cases.forEach { (expression, expected) ->
            assertEquals(expression, 0 to expected,
                shell(command(options("PROBE", expression), "printf '%s' \"\$PROBE\"")))
        }
    }

    @Test fun homeAndLibraryPathBothInheritQuotedAndSpacedValues() {
        assertEquals(0 to "$inheritedHome/cache:$inheritedLibraryPath/module:$inheritedHome",
            shell(command(options("PROBE", "\${HOME}/cache:\$LD_LIBRARY_PATH/module:\$HOME"),
                "printf '%s' \"\$PROBE\"")))
    }

    @Test fun unsetVariablesExpandToEmptyWithoutDroppingLiteralText() {
        assertEquals(0 to "/prefix::suffix", shell(command(options("PROBE", "/prefix:\${KSU_WEBUI_ABSENT}:suffix"),
            "printf '%s' \"\$PROBE\"")))
    }

    @Test fun followingExportsUseTheEnvironmentProducedByEarlierExports() {
        val env = JSONObject().put("KSU_COMPAT_A", "earlier value").put("KSU_COMPAT_B", "\${KSU_COMPAT_A}/later")
        // Use the actual JSONObject iteration order used by both production bridges.
        val order = env.keys().asSequence().toList()
        val first = order[0]
        val second = order[1]
        env.put(first, "'earlier value'")
        env.put(second, "\${$first}/later")
        assertEquals(0 to "earlier value/later", shell(command(JSONObject().put("env", env).toString(),
            "printf '%s' \"\${$second}\"")))
    }

    @Test fun prequotedValuesUseShellQuoteRemoval() {
        assertEquals(0 to "/module path/'quoted'", shell(command(options("PATH", "\"/module path/'quoted'\""))))
    }

    @Test fun unquotedSpacesFollowTheUpstreamExportContract() {
        assertEquals(0 to "literal", shell(command(options("PROBE", "literal space"), "printf '%s' \"\$PROBE\"")))
    }

    @Test fun spawnInheritsPathAndConsumesQuotedArgumentsOnce() {
        val value = "space ' \" \$PATH \$(printf injected) ;\nargument"
        val quoted = "'" + value.replace("'", "'\"'\"'") + "'"
        assertEquals(0 to "$value:/module/bin:$inherited", shell(command(options("PATH", "/module/bin:\$PATH"),
            "printf '%s' " + quoted + ":\"\$PATH\"")))
        assertEquals(0 to value, shell(spawn(options("PATH", "/module/bin:\${PATH}"), quoted)))
    }

    @Test fun officialKpatchParameterFragmentsFollowTheKsuContract() {
        val cases = mapOf(
            "\"nonce with spaces\"" to "nonce with spaces",
            "\"nonce \\\"quoted\\\"\"" to "nonce \"quoted\"",
            "\"nonce\\'s value\"" to "nonce\\'s value",
            "\"nonce \\\$HOME \\`printf x\\`\"" to "nonce \$HOME `printf x`"
        )
        cases.forEach { (fragment, expected) ->
            assertEquals(fragment, 0 to expected, shell(spawn("{}", fragment)))
        }
    }

    @Test fun spawnArgumentsRetainShellExpansionAndQuoteRemoval() {
        assertEquals(0 to inheritedHome, shell(spawn("{}", "\"\${HOME}\"")))
        assertEquals(0 to "computed", shell(spawn("{}", "\$(printf computed)")))
        assertEquals(0 to "", shell(spawn("{}", "\"\"")))
    }

    @Test fun invalidSpawnArgumentsRemainRejected() {
        val method = WebViewInterface::class.java.getDeclaredMethod("buildSpawnCommand", String::class.java,
            String::class.java, String::class.java).apply { isAccessible = true }
        for (args in listOf(JSONArray().put(7).toString(), JSONArray().put("before\u0000after").toString())) {
            try {
                bridge().use { method.invoke(it, "printf", args, "{}"); "" }
                fail("Invalid spawn argument was accepted")
            } catch (error: InvocationTargetException) {
                assertTrue(error.targetException is IllegalArgumentException)
                assertEquals("Invalid spawn argument", error.targetException.message)
            }
        }
    }

    @Test fun cwdWithQuotesAndSpacesUsesTheActualDirectory() {
        val root = Files.createTempDirectory("webui-options-")
        val child = Files.createDirectory(root.resolve("space ' quoted"))
        val marker = child.resolve("directory-proof")
        try {
            Files.writeString(marker, "actual-directory\n")
            val result = shell(command(options("PATH", "\$PATH", child.toString().replace('\\', '/')),
                "IFS= read -r proof < directory-proof; printf '%s' \"\$proof\""))
            assertEquals(0 to "actual-directory", result)
        } finally { Files.deleteIfExists(marker); Files.deleteIfExists(child); Files.deleteIfExists(root) }
    }

    @Test fun invalidEnvironmentNameDoesNotGenerateACommand() {
        try { command(options("PATH;printf injected", "x")); fail("Invalid environment name was accepted") }
        catch (expected: IllegalArgumentException) { assertEquals("Invalid environment name", expected.message) }
    }

    @Test fun nulEnvironmentValueDoesNotGenerateACommand() {
        try { command(options("PROBE", "before\u0000after")); fail("NUL environment value was accepted") }
        catch (expected: IllegalArgumentException) { assertEquals("Invalid environment value", expected.message) }
    }

    @Test fun jsonNullRemainsAnEmptyEnvironmentValue() {
        val opts = JSONObject().put("env", JSONObject().put("PROBE", JSONObject.NULL)).toString()
        assertEquals(0 to "", shell(command(opts, "printf '%s' \"\$PROBE\"")))
    }

    @Test fun malformedOptionsRemainAnEmptyObject() {
        assertEquals(0 to "boundary-preserved", shell(command("{invalid", "printf '%s' boundary-preserved")))
    }
}
