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
  private var settingsState: SettingsState = SettingsState()

  override fun getState(): SettingsState? {
    return settingsState
  }

  override fun loadState(state: SettingsState) {
    settingsState = state
  }

  class SettingsState {
    var debug = false
  }

  companion object {
    val instance: LigaturesLimitedSettings
      get() = ServiceManager.getService(LigaturesLimitedSettings::class.java)
  }
}
