-- structure d’un index plein texte avec informations linguistiques

PRAGMA encoding = 'UTF-8';
PRAGMA main.page_size = 4096;
PRAGMA main.cache_size = 10000;
PRAGMA temp_store = 2; -- memory temp table
PRAGMA journal_mode = OFF; -- unsecure
PRAGMA synchronous = OFF; -- unsecure

CREATE TABLE doc (
  -- Groupe de vecteurs
  id         INTEGER,  -- alias rowid
  title      TEXT,     -- affichable
  date       INTEGER,  -- date
  byline     TEXT,     -- auteur
  occs       INTEGER,  -- nombre d’occurrences pour le document
  PRIMARY KEY(id ASC)
);

Create table occ (
  -- indexation des occurrences
  id         INTEGER,  -- alias rowid
  graph      TEXT,     -- graphie 
  orth       INTEGER,  -- orthographe normalisée 
  tag        INTEGER,  -- catégorie morpho-syntaxique 
  lem        INTEGER,  -- lemme 
  start      INTEGER,  -- position du premier caractère (Unicode) dans le texte source lemmatisé
  end        INTEGER,  -- position du caractère (Unicode) suivant dans le texte source lemmatisé
  PRIMARY KEY(id ASC)
);
CREATE INDEX occOrth ON occ( orth );
CREATE INDEX occLem ON occ( lem );
CREATE INDEX occTag ON occ( tag );

CREATE TABLE term (
  -- Dictionnaire de termes indexés,
  id         INTEGER, -- alias rowid
  form       TEXT,    -- forme graphique de référence
  PRIMARY KEY(id ASC)
);

CREATE TABLE vek (
  -- Vecteur d’un terme dans un document
  id         INTEGER, -- alias rowid
  term       INTEGER REFERENCES term(id),  -- mot pivot du vecteur
  doc        INTEGER REFERENCES doc(id),   -- lien d’un vecteur à un document
  count      INTEGER, -- effectif du terme dans le document
  PRIMARY KEY(id ASC)
);
CREATE INDEX vekDoc ON vek( doc );

CREATE TABLE cooc (
  -- Une cooccurrence dans un vecteur
  id         INTEGER, -- alias rowid
  term       INTEGER REFERENCES term(id),  -- le mot en cooccurrence
  vek        INTEGER REFERENCES vek(id),   -- lien d’un vecteur à un document
  count      INTEGER, -- effectif du terme dans la cooccurrence
  PRIMARY KEY(id ASC)
);
