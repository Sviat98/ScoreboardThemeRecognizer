package com.bashkevich.scoreboardthemerecognizer.model.theme.analysis

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.executor.model.executeStructured
import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.AttachmentSource

/** "The image is not a tennis scoreboard" — surfaced to the user as an error (NOT a fallback). */
class NotAScoreboardException(message: String) : Exception(message)

/**
 * The semantic half of the connected-components path. OpenCV already found and NUMBERED the
 * scoreboard's elements; this agent receives the image with those numbers drawn on it and only
 * decides WHICH element number plays each role (serve / main_text / current_game / …). It never
 * outputs coordinates — it picks from a small discrete set of element indices — so there is no
 * coordinate drift. One structured-output call to GPT-4o at `temperature=0`.
 */
internal class ElementThemeAgent {

    private val executor: PromptExecutor by lazy {
        MultiLLMPromptExecutor(
            OpenAILLMClient(
                loadOpenAIApiKey() ?: error("OPENAI_API_KEY is not set (env var or openai.key file)")
            )
        )
    }

    suspend fun localizeRoles(numberedImage: ByteArray): ElementRoles {
        val rolesPrompt = prompt("scoreboard_element_roles") {
            system(ELEMENT_ROLES_PROMPT)
            user {
                +"The image is a tennis scoreboard with each detected element marked by a NUMBERED box."
                +"For each role, return the box number that best shows it (or null if absent)."
                image(
                    AttachmentSource.Image(
                        content = AttachmentContent.Binary.Bytes(numberedImage),
                        format = "png",
                        mimeType = "image/png",
                        fileName = "scoreboard-elements.png",
                    )
                )
            }
        }.withUpdatedParams { temperature = 0.0 }

        val result = executor.executeStructured<ElementRoles>(
            prompt = rolesPrompt,
            model = OpenAIModels.Chat.GPT4o,
        )
        return result.getOrElse { error ->
            throw IllegalStateException("LLM failed to label scoreboard elements", error)
        }.data
    }

    /**
     * Palette-classification path (debug screen). Receives the scoreboard PNG and the palette
     * extracted from it, and asks the LLM to map each of the nine theme slots to a single hex from
     * the palette, using the image for semantics (frequency is only a hint — backgrounds dominate it,
     * accents are rarer). Returned hexes are snapped to their nearest palette color, so an LLM typo
     * can't invent a non-palette color.
     */
    suspend fun classifyPalette(
        analyzedImagePng: ByteArray,
        palette: List<ClusterInfo>,
    ): PaletteClassification {
        val paletteText = buildString {
            if (palette.isEmpty()) {
                append("_(empty palette)_")
            } else {
                append("| Hex | Frequency |\n")
                append("|---|---|\n")
                palette.forEach { c ->
                    append("| `")
                    append(c.centroid.toHex())
                    append("` | ")
                    append("%.1f%%".format(c.share * 100))
                    append(" |\n")
                }
            }
        }

        val palettePrompt = prompt("scoreboard_palette_roles") {
            system(PALETTE_ROLES_PROMPT)
            user {
                +"The attached image is the scoreboard. Its color palette as a"
                +"markdown table (hex + frequency %):"
                +paletteText
                +"Pick a hex for EVERY slot from the table above — never return null."
                image(
                    AttachmentSource.Image(
                        content = AttachmentContent.Binary.Bytes(analyzedImagePng),
                        format = "png",
                        mimeType = "image/png",
                        fileName = "scoreboard-palette.png",
                    )
                )
            }
        }
            //.withUpdatedParams { temperature = 0.0 }

        val result = executor.executeStructured<PaletteClassification>(
            prompt = palettePrompt,
            model = OpenAIModels.Chat.GPT5_4,
        )
        val raw = result.getOrElse { error ->
            throw IllegalStateException("LLM failed to classify palette", error)
        }.data
        return snapToPalette(raw, palette)
    }
}

