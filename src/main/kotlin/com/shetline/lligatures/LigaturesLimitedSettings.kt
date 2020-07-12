package com.shetline.lligatures

import com.google.gson.*
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.shetline.json.Json5ToJson.Companion.json5toJson
import java.lang.reflect.Type

@State(
  name = "LigaturesLimitedSettings",
  storages = [Storage("ligatures-limited.xml")]
)
class LigaturesLimitedSettings : PersistentStateComponent<LigaturesLimitedSettings.SettingsState> {
  enum class CursorMode { OFF, CURSOR, LINE }

  private var settingsState: SettingsState = SettingsState()
  private lateinit var extSettingsState: ExtSettingsState

  init {
    loadState(SettingsState())
  }

  override fun getState(): SettingsState? {
    return settingsState
  }

  val extState: ExtSettingsState? get() = extSettingsState

  override fun loadState(state: SettingsState) {
    settingsState = state
    extSettingsState = ExtSettingsState(state)

    try {
      extSettingsState.config = parseJson(settingsState.json)
    }
    catch (e: Exception) {
      settingsState.json = defaultJson
      extSettingsState.config = parseJson(settingsState.json)
    }

    extSettingsState.globalMatchLigatures = ligaturesToRegex(extSettingsState.config!!.globalLigatures)
  }

  open class SettingsState {
    var cursorMode = CursorMode.CURSOR
    var debug = false
    var json = defaultJson
  }

  class ExtSettingsState(state: SettingsState) : SettingsState() {
    var config: GlobalConfig? = null
    var globalMatchLigatures: Regex? = null

    init {
      cursorMode = state.cursorMode
      debug = state.debug
      json = state.json
    }
  }

  open class ContextConfig (
    var debug: Boolean = false,
    var ligatures: MutableSet<String> = mutableSetOf(),
    var ligaturesMatch: Regex = Regex("\\x00"),
    var ligaturesListedAreEnabled: Boolean = false
  )

  open class LanguageConfig (
    var contexts: MutableSet<ElementCategory> = mutableSetOf(),
    var fullOnOrOff: Boolean? = null,
    var inherit: String? = null,
    var ligaturesByContext: MutableMap<ElementCategory, ContextConfig> = mutableMapOf()
  ) : ContextConfig()

  class GlobalConfig (
      var disregarded: MutableSet<String> = mutableSetOf(),
      var languages: MutableMap<String, LanguageConfig> = mutableMapOf(),
      var globalLigatures: MutableSet<String> = mutableSetOf()
  ) : LanguageConfig()

