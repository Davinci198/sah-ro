package com.sah.play

import com.sah.content.Language

/**
 * The app's own feedback strings, for the screens whose script defines no `wrong`
 * key: the `tut_fb_*` entries of the shipped string resources.
 *
 * Keeping them here (instead of in the bundled lesson strings) mirrors the shipped
 * app, where these live in the resources and are always available, whatever the
 * lesson script says.
 */
enum class LessonFeedback(private val ro: String, private val en: String) {
    WRONG_SQUARE(
        "Nu acest câmp. Încearcă din nou.",
        "Not this square. Try again.",
    ),
    WRONG_MOVE(
        "Mutarea este permisă, dar nu este cea de care avem nevoie. Încearcă din nou.",
        "That move is allowed, but it is not the one we need. Try again.",
    ),
    WRONG_ANSWER(
        "Nu chiar. Încearcă din nou.",
        "Not quite. Try again.",
    ),
    PLACE_WRONG(
        "Această piesă nu pornește de acolo. Încearcă alt câmp.",
        "This piece does not start there. Try another square.",
    ),
    SEQUENCE_RESTART(
        "Hopa, nu acesta. Să o luăm de la capăt, de la primul câmp.",
        "Oops, not that one. Let's start again from the first square.",
    ),
    TOO_MANY_MOVES(
        "Nu mai ai mutări! Încearcă din nou, de data asta cu mai puține mutări.",
        "Out of moves! Try again, this time with fewer moves.",
    ),
    ONLY_THIS_PIECE(
        "În această sarcină se mută o singură piesă. Celelalte doar încurcă.",
        "In this task only one piece moves. The others are just in the way.",
    );

    fun of(language: Language): String = if (language == Language.RO) ro else en
}
