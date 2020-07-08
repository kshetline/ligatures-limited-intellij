package com.shetline.lligatures

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.json.json5.Json5Language
import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.ProjectManager
import com.intellij.ui.LanguageTextField
import com.shetline.lligatures.LigaturesLimitedSettings.Companion.parseJson
import com.shetline.lligatures.LigaturesLimitedSettings.CursorMode
import com.shetline.lligatures.LigaturesLimitedSettings.SettingsState
import java.awt.BorderLayout
import java.awt.Font
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.*

class LigaturesLimitedConfig : Configurable, Disposable {
  private lateinit var wrapper: JPanel
  private lateinit var contexts: JTextField
  private lateinit var cursorMode: JComboBox<CursorMode>
  private lateinit var debug: JCheckBox
  private lateinit var jsonConfig: LanguageTextField
  private lateinit var restoreDefaults: JButton

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
      println(parseJson(jsonConfig.text))
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
    private var initDone = false

    override fun componentResized(e: ComponentEvent?) {
      componentShown(e)
    }

    override fun componentShown(e: ComponentEvent?) {
      val editor = jsonConfig.editor as? EditorEx

      if (editor == null || initDone)
        return

      editor.settings.isLineNumbersShown = true
      editor.contentComponent.focusTraversalKeysEnabled = false
      editor.contentComponent.addKeyListener(object: KeyAdapter() {
        override fun keyPressed(e: KeyEvent?) {
          if (e != null && e.modifiersEx == 0 && e.keyChar == '\t')
            insertTab(editor, e)
        }
      })
      editor.setHorizontalScrollbarVisible(true)
      editor.setVerticalScrollbarVisible(true)

      initDone = true
    }
  }

  private fun insertTab(editor: EditorEx, origEvent: KeyEvent) {
    val event = KeyEvent(origEvent.component, KeyEvent.KEY_TYPED, origEvent.`when`, 0, KeyEvent.VK_UNDEFINED, ' ')
    val tabSize = 2

    for (caret in editor.caretModel.allCarets) {
      val column = caret.logicalPosition.column
      val spaces = tabSize - column % tabSize

      for (i in 1..spaces)
        editor.processKeyTyped(event)
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
