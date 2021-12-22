PRAGMA encoding = 'UTF-8'; -- W. encoding used for output
PRAGMA page_size = 32768; -- W. said as best for perfs
PRAGMA foreign_keys = FALSE; -- W. for efficiency
PRAGMA journal_mode = OFF; -- W. Dangerous, no roll back, maybe efficient
PRAGMA synchronous = OFF; --W. Dangerous, but no parallel write check
PRAGMA mmap_size = 1073741824; -- W/R. should be more efficient

-- Schema to store lemmatized texts
DROP TABLE IF EXISTS doc;
CREATE table doc (
-- an indexed HTML document
    id          INTEGER, -- rowid auto
    code        TEXT UNIQUE NOT NULL,   -- ! source filename without extension, unique for base
    filemtime   INTEGER NOT NULL, -- ! modified time
    filesize    INTEGER, -- ! filesize
    title       TEXT NOT NULL,    -- ! html, for a structured short result
    byline      TEXT,    -- ? authors… text, searchable
    date        INTEGER, -- ? favorite date for chronological sort
    start       INTEGER, -- ? creation, start year, when chronological queries are relevant
    end         INTEGER, -- ? creation, end year, when chronological queries are relevant
    issued      INTEGER, -- ? publication year of the text
    bibl        TEXT,    -- ? publication year of the text
    source      TEXT,    -- ? URL of source file (ex: XML/TEI)
    editby      TEXT,    -- ? editors
    publisher   TEXT,    -- ? Name of the original publisher of the file in case of compilation
    identifier  TEXT,    -- ? URL of the orginal publication in case of compilation
    PRIMARY KEY(id ASC)
);
CREATE UNIQUE INDEX IF NOT EXISTS doc_code ON doc(code);
CREATE INDEX IF NOT EXISTS doc_byline_date ON doc(byline, date, code);
CREATE INDEX IF NOT EXISTS doc_date_byline ON doc(date, byline, code);


DROP TABLE IF EXISTS tok;
CREATE TABLE tok (
-- compiled table of occurrences
    id          INTEGER, -- rowid auto
    doc         INTEGER NOT NULL,  -- ! doc id
    orth        INTEGER NOT NULL,  -- ! normalized orthographic form id
    lem         INTEGER NOT NULL,  -- ! lemma form id
    cat         INTEGER NOT NULL,  -- ! word category id
    offset      INTEGER NOT NULL,  -- ! start offset in source file, utf8 chars
    length      INTEGER NOT NULL,  -- ! size of token, utf8 chars
    PRIMARY KEY(id ASC)
);

CREATE TABLE orth (
-- Index of orthographic forms
    id          INTEGER, -- rowid auto
    form        TEXT NOT NULL,     -- ! letters of orthographic form
    cat         INTEGER,           -- ! word category id
    lem         INTEGER,           -- ! (form, cat) -> lemma
    PRIMARY KEY(id ASC)
)
CREATE UNIQUE INDEX IF NOT EXISTS doc_code ON doc(code);

CREATE TABLE lem (
-- Index of orthographic forms
    id          INTEGER, -- rowid auto
    form        TEXT NOT NULL,     -- ! letters of orthographic form
    PRIMARY KEY(id ASC)
)
