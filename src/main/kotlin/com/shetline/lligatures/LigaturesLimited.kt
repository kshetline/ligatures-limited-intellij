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
import com.intellij.notification.NotificationDisplayType
import com.intellij.notification.NotificationGroup
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.TextAttributesScheme
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.ex.RangeHighlighterEx
import com.intellij.openapi.editor.ex.util.LexerEditorHighlighter
import com.intellij.openapi.editor.impl.EditorImpl
import com.intellij.openapi.editor.impl.event.MarkupModelListener
import com.intellij.openapi.editor.markup.*
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.progress.ProcessCanceledException
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
import java.io.PrintWriter
import java.io.StringWriter
import java.lang.Thread.sleep

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
      file: PsiFile, updateWholeFile: Boolean, holder: HighlightInfoHolder, action: Runnable): Boolean {
    action.run()

    try {
      searchForLigatures(file, holder)
    }
    catch (e: ProcessCanceledException) {
      throw e
    }
    catch (e: Exception) {
      notify("Exception during searchForLigatures: ${e.message ?: e.javaClass.simpleName}; ${stackTraceAsString(e)}")
    }

    return true
  }

  override fun visit(elem: PsiElement) {}

  private fun searchForLigatures(file: PsiFile, holder: HighlightInfoHolder) {
    val text = file.text
    val languageId = if (file.context != null) file.language.idNormalized else null
    val languageInfo = if (languageId != null) languageLookup[languageId] else null
    val editor = currentEditors[file]

    if (languageInfo != null && languageInfo.syntaxHighlighter == null)
      languageInfo.syntaxHighlighter = SyntaxHighlighterFactory.getSyntaxHighlighter(file.language,
        file.project, file.virtualFile)

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
    val newSpans = ArrayList<LigatureSpan>()
    var lastDebugSpan: LigatureSpan? = null
    var lastDebugCategory: ElementCategory? = null

    while (index < text.length && { match = ligatures.find(text, index); match }() != null) {
      val matchIndex = match!!.range.first
      val matchText = match!!.groupValues[0]
      val elem = file.findElementAt(matchIndex)

      index = (matchIndex + matchText.length).coerceAtLeast(index + 1)
      ProgressManager.checkCanceled()

      if (elem == null)
        continue

      val category = ElementCategorizer.categoryFor(elem, matchText, matchIndex)
      val elemLanguageId = getLanguageId(elem, file)
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

          newSpans.add(LigatureSpan(foreground, elem, category, matchText, matchIndex + i, 1, null, null))
        }

        lastDebugSpan = null
      }
      else if (debug) {
        val diff = if (lastDebugSpan != null)
          lastDebugSpan.index + lastDebugSpan.span - lastDebugSpan.index else 0

        if (lastDebugSpan != null && (diff == 0 || diff == 1) && lastDebugCategory == category)
          lastDebugSpan.span += matchText.length + extra
        else {
          lastDebugSpan = LigatureSpan(DEBUG_GREEN, elem, category, matchText, matchIndex,
            matchText.length + extra, null, null)
          lastDebugCategory = category
          newSpans.add(lastDebugSpan)
        }
      }
    }

    val hIndex = if (lastDebugSpan != null) lastDebugSpan.index + lastDebugSpan.span else -1

    if (hIndex > 0 && hIndex < text.length - 1) {
      val extensionCandidate = text.substring(hIndex - 1, hIndex + 1)
      match = ligatures.find(text, index)

      if (match != null) {
        val elem = file.findElementAt(hIndex)

        if (elem != null) {
          val debugCategory = ElementCategorizer.categoryFor(elem, extensionCandidate, (hIndex - 1))

          if (debugCategory == lastDebugCategory)
            ++lastDebugSpan!!.span
        }
      }
    }

    if (editor != null && languageId == null) {
      if (editor is EditorImpl && !markupListeners.containsKey(editor))
        EditorMarkupListener(file, editor)

      ApplicationManager.getApplication().invokeLater {
        removePreviousHighlights(editor)
        cleanUpStrayHighlights(editor)

        if (newSpans.size > 0) {
          markupListeners[editor]?.spans = newSpans
          applyLigatureSpans(editor, syntaxHighlighter, defaultForeground, newSpans)
        }

        highlightForCaret(editor, editor.caretModel.logicalPosition)
      }
    }
  }

  private fun applyLigatureSpans(editor: Editor, syntaxHighlighter: SyntaxHighlighter,
                                 defaultForeground: Color, spans: ArrayList<LigatureSpan>) {
    if (editor !is EditorImpl || spans.isEmpty())
      return

    val markupModel = editor.filteredDocumentMarkupModel
    val newHighlights = ArrayList<RangeHighlighter>()
    val existingHighlighters = getHighlighters(editor)

    for (span in spans) {
      val index = span.index % 2
      val foreground = span.color ?:
        getHighlightColors(span.elem, span.category, span.ligature,
          span.index, span.span, syntaxHighlighter, editor, editor.colorsScheme,
          defaultForeground, existingHighlighters)?.getOrNull(index)
      val background = if (span.color != null) defaultForeground else null

      if (foreground === DONT_SUPPRESS)
        continue

      try {
        var needToAdd = true
        var newHighlight: RangeHighlighter? = null
        val highlightEnd = span.index + span.span
        val style = if (foreground == null) BREAK_STYLE shl index else 0

        if (span.lastHighlighter != null) {
          val attrs = span.lastHighlighter?.textAttributes

          if (attrs != null && foreground == attrs.foregroundColor && attrs.fontType == style) {
            needToAdd = false
            newHighlight = span.lastHighlighter!!
          }
          else
            markupModel.removeHighlighter(span.lastHighlighter!!)
        }

        if (needToAdd && highlightEnd < editor.document.textLength) {
          newHighlight = markupModel.addRangeHighlighter(
            span.index, highlightEnd, MY_LIGATURE_LAYER,
            TextAttributes(foreground, null, null, EffectType.BOXED, style),
            HighlighterTargetArea.EXACT_RANGE
          )
        }

        if (newHighlight != null) {
          newHighlights.add(newHighlight)
          span.lastHighlighter = newHighlight
        }

        if (background != null) {
          if (span.lastBackground == null && highlightEnd < editor.document.textLength) {
            // Apply background at lower layer so selection layer can override it
            span.lastBackground = markupModel.addRangeHighlighter(
              span.index, highlightEnd, MY_LIGATURE_BACKGROUND_LAYER,
              TextAttributes(null, background, null, EffectType.BOXED, 0),
              HighlighterTargetArea.EXACT_RANGE)
          }

          newHighlights.add(span.lastBackground!!)
        }
      }
      catch (e: Exception) {
        notify("Exception during applyHighlighters: ${e.message ?: e.javaClass.simpleName}; ${stackTraceAsString(e)}")
      }
    }

    syntaxHighlighters[editor] = newHighlights
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

    return config.ligaturesMatch.matches(matchText) == !config.ligaturesListedAreEnabled
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

    currentFiles.remove(editor)
    cursorHighlighters.remove(editor)
    syntaxHighlighters.remove(editor)
    markupListeners.remove(editor)
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
      oldHighlights.forEach { markupModel.removeHighlighter(it) }
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
        val extra = extendedLength(file, file.text, elem, category, getLanguageId(elem, file), lig.value,
          lineStart + lig.range.first)

        if (mode == CursorMode.LINE ||
            (mode == CursorMode.CURSOR && pos.column in lig.range.first..lig.range.last + extra + 1)) {
          for (i in lig.range.first..lig.range.last + extra) {
            val colors = if (!debug) getHighlightColors(elem, category, lig.value, lineStart + i, 1,
                  syntaxHighlighter, editor, editor.colorsScheme, defaultForeground)
                else getMatchingColors(DEBUG_RED)
            val index = (lineStart + i) % 2
            val color = colors?.getOrNull(index)
            val style = if (color == null) BREAK_STYLE shl index else 0

            if (color !== DONT_SUPPRESS && lineStart + i + 1 < editor.document.textLength)
              newHighlights.add(markupModel.addRangeHighlighter(
                lineStart + i, lineStart + i + 1, MY_SELECTION_LAYER,
                TextAttributes(color, background, null, EffectType.BOXED, style),
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

  private fun getHighlightColors(elem: PsiElement, category: ElementCategory?, ligature: String,
      textOffset: Int, span: Int, syntaxHighlighterIn: SyntaxHighlighter?, editor: EditorImpl?,
      colorsScheme: TextAttributesScheme, defaultForeground: Color,
      defaultHighlighters: List<RangeHighlighter>? = null): Array<Color?>? {
    var color: Color? = null
    val startOffset = maxOf(elem.textRange.startOffset, textOffset)
    val endOffset = minOf(elem.textRange.endOffset, textOffset + span)
    val highlighters = defaultHighlighters ?: getHighlighters(editor)
    var maxLayer = -1
    var minSpan = Int.MAX_VALUE
    val startIndex = findFirstIndex(highlighters, startOffset)
    var syntaxHighlighter = syntaxHighlighterIn
    var hintType: IElementType? = null
    var style = 0

    if (startIndex >= 0) {
      for (i in startIndex until highlighters.size) {
        val highlighter = highlighters[i]

        if (highlighter.startOffset <= startOffset && endOffset <= highlighter.endOffset) {
          style = style or (highlighter.textAttributes!!.fontType and STYLE_MASK)

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

    if (color == null && isWordy(ligature)) {
      if (style == 0 || !PROBABLY_SAFE_WITH_LEXER_COLOR.contains(category))
        return null
      else
        color = DONT_SUPPRESS
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

    val highlighters = mutableListOf(*editor.markupModel.allHighlighters, *editor.filteredDocumentMarkupModel.allHighlighters)

    markupListeners[editor]?.clearAccumulatedChanges()

    highlighters.sortWith(Comparator { a, b ->
      if (a.startOffset != b.startOffset) a.startOffset - b.startOffset else a.endOffset - b.endOffset
    })

    return highlighters.filter { h -> !isMyLayer(h.layer) && h.layer < HighlighterLayer.HYPERLINK &&
        (h.textAttributes?.foregroundColor != null || getLanguageHints(h.textAttributes) != null) }
  }

  private fun removePreviousHighlights(editor: Editor?) {
    val oldHighlighters = syntaxHighlighters[editor]

    if (oldHighlighters != null && editor is EditorImpl) {
      oldHighlighters.forEach { editor.filteredDocumentMarkupModel.removeHighlighter(it) }
      syntaxHighlighters.remove(editor)
    }
  }

  private fun cleanUpStrayHighlights(editor: Editor?)
  {
    if (editor !is EditorImpl)
      return

    val highlighters = arrayOf(*editor.filteredDocumentMarkupModel.allHighlighters)

    highlighters.sortWith(Comparator { a, b ->
      if (a.startOffset != b.startOffset) a.startOffset - b.startOffset else b.endOffset - a.endOffset
    })

    val strays = highlighters.filter { h -> isMyLayer(h.layer) &&
        (h.textAttributes?.foregroundColor is LLColor || (h.textAttributes?.fontType ?: 0) >= BREAK_STYLE) }

    strays.forEach { editor.filteredDocumentMarkupModel.removeHighlighter(it) }
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

    return elem.language.idNormalized
  }

  private fun getLanguageId(elem: PsiElement, file: PsiFile): String {
    var id = getLanguageId(elem)
    val fileId = file.language.rootLanguage.idNormalized

    if (fileId == id)
      id = file.language.idNormalized

    return id
  }

  class HighlightingPass(file: PsiFile, editor: Editor) :
      TextEditorHighlightingPass(file.project, editor.document, false) {
    override fun doCollectInformation(progress: ProgressIndicator) {}
    override fun doApplyInformationToEditor() {}
  }

  inner class EditorMarkupListener(private val file: PsiFile, private val editor: EditorImpl) : MarkupModelListener {
    private var changes = 0
    private var updater: Thread? = null
    private var _spans: ArrayList<LigatureSpan>? = null

    init {
      editor.markupModel.addMarkupModelListener({}, this)
      editor.filteredDocumentMarkupModel.addMarkupModelListener({}, this)
      markupListeners[editor] = this
    }

    var spans: ArrayList<LigatureSpan>?
      @Synchronized get() = _spans
      @Synchronized set(value) { _spans = value }

    @Synchronized
    override fun attributesChanged(highlighter: RangeHighlighterEx, renderersChanged: Boolean,
        fontStyleOrColorChanged: Boolean) {
      if (doICare(highlighter) || renderersChanged || fontStyleOrColorChanged) {
        ++changes
        checkUpdater()
      }
    }

    override fun beforeRemoved(highlighter: RangeHighlighterEx) {
      attributesChanged(highlighter, renderersChanged = false, fontStyleOrColorChanged = false)
    }

    override fun afterAdded(highlighter: RangeHighlighterEx) {
      attributesChanged(highlighter, renderersChanged = false, fontStyleOrColorChanged = false)
    }

    @Synchronized
    fun clearAccumulatedChanges() {
      changes = 0
      checkUpdater()
    }

    private fun checkUpdater() {
      if (changes > 0 && updater == null) {
        updater = Thread {
          try {
            sleep(UPDATE_DEBOUNCE)
          }
          catch (e: InterruptedException) {
            return@Thread
          }
          finally {
            updater = null
          }

          ApplicationManager.getApplication().invokeLater @Synchronized {
            if (changes > 0 && _spans != null) {
              val syntaxHighlighter = SyntaxHighlighterFactory.getSyntaxHighlighter(file.language, file.project,
                file.virtualFile)
              val defaultForeground = EditorColorsManager.getInstance().globalScheme.defaultForeground

              applyLigatureSpans(editor, syntaxHighlighter, defaultForeground, _spans!!)
              highlightForCaret(editor, editor.caretModel.logicalPosition)
            }
          }
        }

        updater!!.start()
      }
      else if (changes == 0 && updater != null)
        updater!!.interrupt()
    }

    private fun doICare(highlighter: RangeHighlighterEx) = !isMyLayer(highlighter.layer) &&
        highlighter.textAttributes?.foregroundColor != null
  }

  data class LigatureSpan (
    var color: Color?,
    var elem: PsiElement,
    var category: ElementCategory?,
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
  class LLColor(rgb: Int) : Color(rgb)

  companion object {
    private const val MY_LIGATURE_BACKGROUND_LAYER = HighlighterLayer.HYPERLINK - 3
    private const val MY_LIGATURE_LAYER = HighlighterLayer.HYPERLINK - 2
    private const val MY_SELECTION_LAYER = HighlighterLayer.HYPERLINK - 1
    private const val STYLE_MASK = 0x3
    private const val BREAK_STYLE = 0x4 // A fake font style one bit position higher than BOLD or ITALIC
    private const val UPDATE_DEBOUNCE = 500L

    private val DONT_SUPPRESS = LLColor(0x808080)
    private val PROBABLY_SAFE_WITH_LEXER_COLOR = setOf(
      ElementCategory.BLOCK_COMMENT, ElementCategory.LINE_COMMENT, ElementCategory.STRING,
      ElementCategory.NUMBER, ElementCategory.OPERATOR, ElementCategory.REGEXP, ElementCategory.STRING,
      ElementCategory.TEXT)
    private val NOTIFIER = NotificationGroup("Ligatures Limited", NotificationDisplayType.NONE, true)

    private val DEBUG_GREEN = Color(0x009900)
    private val DEBUG_RED = Color(0xDD0000)
    private val currentEditors = mutableMapOf<PsiFile, Editor>()
    private val currentFiles = mutableMapOf<Editor, PsiFile>()
    private val cursorHighlighters = mutableMapOf<Editor, List<RangeHighlighter>>()
    private val syntaxHighlighters = mutableMapOf<Editor, List<RangeHighlighter>>()
    private val markupListeners = mutableMapOf<Editor, EditorMarkupListener>()
    private var nextLanguageId = 0

    private val cachedColors = mutableMapOf<Color, Array<Color?>>()
    private val NO_COLORS = arrayOf<Color?>(null, null)

    private val languageLookup = mutableMapOf<String, LanguageInfo>()
    private val languageIndexLookup = mutableMapOf<Int, LanguageInfo>()
    private val cannedLanguageList = """
      asp, css, dtd, editorconfig, genericsql, gitexclude, gitignore, html, java, javascript, json, json5, jsp,
      jspx, jsregexp, kotlin, less, manifest, markdown, mysql, properties, regexp, sass, scss, shell_script, sql,
      sqlite, svg, text, typescript, typescript_jsx, xhtml, xml, yaml""".trim().split(Regex("""\s*,\s*""")).toSet()

    init {
      for (language in LanguageUtil.getFileLanguages()) {
        val li = LanguageInfo(language, ++nextLanguageId)
        val id = language.idLc

        languageLookup[id] = li
        languageIndexLookup[nextLanguageId] = li
      }
    }

    private fun isMyLayer(layer: Int): Boolean {
      return layer == MY_LIGATURE_LAYER || layer == MY_LIGATURE_BACKGROUND_LAYER || layer == MY_SELECTION_LAYER
    }

    fun notify(message: String, notificationType: NotificationType = NotificationType.ERROR) {
      println("$notificationType: $message")
      NOTIFIER.createNotification(message, notificationType).notify(null)
    }

    private fun stackTraceAsString(t: Throwable): String {
      val sw = StringWriter()

      t.printStackTrace(PrintWriter(sw))

      return sw.toString()
    }

    fun hasLanguage(idNormalized: String) =
      cannedLanguageList.contains(idNormalized) || languageLookup.containsKey(idNormalized)

    private fun getMatchingColors(color: Color?): Array<Color?> {
      if (color == null)
        return NO_COLORS
      else if (color === DONT_SUPPRESS)
        return arrayOf(DONT_SUPPRESS, DONT_SUPPRESS)
      else if (!cachedColors.containsKey(color)) {
        val alpha = color.rgb and 0xFF000000.toInt()
        val rgb = color.rgb and 0x00FFFFFF

        if (color.blue > 253)
          cachedColors[color] = arrayOf<Color?>(LLColor(alpha or (rgb - 1)), LLColor(alpha or (rgb - 2)))
        else
          cachedColors[color] = arrayOf<Color?>(LLColor(alpha or (rgb + 1)), LLColor(alpha or (rgb + 2)))
      }

      return cachedColors[color]!!
    }

    private fun isWordy(s: String) = Regex("""\w+""").matches(s)

    fun normalizeLanguageId(id: String, handleAltNames: Boolean = false): String {
      var newId = id.toLowerCase().replace(' ', '_')

      if (handleAltNames)
        newId = newId.replace(Regex("""ecma.?script.*"""), "javascript")

      return newId
    }

    val Language.idNormalized get(): String = normalizeLanguageId(this.id, true)

    private val Language.idLc get(): String = normalizeLanguageId(this.id)

    val Language.rootLanguage get(): Language {
      var lang = this

      while (lang.baseLanguage != null)
        lang = lang.baseLanguage!!

      return lang
    }
  }
}
