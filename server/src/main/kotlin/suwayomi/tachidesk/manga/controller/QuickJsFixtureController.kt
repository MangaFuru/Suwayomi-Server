package suwayomi.tachidesk.manga.controller

import app.cash.quickjs.QuickJs
import app.cash.quickjs.QuickJsException
import io.javalin.http.Handler
import io.javalin.http.HttpStatus

private const val SECRET_SOURCE_MARKER = "GOMANGA_QUICKJS_SECRET_SOURCE"

data class QuickJsOperationTableResponse(
    val evaluateInt: Int,
    val evaluateBoolean: Boolean,
    val evaluateDouble: Double,
    val evaluateString: String,
    val evaluateArray: List<Int>,
    val compiledResult: Int,
    val hostValueResult: String,
    val errorType: String,
    val errorContainsSourceText: Boolean,
)

data class QuickJsFixtureFailureResponse(
    val code: String,
    val exceptionType: String,
)

object QuickJsFixtureController {
    val operationTable = Handler { context ->
        try {
            QuickJs.create().use { quickJs ->
                val evaluateInt = quickJs.evaluate("6 * 7") as Int
                val evaluateBoolean = quickJs.evaluate("true") as Boolean
                val evaluateDouble = quickJs.evaluate("1.25") as Double
                val evaluateString = quickJs.evaluate("'manga'") as String
                val evaluateArray = (quickJs.evaluate("[1, 2, 3]") as IntArray).toList()
                val compiled = quickJs.compile("20 + 22", "compiled-fixture.js")
                val compiledResult = quickJs.execute(compiled) as Int

                quickJs.set("hostValue", String::class.java, "bridge")
                val hostValueResult = quickJs.evaluate("String(hostValue) + '-ok'") as String

                var errorType = "missing"
                var errorContainsSourceText = true
                try {
                    quickJs.evaluate(
                        "throw new Error('expected fixture error') /* $SECRET_SOURCE_MARKER */",
                        "redacted-fixture.js",
                    )
                } catch (error: QuickJsException) {
                    errorType = error.javaClass.simpleName
                    errorContainsSourceText = error.stackTraceToString().contains(SECRET_SOURCE_MARKER)
                }

                context.json(
                    QuickJsOperationTableResponse(
                        evaluateInt = evaluateInt,
                        evaluateBoolean = evaluateBoolean,
                        evaluateDouble = evaluateDouble,
                        evaluateString = evaluateString,
                        evaluateArray = evaluateArray,
                        compiledResult = compiledResult,
                        hostValueResult = hostValueResult,
                        errorType = errorType,
                        errorContainsSourceText = errorContainsSourceText,
                    ),
                )
            }
        } catch (error: QuickJsException) {
            val unavailable = error.message?.contains("not available in this runtime") == true
            context
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .json(
                    QuickJsFixtureFailureResponse(
                        code = if (unavailable) "quickjs_runtime_unavailable" else "quickjs_execution_failed",
                        exceptionType = error.javaClass.simpleName,
                    ),
                )
        }
    }
}
