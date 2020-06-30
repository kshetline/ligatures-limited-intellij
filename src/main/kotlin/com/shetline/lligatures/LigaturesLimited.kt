package com.shetline.lligatures

import com.intellij.codeHighlighting.*
import com.intellij.codeInsight.daemon.impl.HighlightVisitor
import com.intellij.codeInsight.daemon.impl.analysis.HighlightInfoHolder
import com.intellij.ide.AppLifecycleListener
import com.intellij.lang.Language
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.colors.*
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.impl.EditorImpl
import com.intellij.openapi.editor.markup.*
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.elementType
import com.intellij.util.xmlb.XmlSerializerUtil.copyBean
import com.shetline.lligatures.LigaturesLimitedSettings.CursorMode
import org.jetbrains.annotations.Nullable
import java.awt.Color
import kotlin.Comparator
import kotlin.collections.ArrayList
import kotlin.collections.HashMap

class LigaturesLimited : PersistentStateComponent<LigaturesLimited>, AppLifecycleListener, HighlightVisitor,
    TextEditorHighlightingPassFactory, TextEditorHighlightingPassFactoryRegistrar, CaretListener,
    EditorFactoryListener {
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
    searchForLigatures(file)

    return true
  }

  override fun visit(elem: PsiElement) {}

  private fun searchForLigatures(file: PsiFile) {
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

    val editor = currentEditors[file]
    val debug = settings.state!!.debug
    var index = 0
    var match: MatchResult? = null
    val syntaxHighlighter = SyntaxHighlighterFactory.getSyntaxHighlighter(file.language, file.project, file.virtualFile)
    val defaultForeground = EditorColorsManager.getInstance().globalScheme.defaultForeground
    val newHighlights = ArrayList<LigatureHighlight>()
    var lastDebugHighlight: LigatureHighlight? = null
    var lastDebugCategory: ElementCategory? = null

    while ({ match = globalMatchLigatures.find(text, index); match }() != null && index < text.length) {
      val matchIndex = match!!.range.first
      val matchText = match!!.groupValues[0]
      val elem = file.findElementAt(matchIndex)

      if (elem != null && elem.language == file.language) {
        val category = ElementCategorizer.categoryFor(elem, matchText, matchIndex)

        if (shouldSuppressLigature(elem, category, file.language, matchText, matchIndex)) {
          val colors = getMatchingColors(DEBUG_RED)

          for (i in matchText.indices) {
            val phase = (matchIndex + i) % 2
            val foreground = if (debug) colors[phase] else null

            newHighlights.add(LigatureHighlight(foreground, elem, category, matchText, matchIndex + i, 1))
          }

          lastDebugHighlight = null
        }
        else if (debug) {
          if (lastDebugHighlight != null && lastDebugHighlight.index + lastDebugHighlight.span == matchIndex &&
              lastDebugCategory == category )
            lastDebugHighlight.span += matchText.length
          else {
            lastDebugHighlight = LigatureHighlight(DEBUG_GREEN, elem, category, matchText, matchIndex, matchText.length)
            lastDebugCategory = category
            newHighlights.add(lastDebugHighlight)
          }
        }
      }

      index = (matchIndex + matchText.length).coerceAtLeast(index + 1)
      ProgressManager.checkCanceled()
    }

    val hIndex = if (lastDebugHighlight != null) lastDebugHighlight.index + lastDebugHighlight.span else -1

    if (hIndex > 0 && hIndex < text.length - 1) {
      val extensionCandidate = text.substring(hIndex - 1, hIndex + 1)
      match = globalMatchLigatures.find(text, index)

      if (match != null) {
        val elem = file.findElementAt(hIndex)

        if (elem != null) {
          val debugCategory = ElementCategorizer.categoryFor(elem, extensionCandidate, (hIndex - 1))

          if (debugCategory == lastDebugCategory)
            ++lastDebugHighlight!!.span
        }
      }
    }

    if (editor != null) {
      ApplicationManager.getApplication().invokeLater {
        val oldHighlighters = syntaxHighlighters[editor]

        if (oldHighlighters != null) {
          oldHighlighters.forEach { highlighter -> editor.markupModel.removeHighlighter(highlighter) }
          syntaxHighlighters.remove(editor)
        }

        if (newHighlights.size > 0)
          applyHighlighters(editor, syntaxHighlighter, defaultForeground, newHighlights)

        highlightForCaret(editor, editor.caretModel.logicalPosition)
      }
    }
  }

  private fun applyHighlighters(editor: Editor, syntaxHighlighter: SyntaxHighlighter,
                                defaultForeground: Color, highlighters: ArrayList<LigatureHighlight>) {
    if (editor !is EditorImpl || highlighters.isEmpty())
      return

    val markupModel = editor.markupModel
    val newHighlights = ArrayList<RangeHighlighter>()
    val existingHighlighters = getHighlighters(editor)

    for (highlighter in highlighters) {
      val foreground = highlighter.color ?:
        getHighlightColors(highlighter.elem, highlighter.category, highlighter.ligature, highlighter.index, highlighter.span,
          syntaxHighlighter, editor, editor.colorsScheme,
          defaultForeground, existingHighlighters)[highlighter.index % 2]
      val background = if (highlighter.color != null) defaultForeground else null

      try {
        newHighlights.add(markupModel.addRangeHighlighter(
          highlighter.index, highlighter.index + highlighter.span, MY_LIGATURE_LAYER,
          TextAttributes(foreground, null, null, EffectType.BOXED, 0),
          HighlighterTargetArea.EXACT_RANGE
        ))

        if (background != null) {
          // Apply background at lower layer so selection layer can override it
          newHighlights.add(markupModel.addRangeHighlighter(
            highlighter.index, highlighter.index + highlighter.span, MY_LIGATURE_BACKGROUND_LAYER,
            TextAttributes(null, background, null, EffectType.BOXED, 0),
            HighlighterTargetArea.EXACT_RANGE
          ))
        }
      }
      catch (e: Exception) {
        println(e.message)
      }
    }

    syntaxHighlighters[editor] = newHighlights
  }

  private fun shouldSuppressLigature(
      elem: PsiElement, inCategory: ElementCategory?, baseLanguage: Language?,
      matchText: String, matchIndex: Int
  ): Boolean {
    val category = inCategory ?: ElementCategorizer.categoryFor(elem, matchText, matchIndex)

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
    cursorHighlighters.remove(event.editor)
    syntaxHighlighters.remove(event.editor)
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
    val oldHighlights = cursorHighlighters[editor]
    val mode = settings.state!!.cursorMode

    if (oldHighlights != null) {
      oldHighlights.forEach { highlighter -> markupModel.removeHighlighter(highlighter) }
      cursorHighlighters.remove(editor)
    }

    if (settings.state!!.cursorMode == CursorMode.OFF)
      return

    val file = currentFiles[editor] ?: return
    val lineStart = doc.getLineStartOffset(pos.line)
    val lineEnd = if (pos.line < doc.lineCount - 1) doc.getLineStartOffset(pos.line + 1) else doc.textLength
    val line = doc.getText(TextRange(lineStart, lineEnd)).trimEnd()
    val debug = settings.state!!.debug
    val syntaxHighlighter = SyntaxHighlighterFactory.getSyntaxHighlighter(file.language, file.project, file.virtualFile)
    val defaultForeground = EditorColorsManager.getInstance().globalScheme.defaultForeground
    val background = if (debug) EditorColorsManager.getInstance().globalScheme.defaultForeground else null
    val newHighlights = ArrayList<RangeHighlighter>()

    globalMatchLigatures.findAll(line).forEach { lig ->
      if (mode == CursorMode.LINE || (mode == CursorMode.CURSOR && pos.column in lig.range.first..lig.range.last + 1)) {
        val elem = file.findElementAt(lineStart + pos.column - if (pos.column == lig.range.last + 1) 1 else 0)

        if (elem != null) {
          val category = ElementCategorizer.categoryFor(elem, lig.value, lineStart + lig.range.first)

          for (i in lig.range) {
            val colors = if (!debug) getHighlightColors(elem, category, lig.value, lineStart + i, 1,
                syntaxHighlighter, editor, editor.colorsScheme, defaultForeground)
              else getMatchingColors(DEBUG_RED)

            newHighlights.add(markupModel.addRangeHighlighter(
              lineStart + i, lineStart + i + 1, MY_SELECTION_LAYER,
              TextAttributes(colors[(lineStart + i) % 2], background, null, EffectType.BOXED, 0),
              HighlighterTargetArea.EXACT_RANGE
            ))
          }
        }
      }
    }

    if (newHighlights.size > 0)
      cursorHighlighters[editor] = newHighlights
  }

  @Nullable
  override fun getState() = this

  override fun loadState(state: LigaturesLimited) {
    copyBean(state, this)
  }

  private fun getHighlightColors(elem: PsiElement, category: ElementCategory,
      ligature: String, textOffset: Int, span: Int,
      syntaxHighlighter: SyntaxHighlighter, editor: Editor?,
      colorsScheme: TextAttributesScheme, defaultForeground: Color,
      defaultHighlighters: List<RangeHighlighter>? = null): Array<Color?> {
    var color: Color? = null
    val startOffset = maxOf(elem.textRange.startOffset, textOffset)
    val endOffset = minOf(elem.textRange.endOffset, textOffset + span)
    val highlighters = defaultHighlighters ?: getHighlighters(editor)
    var maxLayer = -1
    var minSpan = Int.MAX_VALUE
    val startIndex = findFirstIndex(highlighters, startOffset)

    if (startIndex >= 0) {
      for (i in startIndex..highlighters.size) {
        val highlighter = highlighters[i]

        if (highlighter.startOffset <= startOffset && endOffset <= highlighter.endOffset) {
          val specificColor = highlighter.textAttributes!!.foregroundColor
          val highlightSpan = highlighter.endOffset - highlighter.startOffset
          val layer = highlighter.layer

          if (layer > maxLayer || (layer == maxLayer && highlightSpan < minSpan)) {
            color = specificColor
            minSpan = highlightSpan
            maxLayer = layer
          }
        }
        else if (highlighter.startOffset > endOffset)
          break
      }
    }

    if (color == null &&
        (category == ElementCategory.STRING || category == ElementCategory.TEXT ||
         category == ElementCategory.LINE_COMMENT || category == ElementCategory.BLOCK_COMMENT ||
         ElementCategorizer.opRegex.matches(ligature))) {
      val type = elem.elementType ?: elem.node.elementType
      val textAttrKeys = syntaxHighlighter.getTokenHighlights(type)
      val textAttrs = getTextAttributes(colorsScheme, textAttrKeys)

      color = textAttrs?.foregroundColor ?: defaultForeground
    }

    return getMatchingColors(color)
  }

  private fun getHighlighters(editor: Editor?): List<RangeHighlighter>
  {
    if (editor !is EditorImpl)
      return listOf()

    val highlighters = editor.filteredDocumentMarkupModel.allHighlighters

    highlighters.sortWith(Comparator { a, b ->
      if (a.startOffset != b.startOffset) a.startOffset - b.startOffset else b.endOffset - a.endOffset
    })

    return highlighters.filter { h -> h.layer < MY_LIGATURE_LAYER && h.textAttributes?.foregroundColor != null }
  }

  // Not quite close enough to any pre-defined binary search to avoid handling this as a special case
  private fun findFirstIndex(highlighters: List<RangeHighlighter>, offset: Int): Int {
    if (highlighters.isEmpty())
      return -1

    var low = 0
    var high = highlighters.size
    var matched = false
    var mid = -1

    // This will narrow down *a* highlighter that contains `offset`, but not necessarily the first one
    while (low <= high) {
      mid = (low + high) / 2
      val highlighter = highlighters[mid]

      if (offset in highlighter.startOffset..highlighter.endOffset) {
        matched = true
        break
      }
      else if (highlighter.endOffset < offset)
        low = mid + 1
      else
        high = mid - 1
    }

    if (!matched)
      return -1

    // Make sure we find the first matching highlighter
    while (mid > 0) {
      val highlighter = highlighters[mid - 1]

      if (offset in highlighter.startOffset..highlighter.endOffset)
        --mid
      else
        break
    }

    return mid
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

  data class LigatureHighlight (
    var color: Color?,
    var elem: PsiElement,
    var category: ElementCategory,
    var ligature: String,
    var index: Int,
    var span: Int
  )

  companion object {
    private const val MY_LIGATURE_LAYER = HighlighterLayer.SELECTION + 33
    private const val MY_LIGATURE_BACKGROUND_LAYER = HighlighterLayer.SELECTION - 33
    private const val MY_SELECTION_LAYER = MY_LIGATURE_LAYER + 1
    private val baseLigatures = ("""

  .= ..= .- := =:= == != === !== =/= <-< <<- <-- <- <-> -> --> ->> >-> <=< <<= <== <=> => ==> =!= =:=
  =>> >=> >>= >>- >- <~> -< -<< =<< <~~ <~ ~~ ~> ~~> <<< << <= <> >= >> >>> {. {| [| <: :> |] |} .}
  <||| <|| <| <|> |> ||> |||> <$ <$> $> <+ <+> +> <* <*> *> \\ \\\ \* /* */ /// // </ <!-- </> --> />
  ;; :: ::: .. ... ..< !! ?? %% && <:< || ?. ?: ++ +++ -- --- ** *** ~= ~- www ff fi fl ffi ffl
  -~ ~@ ^= ?= /= /== |= ||= #! ## ### #### #{ #[ ]# #( #? #_ #: #= #_( #{} =~ !~ 9x9 0xF 0o7 0b1
  |- |-- -| --| |== =| ==| /==/ ==/ /=/ <~~> =>= =<= :>: :<: /\ \/ _|_ ||- :< >: ::=
  <==== ==== ====> <====> <--- ---> <---> |--- ---| |=== ===| /=== ===/ <~~~ ~~~> <~~~>

""").trim().split(Regex("""\s+"""))

    private val patternSubstitutions = hashMapOf<String, String?>(
      "####" to "#{4,}",
      "<====" to "<={4,}",
      "====" to "={4,}",
      "====>" to "={4,}>",
      "<====>" to "<={4,}>",
      "<---" to "<-{3,}",
      "--->" to "-{3,}>",
      "<--->" to "<-{3,}>",
      "|---" to "\\|-{3,}",
      "---|" to "-{3,}\\|",
      "|===" to "\\|={3,}",
      "===|" to "={3,}\\|",
      "/===" to "\\/={3,}",
      "===/" to "={3,}\\/",
      "<~~~" to "<~{3,}",
      "~~~>" to "~{3,}>",
      "<~~~>" to "<~{3,}>",
      "0xF" to "0x[0-9a-fA-F]",
      "0o7" to "0o[0-7]",
      "0b1" to "(?<![0-9a-fA-FxX])0b[01]",
      "9x9" to "\\dx\\d"
    )

    private val connectionTweaks = hashMapOf<String, Regex?>(
      "-" to Regex("""[-<>|]+"""),
      "=" to Regex("""[=<>|]+"""),
      "~" to Regex("""[~<>|]+""")
    )

    private val charsNeedingRegexEscape = Regex("""[-\[\]/{}()*+?.\\^$|]""")
    private val disregarded = arrayOf<String>()
    private val globalMatchLigatures: Regex
    private val DEBUG_GREEN = Color(0x009900)
    private val DEBUG_RED = Color(0xDD0000)
    private val currentEditors = HashMap<PsiFile, Editor>()
    private val currentFiles = HashMap<Editor, PsiFile>()
    private val cursorHighlighters = HashMap<Editor, List<RangeHighlighter>>()
    private val syntaxHighlighters = HashMap<Editor, List<RangeHighlighter>>()

    init {
      val sorted = baseLigatures.sortedWith(Comparator { a, b -> b.length - a.length })
      val escaped = sorted.map { lig -> patternSubstitutions[lig] ?: generatePattern(lig) }
      globalMatchLigatures = Regex(escaped.joinToString("|"))
    }

    private fun generatePattern(ligature: String): String {
      val leadingSet = HashSet<String>()
      val trailingSet = HashSet<String>()
      val len = ligature.length

      // Give triangles (◁, ▷) and diamonds (♢) formed using < and > higher priority.
      if (Regex("""^[>|]""").containsMatchIn(ligature))
        leadingSet.add("<")

      if (Regex("""[|<]$""").containsMatchIn(ligature))
        trailingSet.add(">")

      for (other in disregarded) {
        if (other.length <= len)
          break

        var index = 0

        while ({ index = other.indexOf(ligature, index); index }() >= 0) {
          if (index > 0)
            leadingSet.add(other[index - 1].toString())

          if (index + len < other.length)
            trailingSet.add(other[index + len].toString())

          ++index
        }
      }

      // Handle ligatures which are supposed to blend with combinatory arrow ligatures
      connectionTweaks.forEach { (key, pattern) ->
        if (ligature.startsWith(key) && pattern!!.matches(ligature))
          leadingSet.add(key)

        if (ligature.endsWith(key) && pattern!!.matches(ligature))
          trailingSet.add(key)
      }

      // Handle ligatures which are supposed to be connective with other ligatures

      val leading = createLeadingOrTrailingClass(leadingSet)
      val trailing = createLeadingOrTrailingClass(trailingSet)
      var pattern = ""

      if (!leading.isNullOrEmpty()) // Create negative lookbehind, so this ligature isn't matched if preceded by these characters.
        pattern += "(?<!$leading)"

      pattern += escapeForRegex(ligature)

      if (!trailing.isNullOrEmpty()) // Create negative lookahead, so this ligature isn't matched if followed by these characters.
        pattern += "(?!$trailing)"

      return pattern
    }

    private fun createLeadingOrTrailingClass(set: MutableSet<String>): String? {
      if (set.isEmpty())
        return ""
      else if (set.size == 1)
        return escapeForRegex(set.elementAt(0))

      var klass = "["

      // If present, dash (`-`) must go first, in case it's the start of a [] class pattern
      if (set.contains("-")) {
        klass += "-"
        set.remove("-")
      }

      set.forEach { c -> klass += escapeForRegex(c) }

      return "$klass]"
    }

    private fun escapeForRegex(s: String): String {
      return s.replace(charsNeedingRegexEscape) { m -> "\\" + m.value }
    }

    private val cachedColors: MutableMap<Color, Array<Color?>> = HashMap()
    private val noColors = arrayOf<Color?>(null, null)

    private fun getMatchingColors(color: Color?): Array<Color?> {
      if (color == null)
        return noColors
      else if (!cachedColors.containsKey(color)) {
        val alpha = color.rgb and 0xFF000000.toInt()
        val rgb = color.rgb and 0x00FFFFFF

        if (color.blue > 253)
          cachedColors[color] = arrayOf<Color?>(Color(alpha or (rgb - 1)), Color(alpha or (rgb - 2)))
        else
          cachedColors[color] = arrayOf<Color?>(Color(alpha or (rgb + 1)), Color(alpha or (rgb + 2)))
      }

      return cachedColors[color]!!
    }
  }
}
