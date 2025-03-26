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
public class TagFr extends Tag {

    // 1x, verbes
    /** Semantic verb */
    public static final Tag VERB = new Tag("VERB", 0x10, "Verbe", "Verbe sémantique (hors autres catégories verbales).");
    /** Auxiliary verb */
    public static final Tag VERBaux = new Tag("VERBaux", 0x11, "Auxilliaire", "Être, avoir. (verbe auxilliaire du français)");
    /** Semi-auxiliary verb */
    public static final Tag VERBaux2 = new Tag("VERBaux2", 0x12, "Semi‑aux.",
            "« Je vais faire… », aller, faire, venir de. (verbes semi-auxilliaires, faiblement sémantiques).");
    /** Modal verb */
    public static final Tag VERBmod = new Tag("VERBmod", 0x13, "Modaux", "Devoir, pouvoir, falloir. (verbes modaux).");
    /** Expression verb */
    public static final Tag VERBexpr = new Tag("VERBexpr", 0x15, "V. d’expression", "Dire, répondre, s’écrier… (verbes d’expression).");
    /** Infinitive */
    public static final Tag VERBinf = new Tag("VERBinf", 0x17, "Infinitif", "Infinitif.");
    /** Participle past */
    public static final Tag VERBpp = new Tag("VERBpp", 0x18, "Part. passé", "Participe passé (tous emplois : verbal, adjectif, substantif).");
    /** Participle present */
    public static final Tag VERBger = new Tag("VERBger", 0x19, "Gérondif", "Participe présent (tous emplois : verbal, adjectif, substantif).");

    // 2x, substantifs
    /** Substantive */
    public static final Tag SUB = new Tag("SUB", 0x20, "Substantif", "Arbre, bonheur… (“Nom commun“, espèce).");
    /*
     * public static final Tag SUBm = new Tag("SUBm", 0x21, "Substantif masculin", "(futur)") { }, public static final Tag SUBf = new Tag("SUBf", 0x22,
     * "Substantif féminin", "(futur)") { },
     */
    /** Person title */
    public static final Tag SUBpers = new Tag("SUBpers", 0x28, "Titulature", "Monsieur, madame, prince… (introduit des noms de personnes).");
    /** Adress substantive */
    public static final Tag SUBplace = new Tag("SUBplace", 0x29, "Adressage", "Faubourg, rue, hôtel… (introduit des noms de lieux).");

    /** Adjective */
    public static final Tag ADJ = new Tag("ADJ", 0x30, "Adjectif", "Adjectif, en emploi qualificatif ou attribut.");

    // 3x, entités nommées
    /** Proper name, unknown from dictionaries */
    public static final Tag NAME = new Tag("NAME", 0x40, "Nom propre",
            "Kala Matah, Taj Mah de Groüpt… (nom propre inféré de la typographie, inconnu des dictionnaires).");
    /** Personal name */
    public static final Tag NAMEpers = new Tag("NAMEpers", 0x41, "Personne",
            "Victor Hugo, monsieur A… (Nom de de personne reconnu par dictionnaire ou inféré d’une titulature).");
    /** Masculine firstname */
    public static final Tag NAMEpersm = new Tag("NAMEpersm", 0x42, "Prénom m.", "Charles, Jean… (prénom masculin non ambigu, dictionnaire).");
    /** Feminine firstname */
    public static final Tag NAMEpersf = new Tag("NAMEpersf", 0x43, "Prénom f.", "Marie, Jeanne… (prénom féminin non ambigu, dictionnaire).");
    /** Place name */
    public static final Tag NAMEplace = new Tag("NAMEplace", 0x44, "Lieu", "Paris, Allemagne… (nom de lieu, dictionnaire).");
    /** Organisation name */
    public static final Tag NAMEorg = new Tag("NAMEorg", 0x45, "Organisation", "l’Église, l’État, P.S.… (nom d’organisation, dictionnaire).");
    /** People name */
    public static final Tag NAMEpeople = new Tag("NAMEpeople", 0x46, "Peuple", " (nom de peuple, dictionnaire).");
    /** Event name */
    public static final Tag NAMEevent = new Tag("NAMEevent", 0x47, "Événement", "La Révolution, XIIe siècle… (nom d’événement, dictionnaire).");
    /** Author name */
    public static final Tag NAMEauthor = new Tag("NAMEauthor", 0x48, "Auteur", "Hugo, Racine, La Fontaine… (nom de persone auteur, dictionnaire).");
    /** Fiction character name */
    public static final Tag NAMEfict = new Tag("NAMEfict", 0x49, "Personnage", "Rodogune, Chicot… (nom de personnage fictif, dictionnaire).");
    // public static final Tag NAMEtitle = new Tag("NAMEtitle", 0x3A, "Titre", " Titre d’œuvre (dictionnaire)") { },
    /** God name */
    public static final Tag NAMEgod = new Tag("NAMEgod", 0x4F, "Divinité", "Dieu, Cupidon… (noms de divinité, dictionnaire).");

