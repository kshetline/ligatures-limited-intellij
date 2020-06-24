package com.shetline.lligatures

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.json.json5.Json5Language
import com.intellij.openapi.Disposable
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.ui.LanguageTextField
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextField

class LigaturesLimitedConfig(private val project: Project) : Configurable, Disposable {
  private lateinit var wrapper: JPanel
  private lateinit var panel: JPanel
  private lateinit var contexts: JTextField
  private lateinit var debug: JCheckBox
  private lateinit var disregarded: JTextField
  private lateinit var json: LanguageTextField
  private lateinit var advancedSettings: JCheckBox

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
    ProjectManager.getInstance().openProjects.forEach()
      { project -> DaemonCodeAnalyzer.getInstance(project).restart() }
  }

  override fun createComponent(): JComponent? {
    return wrapper
  }

  private fun createUIComponents() {
    json = LanguageTextField(Json5Language.INSTANCE, project, "")
  }

  override fun reset() {
    debug.isSelected = configState?.debug ?: false
  }

  override fun dispose() {
    // Nothing to do yet
  }
}
