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

import org.jetbrains.kotlin.K1Deprecation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.create
import org.jetbrains.kotlin.com.intellij.openapi.Disposable
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiFileFactory
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtScript
import org.jetbrains.kotlin.psi.KtScriptInitializer


/**
 * Erases the statements enclosing given compilation error locations from a Kotlin
 * script source so the script can be recompiled without the offending statements.
 *
 * Statements are located with the embedded Kotlin parser (PSI): the source is parsed
 * into a [KtFile] and, for each error location, the nearest enclosing statement — a
 * [KtExpression] or [KtDeclaration] directly contained in a [KtBlockExpression],
 * [KtScript] or [KtScriptInitializer] — is selected for erasure.
 *
 * Erasure replaces every non-whitespace character in the erased ranges with a
 * space, preserving newlines, so line numbers never shift between rounds.
 */
internal
class LenientStatementEraser private constructor(
    private val parseScript: (String) -> KtFile
) {

    internal
    data class ErrorLocation(val line: Int, val column: Int)

    internal
    data class ErasedStatement(
        /** Character range of the erased statement in the source text it was computed from. */
        val range: IntRange,
        /** 1-based number of the first line of the erased statement. */
        val firstLine: Int,
        /** The simple name when the erased statement has the shape `name { ... }`, null otherwise. */
        val blockName: String?
    )

    internal
    data class ErasureResult(
        val erasedText: String,
        val erasedStatements: List<ErasedStatement>
    )

    /**
     * Returns [scriptText] with the statements enclosing [errorLocations] (1-based)
     * erased, or null when no located, erasable statement exists or erasing would not
     * change the text.
     *
     * [previouslyErased] holds the character ranges erased by earlier rounds; when the
     * statement found for an error location is (still) contained in a previously erased
     * range, the erasure escalates one statement level up so the enclosing statement is
     * erased instead.
     */
    internal
    fun erase(
        scriptText: String,
        errorLocations: List<ErrorLocation>,
        previouslyErased: List<IntRange>
    ): ErasureResult? {
        if (scriptText.isEmpty()) {
            return null
        }

        val ktFile = parseScript(scriptText)
        val lineStartOffsets = lineStartOffsetsOf(scriptText)

        val statements = errorLocations
            .mapNotNull { statementAt(ktFile, it, scriptText, lineStartOffsets, previouslyErased) }
            .mergeOverlapping()

        if (statements.isEmpty()) {
            return null
        }

        val erasedText = eraseRanges(scriptText, statements.map { it.range })
        if (erasedText == scriptText) {
            return null
        }

        return ErasureResult(erasedText, statements)
    }

    private
    fun statementAt(
        ktFile: KtFile,
        location: ErrorLocation,
        scriptText: String,
        lineStartOffsets: List<Int>,
        previouslyErased: List<IntRange>
    ): ErasedStatement? {
        val offset = offsetOf(location, scriptText, lineStartOffsets) ?: return null
        val leaf = ktFile.findElementAt(offset)
            ?: ktFile.findElementAt((offset - 1).coerceAtLeast(0))
            ?: return null
        val statement = statementOf(leaf) ?: return null
        val target = when {
            isContainedIn(statement, previouslyErased) -> statementOf(statement.parent) ?: return null
            else -> statement
        }
        val textRange = target.textRange
        if (textRange.isEmpty) {
            return null
        }
        return ErasedStatement(
            range = textRange.startOffset until textRange.endOffset,
            firstLine = lineIndexOf(lineStartOffsets, textRange.startOffset) + 1,
            blockName = blockNameOf(target)
        )
    }

    /**
     * The nearest enclosing statement of [element]: a [KtExpression] or [KtDeclaration]
     * directly contained in a [KtBlockExpression], [KtScript] or [KtScriptInitializer].
     */
    private
    fun statementOf(element: PsiElement?): PsiElement? =
        generateSequence(element) { it.parent }.firstOrNull {
            (it is KtExpression || it is KtDeclaration) &&
                (it.parent is KtBlockExpression || it.parent is KtScript || it.parent is KtScriptInitializer)
        }

    private
    fun isContainedIn(statement: PsiElement, previouslyErased: List<IntRange>): Boolean {
        val textRange = statement.textRange
        return previouslyErased.any { range ->
            range.first <= textRange.startOffset && textRange.endOffset - 1 <= range.last
        }
    }

    /**
     * The simple name when [statement] is a call with a trailing lambda, i.e. the
     * statement has the shape `name { ... }`, null otherwise.
     */
    private
    fun blockNameOf(statement: PsiElement): String? =
        (statement as? KtCallExpression)
            ?.takeIf { it.lambdaArguments.isNotEmpty() }
            ?.calleeExpression
            ?.text

    private
    fun offsetOf(location: ErrorLocation, scriptText: String, lineStartOffsets: List<Int>): Int? {
        val lineIndex = location.line - 1
        if (lineIndex !in lineStartOffsets.indices) {
            return null
        }
        val lineStart = lineStartOffsets[lineIndex]
        val lineEnd = lineStartOffsets.getOrNull(lineIndex + 1) ?: scriptText.length
        val offset = lineStart + (location.column - 1).coerceAtLeast(0)
        return offset.coerceIn(lineStart, (lineEnd - 1).coerceAtLeast(lineStart))
    }

    private
    fun lineStartOffsetsOf(text: String): List<Int> {
        val offsets = mutableListOf(0)
        for (i in text.indices) {
            if (text[i] == '\n') {
                offsets.add(i + 1)
            }
        }
        return offsets
    }

    private
    fun lineIndexOf(lineStartOffsets: List<Int>, offset: Int): Int {
        var result = 0
        for (i in lineStartOffsets.indices) {
            if (lineStartOffsets[i] <= offset) {
                result = i
            } else {
                break
            }
        }
        return result
    }

    private
    fun List<ErasedStatement>.mergeOverlapping(): List<ErasedStatement> {
        val merged = mutableListOf<ErasedStatement>()
        for (statement in sortedBy { it.range.first }) {
            val last = merged.lastOrNull()
            if (last != null && statement.range.first <= last.range.last + 1) {
                merged[merged.lastIndex] = ErasedStatement(
                    range = last.range.first..maxOf(last.range.last, statement.range.last),
                    firstLine = last.firstLine,
                    blockName = last.blockName ?: statement.blockName
                )
            } else {
                merged.add(statement)
            }
        }
        return merged
    }

    private
    fun eraseRanges(scriptText: String, ranges: List<IntRange>): String {
        val chars = scriptText.toCharArray()
        for (range in ranges) {
            for (i in range.first..minOf(range.last, chars.size - 1)) {
                if (!chars[i].isWhitespace()) {
                    chars[i] = ' '
                }
            }
        }
        return String(chars)
    }

    internal
    companion object {

        /**
         * Creates an eraser backed by the embedded Kotlin parser (PSI). The parsing
         * environment is created lazily on first use and disposed with [disposable].
         */
        @OptIn(K1Deprecation::class)
        fun usingEmbeddedKotlinParser(disposable: Disposable): LenientStatementEraser {
            val environment by lazy(LazyThreadSafetyMode.NONE) {
                disposable.kotlinCoreEnvironmentFor(parsingConfiguration())
            }
            return LenientStatementEraser { scriptText ->
                PsiFileFactory.getInstance(environment.project)
                    .createFileFromText("script.gradle.kts", KotlinFileType.INSTANCE, scriptText) as KtFile
            }
        }

        @OptIn(CompilerConfiguration.Internals::class)
        private
        fun parsingConfiguration(): CompilerConfiguration =
            CompilerConfiguration.create().apply {
                put(CommonConfigurationKeys.MESSAGE_COLLECTOR_KEY, MessageCollector.NONE)
            }
    }
}
