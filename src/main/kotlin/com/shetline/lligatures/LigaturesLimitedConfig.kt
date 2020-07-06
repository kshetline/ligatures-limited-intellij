package com.shetline.lligatures

import com.beust.klaxon.Klaxon
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.json.json5.Json5Language
import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.ProjectManager
import com.intellij.ui.LanguageTextField
import com.shetline.lligatures.Json5ToJson.Companion.json5toJson
import com.shetline.lligatures.LigaturesLimitedSettings.CursorMode
import com.shetline.lligatures.LigaturesLimitedSettings.LigatureConfigJson
import com.shetline.lligatures.LigaturesLimitedSettings.SettingsState
import java.awt.BorderLayout
import java.awt.Font
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.io.StringReader
import javax.swing.*

class LigaturesLimitedConfig : Configurable, Disposable {
  private lateinit var wrapper: JPanel
  private lateinit var contexts: JTextField
  private lateinit var cursorMode: JComboBox<CursorMode>
  private lateinit var debug: JCheckBox
  private lateinit var jsonConfig: LanguageTextField
  private lateinit var restoreDefaults: JButton

  private val klaxon = Klaxon()
  private val configState
    get() = LigaturesLimitedSettings.instance.state

  override fun isModified(): Boolean {
    return (
      contexts.text != configState?.contexts ||
      cursorMode.selectedItem != configState?.cursorMode ||
      debug.isSelected != configState?.debug ||
      jsonConfig.text != configState?.json
    )
  }

  override fun getDisplayName(): String {
    return "Ligatures Limited"
  }

  override fun apply() {
    try {
      val config = klaxon.parse<LigatureConfigJson>(StringReader(json5toJson(jsonConfig.text)))
      println(config)
    }
    catch (e: Exception) {
      throw ConfigurationException(e.message)
    }

    configState?.contexts = contexts.text
    configState?.cursorMode = cursorMode.selectedItem as CursorMode
    configState?.debug = debug.isSelected
    configState?.json = jsonConfig.text

    ProjectManager.getInstance().openProjects.forEach()
      { project -> DaemonCodeAnalyzer.getInstance(project).restart() }
  }

  override fun createComponent(): JComponent? {
    cursorMode.addItem(CursorMode.OFF)
    cursorMode.addItem(CursorMode.CURSOR)
    cursorMode.addItem(CursorMode.LINE)

    val projectManager: ProjectManager = ProjectManager.getInstance()
    val projects = projectManager.openProjects
    val scheme = EditorColorsManager.getInstance().globalScheme
    val project = if (projects.isNotEmpty()) projects[0] else projectManager.defaultProject
    val parent = jsonConfig.parent
    val newJsonConfig = LanguageTextField(Json5Language.INSTANCE, project, configState?.json ?: "")

    newJsonConfig.font = Font(scheme.editorFontName, Font.PLAIN, scheme.editorFontSize)
    newJsonConfig.setOneLineMode(false)
    newJsonConfig.minimumSize = jsonConfig.minimumSize
    newJsonConfig.preferredSize = jsonConfig.preferredSize
    newJsonConfig.maximumSize = jsonConfig.maximumSize
    newJsonConfig.autoscrolls = true

    parent.remove(jsonConfig)
    jsonConfig = newJsonConfig
    jsonConfig.addComponentListener(MyComponentAdapter())
    parent.add(BorderLayout.CENTER, jsonConfig)

    restoreDefaults.addActionListener { reset(SettingsState()) }

    return wrapper
  }

  inner class MyComponentAdapter : ComponentAdapter() {
    override fun componentResized(e: ComponentEvent?) {
      componentShown(e)
    }

    override fun componentShown(e: ComponentEvent?) {
      jsonConfig.editor?.settings?.isLineNumbersShown = true
      (jsonConfig.editor as? EditorEx)?.setHorizontalScrollbarVisible(true)
      (jsonConfig.editor as? EditorEx)?.setVerticalScrollbarVisible(true)
    }
  }

  override fun reset() {
    reset(configState)
  }

  private fun reset(state: SettingsState?) {
    contexts.text = state?.contexts ?: ""
    cursorMode.selectedItem = state?.cursorMode ?: CursorMode.CURSOR
    debug.isSelected = state?.debug ?: false
    jsonConfig.text = state?.json ?: ""
  }

  override fun dispose() {
    // Nothing to do yet
  }
}
