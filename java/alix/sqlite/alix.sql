-- structure d’un index plein texte avec informations linguistiques

PRAGMA encoding = 'UTF-8';
PRAGMA page_size = 4096;
PRAGMA cache_size = 10000;
PRAGMA temp_store = 2; -- memory temp table
PRAGMA journal_mode = OFF; -- unsecure
PRAGMA synchronous = OFF; -- unsecure
PRAGMA auto_vacuum = FULL;

CREATE TABLE doc (
  -- Document = fichier
  id         INTEGER,  -- alias rowid
  name       STRING,   -- nom de fichier unique
  url        TEXT,     -- lien Internet
  collection STRING,   -- sous-corpus
  title      TEXT,     -- titre affichable
  page       INTEGER,  -- index
  byline     TEXT,     -- auteur
  date       STRING,   -- date format AAAA-MM-JJ
  julianday  INTEGER,  -- numéro de jour depuis -4714
  year       INTEGER,  -- année entier
  month      INTEGER,  -- mois entier
  daymonth   INTEGER,  -- jour du mois
  dayweek    INTEGER,  -- jour de la semaine
  chars      INTEGER,  -- nombre dr caractères du document
  occs       INTEGER,  -- nombre d’occurrences pour le document
  PRIMARY KEY(id ASC)
);
CREATE UNIQUE INDEX doc_name ON doc(name);

CREATE TABLE blob (
  -- Texte brut à indexer
  id         INTEGER,  -- alias rowid
  text       BLOB,     -- texte original
  PRIMARY KEY(id ASC)
);

CREATE TABLE occ (
  -- indexation des occurrences
  id         INTEGER,  -- alias rowid
  doc        INTEGER,
  orth       INTEGER,  -- orthographe normalisée 
  tag        INTEGER,  -- catégorie morpho-syntaxique 
  lem        INTEGER,  -- lemme 
  start      INTEGER,  -- position du premier caractère (Unicode) dans le texte source
  end        INTEGER,  -- position du caractère (Unicode) suivant dans le texte source
  PRIMARY KEY(id ASC)
);

CREATE TABLE orth (
  -- Dictionnaire de termes indexés,
  id         INTEGER, -- alias rowid
  form       TEXT,    -- forme graphique de référence
  tag        INTEGER,
  lem        INTEGER,
  PRIMARY KEY(id ASC)
);

CREATE TABLE lem (
  -- Dictionnaire de termes indexés,
  id         INTEGER, -- alias rowid
  form       TEXT,    -- forme graphique de référence
  tag        INTEGER,
  PRIMARY KEY(id ASC)
);

CREATE INDEX doc_year ON doc(year);
CREATE INDEX doc_dayweek ON doc(dayweek);
CREATE INDEX doc_collection ON doc(collection, julianday);
CREATE INDEX doc_collweek ON doc(collection, week, date);
CREATE INDEX doc_collmonth ON doc(collection, month, date);

CREATE INDEX occ_orth ON occ(orth);
CREATE INDEX occ_lem ON occ(lem);
CREATE INDEX occ_tag ON occ(tag);


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
