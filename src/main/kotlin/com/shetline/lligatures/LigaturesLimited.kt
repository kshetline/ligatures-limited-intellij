package com.shetline.lligatures

import com.intellij.codeHighlighting.*
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.HighlightInfoType
import com.intellij.codeInsight.daemon.impl.HighlightVisitor
import com.intellij.codeInsight.daemon.impl.analysis.HighlightInfoHolder
import com.intellij.ide.AppLifecycleListener
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.util.xmlb.XmlSerializerUtil.copyBean
import org.jetbrains.annotations.Nullable


class LigaturesLimited : PersistentStateComponent<LigaturesLimited>, AppLifecycleListener, HighlightVisitor, TextEditorHighlightingPassFactory,
    TextEditorHighlightingPassFactoryRegistrar, EditorColorsListener {
  private var highlightInfoHolder : HighlightInfoHolder? = null
  private var baseLanguage: String? = null
  private val ligatureHighlight: HighlightInfoType = HighlightInfoType
          .HighlightInfoTypeImpl(HighlightSeverity.INFORMATION, DefaultLanguageHighlighterColors.CONSTANT)

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
      println(globalMatchLigatures)
      println(globalMatchLigatures.matches(":"))
      println(globalMatchLigatures.matches("0xB"))
    }
  }

  override fun appFrameCreated(commandLineArgs: MutableList<String>) {
    println("*** appFrameCreated")
  }

  override fun suitableForFile(file: PsiFile): Boolean {
    println("*** suitableForFile, ${file.name}")

    return true
  }

  override fun analyze(
      file: PsiFile, updateWholeFile: Boolean, holder: HighlightInfoHolder, action: Runnable
  ): Boolean {
    println("******** analyze, ${file.name}, ${file.textLength} ********")
    highlightInfoHolder = holder
    baseLanguage = file.language.id
    searchForLigatures(file, holder)
    println("******** analyze-done, ${file.name} ********")
    highlightInfoHolder = null

    return true
  }

  override fun visit(element: PsiElement) {}

  private fun searchForLigatures(file: PsiFile, holder: HighlightInfoHolder) {
    val text = file.text
    var match: MatchResult? = null
    var index = 0
    var syntaxHighlighter = SyntaxHighlighterFactory.getSyntaxHighlighter(file.language, null, null)
    var phase = 0

    while ({ match = globalMatchLigatures.find(text, index); match }() != null) {
      println("match: ${match!!.groupValues[0]}")
      val matchIndex = match!!.range.first
      val elem = file.findElementAt(matchIndex)

      if (elem != null) {
        var displayText = elem.text.replace(Regex("""\r\n|\r|\n"""), "↵ ")
        val matchText = match!!.groupValues[0]
        val type = elem.node.elementType
        val textAttrKeys = syntaxHighlighter.getTokenHighlights(type)
        val fontType = if (textAttrKeys.isNotEmpty()) textAttrKeys[0].defaultAttributes.fontType else 0

        if (displayText.length > 40)
          displayText = displayText.substring(0, 40)
        // println("visit: ${elem.containingFile.fileType.name}, $baseLanguage, ${elem.language}, $type, $displayText")

        if (type.toString().contains(Regex("""(\b|(?:_))(STRING|COMMENT)(\b|(?:_))""", RegexOption.IGNORE_CASE))) {
          for (i in matchText.indices) {
            println("$matchText, $fontType")
            highlightInfoHolder?.add(
              HighlightInfo
              .newHighlightInfo(ligatureHighlight)
              .textAttributes(TextAttributes(null, null, null, EffectType.BOXED, (if (phase == 0) 0x2000 else 0x4000) + fontType))
              .range(elem, matchIndex + i, matchIndex + i + 1)
              .create())
            phase = phase xor 1
          }
        }
      }

      index = (match!!.range.first + match!!.groupValues[0].length).coerceAtLeast(index + 1)
      // println("index: $index");

      if (index > text.length)
        break
    }
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
}
