package com.shetline.lligatures

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.json.json5.Json5Language
import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.ui.LanguageTextField
import com.shetline.lligatures.LigaturesLimitedSettings.CursorMode
import java.awt.BorderLayout
import java.awt.Font
import javax.swing.*

class LigaturesLimitedConfig : Configurable, Disposable {
  private lateinit var wrapper: JPanel
  private lateinit var contexts: JTextField
  private lateinit var cursorMode: JComboBox<CursorMode>
  private lateinit var debug: JCheckBox
  private lateinit var disregarded: JTextField
  private lateinit var json: LanguageTextField
  private lateinit var advancedSettings: JCheckBox

  private val configState
    get() = LigaturesLimitedSettings.instance.state

  override fun isModified(): Boolean {
    if (advancedSettings.isSelected != configState?.advanced)
      return false
    else if (advancedSettings.isSelected)
      return json.text != configState?.json

    return (
      contexts.text != configState?.contexts ||
      cursorMode.selectedItem != configState?.cursorMode ||
      debug.isSelected != configState?.debug ||
      disregarded.text != configState?.disregarded
    )
  }

  override fun getDisplayName(): String {
    return "Ligatures Limited"
  }

  override fun apply() {
    configState?.contexts = contexts.text
    configState?.cursorMode = cursorMode.selectedItem as CursorMode
    configState?.debug = debug.isSelected
    configState?.disregarded = disregarded.text
    configState?.advanced = advancedSettings.isSelected
    configState?.json = json.text

    adjustEnabling()

    ProjectManager.getInstance().openProjects.forEach()
      { project -> DaemonCodeAnalyzer.getInstance(project).restart() }
  }

  private fun adjustEnabling() {
    val advanced = advancedSettings.isSelected

    contexts.isEnabled = !advanced
    cursorMode.isEnabled = !advanced
    debug.isEnabled = !advanced
    disregarded.isEnabled = !advanced
    json.isEnabled = advanced
  }

  override fun createComponent(): JComponent? {
    cursorMode.addItem(CursorMode.OFF)
    cursorMode.addItem(CursorMode.CURSOR)
    cursorMode.addItem(CursorMode.LINE)

    val projectManager: ProjectManager = ProjectManager.getInstance()
    val projects = projectManager.openProjects
    val scheme = EditorColorsManager.getInstance().globalScheme
    val project = if (projects.isNotEmpty()) projects[0] else projectManager.defaultProject
    val parent = json.parent

    parent.remove(json)
    json = LanguageTextField(Json5Language.INSTANCE, project, configState?.json ?: "")
    json.font = Font(scheme.editorFontName, Font.PLAIN, scheme.editorFontSize)
    json.setOneLineMode(false)
    parent.add(BorderLayout.CENTER, json)

    advancedSettings.addActionListener { adjustEnabling() }
    adjustEnabling()

    return wrapper
  }

  override fun reset() {
    contexts.text = configState?.contexts ?: ""
    cursorMode.selectedItem = configState?.cursorMode ?: CursorMode.CURSOR
    debug.isSelected = configState?.debug ?: false
    disregarded.text = configState?.disregarded ?: ""
    advancedSettings.isSelected = configState?.advanced ?: false
    json.text = configState?.json ?: ""

    adjustEnabling()
  }

  override fun dispose() {
    // Nothing to do yet
  }
}
