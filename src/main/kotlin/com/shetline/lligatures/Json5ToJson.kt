package com.shetline.lligatures

class Json5ToJson {
  companion object {
    fun json5toJson(j5: String): String {
      var index = 0
      var match: MatchResult? = null
      val parts = mutableListOf<String>()

      // Separate quoted and non-quoted parts
      while ({ match = Regex("""['"]""").find(j5, index); match }() != null) {
        val quote = match!!.value
        val startIndex = match!!.range.first
        var stringIndex = startIndex + 1
        var stringMatch: MatchResult? = null
        val stringParts = mutableListOf<String>()

        parts.add(j5.substring(index, startIndex))

        // Find escaped characters or closing quote
        while ({ stringMatch = Regex("\\\\(\\r\\n|\\r|\\n|.)|${quote}|\"").find(j5, stringIndex); stringMatch }() != null) {
          val qOrE = stringMatch!!.value

          stringParts.add(j5.substring(stringIndex, stringMatch!!.range.first))
          stringIndex = stringMatch!!.range.last + 1

          if (qOrE == quote)
            break
          else if (qOrE == "\"")
            stringParts.add("\\\"")
          else if (qOrE == "\\\r\n")
            stringParts.add("""\r\n""")
          else if (qOrE == "\\\r")
            stringParts.add("""\r""")
          else if (qOrE == "\\\n")
            stringParts.add("""\n""")
          else
            stringParts.add(qOrE)
        }

        parts.add("\"${stringParts.joinToString("")}\"")
        index = if (stringMatch == null) j5.length else stringIndex
      }

      if (index < j5.length - 1)
        parts.add(j5.substring(index))

      for (i in 0 until parts.size step 2) {
        parts[i] = parts[i]
          // Remove comments
          .replace(Regex("""\s*//.*$""", RegexOption.MULTILINE), "")
          .replace(Regex("""\s*/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
          // Quote unquoted identifiers -- regex here is just good enough for needed identifiers, not all valid ones
          .replace(Regex("""\b([a-zA-Z_]+)(?=\s*:)"""), "\"$1\"")
          // Clean up trailing commas
          .replace(Regex(""",(?=\s*[]}])"""), "")
      }

      return parts.joinToString("")
    }
  }
}
