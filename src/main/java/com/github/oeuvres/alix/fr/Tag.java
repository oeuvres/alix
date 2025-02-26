/*
 * Alix, A Lucene Indexer for XML documents.
 * 
 * Copyright 2016 Frédéric Glorieux <frederic.glorieux@fictif.org>
 * Copyright 2009 Pierre Dittgen <pierre@dittgen.org> 
 *                Frédéric Glorieux <frederic.glorieux@fictif.org>
 *
 * Alix is a java library to index and search XML text documents
 * with Lucene https://lucene.apache.org/core/
 * including linguistic expertness for French,
 * available under Apache license.
 * 
 * Alix has been started in 2009 under the javacrim project
 * https://sf.net/projects/javacrim/
 * for a java course at Inalco  http://www.er-tim.fr/
 * Alix continues the concepts of SDX under another licence
 * «Système de Documentation XML»
 * 2000-2010  Ministère de la culture et de la communication (France), AJLSM.
 * http://savannah.nongnu.org/projects/sdx/
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.oeuvres.alix.fr;

import java.util.HashMap;
import java.util.Map;

import com.github.oeuvres.alix.util.Chain;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Jeu d’étiquettes morphosyntaxique pour le français.
 */
public enum Tag {
    // 0x, messages internes
    
    /** No information */
    NULL(0, "—", "Défaut, aucune information.") {
    },
    /** Known as unknown from dicitonaries */
    UNKNOWN(0x01, "Inconnu", "Connu comme inconnu des dictionnaires.") {
    },
    /** XML tag */
    XML(0x01, "XML", "<tag att=\"value\">, </tag>…") {
    },
    /** Message send by a process */
    TEST(0x0F, "Test", "Message envoyé par une étape de traitement.") {
    },

    // 1x, verbes
    /** Semantic verb */
    VERB(0x10, "Verbe", "Verbe sémantique (hors autres catégories verbales).") {
    },
    /** Auxiliary verb */
    VERBaux(0x11, "Auxilliaire", "Être, avoir. (verbe auxilliaire du français)") {
    },
    /** Semi-auxiliary verb */
    VERBaux2(0x12, "Semi‑aux.",
            "« Je vais faire… », aller, faire, venir de. (verbes semi-auxilliaires, faiblement sémantiques).") {
    },
    /** Modal verb */
    VERBmod(0x13, "Modaux", "Devoir, pouvoir, falloir. (verbes modaux).") {
    },
    /** Expression verb */
    VERBexpr(0x15, "V. d’expression", "Dire, répondre, s’écrier… (verbes d’expression).") {
    },
    /** Participle past */
    VERBppass(0x18, "Part. passé", "Participe passé (tous emplois : verbal, adjectif, substantif).") {
    },
    /** Participle present */
    VERBger(0x19, "Gérondif", "Participe présent (tous emplois : verbal, adjectif, substantif).") {
    },

    // 2x, substantifs
    /** Substantive */
    SUB(0x20, "Substantif", "Arbre, bonheur… (“Nom commun“, espèce).") {
    },
    /*
     * SUBm(0x21, "Substantif masculin", "(futur)") { }, SUBf(0x22,
     * "Substantif féminin", "(futur)") { },
     */
    /** Person title */
    SUBpers(0x28, "Titulature", "Monsieur, madame, prince… (introduit des noms de personnes).") {
    },
    /** Adress substantive */
    SUBplace(0x29, "Adressage", "Faubourg, rue, hôtel… (introduit des noms de lieux).") {
    },

    /** Adjective */
    ADJ(0x30, "Adjectif", "Adjectif, en emploi qualificatif ou attribut.") {
    },

    // 3x, entités nommées
    /** Proper name, unknown from dictionaries */
    NAME(0x40, "Nom propre",
            "Kala Matah, Taj Mah de Groüpt… (nom propre inféré de la typographie, inconnu des dictionnaires).") {
    },
    /** Personal name */
    NAMEpers(0x41, "Personne",
            "Victor Hugo, monsieur A… (Nom de de personne reconnu par dictionnaire ou inféré d’une titulature).") {
    },
    /** Masculine firstname */
    NAMEpersm(0x42, "Prénom m.", "Charles, Jean… (prénom masculin non ambigu, dictionnaire).") {
    },
    /** Feminine firstname */
    NAMEpersf(0x43, "Prénom f.", "Marie, Jeanne… (prénom féminin non ambigu, dictionnaire).") {
    },
    /** Place name */
    NAMEplace(0x44, "Lieu", "Paris, Allemagne… (nom de lieu, dictionnaire).") {
    },
    /** Organisation name */
    NAMEorg(0x45, "Organisation", "l’Église, l’État, P.S.… (nom d’organisation, dictionnaire).") {
    },
    /** People name */
    NAMEpeople(0x46, "Peuple", " (nom de peuple, dictionnaire).") {
    },
    /** Event name */
    NAMEevent(0x47, "Événement", "La Révolution, XIIe siècle… (nom d’événement, dictionnaire).") {
    },
    /** Author name */
    NAMEauthor(0x48, "Auteur", "Hugo, Racine, La Fontaine… (nom de persone auteur, dictionnaire).") {
    },
    /** Fiction character name */
    NAMEfict(0x49, "Personnage", "Rodogune, Chicot… (nom de personnage fictif, dictionnaire).") {
    },
    // NAMEtitle(0x3A, "Titre", " Titre d’œuvre (dictionnaire)") { },
    /** God name */
    NAMEgod(0x4F, "Divinité", "Dieu, Cupidon… (noms de divinité, dictionnaire).") {
    },

