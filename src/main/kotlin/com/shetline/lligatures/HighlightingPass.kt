package com.shetline.lligatures

import com.intellij.codeHighlighting.TextEditorHighlightingPass
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.psi.PsiFile

class HighlightingPass(private var file: PsiFile, private var editor: Editor) :
    TextEditorHighlightingPass(file.project, editor.document, false) {
  override fun doCollectInformation(progress: ProgressIndicator) {
    println("doCollectInformation: $myDocument, ${editor.editorKind.name}");
  }

  override fun doApplyInformationToEditor() {
    println("doApplyInformationToEditor: ${this.file.name}")
  }
}
