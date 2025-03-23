package com.shetline.lligatures

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.Disposable
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.LanguageTextField
import com.intellij.util.ui.JBUI
import com.shetline.json.Json5Editor
import com.shetline.lligatures.LigaturesLimitedSettings.Companion.parseJson
import com.shetline.lligatures.LigaturesLimitedSettings.CursorMode
import com.shetline.lligatures.LigaturesLimitedSettings.SettingsState
import java.awt.*
import javax.swing.*

class LigaturesLimitedConfig : Configurable, Disposable {
  private var wrapper = JPanel(BorderLayout())
  private val cursorMode = ComboBox<CursorMode>()
  private var debug = JCheckBox("(highlight suppressed and enabled ligatures)")
  private var jsonConfig = LanguageTextField()
  private var restoreDefaults = JButton("Restore defaults")

  private val configState
    get() = LigaturesLimitedSettings.instance.state

  init {
    // Main wrapper panel
    wrapper.minimumSize = Dimension(480, 480)
    wrapper.maximumSize = Dimension(640, 640)
    wrapper.preferredSize = Dimension(640, -1)

    // Create a child panel with GridLayoutManager-like layout
    val innerPanel = JPanel()
    innerPanel.layout = GridBagLayout()
    innerPanel.minimumSize = Dimension(480, 130)
    innerPanel.maximumSize = Dimension(640, 180)
    innerPanel.preferredSize = Dimension(640, 130)
    innerPanel.border = BorderFactory.createEmptyBorder(8, 8, 8, 8)

    val gridBagConstraints = GridBagConstraints().apply {
      fill = GridBagConstraints.NONE
      gridx = 0
      gridy = 0
      insets = JBUI.insets(4)
    }

    // Cursor Mode Label and ComboBox
    val cursorModeLabel = JLabel("Cursor Mode")
    gridBagConstraints.anchor = GridBagConstraints.WEST
    innerPanel.add(cursorModeLabel, gridBagConstraints)

    gridBagConstraints.gridx = 1
    gridBagConstraints.fill = GridBagConstraints.HORIZONTAL
    gridBagConstraints.anchor = GridBagConstraints.EAST
    cursorMode.preferredSize = Dimension(100, 28)
    innerPanel.add(cursorMode, gridBagConstraints)

    // Debug Label and CheckBox
    gridBagConstraints.gridy = 1
    gridBagConstraints.gridx = 0
    gridBagConstraints.fill = GridBagConstraints.NONE
    gridBagConstraints.anchor = GridBagConstraints.WEST
    val debugLabel = JLabel("Debug")
    innerPanel.add(debugLabel, gridBagConstraints)

    gridBagConstraints.gridx = 1
    gridBagConstraints.anchor = GridBagConstraints.EAST
    innerPanel.add(debug, gridBagConstraints)

    // Vertical Spacer
    gridBagConstraints.gridy = 2
    gridBagConstraints.gridx = 0
    gridBagConstraints.gridwidth = 2
    gridBagConstraints.weighty = 1.0
    gridBagConstraints.fill = GridBagConstraints.VERTICAL
    innerPanel.add(Box.createVerticalStrut(4), gridBagConstraints)

    // Advanced settings Label and Restore Defaults Button
    gridBagConstraints.gridy = 3
    gridBagConstraints.gridwidth = 1
    gridBagConstraints.weighty = 0.0
    gridBagConstraints.gridx = 0
    gridBagConstraints.fill = GridBagConstraints.NONE
    gridBagConstraints.anchor = GridBagConstraints.WEST
    val advancedSettingsLabel = JLabel("Advanced settings:")
    innerPanel.add(advancedSettingsLabel, gridBagConstraints)

    gridBagConstraints.gridx = 1
    gridBagConstraints.anchor = GridBagConstraints.EAST
    innerPanel.add(restoreDefaults, gridBagConstraints)

    wrapper.add(innerPanel, BorderLayout.NORTH)

    // JSON configuration
    jsonConfig.minimumSize = Dimension(480, 320)
    jsonConfig.maximumSize = Dimension(640, 480)
    jsonConfig.preferredSize = Dimension(640, 480)

    wrapper.add(jsonConfig, BorderLayout.CENTER)
  }

  override fun isModified(): Boolean {
    return (
      cursorMode.selectedItem != configState.cursorMode ||
      debug.isSelected != configState.debug ||
      jsonTrim(jsonConfig.text) != jsonTrim(configState.json)
    )
  }

  override fun getDisplayName(): String {
    return "Ligatures Limited"
  }

  override fun apply() {
    try {
      parseJson(jsonConfig.text)
    }
    catch (e: Exception) {
      throw ConfigurationException(e.message)
    }

    configState.cursorMode = cursorMode.selectedItem as CursorMode
    configState.debug = debug.isSelected
    configState.json = jsonTrim(jsonConfig.text)!!

    LigaturesLimitedSettings.instance.loadState(configState)

    ProjectManager.getInstance().openProjects.forEach()
      { project -> DaemonCodeAnalyzer.getInstance(project).restart() }
  }

  override fun createComponent(): JComponent {
    cursorMode.addItem(CursorMode.OFF)
    cursorMode.addItem(CursorMode.CURSOR)
    cursorMode.addItem(CursorMode.LINE)

    val projectManager: ProjectManager = ProjectManager.getInstance()
    val projects = projectManager.openProjects
    val project = if (projects.isNotEmpty()) projects[0] else projectManager.defaultProject
    val parent = jsonConfig.parent
    val newJsonConfig = Json5Editor(project, configState.json)

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
