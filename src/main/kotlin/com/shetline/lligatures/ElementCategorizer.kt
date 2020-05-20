@file:Suppress("SpellCheckingInspection")

package com.shetline.lligatures

import com.intellij.psi.PsiElement

enum class ElementCategory {
  COMMENT,
  COMMENT_MARKER,
  CONSTANT,
  IDENTIFIER,
  KEYWORD,
  NUMBER,
  OPERATOR,
  OTHER,
  STRING,
  TEXT,
  WHITESPACE
}

class ElementCategorizer {
  companion object {

    fun categoryFor(element: PsiElement, matchText: String, matchIndex: Int): ElementCategory {
      // Replace underscores with tildes so they act as regex word boundaries.
      val type = element.node.elementType.toString().replace('_', '~').toLowerCase()

      return when {
        Regex("""\bstring\b""").containsMatchIn(type) -> ElementCategory.STRING
        Regex("""\bkeyword\b""").containsMatchIn(type) -> ElementCategory.KEYWORD
        Regex("""\bidentifier\b""").containsMatchIn(type) -> ElementCategory.IDENTIFIER
        Regex("""\bwhitespace\b""").containsMatchIn(type) -> ElementCategory.WHITESPACE
        Regex("""\b(float|integer|number)\b""").containsMatchIn(type) -> ElementCategory.NUMBER
        Regex("""\bconstant\b""").containsMatchIn(type) -> ElementCategory.CONSTANT
        Regex("""\btext\b""").containsMatchIn(type) -> ElementCategory.TEXT
        else -> {
          val text = element.text
          val elementIndex = element.textOffset;
          val operatorLike = matchText.length < 8 && Regex("""^[!-\/:-@\[-^`{-~]+$""").matches(matchText)
          val commentLike = Regex("""\bcomment\b""").containsMatchIn(type)

          if (operatorLike && commentLike) {
            return if (elementIndex == matchIndex ||
                       (elementIndex + text.length - matchText.length == matchIndex &&
                        Regex("""\b(block~comment|c~style~comment)\b""").containsMatchIn(type)))
              ElementCategory.COMMENT_MARKER
            else
              ElementCategory.COMMENT
          }

          return when {
            (commentLike) -> ElementCategory.COMMENT
            (operatorLike)->  ElementCategory.OPERATOR
            else -> ElementCategory.OTHER
          }
        }
      }
    }
  }
}