    // 5x, Adverbes
    /** Significative adverb */
    ADV(0x50, "Adverbe", "Adverbe significatif, souvent en adjectif+ment.") {
    },
    /** Negation adverb */
    ADVneg(0x51, "Adv. négation", "Ne, pas, point… (adverbe de négation).") {
    },
    /** Question adverb */
    ADVinter(0x52, "Adv. interr.", "Comment, est-ce que (adverbe interrogatif).") {
    },
    /** Space-time adverb */
    ADVscen(0x53, "Adv. scène", ":Ici, maintenant, derrière… (adverbe spacio-temporel).") {
    },
    /** Aspect adverb */
    ADVasp(0x54, "Adv. aspect", "Toujours, souvent… (adverbe de temps, d’aspect).") {
    },
    /** Gradation adverb */
    ADVdeg(0x55, "Adv. degré", "Plus, presque, très… (adverbe d’intensité)") {
    },
    /** Modal adverb */
    ADVmod(0x56, "Adv. mode", "Probablement, peut-être… (adverbe de modalité)") {
    },

    // 6x, déterminants
    /** Determiner, other than below */
    DET(0x60, "Déterminant", "Déterminant autre que dans les autres catégories.") {
    },
    /** Article */
    DETart(0x61, "Article", "Le, la, un, des… (articles définis et indéfinis, singulier et pluriel).") {
    },
    /** Prepositional determiner */
    DETprep(0x62, "Dét. prép.", "Du, au… (“de le”, “à les”, déterminant prépositionnel).") {
    },
    /** Numbers */
    DETnum(0x63, "Dét. num.", "Deux, trois… (déterminant numéral).") {
    },
    /** Quantifiers */
    DETindef(0x6A, "Dét. indéf.", "Tout, tous quelques… (déterminant indéfini).") {
    },
    /** Interrogative determiner */
    DETinter(0x6B, "Dét. inter.", "Quel, quelles… (déterminant interrogatif).") {
    },
    /** Demonstrative determiner */
    DETdem(0x6C, "Dét. dém.", "Ce, cette, ces… (déterminant démonstratif).") {
    },
    /** Possesive determiner */
    DETposs(0x6D, "Dét. poss.", "Son, ma, leurs… (déterminant possessif).") {
    },

    // 7x, pronoms
    /** Pronouns, other than below */
    PRO(0x70, "Pronom", "Pronom hors catégories particulières.") {
    },
    /** Personal pronouns */
    PROpers(0x71, "Pron. pers.", "Il, se, je, moi, nous… (pronom personnel).") {
    },
    /** Indefinite pronouns */
    PROindef(0x7A, "Pron. indéf", "Y, rien, tout… (pronom indéfini).") {
    },
    /** Demonstrative pronouns */
    PROdem(0x7C, "Pron. dém.", "C’, ça, cela… (pronom démonstratif).") {
    },
    /** Possessive pronouns */
    PROposs(0x7D, "Pron. poss.", "Le mien, la sienne… (pronom possessif).") {
    },

    // 8x, connecteurs
    /** Connectors, other than below */
    CONN(0x80, "Connecteur", "Mot invariable de connection hors autres catégories.") {
    },
    /** Coordinating conjunction */
    CONJcoord(0x81, "Conj. coord.", "Et, mais, ou… (conjonction de coordination).") {
    },
    /** Coordinating conjunction */
    CONJsub(0x82, "Conj. subord.", "Comme, si, parce que… (conjonction de subordination).") {
    },
    /** Adverbial conjunction */
    ADVconj(0x83, "Adv. conj.", "Cependant, désormais… (adverbe de connexion).") {
    },
    /** Preposition */
    PREP(0x88, "Préposition", "De, dans, par…") {
    },

    // Ax, Numéraux divers
    /** Numeral, other than below */
    NUM(0xA0, "Numéral", "3, milliers, centième… (nombre quantifiant).") {
    },
    /** Ordinal */
    NUMno(0xA1, "Numéro", "1er, second… (nombre non quantifiant, code, “ordinal”…).") {
    },
    /** Reference */
    REF(0xA2, "Référence", "p. 50, f. 2… (un numéro utilisé comme référence, comme une page, une note…") {
    },
    /** Math operator */
    MATH(0xA3, "Math", "+, -, /… (opérateur mathématique).") {
    },
    /** Units */
    NUMunit(0xA4, "Unités", "Cm, mm, kg… (unités métriques).") {
    },

