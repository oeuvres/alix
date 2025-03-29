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


import com.github.oeuvres.alix.common.Tag;

/**
 * Jeu d’étiquettes morphosyntaxique pour le français.
 */
public enum TagFr implements Tag {


    // 1x, verbes
    /** Semantic verb */
    VERB(0x10, "Verbe", "Verbe sémantique (hors autres catégories verbales)."),
    /** Auxiliary verb */
    VERBaux(0x11, "Auxilliaire", "Être, avoir. (verbe auxilliaire du français)"),
    /** Semi-auxiliary verb */
    VERBaux2(0x12, "Semi‑aux.",
            "« Je vais faire… », aller, faire, venir de. (verbes semi-auxilliaires, faiblement sémantiques)."),
    /** Modal verb */
    VERBmod(0x13, "Modaux", "Devoir, pouvoir, falloir. (verbes modaux)."),
    /** Expression verb */
    VERBexpr(0x15, "V. d’expression", "Dire, répondre, s’écrier… (verbes d’expression)."),
    /** Infinitive */
    VERBinf(0x17, "Infinitif", "Infinitif."),
    /** Participle past */
    VERBppas(0x18, "Part. passé", "Participe passé (tous emplois : verbal, adjectif, substantif)."),
    /** Participle present */
    VERBger(0x19, "Gérondif", "Participe présent (tous emplois : verbal, adjectif, substantif)."),

    // 2x, substantifs
    /** Substantive */
    SUB(0x20, "Substantif", "Arbre, bonheur… (“Nom commun“, espèce)."),
    /*
     * SUBm(0x21, "Substantif masculin", "(futur)") { }, SUBf(0x22,
     * "Substantif féminin", "(futur)") { },
     */
    /** Person title */
    SUBpers(0x28, "Titulature", "Monsieur, madame, prince… (introduit des noms de personnes)."),
    /** Adress substantive */
    SUBplace(0x29, "Adressage", "Faubourg, rue, hôtel… (introduit des noms de lieux)."),

    /** Adjective */
    ADJ(0x30, "Adjectif", "Adjectif, en emploi qualificatif ou attribut."),

    // 3x, entités nommées
    /** Proper name, unknown from dictionaries */
    NAME(0x40, "Nom propre",  "Kala Matah, Taj Mah de Groüpt… (nom propre inféré de la typographie, inconnu des dictionnaires)."),
    /** Personal name */
    NAMEpers(0x41, "Personne",  "Victor Hugo, monsieur A… (Nom de de personne reconnu par dictionnaire ou inféré d’une titulature)."),
    /** Masculine firstname */
    NAMEpersm(0x42, "Prénom m.", "Charles, Jean… (prénom masculin non ambigu, dictionnaire)."),
    /** Feminine firstname */
    NAMEpersf(0x43, "Prénom f.", "Marie, Jeanne… (prénom féminin non ambigu, dictionnaire)."),
    /** Place name */
    NAMEplace(0x44, "Lieu", "Paris, Allemagne… (nom de lieu, dictionnaire)."),
    /** Organisation name */
    NAMEorg(0x45, "Organisation", "l’Église, l’État, P.S.… (nom d’organisation, dictionnaire)."),
    /** Event name */
    NAMEevent(0x47, "Événement", "La Révolution, XIIe siècle… (nom d’événement, dictionnaire)."),
    /** Author name */
    NAMEauthor(0x48, "Auteur", "Hugo, Racine, La Fontaine… (nom de persone auteur, dictionnaire)."),
    /** Fiction character name */
    NAMEfict(0x49, "Personnage", "Rodogune, Chicot… (nom de personnage fictif, dictionnaire)."),
    /** title name */
    NAMEtitle(0x4A, "Titre", " Titre d’œuvre (dictionnaire)") { },
    /** People name */
    NAMEpeople(0x4E, "Peuple", " (nom de peuple, dictionnaire)."),
    /** God name */
    NAMEgod(0x4F, "Divinité", "Dieu, Cupidon… (noms de divinité, dictionnaire)."),

    // 5x, Adverbes
    /** Significative adverb */
    ADV(0x50, "Adverbe", "Adverbe significatif, souvent en adjectif+ment."),
    /** Negation adverb */
    ADVneg(0x51, "Adv. négation", "Ne, pas, point… (adverbe de négation)."),
    /** Question adverb */
    ADVquest(0x52, "Adv. interr.", "Comment, est-ce que (adverbe interrogatif)."),
    /** Space-time adverb */
    ADVscen(0x53, "Adv. scène", ":Ici, maintenant, derrière… (adverbe spacio-temporel)."),
    /** Aspect adverb */
    ADVasp(0x54, "Adv. aspect", "Toujours, souvent… (adverbe de temps, d’aspect)."),
    /** Gradation adverb */
    ADVdeg(0x55, "Adv. degré", "Plus, presque, très… (adverbe d’intensité)"),

