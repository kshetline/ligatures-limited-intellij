package com.shetline.lligatures

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.Disposable
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.ProjectManager
import com.intellij.ui.LanguageTextField
import com.shetline.json.JsonEditor
import com.shetline.lligatures.LigaturesLimitedSettings.Companion.parseJson
import com.shetline.lligatures.LigaturesLimitedSettings.CursorMode
import com.shetline.lligatures.LigaturesLimitedSettings.SettingsState
import java.awt.BorderLayout
import javax.swing.*

class LigaturesLimitedConfig : Configurable, Disposable {
  private lateinit var wrapper: JPanel
  private lateinit var cursorMode: JComboBox<CursorMode>
  private lateinit var debug: JCheckBox
  private lateinit var jsonConfig: LanguageTextField
  private lateinit var restoreDefaults: JButton

  private val configState
    get() = LigaturesLimitedSettings.instance.state

  override fun isModified(): Boolean {
    return (
      cursorMode.selectedItem != configState?.cursorMode ||
      debug.isSelected != configState?.debug ||
      jsonTrim(jsonConfig.text) != jsonTrim(configState?.json)
    )
  }

  override fun getDisplayName(): String {
    return "Ligatures Limited"
  }

  override fun apply() {
    try {
      println(parseJson(jsonConfig.text))
    }
    catch (e: Exception) {
      throw ConfigurationException(e.message)
    }

    configState?.cursorMode = cursorMode.selectedItem as CursorMode
    configState?.debug = debug.isSelected
    configState?.json = jsonTrim(jsonConfig.text)!!

    ProjectManager.getInstance().openProjects.forEach()
      { project -> DaemonCodeAnalyzer.getInstance(project).restart() }
  }

  override fun createComponent(): JComponent? {
    cursorMode.addItem(CursorMode.OFF)
    cursorMode.addItem(CursorMode.CURSOR)
    cursorMode.addItem(CursorMode.LINE)

    val projectManager: ProjectManager = ProjectManager.getInstance()
    val projects = projectManager.openProjects
    val project = if (projects.isNotEmpty()) projects[0] else projectManager.defaultProject
    val parent = jsonConfig.parent
    val newJsonConfig = JsonEditor(project, configState?.json)

    newJsonConfig.minimumSize = jsonConfig.minimumSize
    newJsonConfig.preferredSize = jsonConfig.preferredSize
    newJsonConfig.maximumSize = jsonConfig.maximumSize
    parent.remove(jsonConfig)
    jsonConfig = newJsonConfig
    parent.add(BorderLayout.CENTER, jsonConfig)

    restoreDefaults.addActionListener { reset(SettingsState()) }

    return wrapper
  }

  override fun reset() {
    reset(configState)
  }

  private fun reset(state: SettingsState?) {
    cursorMode.selectedItem = state?.cursorMode ?: CursorMode.CURSOR
    debug.isSelected = state?.debug ?: false
    jsonConfig.text = jsonTrim(state?.json ?: "")!!
  }

  private fun jsonTrim(s: String?) = s?.trimStart()?.replace(Regex("""[\r\n]+$""", RegexOption.MULTILINE), "\n")

  override fun dispose() {}
}
