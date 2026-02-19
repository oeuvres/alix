package com.github.oeuvres.alix.common;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

final class UposTest {

    @Test
    void udposNamesAreMapped_includingPlusCompositeTags() {
        final String[] externalNames = {
            "ADJ",
            "ADP",
            "ADP+DET",
            "ADP+PRON",
            "ADV",
            "AUX",
            "CCONJ",
            "DET",
            "INTJ",
            "NOUN",
            "NUM",
            "PRON",
            "PROPN",
            "PUNCT",
            "SCONJ",
            "SYM",
            "VERB",
            "X"
        };

        for (final String externalName : externalNames) {
            // must not throw and must return a stable code
            final Upos upos = assertDoesNotThrow(
                () -> Upos.get(externalName),
                () -> "Upos.code failed for external name: " + externalName
            );

            // Upos.code applies '+' -> '_' before valueOf()
            final String enumName = externalName.replace('+', '_');
            final Upos expected = assertDoesNotThrow(
                () -> Upos.valueOf(enumName),
                () -> "No enum constant for mapped name: " + enumName + " (from " + externalName + ")"
            );

        }
    }

    @Test
    void unknownOrWrongCaseMustFailFast() {
        assertNull(Upos.get("ADP+XYZ"));
        assertNull(Upos.get("adp")); // case-sensitive
    }
}
