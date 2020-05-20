package com.shetline.lligatures

import com.intellij.codeHighlighting.TextEditorHighlightingPass
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.psi.PsiFile

class HighlightingPass(private var file: PsiFile, private var editor: Editor) : TextEditorHighlightingPass(file.project, editor.document, false) {
  override fun doCollectInformation(progress: ProgressIndicator) {
    println("doCollectInformation: $myDocument, ${editor.editorKind.name}");
  }

  override fun doApplyInformationToEditor() {
    println("doApplyInformationToEditor: ${this.file.findElementAt(0)}, ${this.file.findElementAt(0)?.text?.substr(0, 20)}")
    this.file.viewProvider.languages.forEach {action -> println(action) }
  }

  private fun String.substr(start: Int, end: Int): String {
    var s = start
    var e = end

    if (s < 0)
      s = 0

    e = e.coerceAtMost(this.length).coerceAtLeast(s)

    return this.substring(s, e)
  }
}
