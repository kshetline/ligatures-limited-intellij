package com.shetline.lligatures

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@State(
  name = "LigaturesLimitedSettings",
  storages = [Storage("ligatures-limited.xml")]
)
open class LigaturesLimitedSettings : PersistentStateComponent<LigaturesLimitedSettings.SettingsState> {
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
    "markdown": true // All ligatures enabled in all contexts for Markdown
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
    var ligatures: HashSet<String>,
    var ligaturesListedAreEnabled: Boolean
  )

  data class LigatureConfig (
    var contexts: HashSet<String>,
    var debug: Boolean,
    var ligatures: HashSet<String>,
    var ligaturesByContext: HashMap<String, ContextConfig>,
    var ligaturesListedAreEnabled: Boolean,
    var selectionMode: LigaturesLimitedSettings.CursorMode
  )

  companion object {
    val instance: LigaturesLimitedSettings
      get() = ServiceManager.getService(LigaturesLimitedSettings::class.java)
  }
}
