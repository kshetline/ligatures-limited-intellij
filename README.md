# Ligatures Limited

## Code ligatures _only where you want them_, not where you don’t

> Note: This early release is almost certain to have a few lurking bugs. Please don’t be shy about reporting bugs back to me. The most likely bugs will involve handling of ligatures in programming languages I don’t commonly use myself.

I enjoy using ligature fonts for coding so that symbols like arrows (<img src="https://shetline.com/readme/ligatures-limited-ij/v0.0.1/fat_arrow_nolig.png" width="16" height="14" align="absmiddle" style="display: inline-block; position: relative; top: -0.075em" alt="fat arrow no ligature">) look like arrows (<img src="https://shetline.com/readme/ligatures-limited-ij/v0.0.1/fat_arrow.png" width="17" height="14" align="absmiddle" style="display: inline-block; position: relative; top: -0.075em" alt="fat arrow ligature">) and does-not-equal signs (<img src="https://shetline.com/readme/ligatures-limited-ij/v0.0.1/not_equal_nolig.png" width="17" height="14" align="absmiddle" style="display: inline-block; position: relative; top: -0.075em" alt="not-equal no ligature">) look like the real thing from math class (<img src="https://shetline.com/readme/ligatures-limited-ij/v0.0.1/not_equal.png" width="17" height="14" align="absmiddle" style="display: inline-block; position: relative; top: -0.075em" alt="not-equal ligature">). The problem is that, even with the contextual smarts built into ligature fonts like Fira Code, ligatures have a knack of popping up where you don’t want them.

<img src="https://shetline.com/readme/ligatures-limited-ij/v0.0.1/without_suppressed_ligatures.jpg" width="407" height="67" alt="Without ligature suppression">
<br>
<img src="https://shetline.com/readme/ligatures-limited-ij/v0.0.1/with_suppressed_ligatures.jpg" width="407" height="67" alt="With ligature suppression">

*Ligatures Limited* is designed to make the rendering of ligatures more context-dependent.

In the top image, you can see ligatures that don’t make sense where they are — in the regex, where three different characters are being checked (not one big two-headed arrow), and the oddly formatted asterisks in the message string.

The image below shows how those out-of-place ligatures are suppressed by *Ligatures Limited*, replaced with individual characters, while the triple-equals and double-ampersand ligatures are retained.

With the default settings for this plugin ligatures are only rendered in three contexts: _operators_, _punctuation_, and _comment markers_, plus three special cases: <img src="https://shetline.com/readme/ligatures-limited-ij/v0.0.1/0x_nolig.png" width="16" height="14" align="absmiddle" style="display: inline-block; position: relative; top: -0.075em" alt="0x ligature"> when followed by a hexadecimal digit in a numeric context, rendered as `0×` (if supported by your chosen font), and a similar pattern, `0o7` for octal numbers, and `0b1` for binary numbers as well.

Also, by default, the special case of `x` between any two decimal digits is suppressed, which would render as (for example) `2×4`. If you want to see these ligatures rendered in any or all contexts, (provided, of course, that your chosen font defines them), you must expressly enable them.

Ligatures can also be suppressed (with individual characters shown instead) at the current insert cursor position (this is the default setting), or for all the current line being edited. This feature can be turned off entirely as well, so that the cursor position or text selection have no effect.