  companion object {
    private val gson = GsonBuilder()
    private val baseLigatures = ("""

  .= ..= .- := =:= == != === !== =/= <-< <<- <-- <- <-> -> --> ->> >-> <=< <<= <== <=> => ==> =!= =:=
  =>> >=> >>= >>- >- <~> -< -<< =<< <~~ <~ ~~ ~> ~~> <<< << <= <> >= >> >>> {. {| [| <: :> |] |} .}
  <||| <|| <| <|> |> ||> |||> <$ <$> $> <+ <+> +> <* <*> *> \\ \\\ \* /* */ /// // </ <!-- </> --> />
  ;; :: ::: .. ... ..< !! ?? %% && <:< || ?. ?: ++ +++ -- --- ** *** ~= ~- www ff fi fl ffi ffl
  -~ ~@ ^= ?= /= /== |= ||= #! ## ### #### #{ #[ ]# #( #? #_ #: #= #_( #{} =~ !~ 9x9 0xF 0o7 0b1
  |- |-- -| --| |== =| ==| /==/ ==/ /=/ <~~> =>= =<= :>: :<: /\ \/ _|_ ||- :< >: ::=
  <==== ==== ====> <====> <--- ---> <---> |--- ---| |=== ===| /=== ===/ <~~~ ~~~> <~~~>

""").trim().split(Regex("""\s+"""))

  private val patternSubstitutions = hashMapOf<String, String?>(
    "####" to "#{4,}",
    "<====" to "<={4,}",
    "====" to "={4,}",
    "====>" to "={4,}>",
    "<====>" to "<={4,}>",
    "<---" to "<-{3,}",
    "--->" to "-{3,}>",
    "<--->" to "<-{3,}>",
    "|---" to "\\|-{3,}",
    "---|" to "-{3,}\\|",
    "|===" to "\\|={3,}",
    "===|" to "={3,}\\|",
    "/===" to "\\/={3,}",
    "===/" to "={3,}\\/",
    "<~~~" to "<~{3,}",
    "~~~>" to "~{3,}>",
    "<~~~>" to "<~{3,}>",
    "0xF" to "0x[0-9a-fA-F]",
    "0o7" to "0o[0-7]",
    "0b1" to "(?<![0-9a-fA-FxX])0b[01]",
    "9x9" to "\\dx\\d"
  )

  private val connectionTweaks = hashMapOf<String, Regex?>(
    "-" to Regex("""[-<>|]+"""),
    "=" to Regex("""[=<>|]+"""),
    "~" to Regex("""[~<>|]+""")
  )

  private val charsNeedingRegexEscape = Regex("""[-\[\]/{}()*+?.\\^$|]""")

  private fun ligaturesToRegex(ligatures: Set<String>): Regex {
    val sorted = ligatures.toList().sortedWith(Comparator { a, b -> b.length - a.length })
    val escaped = sorted.map { lig -> patternSubstitutions[lig] ?: generatePattern(lig, ligatures.toSet()) }

    return Regex(escaped.joinToString("|"))
  }

  private fun generatePattern(ligature: String, disregarded: Set<String>): String {
    val leadingSet = HashSet<String>()
    val trailingSet = HashSet<String>()
    val len = ligature.length

    // Give triangles (◁, ▷) and diamonds (♢) formed using < and > higher priority.
    if (Regex("""^[>|]""").containsMatchIn(ligature))
      leadingSet.add("<")

    if (Regex("""[|<]$""").containsMatchIn(ligature))
      trailingSet.add(">")

    for (other in disregarded) {
      if (other.length <= len)
        break

      var index = 0

      while ({ index = other.indexOf(ligature, index); index }() >= 0) {
        if (index > 0)
          leadingSet.add(other[index - 1].toString())

        if (index + len < other.length)
          trailingSet.add(other[index + len].toString())

        ++index
      }
    }

    // Handle ligatures which are supposed to blend with combinatory arrow ligatures
    connectionTweaks.forEach { (key, pattern) ->
      if (ligature.startsWith(key) && pattern!!.matches(ligature))
        leadingSet.add(key)

      if (ligature.endsWith(key) && pattern!!.matches(ligature))
        trailingSet.add(key)
    }

    // Handle ligatures which are supposed to be connective with other ligatures

    val leading = createLeadingOrTrailingClass(leadingSet)
    val trailing = createLeadingOrTrailingClass(trailingSet)
    var pattern = ""

    if (!leading.isNullOrEmpty()) // Create negative lookbehind, so this ligature isn't matched if preceded by these characters.
      pattern += "(?<!$leading)"

    pattern += escapeForRegex(ligature)

    if (!trailing.isNullOrEmpty()) // Create negative lookahead, so this ligature isn't matched if followed by these characters.
      pattern += "(?!$trailing)"

    return pattern
  }

  private fun createLeadingOrTrailingClass(set: MutableSet<String>): String? {
    if (set.isEmpty())
      return ""
    else if (set.size == 1)
      return escapeForRegex(set.elementAt(0))

    var klass = "["

    // If present, dash (`-`) must go first, in case it's the start of a [] class pattern
    if (set.contains("-")) {
      klass += "-"
      set.remove("-")
    }

    set.forEach { c -> klass += escapeForRegex(c) }

    return "$klass]"
  }

  private fun escapeForRegex(s: String): String {
    return s.replace(charsNeedingRegexEscape) { m -> "\\" + m.value }
  }

  const val defaultJson = """{
  "contexts": "operator punctuation comment_marker",
  "disregarded": "ff fi fl ffi ffl", // These ligatures will neither be actively enabled nor suppressed
  "languages": {
    "markdown": true // All ligatures will be enabled in all contexts for Markdown
  },
  "ligatures": "- 0xF 0o7 0b1 9x9", // These ligatures are suppressed
  "ligaturesByContext": {
    "number": "+ 0xF 0o7 0b1"
  }
}"""

    init {
      gson.registerTypeAdapter(GlobalConfig::class.java, MyDeserializer())
    }

    val instance: LigaturesLimitedSettings
      get() = ServiceManager.getService(LigaturesLimitedSettings::class.java)

    fun parseJson(json5: String): GlobalConfig {
      return gson.create().fromJson<GlobalConfig>(json5toJson(json5), GlobalConfig::class.java)
    }

    private class MyDeserializer : JsonDeserializer<GlobalConfig> {
      private val globalAddBacks = mutableSetOf<String>()

      override fun deserialize(elem: JsonElement?, type: Type?, context: JsonDeserializationContext?): GlobalConfig {
        return deserializeAux(GlobalConfig(), "configuration", elem)
      }

      private fun <T : LanguageConfig> deserializeAux(result: T, name: String, elem: JsonElement?): T {
        if (elem == null || !elem.isJsonObject)
          throw JsonParseException("JSON object expected for $name")

        val root = elem as JsonObject
        var languages: JsonElement? = null
        var ligaturesByContext: JsonElement? = null

        for (key in root.keySet()) {
          val child = root[key]

          if (result !is GlobalConfig && setOf("disregarded", "languages").contains(key) ||
              result is GlobalConfig && setOf("debug", "inherit").contains(key))
            throw JsonParseException("Unknown JSON field '$key' in '$name'")

          when (key) {
            "contexts" -> applyContextList(child, result.contexts, key)
            "debug" -> result.debug = if (!child.isBoolean)
                throw JsonParseException("JSON boolean expected for 'debug'")
              else
                child.asBoolean
            "disregarded" -> if (result is GlobalConfig) result.disregarded.addAll(getStringArray(child, key, false))
            "inherit" -> result.inherit = if (child.isString) child.asString else
              throw JsonParseException("JSON string expected for 'inherit'")
            "languages" -> languages = if (child.isJsonObject) child else
              throw JsonParseException("JSON object expected for 'languages' configuration")
            "ligatures" -> result.ligaturesListedAreEnabled = applyLigatureList(child, result.ligatures,
              result.ligaturesListedAreEnabled, globalAddBacks, key)
            "ligaturesByContext" -> ligaturesByContext = if (child.isJsonObject) child else
              throw JsonParseException("JSON object expected for 'ligaturesByContext' configuration")
            else -> throw JsonParseException("Unknown JSON field '$key'")
          }
        }

        if (languages != null && result is GlobalConfig)
          deserializeLanguages(languages, result.languages, result)

        if (ligaturesByContext != null)
          deserializeLigaturesByContext(ligaturesByContext, result.ligaturesByContext, result)

        if (result is GlobalConfig) {
          result.globalLigatures.addAll(baseLigatures)
          result.globalLigatures.removeAll(result.disregarded)
          result.globalLigatures.addAll(globalAddBacks)
        }

        return result
      }

      private fun deserializeLanguages(elem: JsonElement, languages: MutableMap<String, LanguageConfig>,
          baseConfig: LanguageConfig) {
        if (!elem.isJsonObject)
          throw JsonParseException("JSON object expected for languages")

        val root = elem as JsonObject
        val resolved = mutableSetOf<String>()

        for (i in 1..root.size()) {
          var pending = 0

          for (key in root.keySet()) {
            if (resolved.contains(key))
              continue

            val child = root[key]

            if (!child.isBoolean && !child.isJsonObject)
              throw JsonParseException("Language settings must be an object or a boolean")

            val language = LanguageConfig()

            if (child.isJsonObject) {
              val inheritance = (child as JsonObject)["inherit"]
              val parent = if (inheritance?.isString == true) languages[inheritance.asString] else baseConfig

              if (parent != null) {
                resolved.add(key)
                language.contexts.addAll(parent.contexts)
                language.debug = parent.debug
                language.ligatures.addAll(parent.ligatures)
                language.ligaturesMatch = ligaturesToRegex(language.ligatures)
                language.ligaturesListedAreEnabled = parent.ligaturesListedAreEnabled
                language.ligaturesByContext = parent.ligaturesByContext.toMutableMap()

                deserializeAux(language, key, child)
              }
              else {
                ++pending
                continue
              }
            }
            else {
              resolved.add(key)
              language.fullOnOrOff = child.asBoolean
            }

            for (languageName in getStringArray(key, true))
              languages[languageName] = language
          }

          if (pending == 0)
            break
        }

        if (resolved.size != root.size()) {
          val unresolved = root.keySet().minus(resolved).joinToString(", ")
          throw JsonParseException("Unresolved language inheritance for: $unresolved")
        }
      }

      private fun deserializeLigaturesByContext(elem: JsonElement, contexts: MutableMap<ElementCategory, ContextConfig>,
          baseConfig: LanguageConfig) {
        if (!elem.isJsonObject)
          throw JsonParseException("JSON object is expected for ligaturesByContext")

        val root = elem as JsonObject

        for (key in root.keySet()) {
          val contextInfo0 = root[key]

          if (!contextInfo0.isJsonObject && !contextInfo0.isString)
            throw JsonParseException("Context settings must be an object or a string")

          val contextConfig = ContextConfig()

          contextConfig.debug = baseConfig.debug
          contextConfig.ligatures.addAll(baseConfig.ligatures)
          contextConfig.ligaturesMatch = ligaturesToRegex(contextConfig.ligatures)
          contextConfig.ligaturesListedAreEnabled = baseConfig.ligaturesListedAreEnabled

          if (contextInfo0.isString) {
            contextConfig.ligaturesListedAreEnabled = applyLigatureList(contextInfo0.asString, contextConfig.ligatures,
              contextConfig.ligaturesListedAreEnabled, globalAddBacks, key)
          }
          else {
            val contextInfo = contextInfo0 as JsonObject

            for (context in contextInfo.keySet()) {
              val child = contextInfo[context]

              when (context) {
                "debug" -> contextConfig.debug = if (!child.isBoolean)
                    throw JsonParseException("JSON boolean expected for 'debug'")
                  else
                    child.asBoolean
                "ligatures" -> contextConfig.ligaturesListedAreEnabled = applyLigatureList(child, contextConfig.ligatures,
                  contextConfig.ligaturesListedAreEnabled, globalAddBacks, key)
                else -> throw JsonParseException("Unknown JSON field '$key'")
              }
            }
          }

          for (contextName in getStringArray(key, true)) {
            try {
              val category = ElementCategory.valueOf(contextName.toUpperCase())

              contexts[category] = contextConfig
            }
            catch (e: Exception) {
              throw JsonParseException("Invalid context name '$contextName'")
            }
          }
        }
      }
    }

    private fun getStringArray(elem: Any, name: String, splitOnComma: Boolean): Array<String> {
      if (elem is String)
        return getStringArray(elem, splitOnComma)

      elem as JsonElement

      if (!elem.isJsonPrimitive && !elem.isJsonArray)
        throw JsonParseException("JSON string or string array expected")

      if (elem.isJsonArray) {
        val result = arrayListOf<String>()

        for (item in (elem as JsonArray).asIterable()) {
          if (item.isString)
            result.add(item.asString)
          else
            throw JsonParseException("Values in '$name' array must be strings")
        }

        return result.toTypedArray()
      }
      else if (elem.isString)
        return getStringArray(elem.asString, splitOnComma)
      else
        throw JsonParseException("JSON string or string array expected")
    }

    private fun getStringArray(s: String, splitOnComma: Boolean): Array<String> {
      return s.trim().split(if (splitOnComma) Regex("""\s*?[,\s]\s*""") else Regex("""\s+""")).toTypedArray()
    }

    private fun applyLigatureList(elem: Any, ligatureList: MutableSet<String>, listedEnabled: Boolean,
                                  globalAddBacks: MutableSet<String>, name: String):
        Boolean {
      val specs = getStringArray(elem, name, false)
      var listedAreEnabled = listedEnabled
      var addToList = false
      var removeFromList = false

      for (spec0 in specs) {
        var spec = spec0

        if (spec.length == 1) {
          spec = spec.toUpperCase()

          if (spec == "+") {
            removeFromList = !listedAreEnabled
            addToList = !removeFromList
          }
          else if (spec == "-") {
            removeFromList = listedAreEnabled
            addToList = !removeFromList
          }
          else if (spec == "0" || spec == "O") {
            ligatureList.clear()
            addToList = true
            listedAreEnabled = true
            removeFromList = false
          }
          else if (spec == "X") {
            ligatureList.clear()
            addToList = true
            removeFromList = false
            listedAreEnabled = false
          }
          else
            throw JsonParseException("Invalid ligature specification")
        }
        else if (spec.length > 1) {
          globalAddBacks.add(spec)

          if (addToList)
            ligatureList.add(spec)
          else if (removeFromList)
            ligatureList.remove(spec)
        }
      }

      return listedAreEnabled
    }

    private fun applyContextList(elem: Any, contextList: MutableSet<ElementCategory>, name: String) {
      val specs = getStringArray(elem, name, true)
      var enable = true

      for (spec0 in specs) {
        val spec = spec0.toUpperCase()

        if (spec.length == 1) {
          enable = if (spec == "+")
            true
          else if (spec == "-")
            false
          else if (spec == "0" || spec == "O") {
            contextList.clear()
            true
          }
          else
            throw JsonParseException("Invalid context specification")
        }
        else if (spec.length > 1) {
          try {
            val context = ElementCategory.valueOf(spec)

            if (enable)
              contextList.add(context)
            else
              contextList.remove(context)
          }
          catch (e: Exception) {
            throw JsonParseException("Invalid context name '$spec'")
          }
        }
      }
    }

    private val JsonElement.isBoolean get(): Boolean = this.isJsonPrimitive && (this as JsonPrimitive).isBoolean
    private val JsonElement.isString get(): Boolean = this.isJsonPrimitive && (this as JsonPrimitive).isString
  }
}