    // 5x, Adverbes
    /** Significative adverb */
    public static final Tag ADV = new Tag("ADV", 0x50, "Adverbe", "Adverbe significatif, souvent en adjectif+ment.");
    /** Negation adverb */
    public static final Tag ADVneg = new Tag("ADVneg", 0x51, "Adv. négation", "Ne, pas, point… (adverbe de négation).");
    /** Question adverb */
    public static final Tag ADVinter = new Tag("ADVinter", 0x52, "Adv. interr.", "Comment, est-ce que (adverbe interrogatif).");
    /** Space-time adverb */
    public static final Tag ADVscen = new Tag("ADVscen", 0x53, "Adv. scène", ":Ici, maintenant, derrière… (adverbe spacio-temporel).");
    /** Aspect adverb */
    public static final Tag ADVasp = new Tag("ADVasp", 0x54, "Adv. aspect", "Toujours, souvent… (adverbe de temps, d’aspect).");
    /** Gradation adverb */
    public static final Tag ADVdeg = new Tag("ADVdeg", 0x55, "Adv. degré", "Plus, presque, très… (adverbe d’intensité)");
    /** Modal adverb */
    public static final Tag ADVmod = new Tag("ADVmod", 0x56, "Adv. mode", "Probablement, peut-être… (adverbe de modalité)");

    // 6x, déterminants
    /** Determiner, other than below */
    public static final Tag DET = new Tag("DET", 0x60, "Déterminant", "Déterminant autre que dans les autres catégories.");
    /** Article */
    public static final Tag DETart = new Tag("DETart", 0x61, "Article", "Le, la, un, des… (articles définis et indéfinis, singulier et pluriel).");
    /** Prepositional determiner */
    public static final Tag DETprep = new Tag("DETprep", 0x62, "Dét. prép.", "Du, au… (“de le”, “à les”, déterminant prépositionnel).");
    /** Numbers */
    public static final Tag DETnum = new Tag("DETnum", 0x63, "Dét. num.", "Deux, trois… (déterminant numéral).");
    /** Quantifiers */
    public static final Tag DETindef = new Tag("DETindef", 0x6A, "Dét. indéf.", "Tout, tous quelques… (déterminant indéfini).");
    /** Interrogative determiner */
    public static final Tag DETinter = new Tag("DETinter", 0x6B, "Dét. inter.", "Quel, quelles… (déterminant interrogatif).");
    /** Demonstrative determiner */
    public static final Tag DETdem = new Tag("DETdem", 0x6C, "Dét. dém.", "Ce, cette, ces… (déterminant démonstratif).");
    /** Possessive determiner */
    public static final Tag DETposs = new Tag("DETposs", 0x6D, "Dét. poss.", "Son, ma, leurs… (déterminant possessif).");

    // 7x, pronoms
    /** Pronouns, other than below */
    public static final Tag PRO = new Tag("PRO", 0x70, "Pronom", "Pronom hors catégories particulières.");
    /** Personal pronouns */
    public static final Tag PROpers = new Tag("PROpers", 0x71, "Pron. pers.", "Il, se, je, moi, nous… (pronom personnel).");
    /** Indefinite pronouns */
    public static final Tag PROindef = new Tag("PROindef", 0x7A, "Pron. indéf", "Y, rien, tout… (pronom indéfini).");
    /** Demonstrative pronouns */
    public static final Tag PROdem = new Tag("PROdem", 0x7C, "Pron. dém.", "C’, ça, cela… (pronom démonstratif).");
    /** Possessive pronouns */
    public static final Tag PROposs = new Tag("PROposs", 0x7D, "Pron. poss.", "Le mien, la sienne… (pronom possessif).");

