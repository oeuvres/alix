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
  creator    TEXT,     -- auteur
  occs       INTEGER,  -- nombre d’occurrences pour le document
  PRIMARY KEY(id ASC)
);

CREATE TABLE term (
  -- Dictionnaires des termes indexés,
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
CREATE INDEX vek_doc ON vek(doc);

CREATE TABLE cooc (
  -- Une cooccurrence dans un vecteur
  id         INTEGER, -- alias rowid
  term       INTEGER REFERENCES term(id),  -- le mot en cooccurrence
  vek        INTEGER REFERENCES vek(id),   -- lien d’un vecteur à un document
  count      INTEGER, -- effectif du terme dans la cooccurrence
  PRIMARY KEY(id ASC)
);
