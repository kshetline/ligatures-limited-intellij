package com.shetline.json

import com.intellij.ide.DataManager
import com.intellij.lang.LanguageUtil
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.actionSystem.EditorAction
import com.intellij.openapi.editor.actions.IndentSelectionAction
import com.intellij.openapi.editor.actions.UnindentSelectionAction
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.project.Project
import com.intellij.ui.LanguageTextField
import java.awt.Font
import java.awt.KeyboardFocusManager
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.JComponent
import javax.swing.KeyStroke

class Json5Editor(project: Project, value: String? = ""):
    LanguageTextField(LanguageUtil.getLanguages { lang -> lang.id == "JSON5" }[0], project, value ?: "") {
  init {
    val scheme = EditorColorsManager.getInstance().globalScheme

    font = Font(scheme.editorFontName, Font.PLAIN, scheme.editorFontSize)
    isOneLineMode = false
    autoscrolls = true

    background = if (scheme.defaultBackground.green < 128)
      scheme.defaultBackground.darker()
    else
      scheme.defaultBackground.brighter()

    addComponentListener(MyComponentAdapter())
  }

  inner class MyComponentAdapter : ComponentAdapter() {
    private var initDone = false

    override fun componentResized(e: ComponentEvent?) {
      componentShown(e)
    }

    override fun componentShown(e: ComponentEvent?) {
      val editor = editor as? EditorEx

      if (editor == null || initDone)
        return

      val content = editor.contentComponent
      val indentKeys = getKeys(content, KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS, "pressed TAB")
      val unindentKeys = getKeys(content, KeyboardFocusManager.BACKWARD_TRAVERSAL_KEYS, "shift pressed TAB")

      editor.settings.isLineNumbersShown = true
      content.focusTraversalKeysEnabled = false
      content.addKeyListener(object: KeyAdapter() {
        override fun keyPressed(event: KeyEvent?) {
          val keyStroke = if (event == null) null else KeyStroke.getKeyStrokeForEvent(event).toString()

          if (indentKeys.contains(keyStroke))
            performAction(editor, IndentSelectionAction())
          else if (unindentKeys.contains(keyStroke))
            performAction(editor, UnindentSelectionAction())
        }
      })
      editor.setHorizontalScrollbarVisible(true)
      editor.setVerticalScrollbarVisible(true)

      initDone = true
    }
  }

  private fun getKeys(component: JComponent, whichKeys: Int, default: String): List<String> {
    val keys = component.getFocusTraversalKeys(whichKeys)?.map { key -> key.toString() }

    return if (keys.isNullOrEmpty()) listOf(default) else keys
  }

  private fun performAction(editor: EditorEx, action: EditorAction) {
    for (caret in editor.caretModel.allCarets) {
      WriteCommandAction.runWriteCommandAction(editor.project) {
        action.handler.execute(editor, caret, DataManager.getInstance().getDataContext(editor.contentComponent))
      }
    }
  }
}
