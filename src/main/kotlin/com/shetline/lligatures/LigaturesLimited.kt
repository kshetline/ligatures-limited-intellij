package com.shetline.lligatures

import com.intellij.codeHighlighting.*
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.HighlightInfoType
import com.intellij.codeInsight.daemon.impl.HighlightVisitor
import com.intellij.codeInsight.daemon.impl.analysis.HighlightInfoHolder
import com.intellij.ide.AppLifecycleListener
import com.intellij.lang.Language
import com.intellij.lang.LanguageUtil
import com.intellij.lang.annotation.HighlightSeverity
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
import com.intellij.openapi.editor.ex.util.LexerEditorHighlighter
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
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.elementType
import com.intellij.util.xmlb.XmlSerializerUtil.copyBean
import com.shetline.lligatures.LigaturesLimitedSettings.CursorMode
import org.jetbrains.annotations.Nullable
import java.awt.Color
import java.util.concurrent.ConcurrentHashMap
import kotlin.Comparator
import kotlin.collections.ArrayList
import kotlin.collections.HashMap

class LigaturesLimited : PersistentStateComponent<LigaturesLimited>, AppLifecycleListener, HighlightVisitor,
    TextEditorHighlightingPassFactory, TextEditorHighlightingPassFactoryRegistrar, CaretListener,
    EditorFactoryListener {
  private val settings = LigaturesLimitedSettings.instance
  private val debugCategories = false
  private val infoType = HighlightInfoType.HighlightInfoTypeImpl(HighlightSeverity("MARKER", 1),
    CodeInsightColors.INFORMATION_ATTRIBUTES)

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

  override fun visit(elem: PsiElement) {}

  private fun searchForLigatures(file: PsiFile, holder: HighlightInfoHolder) {
    val text = file.text
    val languageId = if (file.context != null) file.language.idLc else null
    val languageInfo = if (languageId != null) languageLookup[languageId] else null
    val editor = currentEditors[file]

    if (languageInfo != null && languageInfo.syntaxHighlighter == null)
      languageInfo.syntaxHighlighter = SyntaxHighlighterFactory.getSyntaxHighlighter(file.language,
        file.project, file.virtualFile)

    if (editor != null) {
      highlightRechecks[editor]?.interrupt()
      highlightRechecks.remove(editor)
    }

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

    val debug = settings.state!!.debug
    val ligatures = settings.extState!!.globalMatchLigatures!!
    var index = 0
    var match: MatchResult? = null
    val syntaxHighlighter = SyntaxHighlighterFactory.getSyntaxHighlighter(file.language, file.project, file.virtualFile)
    val defaultForeground = EditorColorsManager.getInstance().globalScheme.defaultForeground
    val newHighlights = ArrayList<LigatureHighlight>()
    var lastDebugHighlight: LigatureHighlight? = null
    var lastDebugCategory: ElementCategory? = null

    while (index < text.length && { match = ligatures.find(text, index); match }() != null) {
      val matchIndex = match!!.range.first
      val matchText = match!!.groupValues[0]
      val elem = file.findElementAt(matchIndex)

      index = (matchIndex + matchText.length).coerceAtLeast(index + 1)
      ProgressManager.checkCanceled()

      if (elem == null || elem.language != file.language)
        continue

      val category = ElementCategorizer.categoryFor(elem, matchText, matchIndex)
      val elemLanguageId = getLanguageId(elem)
      val extra = extendedLength(file, text, elem, category, elemLanguageId, matchText, matchIndex)

      if (languageId != null) {
        holder.add(HighlightInfo.newHighlightInfo(infoType)
          .range(elem, matchIndex, matchIndex + matchText.length + extra)
          .textAttributes(TextAttributes(null, null,
            ColorPayload(elem.elementType, languageId), EffectType.WAVE_UNDERSCORE, 0))
          .create())
      }
      else if (shouldSuppressLigature(elem, category, elemLanguageId, matchText, matchIndex)) {
        val colors = getMatchingColors(DEBUG_RED)

        for (i in 0 until matchText.length + extra) {
          val phase = (matchIndex + i) % 2
          val foreground = if (debug) colors[phase] else null

          newHighlights.add(LigatureHighlight(foreground, elem, matchText, matchIndex + i, 1, null, null))
        }

        lastDebugHighlight = null
      }
      else if (debug) {
        val diff = if (lastDebugHighlight != null)
          lastDebugHighlight.index + lastDebugHighlight.span - lastDebugHighlight.index else 0

        if (lastDebugHighlight != null && (diff == 0 || diff == 1) && lastDebugCategory == category)
          lastDebugHighlight.span += matchText.length + extra
        else {
          lastDebugHighlight = LigatureHighlight(DEBUG_GREEN, elem, matchText, matchIndex,
            matchText.length + extra, null, null)
          lastDebugCategory = category
          newHighlights.add(lastDebugHighlight)
        }
      }
    }

    val hIndex = if (lastDebugHighlight != null) lastDebugHighlight.index + lastDebugHighlight.span else -1

    if (hIndex > 0 && hIndex < text.length - 1) {
      val extensionCandidate = text.substring(hIndex - 1, hIndex + 1)
      match = ligatures.find(text, index)

      if (match != null) {
        val elem = file.findElementAt(hIndex)

        if (elem != null) {
          val debugCategory = ElementCategorizer.categoryFor(elem, extensionCandidate, (hIndex - 1))

          if (debugCategory == lastDebugCategory)
            ++lastDebugHighlight!!.span
        }
      }
    }

    if (editor != null && languageId == null) {
      ApplicationManager.getApplication().invokeLater {
        val oldHighlighters = syntaxHighlighters[editor]

        if (oldHighlighters != null && editor is EditorImpl) {
          oldHighlighters.forEach { highlighter -> editor.filteredDocumentMarkupModel.removeHighlighter(highlighter) }
          syntaxHighlighters.remove(editor)
        }

        if (newHighlights.size > 0)
          applyHighlighters(editor, syntaxHighlighter, defaultForeground, newHighlights)

        highlightForCaret(editor, editor.caretModel.logicalPosition)
      }
    }
  }

  private fun applyHighlighters(editor: Editor, syntaxHighlighter: SyntaxHighlighter,
                                defaultForeground: Color, highlighters: ArrayList<LigatureHighlight>, count: Int = 0) {
    if (editor !is EditorImpl || highlighters.isEmpty())
      return

    val markupModel = editor.filteredDocumentMarkupModel
    val newHighlights = ArrayList<RangeHighlighter>()
    val existingHighlighters = getHighlighters(editor)

    for (highlighter in highlighters) {
      val foreground = highlighter.color ?:
        getHighlightColors(highlighter.elem, highlighter.ligature, highlighter.index, highlighter.span,
          syntaxHighlighter, editor, editor.colorsScheme,
          defaultForeground, existingHighlighters)[highlighter.index % 2]
      val background = if (highlighter.color != null) defaultForeground else null

      try {
        var needToAdd = true
        var newHighlight: RangeHighlighter? = null

        if (highlighter.lastHighlighter != null) {
          if (foreground != null && foreground == highlighter.lastHighlighter?.textAttributes?.foregroundColor) {
            needToAdd = false
            newHighlight = highlighter.lastHighlighter!!
          }
          else
            markupModel.removeHighlighter(highlighter.lastHighlighter!!)
        }

        if (needToAdd) {
          newHighlight = markupModel.addRangeHighlighter(
            highlighter.index, highlighter.index + highlighter.span, MY_LIGATURE_LAYER,
            TextAttributes(foreground, null, null, EffectType.BOXED, 0),
            HighlighterTargetArea.EXACT_RANGE
          )
        }

        newHighlights.add(newHighlight!!)
        highlighter.lastHighlighter = newHighlight

        if (background != null) {
          if (highlighter.lastBackground == null) {
            // Apply background at lower layer so selection layer can override it
            highlighter.lastBackground = markupModel.addRangeHighlighter(
              highlighter.index, highlighter.index + highlighter.span, MY_LIGATURE_BACKGROUND_LAYER,
              TextAttributes(null, background, null, EffectType.BOXED, 0),
              HighlighterTargetArea.EXACT_RANGE)
          }

          newHighlights.add(highlighter.lastBackground!!)
        }
      }
      catch (e: Exception) {
        println(e.message)
      }
    }

    syntaxHighlighters[editor] = newHighlights

    if (count < MAX_HIGHLIGHT_RECHECK_COUNT && !highlightRechecks.contains(editor)) {
      val recheck = HighlightRecheck(editor, syntaxHighlighter, defaultForeground, highlighters, count + 1)

      highlightRechecks[editor] = recheck
      recheck.start()
    }
  }

  private fun getMatchingConfiguration(elem: PsiElement, inCategory: ElementCategory?, baseLanguage: String,
                                       matchText: String, matchIndex: Int
  ): LigaturesLimitedSettings.ContextConfig? {
    val category = inCategory ?: ElementCategorizer.categoryFor(elem, matchText, matchIndex)
    val globalConfig = settings.extState!!.config!!
    var langConfig: LigaturesLimitedSettings.LanguageConfig = globalConfig

    if (globalConfig.languages.containsKey(baseLanguage))
      langConfig = globalConfig.languages[baseLanguage]!!

    if (langConfig.fullOnOrOff != null)
      return langConfig

    return if (langConfig.ligaturesByContext.containsKey(category))
      langConfig.ligaturesByContext[category]!!
    else if (!langConfig.contexts.contains(category))
      null
    else
      langConfig
  }

  private fun extendedLength(file: PsiFile, text: String, elem: PsiElement, category: ElementCategory?,
      baseLanguage: String, matchText: String, matchIndex: Int
  ): Int {
    val nextIndex = matchIndex + matchText.length

    if (nextIndex < 2 || nextIndex >= text.length)
      return 0

    val nextElem = file.findElementAt(nextIndex) ?: return 0
    val nextCategory = ElementCategorizer.categoryFor(nextElem, "", nextIndex)

    if (nextCategory != category)
      return 0

    val config = getMatchingConfiguration(elem, category, baseLanguage, matchText, matchIndex)

    if (config is LigaturesLimitedSettings.LanguageConfig && config.fullOnOrOff == false)
      return 0

    val matcher = if (config != null && (config as? LigaturesLimitedSettings.LanguageConfig)?.fullOnOrOff != true)
      config.ligaturesMatch else settings.extState!!.globalMatchLigatures!!
    val extendCandidate = text.substring(nextIndex - 2, nextIndex + 1)
    val searchStart = if (extendCandidate == "==/") 0 else 1 // Special case, since =/ by itself isn't a known ligature.

    return if (matcher.find(extendCandidate, searchStart) != null) 1 else 0
  }

  private fun shouldSuppressLigature(elem: PsiElement, inCategory: ElementCategory?, baseLanguage: String,
      matchText: String, matchIndex: Int
  ): Boolean {
    val config = getMatchingConfiguration(elem, inCategory, baseLanguage, matchText, matchIndex)

    if (config == null)
      return true
    else if (config is LigaturesLimitedSettings.LanguageConfig) {
      if (config.fullOnOrOff == true)
        return false
      else if (config.fullOnOrOff == false)
        return true
    }

    return config.ligatures.contains(matchText) == !config.ligaturesListedAreEnabled
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
      Pass.UPDATE_ALL,
      false,
      false
    )
  }

  override fun editorCreated(event: EditorFactoryEvent) {
    event.editor.caretModel.addCaretListener(this)
  }

  override fun editorReleased(event: EditorFactoryEvent) {
    val editor = event.editor

    if (currentFiles[editor] != null)
      currentEditors.remove(currentFiles[editor])

    highlightRechecks[editor]?.interrupt()
    highlightRechecks.remove(editor)
    currentFiles.remove(editor)
    cursorHighlighters.remove(editor)
    syntaxHighlighters.remove(editor)
    editor.caretModel.removeCaretListener(this)
  }

  override fun caretPositionChanged(event: CaretEvent) {
    highlightForCaret(event.editor, event.caret?.logicalPosition)
  }

  private fun highlightForCaret(editor: Editor, pos: LogicalPosition?) {
    if (pos == null || editor !is EditorImpl)
      return

    val doc = editor.document
    val markupModel = editor.filteredDocumentMarkupModel
    val oldHighlights = cursorHighlighters[editor]
    val mode = settings.state!!.cursorMode

    if (oldHighlights != null) {
      oldHighlights.forEach { highlighter -> markupModel.removeHighlighter(highlighter) }
      cursorHighlighters.remove(editor)
    }

    if (settings.state!!.cursorMode == CursorMode.OFF)
      return

    val file = currentFiles[editor] ?: return
    val ligatures = settings.extState!!.globalMatchLigatures
    val lineStart = doc.getLineStartOffset(pos.line)
    val lineEnd = if (pos.line < doc.lineCount - 1) doc.getLineStartOffset(pos.line + 1) else doc.textLength
    val line = doc.getText(TextRange(lineStart, lineEnd)).trimEnd()
    val debug = settings.state!!.debug
    val syntaxHighlighter = SyntaxHighlighterFactory.getSyntaxHighlighter(file.language, file.project, file.virtualFile)
    val defaultForeground = EditorColorsManager.getInstance().globalScheme.defaultForeground
    val background = if (debug) EditorColorsManager.getInstance().globalScheme.defaultForeground else null
    val newHighlights = ArrayList<RangeHighlighter>()

    ligatures?.findAll(line)?.forEach { lig ->
      val elem = file.findElementAt(lineStart + lig.range.first)

      if (elem != null) {
        val category = ElementCategorizer.categoryFor(elem, lig.value, lig.range.first)
        val extra = extendedLength(file, file.text, elem, category, getLanguageId(elem), lig.value,
          lineStart + lig.range.first)

        if (mode == CursorMode.LINE ||
            (mode == CursorMode.CURSOR && pos.column in lig.range.first..lig.range.last + extra + 1)) {
          for (i in lig.range.first..lig.range.last + extra) {
            val colors = if (!debug) getHighlightColors(elem, lig.value, lineStart + i, 1,
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

  private fun getHighlightColors(elem: PsiElement, ligature: String, textOffset: Int, span: Int,
      syntaxHighlighterIn: SyntaxHighlighter?, editor: EditorImpl?,
      colorsScheme: TextAttributesScheme, defaultForeground: Color,
      defaultHighlighters: List<RangeHighlighter>? = null): Array<Color?> {
    var color: Color? = null
    val startOffset = maxOf(elem.textRange.startOffset, textOffset)
    val endOffset = minOf(elem.textRange.endOffset, textOffset + span)
    val highlighters = defaultHighlighters ?: getHighlighters(editor)
    var maxLayer = -1
    var minSpan = Int.MAX_VALUE
    val startIndex = findFirstIndex(highlighters, startOffset)
    var syntaxHighlighter = syntaxHighlighterIn
    var hintType: IElementType? = null

    if (startIndex >= 0) {
      for (i in startIndex until highlighters.size) {
        val highlighter = highlighters[i]

        if (highlighter.startOffset <= startOffset && endOffset <= highlighter.endOffset) {
          val specificColor = highlighter.textAttributes!!.foregroundColor

          if (specificColor == null) {
            val (languageId, elemType) = getLanguageHints(highlighter.textAttributes)!!
            val languageInfo = languageLookup[languageId]

            hintType = elemType
            syntaxHighlighter = languageInfo?.syntaxHighlighter

            if (syntaxHighlighter == null && languageInfo != null && editor != null) {
              val file = currentFiles[editor]

              if (file != null)
                languageInfo.syntaxHighlighter = SyntaxHighlighterFactory.getSyntaxHighlighter(languageInfo.language,
                  file.project, file.virtualFile)
              }

            continue
          }

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

    if (color == null && editor?.highlighter is LexerEditorHighlighter && syntaxHighlighter == syntaxHighlighterIn) {
      try {
        val attrs = (editor.highlighter as LexerEditorHighlighter)
          .getAttributesForPreviousAndTypedChars(editor.document, textOffset, ligature[0])

        for (attr in attrs.reversed()) {
          color = attr.foregroundColor

          if (color != null)
            break
        }
      }
      catch (e: Exception) {}
    }

    if (color == null) {
      val type = hintType ?: elem.elementType
      val textAttrKeys = syntaxHighlighter?.getTokenHighlights(type)
      val textAttrs = if (textAttrKeys != null) getTextAttributes(colorsScheme, textAttrKeys) else null

      color = textAttrs?.foregroundColor ?: defaultForeground
    }

    return getMatchingColors(color)
  }

  private fun getHighlighters(editor: Editor?): List<RangeHighlighter>
  {
    if (editor !is EditorImpl)
      return listOf()

    val highlighters = arrayOf(*editor.markupModel.allHighlighters, *editor.filteredDocumentMarkupModel.allHighlighters)

    highlighters.sortWith(Comparator { a, b ->
      if (a.startOffset != b.startOffset) a.startOffset - b.startOffset else b.endOffset - a.endOffset
    })

    return highlighters.filter { h -> !isMyLayer(h.layer) &&
        (h.textAttributes?.foregroundColor != null || getLanguageHints(h.textAttributes) != null) }
  }

  data class LanguageHints(var languageId: String, var elemType: IElementType?)
  private fun getLanguageHints(textAttrs: TextAttributes?): LanguageHints? {
    if (textAttrs == null || textAttrs.foregroundColor != null || textAttrs.effectColor == null ||
        textAttrs.effectType != EffectType.WAVE_UNDERSCORE || textAttrs.effectColor !is ColorPayload)
      return null

    val color = textAttrs.effectColor as ColorPayload

    return LanguageHints(color.language, color.elementType)
  }

  // Not quite close enough to any pre-defined binary search to avoid handling this as a special case
  private fun findFirstIndex(highlighters: List<RangeHighlighter>, offset: Int): Int {
    if (highlighters.isEmpty())
      return -1

    var low = 0
    var high = highlighters.size - 1
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

  private fun getLanguageId(elem: PsiElement): String {
    var parent: PsiElement? = elem

    while ({ parent = parent?.parent; parent }() != null && parent !is PsiFile) {
      if (parent!!.elementType.toString().endsWith(":CODE_FENCE")) {
        if (parent!!.children.size > 1) {
          val lang = parent!!.children[1]

          if (lang.elementType.toString().endsWith(":FENCE_LANG"))
            return lang.text.trim().toLowerCase()
        }

        break
      }
    }

    return elem.language.idLc
  }

  class HighlightingPass(file: PsiFile, editor: Editor) :
      TextEditorHighlightingPass(file.project, editor.document, false) {
    override fun doCollectInformation(progress: ProgressIndicator) {}
    override fun doApplyInformationToEditor() {}
  }

  inner class HighlightRecheck(private var editor: Editor, private var syntaxHighlighter: SyntaxHighlighter,
      private var defaultForeground: Color, private var highlighters: ArrayList<LigatureHighlight>,
      private var count: Int) : Thread() {
    override fun run() {
      try {
        sleep(HIGHLIGHT_RECHECK_DELAY)
      }
      catch (e: InterruptedException) {
        return
      }

      ApplicationManager.getApplication().invokeLater {
        if (!highlightRechecks.contains(editor)) {
          highlightRechecks.remove(editor)
          applyHighlighters(editor, syntaxHighlighter, defaultForeground, highlighters, count)
        }
      }
    }
  }

  data class LigatureHighlight (
    var color: Color?,
    var elem: PsiElement,
    var ligature: String,
    var index: Int,
    var span: Int,
    var lastHighlighter: RangeHighlighter?,
    var lastBackground: RangeHighlighter?
  )

  data class LanguageInfo (
    var language: Language,
    var index: Int,
    var syntaxHighlighter: SyntaxHighlighter? = null
  )

  class ColorPayload(var elementType: IElementType?, var language: String): Color(0, true)

  companion object {
    private const val MY_LIGATURE_LAYER = HighlighterLayer.SELECTION + 33
    private const val MY_LIGATURE_BACKGROUND_LAYER = HighlighterLayer.SELECTION - 33
    private const val MY_SELECTION_LAYER = MY_LIGATURE_LAYER + 1

    private const val HIGHLIGHT_RECHECK_DELAY = 1000L // milliseconds
    private const val MAX_HIGHLIGHT_RECHECK_COUNT = 10

    private fun isMyLayer(layer: Int): Boolean {
      return layer == MY_LIGATURE_LAYER || layer == MY_LIGATURE_BACKGROUND_LAYER || layer == MY_SELECTION_LAYER
    }
    private val DEBUG_GREEN = Color(0x009900)
    private val DEBUG_RED = Color(0xDD0000)
    private val currentEditors = HashMap<PsiFile, Editor>()
    private val currentFiles = HashMap<Editor, PsiFile>()
    private val cursorHighlighters = HashMap<Editor, List<RangeHighlighter>>()
    private val syntaxHighlighters = HashMap<Editor, List<RangeHighlighter>>()
    private val highlightRechecks = ConcurrentHashMap<Editor, HighlightRecheck>()
    private var nextLanguageId = 0

    private val cachedColors: MutableMap<Color, Array<Color?>> = HashMap()
    private val noColors = arrayOf<Color?>(null, null)

    private val languageLookup = mutableMapOf<String, LanguageInfo>()
    private val languageIndexLookup = mutableMapOf<Int, LanguageInfo>()

    init {
      for (language in LanguageUtil.getFileLanguages()) {
        val li = LanguageInfo(language, ++nextLanguageId)
        val id = language.idLc

        languageLookup[id] = li
        languageIndexLookup[nextLanguageId] = li
      }
    }

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

    val Language.idLc get(): String = this.id.toLowerCase().replace(' ', '_')
  }
}
