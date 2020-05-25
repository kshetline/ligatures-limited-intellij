package com.shetline.lligatures

import com.intellij.openapi.Disposable
import com.intellij.openapi.options.Configurable
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextField

class LigaturesLimitedConfig : Configurable, Disposable {
  private lateinit var panel: JPanel
  private lateinit var contexts: JTextField
  private lateinit var debug: JCheckBox
  private lateinit var disregarded: JTextField

  private val configState
    get() = LigaturesLimitedSettings.instance.state

  override fun isModified(): Boolean {
    return debug.isSelected != configState?.debug
  }

  override fun getDisplayName(): String {
    return "Ligatures Limited"
  }

  override fun apply() {
    configState?.debug = debug.isSelected
  }

  override fun createComponent(): JComponent? {
    return panel
  }

  override fun reset() {
    debug.isSelected = configState?.debug ?: false
  }

  override fun dispose() {
    // Nothing to do yet
  }
}
