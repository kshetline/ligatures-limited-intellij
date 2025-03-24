package com.shetline.lligatures

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener

interface LLListenerClient {
  fun editorReleased(event: EditorFactoryEvent)
  fun caretPositionChanged(event: CaretEvent)
}

class LLListener (private val client: LLListenerClient) : CaretListener, EditorFactoryListener, Disposable {
  override fun editorCreated(event: EditorFactoryEvent) {
    event.editor.caretModel.addCaretListener(this)
  }

  override fun editorReleased(event: EditorFactoryEvent) {
    client.editorReleased(event)
    event.editor.caretModel.removeCaretListener(this)
  }

  override fun caretPositionChanged(event: CaretEvent) {
    client.caretPositionChanged(event)
  }

  override fun dispose() {}
}
