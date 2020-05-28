package com.shetline.lligatures

import com.intellij.codeHighlighting.*
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.HighlightInfoType
import com.intellij.codeInsight.daemon.impl.HighlightVisitor
import com.intellij.codeInsight.daemon.impl.analysis.HighlightInfoHolder
import com.intellij.ide.AppLifecycleListener
import com.intellij.lang.Language
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.colors.*
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.markup.*
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.elementType
import com.intellij.util.xmlb.XmlSerializerUtil.copyBean
import com.shetline.lligatures.LigaturesLimitedSettings.CursorMode
import org.jetbrains.annotations.Nullable
import java.awt.Color
import javax.swing.SwingUtilities
import kotlin.Comparator
import kotlin.collections.ArrayList
import kotlin.collections.HashMap

class LigaturesLimited : PersistentStateComponent<LigaturesLimited>, AppLifecycleListener, HighlightVisitor,
    TextEditorHighlightingPassFactory, TextEditorHighlightingPassFactoryRegistrar, EditorColorsListener,
    CaretListener, EditorFactoryListener {
  private val ligatureHighlight: HighlightInfoType = HighlightInfoType
    .HighlightInfoTypeImpl(HighlightSeverity.INFORMATION, DefaultLanguageHighlighterColors.CONSTANT)
  private val settings = LigaturesLimitedSettings.instance
  private val debugCategories = false

  override fun appFrameCreated(commandLineArgs: MutableList<String>) {
    EditorFactory.getInstance().addEditorFactoryListener(this, ApplicationManager.getApplication())
  }

  override fun suitableForFile(file: PsiFile): Boolean = true

  override fun analyze(
      file: PsiFile, updateWholeFile: Boolean, holder: HighlightInfoHolder, action: Runnable
  ): Boolean {
    action.run()
    searchForLigatures(file, holder)

    return true
  }

  override fun visit(element: PsiElement) {}

  private fun searchForLigatures(file: PsiFile, holder: HighlightInfoHolder) {
    val text = file.text

    @Suppress("ConstantConditionIf")
    if (debugCategories) {
      var index = 0

      while (index < text.length) {
        val elem = file.findElementAt(index) ?: break
        val len = elem.textLength
        val elemText = elem.text.substring(0, len.coerceAtMost(25)).replace(Regex("""\r\n|\r|\n"""), "↵ ")

        println("$elemText ${ElementCategorizer.categoryFor(elem, elem.text, elem.textOffset)}")
        index = (index + len).coerceAtLeast(index + 1)
      }
    }

    val syntaxHighlighter = SyntaxHighlighterFactory.getSyntaxHighlighter(file.language, file.project, file.virtualFile)
    val defaultForeground = EditorColorsManager.getInstance().globalScheme.defaultForeground
    var index = 0
    var match: MatchResult? = null
    var phase = 0

    while ({ match = globalMatchLigatures.find(text, index); match }() != null && index < text.length) {
      val matchIndex = match!!.range.first
      val matchText = match!!.groupValues[0]
      val elem = file.findElementAt(matchIndex)

      if (elem != null) {
        val style = getHighlightStyling(elem, syntaxHighlighter, holder.colorsScheme, defaultForeground)

        if (shouldSuppressLigature(elem, file.language, matchText, matchIndex)) {
          for (i in matchText.indices) {
            holder.add(
              HighlightInfo
                .newHighlightInfo(ligatureHighlight)
                .textAttributes(TextAttributes(style.colors[phase], style.background, null, EffectType.BOXED, style.fontType))
                .range(elem, matchIndex + i, matchIndex + i + 1)
                .create()
            )
            phase = phase xor 1
          }
        }
        else if (settings.state!!.debug) {
          holder.add(
            HighlightInfo
              .newHighlightInfo(ligatureHighlight)
              .textAttributes(TextAttributes(DEBUG_GREEN, style.background, null, EffectType.BOXED, style.fontType))
              .range(elem, matchIndex, matchIndex + matchText.length)
              .create()
          )
        }
      }

      index = (matchIndex + matchText.length).coerceAtLeast(index + 1)
    }

    val editor = currentEditors[file]

    if (editor != null)
      SwingUtilities.invokeLater { highlightForCaret(editor, editor.caretModel.logicalPosition) }
  }

  private fun shouldSuppressLigature(
      element: PsiElement, baseLanguage: Language?,
      matchText: String, matchIndex: Int
  ): Boolean {
    val category = ElementCategorizer.categoryFor(element, matchText, matchIndex)

    return category != ElementCategory.OPERATOR && category != ElementCategory.PUNCTUATION &&
        category != ElementCategory.COMMENT_MARKER &&
        !(category == ElementCategory.NUMBER && Regex("""0x[0-9a-f]""", RegexOption.IGNORE_CASE).matches(matchText))
  }

  override fun clone(): HighlightVisitor = LigaturesLimited()

  override fun createHighlightingPass(file: PsiFile, editor: Editor): TextEditorHighlightingPass? {
    currentEditors[file] = editor
    currentFiles[editor] = file

    return HighlightingPass(file, editor)
  }

  override fun registerHighlightingPassFactory(registrar: TextEditorHighlightingPassRegistrar, project: Project) {
    registrar.registerTextEditorHighlightingPass(
      this,
      TextEditorHighlightingPassRegistrar.Anchor.LAST,
      Pass.LAST_PASS,
      false,
      false
    )
  }

  override fun editorCreated(event: EditorFactoryEvent) {
    event.editor.caretModel.addCaretListener(this)
  }

  override fun editorReleased(event: EditorFactoryEvent) {
    if (currentFiles[event.editor] != null)
      currentEditors.remove(currentFiles[event.editor])

    currentFiles.remove(event.editor)
    cursorHighlights.remove(event.editor)
    event.editor.caretModel.removeCaretListener(this)
  }

  override fun caretPositionChanged(event: CaretEvent) {
    highlightForCaret(event.editor, event.caret?.logicalPosition)
  }

  private fun highlightForCaret(editor: Editor, pos: LogicalPosition?) {
    if (pos == null)
      return

    val doc = editor.document
    val markupModel = editor.markupModel
    val oldHighlights = cursorHighlights[editor]
    val mode = settings.state!!.cursorMode

    if (oldHighlights != null) {
      oldHighlights.forEach { highlight -> markupModel.removeHighlighter(highlight) }
      cursorHighlights.remove(editor)
    }

    val file = currentFiles[editor] ?: return
    val lineStart = doc.getLineStartOffset(pos.line)
    val lineEnd = if (pos.line < doc.lineCount - 1) doc.getLineStartOffset(pos.line + 1) else doc.textLength
    val line = doc.getText(TextRange(lineStart, lineEnd)).trimEnd()
    var phase = 0
    val syntaxHighlighter = SyntaxHighlighterFactory.getSyntaxHighlighter(file.language, file.project, file.virtualFile)
    val defaultForeground = EditorColorsManager.getInstance().globalScheme.defaultForeground
    val newHighlights = ArrayList<RangeHighlighter>()

    globalMatchLigatures.findAll(line).forEach { lig ->
      if (mode == CursorMode.LINE || (mode == CursorMode.CURSOR && pos.column in lig.range.first..lig.range.last + 1)) {
        val elem = file.findElementAt(lineStart + pos.column)

        if (elem != null) {
          val style = getHighlightStyling(elem, syntaxHighlighter, editor.colorsScheme, defaultForeground)

          for (i in lig.range) {
            newHighlights.add(markupModel.addRangeHighlighter(
              lineStart + i,
              lineStart + i + 1,
              HighlighterLayer.SELECTION + 100,
              TextAttributes(style.colors[phase], style.background, null, EffectType.BOXED, style.fontType),
              HighlighterTargetArea.EXACT_RANGE
            ))
            phase = phase xor 1
          }
        }
      }
    }

    if (newHighlights.size > 0)
      cursorHighlights[editor] = newHighlights
  }

  @Nullable
  override fun getState() = this

  override fun loadState(state: LigaturesLimited) {
    copyBean(state, this)
  }

  override fun globalSchemeChange(scheme: EditorColorsScheme?) {
    // Do nothing
  }

  private fun getHighlightStyling(elem: PsiElement, syntaxHighlighter: SyntaxHighlighter,
      colorsScheme: TextAttributesScheme, defaultForeground: Color): HighlightStyling {

    val type = elem.elementType ?: elem.node.elementType
    val textAttrKeys = syntaxHighlighter.getTokenHighlights(type)
    val textAttrs = getTextAttributes(colorsScheme, textAttrKeys)
    val color = if (settings.state!!.debug) DEBUG_RED else textAttrs?.foregroundColor ?: defaultForeground
    val colors = getMatchingColors(color)
    val background = if (settings.state!!.debug) defaultForeground else null
    val fontType = textAttrs?.fontType ?: 0

    return HighlightStyling(type, colors, background, fontType)
  }

  private fun getTextAttributes(colorsScheme: TextAttributesScheme, textAttrKeys: Array<TextAttributesKey>) :
      TextAttributes? {
    var textAttrs: TextAttributes? = null

    for (key in textAttrKeys)
      textAttrs = TextAttributes.merge(textAttrs, colorsScheme.getAttributes(key))

    return textAttrs
  }

  class HighlightingPass(file: PsiFile, editor: Editor) :
      TextEditorHighlightingPass(file.project, editor.document, false) {
    override fun doCollectInformation(progress: ProgressIndicator) {}
    override fun doApplyInformationToEditor() {}
  }

  @Suppress("ArrayInDataClass")
  data class HighlightStyling (
    var type: IElementType,
    var colors: Array<Color>,
    var background: Color?,
    var fontType: Int
  )

  companion object {
    private val baseLigatures = ("""

.= .- := =:= == != === !== =/= <-< <<- <-- <- <-> -> --> ->> >-> <=< <<= <== <=> => ==>
=>> >=> >>= >>- >- <~> -< -<< =<< <~~ <~ ~~ ~> ~~> <<< << <= <> >= >> >>> {. {| [| <: :> |] |} .}
<||| <|| <| <|> |> ||> |||> <$ <$> $> <+ <+> +> <* <*> *> \\ \\\ \* /* */ /// // <// <!-- </> --> />
;; :: ::: .. ... ..< !! ?? %% && || ?. ?: ++ +++ -- --- ** *** ~= ~- www ff fi fl ffi ffl 0xF 9x9
-~ ~@ ^= ?= /= /== |= ||= #! ## ### #### #{ #[ ]# #( #? #_ #_(

""").trim().split(Regex("""\s+"""))
    private val escapeRegex = Regex("""[-\[\]\/{}()*+?.\\^$|]""")
    // Comment
    private val globalMatchLigatures: Regex
    private val DEBUG_GREEN = Color(0x009900)
    private val DEBUG_RED = Color(0xDD0000)
    private val currentEditors = HashMap<PsiFile, Editor>()
    private val currentFiles = HashMap<Editor, PsiFile>()
    private val cursorHighlights = HashMap<Editor, List<RangeHighlighter>>()

    init {
      val sorted = baseLigatures.sortedWith(Comparator { a, b -> b.length - a.length })
      val escaped = sorted.map { lg ->
        (lg.replace(escapeRegex) { matchResult -> "\\" + matchResult.value })
          .replace("0xF", "0x[0-9a-fA-F]")
          .replace("9x9", "\\dx\\d")
      }
      globalMatchLigatures = Regex(escaped.joinToString("|"))
    }

    private val cachedColors: MutableMap<Color, Array<Color>> = HashMap()

    private fun getMatchingColors(color: Color): Array<Color> {
      if (!cachedColors.containsKey(color)) {
        val alpha = color.rgb and 0xFF000000.toInt()
        val rgb = color.rgb and 0x00FFFFFF

        if (color.blue > 253)
          cachedColors[color] = arrayOf(Color(alpha or (rgb - 1)), Color(alpha or (rgb - 2)))
        else
          cachedColors[color] = arrayOf(Color(alpha or (rgb + 1)), Color(alpha or (rgb + 2)))
      }

      return cachedColors[color]!!
    }
  }
}
