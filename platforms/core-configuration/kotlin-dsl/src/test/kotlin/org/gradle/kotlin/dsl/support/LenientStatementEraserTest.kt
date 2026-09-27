/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.kotlin.dsl.support

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.core.IsEqual.equalTo
import org.hamcrest.core.IsNull.nullValue
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.junit.AfterClass
import org.junit.Test


class LenientStatementEraserTest {

    @Test
    fun `erases whole statement for error on block opener line`() {
        val script = "tasks.withType<Foo>() {\n}\n"

        val result = erase(script, errorLine = 1)!!

        assertThat(result.erasedText, equalTo(blanked(script)))
        assertThat(result.erasedStatements.size, equalTo(1))
        assertThat(result.erasedStatements[0].firstLine, equalTo(1))
        assertThat(result.erasedStatements[0].blockName, nullValue())
    }

    @Test
    fun `erases only offending inner line`() {
        val script = "tasks.withType<JavaCompile>() {\n    sourceCompatibility = bar.foo\n}\n"

        val result = erase(script, errorLine = 2, errorColumn = 5)!!

        assertThat(
            result.erasedText,
            equalTo(
                "tasks.withType<JavaCompile>() {\n" +
                    blanked("    sourceCompatibility = bar.foo") + "\n" +
                    "}\n"
            )
        )
        assertThat(result.erasedStatements[0].firstLine, equalTo(2))
    }

    @Test
    fun `erases whole block for error on simple block opener line`() {
        val script = "bar {\n    foo = 1\n}\n"

        val result = erase(script, errorLine = 1)!!

        assertThat(result.erasedText, equalTo(blanked(script)))
        assertThat(result.erasedStatements[0].blockName, equalTo("bar"))
    }

    @Test
    fun `erases multiple failing statements`() {
        val script = "bar {\n    foo = 1\n}\n\nbaz {\n    qux = 2\n}\n"

        val result = eraser.erase(script, listOf(errorAt(1), errorAt(5)), emptyList())!!

        assertThat(
            result.erasedText,
            equalTo(
                blanked("bar {\n    foo = 1\n}") + "\n" +
                    "\n" +
                    blanked("baz {\n    qux = 2\n}") + "\n"
            )
        )
        assertThat(result.erasedStatements.size, equalTo(2))
        assertThat(result.erasedStatements[0].blockName, equalTo("bar"))
        assertThat(result.erasedStatements[1].blockName, equalTo("baz"))
    }

    @Test
    fun `expands erasure to enclosing statement for error inside previously erased range`() {
        val script = "tasks {\n    bar {\n        foo = 1\n    }\n}\n"

        val firstRound = erase(script, errorLine = 3, errorColumn = 9)!!
        assertThat(
            firstRound.erasedText,
            equalTo(
                "tasks {\n" +
                    "    bar {\n" +
                    blanked("        foo = 1") + "\n" +
                    "    }\n" +
                    "}\n"
            )
        )

        val secondRound = eraser.erase(
            firstRound.erasedText,
            listOf(errorAt(3, 9)),
            firstRound.erasedStatements.map { it.range }
        )!!

        assertThat(
            secondRound.erasedText,
            equalTo(
                "tasks {\n" +
                    blanked("    bar {\n        foo = 1\n    }") + "\n" +
                    "}\n"
            )
        )
        assertThat(secondRound.erasedStatements[0].firstLine, equalTo(2))
    }

    @Test
    fun `returns null when error line has no erasable statement`() {
        assertThat(erase("", errorLine = 1), nullValue())
        assertThat(erase("foo = 1\n", errorLine = 42), nullValue())
    }

    private
    fun erase(script: String, errorLine: Int, errorColumn: Int = 1): LenientStatementEraser.ErasureResult? =
        eraser.erase(script, listOf(errorAt(errorLine, errorColumn)), emptyList())

    private
    fun errorAt(line: Int, column: Int = 1) =
        LenientStatementEraser.ErrorLocation(line, column)

    private
    fun blanked(text: String): String =
        text.map { if (it == '\n') '\n' else ' ' }.joinToString("")

    private
    companion object {

        private
        val disposable = Disposer.newDisposable()

        /**
         * A single eraser (and thus a single Kotlin parsing environment) shared by all
         * tests, since creating a [org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment]
         * is expensive.
         */
        private
        val eraser = LenientStatementEraser.usingEmbeddedKotlinParser(disposable)

        @JvmStatic
        @AfterClass
        fun disposeParsingEnvironment() {
            Disposer.dispose(disposable)
        }
    }
}