    // 6x, déterminants
    /** Determiner, other than below */
    DET(0x60, "Déterminant", "Déterminant autre que dans les autres catégories."),
    /** Article */
    DETart(0x61, "Article", "Le, la, un, des… (articles définis et indéfinis, singulier et pluriel)."),
    /** Prepositional determiner */
    DETprep(0x62, "Dét. prép.", "Du, au… (“de le”, “à les”, déterminant prépositionnel)."),
    /** Numbers */
    DETnum(0x63, "Dét. num.", "Deux, trois… (déterminant numéral)."),
    /** Quantifiers */
    DETindef(0x6A, "Dét. indéf.", "Tout, tous, quelles, quelques… (déterminant indéfini)."),
    /** Interrogative determiner */
    DETinter(0x6B, "Dét. inter.", "Quel, quelles… (déterminant interrogatif)."),
    /** Demonstrative determiner */
    DETdem(0x6C, "Dét. dém.", "Ce, cette, ces… (déterminant démonstratif)."),
    /** Possesive determiner */
    DETposs(0x6D, "Dét. poss.", "Son, ma, leurs… (déterminant possessif)."),

    // 7x, pronoms
    /** Pronouns, other than below */
    PRO(0x70, "Pronom", "Pronom hors catégories particulières."),
    /** Personal pronouns */
    PROpers(0x71, "Pron. pers.", "Il, se, je, moi, nous… (pronom personnel)."),
    /** Demonstrative pronouns */
    PROdem(0x72, "Pron. dém.", "C’, ça, cela… (pronom démonstratif)."),
    /** Possessive pronouns */
    PROposs(0x73, "Pron. poss.", "Le mien, la sienne… (pronom possessif)."),
    /** Possessive pronouns */
    PROquest(0x74, "Pron. inter.", "Qui, où, quoi ? (pronom interrogatif)"),
    /** Possessive pronouns */
    PROrel(0x75, "Pron. rel.", "Auquel, desquelles… (pronom relatif)"),
    /** Indefinite pronouns */
    PROindef(0x7F, "Pron. indéf", "Y, rien, tout… (pronom indéfini)."),

    // 8x, connecteurs
    /** Connectors, other than below */
    CONN(0x80, "Connecteur", "Mot invariable de connection hors autres catégories."),
    /** Coordinating conjunction */
    CONJcoord(0x81, "Conj. coord.", "Et, mais, ou… (conjonction de coordination)."),
    /** Coordinating conjunction */
    CONJsub(0x82, "Conj. subord.", "Comme, si, parce que… (conjonction de subordination)."),
    /** Adverbial conjunction */
    ADVconj(0x83, "Adv. conj.", "Cependant, désormais… (adverbe de connexion)."),
    /** Preposition */
    PREP(0x88, "Préposition", "De, dans, par…"),
    PREPpro(0x89, "ADP+PRON", "TagFr opennlp"),
    
    // Ax, Numéraux divers
    /** Numeral, other than below */
    NUMBER(0xA0, "Numéral", "3, milliers, centième… (nombre quantifiant)."),
    /** Ordinal */
    NUMno(0xA1, "Numéro", "1er, second… (nombre non quantifiant, code, “ordinal”…)."),
    /** Reference */
    REF(0xA2, "Référence", "p. 50, f. 2… (un numéro utilisé comme référence, comme une page, une note…"),
    /** Math operator */
    MATH(0xA3, "Math", "+, -, /… (opérateur mathématique)."),
    /** Units */
    NUMunit(0xA4, "Unités", "Cm, mm, kg… (unités métriques)."),
    SYM(0xA8, "Symbole", "??? Opennlp"),

    // Fx, divers
    /** Misc */
    MISC(0xF0, "Divers", ""),
    /** Abbreviation (maybe name, substantive…) */
    ABBR(0xF1, "Abréviation", ""),
    /** Exclamation */
    EXCL(0xF2, "Exclamation", "Ho, Ô, haha… (interjections)"),

    ;
    static final Index index = new Index(16, 255);
    static {
        for (TagFr tag : TagFr.values()) index.add(tag.code(), tag);
    }

    /** A structured bit flag between 0-255 */
    public final int code;
    /** A french label for humans */
    public final String label;
    /** A line of explanation */
    public final String desc;
    /** The first hexa digit, used as a type grouping */
    final int parent;

    /**
     * Default constructor, super()
     * @param name
     * @param code
     * @param label
     * @param desc
     */
    private TagFr(final int code, final String label, final String desc) {
        this.code = code;
        this.label = label;
        this.desc = desc;
        this.parent = code & 0xF0;
    }

    @Override
    public int code()
    {
        return code;
    }

    static public boolean isName(final int code)
    {
        return ((code & 0xF0) == NAME.code);
    }
    
    static public boolean isVerb(final int code)
    {
        return ((code & 0xF0) == VERB.code);
    }

    static public String name(final int code)
    {
        Tag tag = index.get(code);
        if (tag == null) return null;
        return tag.name();
    }
    
    @Override
    public int code(final String name)
    {
        return valueOf(name).code;
    }

    @Override
    public String toString()
    {
        return String.format("%02X", code) + "\t" + this.name() + "\t" + this.label + "\t" + this.desc;
    }

}
