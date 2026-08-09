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