    // 8x, connecteurs
    /** Connectors, other than below */
    public static final Tag CONN = new Tag("CONN", 0x80, "Connecteur", "Mot invariable de connection hors autres catégories.");
    /** Coordinating conjunction */
    public static final Tag CONJcoord = new Tag("CONJcoord", 0x81, "Conj. coord.", "Et, mais, ou… (conjonction de coordination).");
    /** Coordinating conjunction */
    public static final Tag CONJsub = new Tag("CONJsub", 0x82, "Conj. subord.", "Comme, si, parce que… (conjonction de subordination).");
    /** Adverbial conjunction */
    public static final Tag ADVconj = new Tag("ADVconj", 0x83, "Adv. conj.", "Cependant, désormais… (adverbe de connexion).");
    /** Preposition */
    public static final Tag PREP = new Tag("PREP", 0x88, "Préposition", "De, dans, par…");
    /** ? tagger opennlp */
    public static final Tag PREPpro = new Tag("PREPpro", 0x89, "ADP+PRON", "TagFr opennlp");

    // Ax, Numéraux divers
    /** Numeral, other than below */
    public static final Tag NUM = new Tag("NUM", 0xA0, "Numéral", "3, milliers, centième… (nombre quantifiant).");
    /** Ordinal */
    public static final Tag NUMno = new Tag("NUMno", 0xA1, "Numéro", "1er, second… (nombre non quantifiant, code, “ordinal”…).");
    /** Reference */
    public static final Tag REF = new Tag("REF", 0xA2, "Référence", "p. 50, f. 2… (un numéro utilisé comme référence, comme une page, une note…");
    /** Math operator */
    public static final Tag MATH = new Tag("MATH", 0xA3, "Math", "+, -, /… (opérateur mathématique).");
    /** Units */
    public static final Tag NUMunit = new Tag("NUMunit", 0xA4, "Unité", "Cm, mm, kg… (unités métriques).");
    /** Symbols */
    public static final Tag SYM = new Tag("SYM", 0xA8, "Symbole", "??? Opennlp");

    // Cx, punctuations
    /** Punctuation, other than below */
    public static final Tag PUN = new Tag("PUN", 0xC0, "Ponctuation", "Ponctuations divers hors catégories spéciales.");
    /** Clause punctuation */
    public static final Tag PUNclause = new Tag("PUNclause", 0xC1, "Clause", ", ; (Séparateur de syntagme).");
    /** Sentence punctuation */
    public static final Tag PUNsent = new Tag("PUNsent", 0xC2, "Phrase", ". ? ! (ponctuation de phrase).");
    /** Paragraph punctuation */
    public static final Tag PUNpara = new Tag("PUNpara", 0xC3, "Paragraphe", "¶ = paragraphe (structure interprétée d’un balisage).");
    /** Section punctuation */
    public static final Tag PUNsection = new Tag("PUNsection", 0xC4, "Section", "§ = section (structure interprétée d’un balisage).");

    // Fx, divers
    /** Misc */
    public static final Tag MISC = new Tag("MISC", 0xF0, "Divers", "");
    /** Abbreviation (maybe name, substantive…) */
    public static final Tag ABBR = new Tag("ABBR", 0xF1, "Abréviation", "");
    /** Exclamation */
    public static final Tag EXCL = new Tag("EXCL", 0xF2, "Exclamation", "Ho, Ô, haha… (interjections)");
    /** Demonstrative particle */
    public static final Tag PARTdem = new Tag("PARTdem", 0xF3, "Part. dém.", "-ci, -là (particule démonstrative)");

    /**
     * Default constructor, super()
     * @param name
     * @param no
     * @param label
     * @param desc
     */
    public TagFr(final String name, final int no, final String label, final String desc) {
        super(name, no, label, desc);
    }


}
