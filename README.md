# Alix
## Tool for French tokenization, lemmatization, POStagging ; corpus analysis ; distributional semantics

#### Alix provides :
- a tokenizer/lemmatizer/POStagger for French
- a one hot encoding vectorizer (muthoVek)
- a corpus statistical analyzer (Grep) that searches for single/multiple occurrences and syntactical patterns

#### Étiquettes morphosyntaxiques

* UNKNOWN — connu comme inconnu (selon les dictionnaires) 
* NULL — valeur par défaut, aucune information
* VERB — verbe, qui n'est pas l'une des catégories ci-dessous
  * VERBaux — auxilliaire, conjugaison d’être et avoir
  * VERBppass — participe passé, peut avoir un emploi adjectif, voire substantif
  * VERBppres — participe présent, a souvent un emploi adjectif ou substantif 
  * VERBsup — verbe support, fréquent mais peut significatif, comme aller (je vais faire) 
* SUB — substantif
  * SUBm — substantif masculin (pas encore renseigné)
  * SUBf — substantif féminin (pas encore renseigné)
* ADJ — adjectif
* ADV — adverbe
  * ADVneg — adverbe de négation : ne, pas, point… 
  * ADVplace — adverbe de lieu
  * ADVtemp — adverbe de temps
  * ADVquant — adverbe de quantité
  * ADVindef — Averbe indéfini : aussi, même…
  * ADVinter — adverbe interrogatif : est-ce que, comment… 
* PREP — préposition
* DET — déterminant
  * DETart — déterminant article : le, la, un, des… 
  * DETprep — déterminant prépositionnel : du, au (?? non comptable ?) 
  * DETnum — déterminant numéral : deux, trois
  * DETindef — déterminant indéfini : tout, tous, quelques…
  * DETinetr — déterminant interrogatif : quel, quelles…
  * DETdem — déterminant démonstratif : ce, cette, ces…
  * DETposs — déterminant possessif : son, mas, leurs…
* PRO — pronom  
  * PROpers — pronom personnel : il, je, se, me…
  * PROrel — pronom relatif : qui, que, où… 
  * PROindef — pronom indéfini : y, rien, tout…
  * PROinter — pronom interrogatif : où, que, qui…
  * PROdem — pronom démonstratif : c', ça, cela…
  * PROposs — pronom possessif : le mien, la sienne…
* CONJ — conjonction
  * CONJcoord — conjonction de coordination : et, mais, ou…
  * CONJsubord — conjonction de subordination : comme, si, parce que…
* NAME — nom propre
  * NAMEpers — nom de personne
  * NAMEpersm — prénom masculin
  * NAMEpersf — prénom féminin
  * NAMEplace — nom de lieu
  * NAMEorg — nom d’organisation
  * NAMEpeople — nom de peuple
  * NAMEevent — nom d’événement : la Révolution, la Seconde Guerre mondiale…
  * NAMEauthor — nom de personne auteur
  * NAMEfict — nom de personnage fictif
  * NAMEtitle — titre d’œuvre
  * NAMEanimal — animal : Pégase…
  * NAMEdemhum — demi-humain : Hercule…
  * NAMEgod — noms de dieux : Dieu, Vénus…
* EXCL — exclamation
* NUM — numéro
* PUN — ponctuation
  * PUNsent — ponctuation de phrase : . ? ! 
  * PUNcl — ponctuation de clause : , ; (
* ABBR — abréviation (encore non résolue)


