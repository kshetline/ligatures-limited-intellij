package com.shetline.lligatures

import com.intellij.codeHighlighting.*
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.HighlightInfoType
import com.intellij.codeInsight.daemon.impl.HighlightVisitor
import com.intellij.codeInsight.daemon.impl.analysis.HighlightInfoHolder
import com.intellij.ide.AppLifecycleListener
import com.intellij.lang.Language
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.util.xmlb.XmlSerializerUtil.copyBean
import org.jetbrains.annotations.Nullable
import java.awt.Color

class LigaturesLimited : PersistentStateComponent<LigaturesLimited>, AppLifecycleListener, HighlightVisitor,
    TextEditorHighlightingPassFactory, TextEditorHighlightingPassFactoryRegistrar, EditorColorsListener {
  private val ligatureHighlight: HighlightInfoType = HighlightInfoType
          .HighlightInfoTypeImpl(HighlightSeverity.INFORMATION, DefaultLanguageHighlighterColors.CONSTANT)
  private val debugCategories = false

  override fun appFrameCreated(commandLineArgs: MutableList<String>) {}

  override fun suitableForFile(file: PsiFile): Boolean = true

  override fun analyze(file: PsiFile, updateWholeFile: Boolean, holder: HighlightInfoHolder, action: Runnable
  ): Boolean {
    println("******** analyze, ${file.name}, ${file.textLength} ********")
    action.run()
    searchForLigatures(file, holder)
    println("******** analyze-done, ${file.name} ********")

    return true
  }

  override fun visit(element: PsiElement) {}

  private fun searchForLigatures(file: PsiFile, holder: HighlightInfoHolder) {
    val text = file.text

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
        val type = elem.node.elementType
        val textAttrKeys = syntaxHighlighter.getTokenHighlights(type)
        val textAttrs = if (textAttrKeys.isNotEmpty()) holder.colorsScheme.getAttributes(textAttrKeys[0]) else null
        val color = textAttrs?.foregroundColor ?: defaultForeground
        val colors = getMatchingColors(color)
        val fontType = textAttrs?.fontType ?: 0

        if (shouldSuppressLigature(elem, file.language, matchText, matchIndex)) {
          for (i in matchText.indices) {
            holder.add(
              HighlightInfo
              .newHighlightInfo(ligatureHighlight)
              .textAttributes(TextAttributes(colors[phase], null, null, EffectType.BOXED, fontType))
              .range(elem, matchIndex + i, matchIndex + i + 1)
              .create())
            phase = phase xor 1
          }
        }
      }

      index = (matchIndex + matchText.length).coerceAtLeast(index + 1)
    }
  }

  private fun shouldSuppressLigature(element: PsiElement, baseLanguage: Language?,
      matchText: String, matchIndex: Int): Boolean {
    val category = ElementCategorizer.categoryFor(element, matchText, matchIndex)

    return category != ElementCategory.OPERATOR && category != ElementCategory.PUNCTUATION
  }

  override fun clone(): HighlightVisitor = LigaturesLimited()

  override fun createHighlightingPass(file: PsiFile, editor: Editor): TextEditorHighlightingPass? {
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

  @Nullable
  override fun getState() = this

  override fun loadState(state: LigaturesLimited) {
    copyBean(state, this)
  }

  override fun globalSchemeChange(scheme: EditorColorsScheme?) {
    // Do nothing
  }

  companion object {
    private val baseLigatures = ("""

.= .- := =:= == != === !== =/= <-< <<- <-- <- <-> -> --> ->> >-> <=< <<= <== <=> => ==>
=>> >=> >>= >>- >- <~> -< -<< =<< <~~ <~ ~~ ~> ~~> <<< << <= <> >= >> >>> {. {| [| <: :> |] |} .}
<||| <|| <| <|> |> ||> |||> <$ <$> $> <+ <+> +> <* <*> *> \\ \\\ \* /* */ /// // <// <!-- </> --> />
;; :: ::: .. ... ..< !! ?? %% && || ?. ?: ++ +++ -- --- ** *** ~= ~- www ff fi fl ffi ffl 0xF 9x9
-~ ~@ ^= ?= /= /== |= ||= #! ## ### #### #{ #[ ]# #( #? #_ #_(

""").trim().split(Regex("""\s+"""))
    private val escapeRegex = Regex("""[-\[\]\/{}()*+?.\\^$|]""")
    private val globalMatchLigatures: Regex

    init {
      val sorted = baseLigatures.sortedWith(Comparator { a, b -> b.length - a.length })
      val escaped = sorted.map{ lg -> (lg.replace(escapeRegex) { matchResult -> "\\" + matchResult.value })
            .replace("0xF", "0x[0-9a-fA-F]")
            .replace("9x9", "\\dx\\d") }
      globalMatchLigatures = Regex(escaped.joinToString("|"))
    }

    private val cachedColors: MutableMap<Color, Array<Color>> = HashMap()

    fun getMatchingColors(color: Color): Array<Color> {
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
