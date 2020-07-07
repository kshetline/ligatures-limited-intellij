package com.shetline.lligatures

import com.google.gson.*
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.shetline.lligatures.Json5ToJson.Companion.json5toJson
import java.lang.reflect.Type

@State(
  name = "LigaturesLimitedSettings",
  storages = [Storage("ligatures-limited.xml")]
)
class LigaturesLimitedSettings : PersistentStateComponent<LigaturesLimitedSettings.SettingsState> {
  enum class CursorMode { OFF, CURSOR, LINE }

  private var settingsState: SettingsState = SettingsState()

  override fun getState(): SettingsState? {
    return settingsState
  }

  override fun loadState(state: SettingsState) {
    settingsState = state
  }

  class SettingsState {
    var contexts = ""
    var cursorMode = CursorMode.CURSOR
    var debug = false
    var json = """{
  "disregarded": "ff fi fl ffi ffl", // These ligatures will neither be actively enabled nor suppressed
  "languages": {
    "markdown": true // All ligatures will be enabled in all contexts for Markdown
  },
  "ligaturesByContext": {
    "number": {
      "ligatures": "+ 0xF 0o7 0b1"
    }
  }
}"""
  }

  data class ContextConfig (
    var debug: Boolean,
    var ligatures: MutableSet<String>,
    var ligaturesListedAreEnabled: Boolean
  )

  data class LanguageConfig (
    var contexts: MutableSet<String>,
    var debug: Boolean,
    var inherit: String,
    var ligatures: MutableSet<String>,
    var ligaturesListedAreEnabled: Boolean,
    var ligaturesByContext: MutableMap<String, ContextConfig>? = null
  )

  data class GlobalConfig (
      var contexts: MutableSet<ElementCategory> = mutableSetOf(ElementCategory.OPERATOR, ElementCategory.PUNCTUATION,
        ElementCategory.COMMENT_MARKER),
      var debug: Boolean = false,
      var disregarded: MutableSet<String> = mutableSetOf(),
      var languages: MutableMap<String, LanguageConfig> = mutableMapOf(),
      var ligatures: MutableSet<String> = mutableSetOf("ff", "fi", "fl", "ffi", "ffl", "0xF", "0o7", "0b1", "9x9"),
      var ligaturesByContext: MutableMap<String, ContextConfig> = mutableMapOf(),
      var ligaturesListedAreEnabled: Boolean = false,
      var globalLigatures: MutableSet<String> = mutableSetOf()
  )

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

    init {
      gson.registerTypeAdapter(GlobalConfig::class.java, MyDeserializer())
    }

    val instance: LigaturesLimitedSettings
      get() = ServiceManager.getService(LigaturesLimitedSettings::class.java)

    fun parseJson(json5: String): GlobalConfig {
      return gson.create().fromJson<GlobalConfig>(json5toJson(json5), GlobalConfig::class.java)
    }

    private class MyDeserializer : JsonDeserializer<GlobalConfig> {
      override fun deserialize(elem: JsonElement?, type: Type?, context: JsonDeserializationContext?): GlobalConfig {
        if (elem == null || !elem.isJsonObject)
          throw JsonParseException("JSON object expected for configuration")

        val result = GlobalConfig()
        val root = elem as JsonObject
        val globalAddBacks = mutableSetOf<String>()
        var languages: JsonElement? = null
        var ligaturesByContext: JsonElement? = null

        for (key in root.keySet()) {
          val child = root.get(key)

          when (key) {
            "contexts" -> applyContextList(child, result.contexts, key)
            "debug" -> result.debug = if (!child.isBoolean)
                throw JsonParseException("JSON boolean expected for 'debug'")
              else
                child.asBoolean
            "disregarded" -> result.disregarded.addAll(getStringArray(child, key))
            "languages" -> languages = if (child.isJsonObject) child else
              throw JsonParseException("JSON object expected for 'languages' configuration")
            "ligatures" -> result.ligaturesListedAreEnabled = applyLigatureList(child, result.ligatures,
              result.ligaturesListedAreEnabled, globalAddBacks, key)
            "ligaturesByContext" -> ligaturesByContext = if (child.isJsonObject) child else
              throw JsonParseException("JSON object expected for 'ligaturesByContext' configuration")
            else -> throw JsonParseException("Unknown JSON field '$key'")
          }
        }

        if (languages != null)
          deserializeLanguages(languages, result.languages)

        if (ligaturesByContext != null)
          deserializeLigaturesByContext(ligaturesByContext, result.ligaturesByContext)

        result.globalLigatures.addAll(baseLigatures)
        result.globalLigatures.removeAll(result.disregarded)
        result.globalLigatures.addAll(globalAddBacks)

        return result
      }
    }

    private fun deserializeLanguages(elem: JsonElement, languages: MutableMap<String, LanguageConfig>) {
    }

    private fun deserializeLigaturesByContext(elem: JsonElement, contexts: MutableMap<String, ContextConfig>) {
    }

    private fun getStringArray(elem: JsonElement, name: String): Array<String> {
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
        return elem.asString.trim().split(Regex("""\s+""")).toTypedArray()
      else
        throw JsonParseException("JSON string or string array expected")
    }

    private fun applyLigatureList(elem: JsonElement, ligatureList: MutableSet<String>, listedEnabled: Boolean,
                                  globalAddBacks: MutableSet<String>, name: String):
        Boolean {
      val specs = getStringArray(elem, name)
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

    private fun applyContextList(elem: JsonElement, contextList: MutableSet<ElementCategory>, name: String) {
      val specs = getStringArray(elem, name)
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