    // Cx, punctuations
    /** Punctuation, other than below */
    PUN(0xC0, "Ponctuation", "Ponctuations divers hors catégories spéciales.") {
    },
    /** Clause punctuation */
    PUNclause(0xC1, "Clause", ", ; (Séparateur de syntagme).") {
    },
    /** Sentence punctuation */
    PUNsent(0xC2, "Phrase", ". ? ! (ponctuation de phrase).") {
    },
    /** Paragraph punctuation */
    PUNpara(0xC3, "Paragraphe", "¶ = paragraphe (structure interprétée d’un balisage).") {
    },
    /** Section punctuation */
    PUNsection(0xC4, "Section", "§ = section (structure interprétée d’un balisage).") {
    },

    // Fx, divers
    /** Misc */
    MISC(0xF0, "Divers", "") {
    },
    /** Abbreviation (maybe name, substantive…) */
    ABBR(0xF1, "Abréviation", "") {
    },
    /** Exclamation */
    EXCL(0xF2, "Exclamation", "Ho, Ô, haha… (interjections)") {
    },
    /** Demonstrative particle */
    PARTdem(0xF3, "Part. dém.", "-ci, -là (particule démonstrative)") {
    },
    /** Stop word */
    STOP(0xF8, "Mot “vide”", "Selon un dictionnaire de mots vides") {
    },
    /** Non stop word */
    NOSTOP(0xF9, "Mot “plein”", "Hors dictionnaire de mots vides") {
    },
    /** Locution  (maybe substantive, conjunction…) */
    LOC(0xFB, "Locution", "parce que, sans pour autant…") {
    },

    ;

    /** Logger */
    static Logger LOGGER = Logger.getLogger(Tag.class.getName());
    /** A structured bit flag between 0-255 */
    final public int flag;
    /** The first hexa digit, used as a type grouping */
    final public int parent;
    /** A name without spaces */
    final public String name;
    /** A french label for humans */
    final public String label;
    /** A line of explanation */
    final public String desc;

    /** Constructor */
    Tag(final int flag, final String label, final String desc) {
        this.flag = flag;
        this.label = label;
        this.desc = desc;
        this.parent = flag & 0xF0;
        this.name = this.toString();
    }

    /** Array to get a tag by number */
    private static final Tag[] Flag4tag = new Tag[256];
    /** Dictionary to get number of a tag by name */
    private static final Map<String, Integer> Name4flag = new HashMap<String, Integer>();
    static {
        for (Tag tag : Tag.values()) {
            Flag4tag[tag.flag] = tag;
            Name4flag.put(tag.toString(), tag.flag);
        }
    }

    /**
     * Check if Tag share same parent, by number.
     * 
     * @param flag Number of a Tag.
     * @return True if tags have same class.
     */
    public boolean sameParent(final int flag)
    {
        return ((flag & 0xF0) == parent);
    }

    /**
     * Return parent Tag by number
     * 
     * @param flag Number of a Tag.
     * @return The parent Tag.
     */
    static public Tag parent(final int flag)
    {
        Tag ret = Flag4tag[flag & 0xF0];
        if (ret == null)
            return UNKNOWN;
        return ret;
    }

    /**
     * Return the String value of the tag.
     * 
     * @return Label.
     */
    public String label()
    {
        return label;
    }

    /**
     * Return the String description of the tag.
     * 
     * @return Description.
     */
    public String desc()
    {
        return desc;
    }

    /**
     * Return the identifier number for this tag.
     * @return The identifier number.
     */
    public int flag()
    {
        return flag;
    }

    /**
     * Returns the identifier number of a <code>Tag</code>, by name.
     * @param name A mutable Tag name.
     * @return The identifier number of a <code>Tag</code>.
     */
    public static int flag(final Chain name)
    {
        @SuppressWarnings("unlikely-arg-type")
        Integer ret = Name4flag.get(name);
        if (ret == null) {
            LOGGER.log(Level.FINEST, "[Alix] unknown tag:" + name);
            return UNKNOWN.flag;
        }
        return ret;
    }

    /**
     * Get Tag number by name.
     * 
     * @param name A tag name.
     * @return The identifier number of a Tag.
     */
    public static int flag(final String name)
    {
        Integer ret = Name4flag.get(name);
        if (ret == null)
            return UNKNOWN.flag;
        return ret;
    }

    /**
     * Get Tag by number.
     * 
     * @param flag A Tag identifier number.
     * @return A Tag.
     */
    public static Tag tag(int flag)
    {
        // the int may be used as a more complex bit flag
        flag = flag & 0xFF;
        return Flag4tag[flag];
    }

    /**
     * Get Tag name by number identifier.
     * 
     * @param flag Tag identifier number.
     * @return Name of a Tag.
     */
    public static String name(int flag)
    {
        Tag tag = tag(flag);
        if (tag == null)
            return null;
        return tag.name;
    }

    /**
     * Get Tag label by number identifier.
     * 
     * @param flag Tag identifier number.
     * @return Label of a Tag.
     */
    public static String label(int flag)
    {
        Tag tag = tag(flag);
        if (tag == null)
            return null;
        return tag.label;
    }
}