private val ELEMENT_ROLES_PROMPT = """
    You are an expert at analyzing tennis scoreboards. You receive ONE image of a tennis scoreboard
    in which a detector has drawn a NUMBERED box around each individual element (a digit, the serve
    indicator/ball, a name letter, etc.). The numbers label the boxes.

    Your job: assign each role to the box NUMBER that best shows it. If a role is not visible, use
    null. Roles:
      - main_text_element: a box around a letter of a player's SURNAME — the LARGE player-name text.
        Do NOT pick the small country code, a flag, or a seed number: pick a letter of the big
        surname. (Used for the main background + the main text color.)
      - serve_element: the box around the SERVE indicator (small ball/dot/asterisk) shown next to the
        one player who is currently serving.
      - prev_set_win_element: a box around one digit of the most recent COMPLETED set belonging to the
        player who WON that set (compare the two players' completed-set digits — the larger is the
        winner).
      - prev_set_lose_element: a box around one digit of that same completed set belonging to the
        player who LOST it.
      - current_set_element: a box around one digit of the CURRENT set score.
      - current_game_element: a box around one digit of the CURRENT game/points score.

    If the image is NOT a tennis scoreboard, set is_scoreboard=false and give a short reason (leave
    the element fields null). Return ONLY the structured result.
""".trimIndent()

private val PALETTE_ROLES_PROMPT = """
    # Tennis scoreboard — palette → theme slots

    You receive ONE scoreboard **image** plus its **color palette** as a markdown
    table of `hex` → `frequency %`. Frequency is only a hint: backgrounds dominate it, text and
    accents are rarer — do *not* rank by frequency alone.

    ## Task
    For each slot below, choose the **single hex** from the palette that best matches what fills
    that slot **on the image**. Several slots may share the same hex.

    ## Slots

    | Slot | What it is |
    |---|---|
    | `main_background_color` | dominant fill behind the player names — the board's base color |
    | `main_text_color` | player SURNAME text color |
    | `serve_color` | serve indicator (small ball/dot), often an accent |
    | `previous_set_win_text_color` | a COMPLETED set's digit for the player who won it |
    | `previous_set_lose_text_color` | the same completed set's digit for the loser — usually the dimmer/grayer version of the win text |
    | `current_set_background_color` | fill of the highlighted cell showing the CURRENT set score |
    | `current_set_text_color` | the digit on that current-set cell |
    | `current_game_background_color` | fill of the highlighted cell showing the CURRENT game/points |
    | `current_game_text_color` | the digit on that current-game cell |

    ## Common patterns (typical, NOT strict rules — trust the image first)
    - **`current_set` and `current_game` cells usually have *different* colors** — both their
      backgrounds and their text. Only copy one cell's colors to the other if the image really shows
      them identical.
    - **`serve_color` and `previous_set_win_text_color` usually *coincide*** — both are typically
      the board's bright accent (e.g. yellow). Pick the same hex for both when that matches the image.
    - Text slots may still all share one hex (e.g. all white); the two patterns above are only about
      cell distinctness and accent-sharing.

    ## Rules
    - Return a hex for **every** slot — never `null` and never empty. If a slot is unclear or not
      clearly visible, still pick the single best-guess hex from the palette. A missing / dash answer
      is never acceptable.
    - Return ONLY hexes that appear **verbatim** in the provided palette.
    - Return ONLY the structured result.
""".trimIndent()

/**
 * Snaps each hex in [raw] to its nearest palette color so the classification only ever references
 * real palette entries (defends against the LLM returning a close-but-not-exact hex).
 */
private fun snapToPalette(raw: PaletteClassification, palette: List<ClusterInfo>): PaletteClassification {
    if (palette.isEmpty()) return raw
    fun snap(hex: String?): String? {
        val c = parseHexColor(hex) ?: return null
        return palette.minByOrNull { it.centroid.distanceTo(c) }?.centroid?.toHex()
    }
    return PaletteClassification(
        mainBackgroundColor = snap(raw.mainBackgroundColor),
        mainTextColor = snap(raw.mainTextColor),
        serveColor = snap(raw.serveColor),
        previousSetWinTextColor = snap(raw.previousSetWinTextColor),
        previousSetLoseTextColor = snap(raw.previousSetLoseTextColor),
        currentSetBackgroundColor = snap(raw.currentSetBackgroundColor),
        currentSetTextColor = snap(raw.currentSetTextColor),
        currentGameBackgroundColor = snap(raw.currentGameBackgroundColor),
        currentGameTextColor = snap(raw.currentGameTextColor),
    )
}
