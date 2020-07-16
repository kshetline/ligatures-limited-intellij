@file:Suppress("SpellCheckingInspection")
package com.shetline.lligatures

import com.intellij.psi.PsiElement

enum class ElementCategory {
  ATTRIBUTE_NAME,
  ATTRIBUTE_VALUE,
  BLOCK_COMMENT,
  COMMENT_MARKER,
  CONSTANT,
  IDENTIFIER,
  KEYWORD,
  LINE_COMMENT,
  NUMBER,
  OPERATOR,
  OTHER,
  PUNCTUATION,
  REGEXP,
  STRING,
  TAG,
  TEXT,
  WHITESPACE
}

class ElementCategorizer {
  companion object {
    private val opRegex = Regex("""[!-/:-@\[-^_`{-~]+""")

    fun categoryFor(element: PsiElement, matchText: String, matchIndex: Int, count: Int = 0): ElementCategory {
      // Replace underscores with tildes so they act as regex word boundaries.
      val type = element.node.elementType.toString().replace('_', '~').toLowerCase()
        .replace(Regex(""".*:"""), "")
      var isWhitespace = false

      when {
        Regex("""\b(string|escape~sequence)\b""").containsMatchIn(type) -> return ElementCategory.STRING
        Regex("""\bregexp\b""").containsMatchIn(type) -> return ElementCategory.REGEXP
        Regex("""\bkeyword\b""").containsMatchIn(type) -> return ElementCategory.KEYWORD
        Regex("""\b(identifier|css~class|css~ident)\b""").containsMatchIn(type) -> return ElementCategory.IDENTIFIER
        Regex("""\bwhite~space\b""").containsMatchIn(type) -> isWhitespace = true
        Regex("""\b(float|integer|numeric|number)\b""").containsMatchIn(type) -> return ElementCategory.NUMBER
        Regex("""\bconstant\b""").containsMatchIn(type) -> return ElementCategory.CONSTANT
        Regex("""\b(text|xml~data~characters|code~fence~content)\b""").containsMatchIn(type) -> return ElementCategory.TEXT
        Regex("""\b(tag~start|tag~end|comma|lpar|rpar|lbrace|rbrace|semicolon|""" +
          """lbracket|rbracket)\b""").containsMatchIn(type) ||
          Regex("""['"`]""").matches(matchText) ||
          element.language.id === "XML" && Regex("""[</>]{1,2}""").matches(matchText)
            -> return ElementCategory.PUNCTUATION
      }

      val text = element.text
      val elementIndex = element.textOffset
      val operatorLike = matchText.length < 8 && opRegex.matches(matchText)
      val commentLike = Regex("""\b(comment|shebang)\b""").containsMatchIn(type)
      val blockComment = commentLike && Regex("""\b(block~comment|c~style~comment)\b""").containsMatchIn(type)

      if (!isWhitespace && operatorLike && commentLike) {
        return if (elementIndex == matchIndex ||
                   (elementIndex + text.length - matchText.length == matchIndex && blockComment))
          ElementCategory.COMMENT_MARKER
        else if (blockComment || text.startsWith("/*"))
          ElementCategory.BLOCK_COMMENT
        else
          ElementCategory.LINE_COMMENT
      }

      when {
        (commentLike) -> return ElementCategory.LINE_COMMENT
        (operatorLike) ->  return ElementCategory.OPERATOR
      }

      if (element.parent != null) {
        when (element.parent.node.elementType.toString().replace('_', '~').toLowerCase()) {
          "xml~doctype" ->
            return when {
              matchText.startsWith("<!") -> ElementCategory.TAG
              Regex("""^['"]""").containsMatchIn(matchText) -> ElementCategory.ATTRIBUTE_VALUE
              else -> ElementCategory.ATTRIBUTE_NAME
            }
          "xml~attribute" -> return ElementCategory.ATTRIBUTE_NAME
          "xml~attribute~value" -> return ElementCategory.ATTRIBUTE_VALUE
          "html~tag" -> return ElementCategory.TAG
          "xml~tag" -> return ElementCategory.TAG
          "xml~text" -> return ElementCategory.TEXT
        }
      }

      if (isWhitespace)
        return ElementCategory.WHITESPACE

      if (count < 2 && element.parent != null)
        return categoryFor(element.parent, matchText, matchIndex, count + 1)

      return ElementCategory.OTHER
    }
  }
}
