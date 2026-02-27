package com.github.oeuvres.alix.ingest;



import org.xml.sax.SAXException;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public final class AlixDebugIngest {

  // ---------------- Diagnostics ----------------

  enum Severity { WARN, ERROR }

  static final class Diagnostic {
    final Severity severity;
    final String message;
    Diagnostic(Severity s, String m) { this.severity = s; this.message = m; }
    @Override public String toString() { return severity + ": " + message; }
  }

  interface DiagnosticSink {
    void report(Severity severity, String message);
  }

  static final class ListDiagnosticSink implements DiagnosticSink {
    final List<Diagnostic> diags = new ArrayList<>();
    @Override public void report(Severity severity, String message) {
      diags.add(new Diagnostic(severity, message));
    }
    boolean hasErrors() {
      for (Diagnostic d : diags) if (d.severity == Severity.ERROR) return true;
      return false;
    }
    void printToStdErr() {
      for (Diagnostic d : diags) System.err.println(d);
    }
    void clear() { diags.clear(); }
  }

  // ---------------- Format-level validator ----------------

  static final class AlixDocumentValidator {

    void validate(AlixDocument doc, DiagnosticSink out) {
      final int n = doc.fieldCount();

      // validate per-field
      for (int i = 0; i < n; i++) {
        AlixDocument.AlixField f = doc.fieldAt(i);

        // INT parse check
        if (f.type == AlixDocument.FieldType.INT) {
          if (!isInt(doc, f.off, f.len)) {
            out.report(Severity.ERROR, "Field '" + f.name + "' type=int has non-integer content");
          }
        }

        // include/exclude should only appear on TEXT (per current usage)
        if ((f.include != null || f.exclude != null) && f.type != AlixDocument.FieldType.TEXT) {
          out.report(Severity.WARN, "Field '" + f.name + "': include/exclude set on non-text type " + f.type);
        }

        // source resolution: source must exist in same document scope
        if (f.source != null) {
          int src = findSourceTextField(doc, f.source);
          if (src < 0) {
            out.report(Severity.ERROR, "Field '" + f.name + "' source='" + f.source + "' not found in same document");
          }
        }
      }
    }

    private static int findSourceTextField(AlixDocument doc, String sourceName) {
      // Small-N: linear scan is simplest and fast enough.
      for (int i = 0; i < doc.fieldCount(); i++) {
        AlixDocument.AlixField f = doc.fieldAt(i);
        if (f.type == AlixDocument.FieldType.TEXT && f.source == null && sourceName.equals(f.name)) {
          return i;
        }
      }
      return -1;
    }

    private static boolean isInt(AlixDocument doc, int off, int len) {
      // Avoid String allocation: scan the document buffer via sliceToString would allocate.
      // We cannot access buffer directly (private), so use sliceToString only for small slices.
      // Here len is expected small; OK.
      String s = doc.sliceToString(off, len).trim();
      if (s.isEmpty()) return false;

      int i = 0;
      char c = s.charAt(0);
      if (c == '+' || c == '-') i++;
      if (i == s.length()) return false;

      for (; i < s.length(); i++) {
        c = s.charAt(i);
        if (c < '0' || c > '9') return false;
      }
      return true;
    }
  }

  // ---------------- Debug consumer ----------------

  static final class DumpConsumer implements AlixSaxHandler.AlixDocumentConsumer {

    private final AlixDocumentValidator validator = new AlixDocumentValidator();
    private final ListDiagnosticSink diags = new ListDiagnosticSink();
    private final boolean strict;

    DumpConsumer(boolean strict) { this.strict = strict; }

    @Override
    public void accept(AlixDocument doc) throws SAXException {
      diags.clear();
      validator.validate(doc, diags);

      // Print header
      System.out.println("== " + doc.documentType() + " id=" + doc.documentId() + " ==");

      // Print diagnostics first (if any)
      if (!diags.diags.isEmpty()) {
        diags.printToStdErr();
        if (strict && diags.hasErrors()) {
          throw new SAXException("Validation errors (strict mode)");
        }
      }

      // Dump fields in your canonical type order
      for (AlixDocument.FieldType t : AlixDocument.FieldType.CANONICAL_ORDER) {
        for (int i = 0; i < doc.fieldCount(); i++) {
          AlixDocument.AlixField f = doc.fieldAt(i);
          if (f.type != t) continue;

          System.out.print("- " + f.type + " " + f.name);
          if (f.source != null) System.out.print(" source=" + f.source);
          if (f.include != null) System.out.print(" include=" + f.include);
          if (f.exclude != null) System.out.print(" exclude=" + f.exclude);
          System.out.println(" len=" + f.len);

          if (f.len > 0) {
            String v = doc.sliceToString(f.off, f.len);
            System.out.println("  " + preview(v, 240));
          }
        }
      }

      System.out.println();
    }

    private static String preview(String s, int max) {
      // Make output readable (keep content, but collapse control chars)
      String p = s.replace("\r", "\\r").replace("\n", "\\n");
      if (p.length() <= max) return p;
      return p.substring(0, max) + "…";
    }
  }

  // ---------------- Main ----------------

  public static void main(String[] args) throws IOException, SAXException {

    // Path file = Path.of(args[0]);
    Path path = Paths.get("src/test/test-data/ingest-alix-test.xml");

    AlixDocument doc = new AlixDocument();
    boolean strict = true;
    AlixSaxHandler.AlixDocumentConsumer consumer = new DumpConsumer(strict);

    AlixFileIngester ing = new AlixFileIngester();
    ing.ingest(path, doc, consumer); // adapt signature if your ingester differs
  }
}