The ligatures `ff`, `fi`, `fl`, `ffi`, and `ffl` are by default rendered in all contexts. You can change your configuration suppress these ligatures, which you may wish to do when using a font which, for instance, renders `fi` within the width of a single character, instead of as two characters (see [Disregarded Ligatures](#disregarded-ligatures)).

While the default settings should meet many users’ needs, custom settings are available to control which syntactical contexts are handled, and which particular ligatures are displayed or suppressed. Settings can be global or on a per-language basis (say, different rules for JavaScript than for Python, if you wish).

## Prerequisites

In order to take advantage of *Ligatures Limited*, you must first select a ligature font like [Fira Code](https://github.com/tonsky/FiraCode) or Jet Brains Mono, and enable ligature support (in the Settings/Preferences dialog, Editor → Font).

## Ligatures handled by *Ligatures Limited*

As rendered using Fira Code:<br>
<img src="https://shetline.com/readme/ligatures-limited-ij/v0.0.1/ligature_set.jpg" width="763" height="136" alt="Supported ligatures">

Fira Code again, but with all ligatures suppressed:<br>
<img src="https://shetline.com/readme/ligatures-limited-ij/v0.0.1/ligature_set_suppressed.jpg" width="763" height="136" alt="Supported ligatures as individual characters">

 <img src="https://shetline.com/readme/ligatures-limited-ij/v0.0.1/0xF_nolig.png" width="24" height="14" align="absmiddle" style="display: inline-block; position: relative; top: -0.075em" alt="0xF ligature"> represents `0x` followed by any hexadecimal digit, and <img src="https://shetline.com/readme/ligatures-limited-ij/v0.0.1/9x9_nolig.png" width="24" height="14" align="absmiddle" style="display: inline-block; position: relative; top: -0.075em" alt="9x9 ligature"> represents `x` surrounded by any decimal digits. You can specify your own additional ligatures if you need _Ligatures Limited_ to be aware of them.

The bottom row are indefinite-width ligatures. When specifying these ligatures, _three_ equals signs (`=`), dashes (`-`), or tildes (`~`) represent three *or more* of those signs, except for the first three ligatures, where _four_ equals signs represent four or more.

`####` is another indefinite-width ligature, representing four or more number signs (`#`) in a row.

## Plugin Settings

These settings are found in the Settings/Preferences dialog, Editor → Ligatures Limited.

### Cursor Mode

As mentioned earlier, ligatures can be suppressed based on the current cursor position. Here you can select from OFF, CURSOR, or LINE.

### Debug

  This allows you to see where *Ligatures Limited* is acting upon the styling of your code. Ligatures which have been disabled will appear in red with an inverse background, and ligatures which have
  been enabled will appear in green with an inverse background.

### Advanced settings

The **Advanced Settings** allow you, via JSON (with comments allowed), to fine-tune the rules by which ligatures are either enabled or suppressed. In lieu of a long-winded explanation of the Advanced settings, a bit of explanation and some examples should do.

#### Contexts

A context is a simplified token category, from the table below:

```text
attribute_name  attribute_value block_comment   comment_marker  constant
identifier      keyword         line_comment    number          operator
other           punctuation     regexp          string          tag
text            whitespace
```

The rules for how *Ligatures Limited* handles ligatures can be summed up by the way you answer the question: “Which ligatures do I want to see, and which do I want suppressed, in which contexts and in which languages?”

#### By-language and by-context settings

A language specifier can be a comma-separated list of languages, and a context specifier for by-context rules can be a comma-separated list of contexts. These are some possible language specifiers:

> aidl, androiddatabinding, angular2html, angular2svg, angularjs, asp, aspectj, cassandraql, cfml, clickhouse, coffeescript, cookie, css, db2, db2_is, db2_zos, decompiled_swf, derby, drools, dtd, ecma_script_level_4, ecmascript_6, editorconfig, exasol, flow_js, ftl, genericsql, gherkin, gitexclude, gitignore, greenplum, groovy, gsp, h2, haml, hgignore, hiveql, hsqldb, html, http_request, integrationperformancetest, java, javascript, jql, jquery-css, jshelllanguage, json, json5, jsp, jspx, jsregexp, jsunicoderegexp, jsx_harmony, knd, kotlin, less, manifest, mariadb, markdown, mxml, mysql, nashorn_js, oracle, oraclesqlplus, play, postgresql, proguard, properties, redshift, regexp, relax-ng, renderscript, sass, scss, shell_script, smali, snowflake, sparksql, sql, sql92, sqlite, stylus, svg, sybase, text, tml, tsql, typescript, typescript_jsx, vertica, xhtml, xml, xpath, xpath2, xsdregexp, yaml

These language specifiers are case-insensitive, and spaces have been replaced by underscores.

Rules for a language can be inherited from rules for another language by using the `inherit` property.

#### Rules hierarchy

* The global context list, disregarded ligature list, and ligature list
* Global by-context ligature lists
* When language inheritance is used, the rules of the inherited language
* Language-specific context lists and ligature lists.
* Language-specific by-context ligature lists.

#### Context lists

Ligatures are _not_ rendered unless they appear inside an explicitly listed context. These contexts are by default very limited since the value of most ligatures is their use as operators and punctuation. To add or remove contexts, simply list them in a space-separated string like this:

`"regexp line_comment - block_comment"`

Any contexts following the `-` sign are _removed_ from any inherited context list. The above example enables ligatures in regular expressions and line comments, but disables them in block comments.

#### Ligature lists

These work much like context lists, except that an explicit `+` sign is needed to enable ligatures. Ligatures listed before either a plus or minus sign are simply added to the list of ligatures for which any action will be taken at all.

`"+ <=> ==> - ## ###"`

This example adds the first two ligatures for rendering, and causes the last two to be suppressed.

Ligature lists can also “start fresh”, clearing inherited ligatures. A leading `0` suppresses all ligatures, after which you can list just the ligatures you want to see rendered.

A leading `X` enables all ligatures, after which you can list just the ligatures you want to suppress.

#### Disregarded ligatures

The `disregarded` property, available only at the global level, is a space-separated string of ligatures for *Ligatures Limited* to entirely ignore. If your font wants to render these ligatures, it will render them regardless of context.

*Ligatures Limited* disregards the following ligatures by default: `ff fi fl ffi ffl`

Ligatures you specify for `disregarded` will be added to the above default list.

If you need to *un*disregard (for lack of a better word) any of these by-default disregarded ligatures, simply remove them from the `disregarded` list.

(Please note that *Ligatures Limited* can’t force ligatures to be rendered that aren’t provided by your selected font in the first place.)

#### Sample configuration

```json5
{
  "contexts": "operator punctuation comment_marker",
  "disregarded": "ff fi fl ffi ffl", // These ligatures will neither be actively enabled nor suppressed
  "languages": {
    "markdown": true, // All ligatures will be enabled in all contexts for Markdown
    "text, json": false,
    "css, javascript": {
      "ligaturesByContext": {
        "block_comment": {
          "ligatures": "0 www"
        }
      }
    },
    "html": {
      "ligaturesByContext": {
        "attribute_value": {
          "ligatures": "0 www"
        }
      }
    },
    "typescript": {
      "inherit": "javascript",
      "ligatures": "- !="
    }
  },
  "ligatures": "- 0xF 0o7 0b1 9x9 www", // These ligatures are suppressed
  "ligaturesByContext": {
    "number": "+ 0xF 0o7 0b1"
  }
}
```
In this configuration (a modification of the default configuration) the ligatures `0xF 0o7 0b1 9x9 www` are suppressed everywhere, unless otherwise overridden. The ligatures `ff fi fl ffi ffl` are disregarded, so they can appear anywhere, as no active effort will be made to either enable or suppress them. All other ligatures can only appear in the contexts `operator`, `punctuation`, and `comment_marker`, except as overridden for the `number` context.

The configuration then enables `www` ligatures, and only `www` ligatures, within block comments for CSS and JavaScript.

TypeScript inherits from the configuration for JavaScript, and additionally disables the `!=` ligature in all contexts.

For HTML, the `www` ligature is rendered only inside attribute values.

Markdown has all ligatures enabled, and plain text and JSON have all ligatures disabled.
