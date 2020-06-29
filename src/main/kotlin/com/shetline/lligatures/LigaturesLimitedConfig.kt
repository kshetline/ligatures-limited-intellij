package com.shetline.lligatures

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.json.json5.Json5Language
import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.ui.LanguageTextField
import java.awt.Font
import javax.swing.*

class LigaturesLimitedConfig : Configurable, Disposable {
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
    return (
      debug.isSelected != configState?.debug ||
      json.text != configState?.json
    )
  }

  override fun getDisplayName(): String {
    return "Ligatures Limited"
  }

  override fun apply() {
    configState?.debug = debug.isSelected
    configState?.json = json.text

    ProjectManager.getInstance().openProjects.forEach()
      { project -> DaemonCodeAnalyzer.getInstance(project).restart() }
  }

  override fun createComponent(): JComponent? {
    return wrapper
  }

  private fun createUIComponents() {
    val projectManager: ProjectManager = ProjectManager.getInstance()
    val projects = projectManager.openProjects
    val scheme = EditorColorsManager.getInstance().globalScheme
    val project = if (projects.isNotEmpty()) projects[0] else projectManager.defaultProject

    SwingUtilities.invokeAndWait {
      json = LanguageTextField(Json5Language.INSTANCE, project, configState?.json ?: "")
      json.font = Font(scheme.editorFontName, Font.PLAIN, scheme.editorFontSize)
      json.setOneLineMode(false)
    }
  }

  override fun reset() {
    debug.isSelected = configState?.debug ?: false

    if (json != null)
      json.text = configState?.json ?: ""
  }

  override fun dispose() {
    // Nothing to do yet
  }
}
