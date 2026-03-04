package com.github.oeuvres.alix.ingest;

import org.xml.sax.SAXException;

import com.github.oeuvres.alix.ingest.AlixDocument.AlixField;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public final class AlixDebugIngest
{
    
    // ---------------- Diagnostics ----------------
    
    enum Severity
    {
        WARN, ERROR
    }
    
    static final class Diagnostic
    {
        final Severity severity;
        final String message;
        
        Diagnostic(Severity s, String m)
        {
            this.severity = s;
            this.message = m;
        }
        
        @Override
        public String toString()
        {
            return severity + ": " + message;
        }
    }
    
    interface DiagnosticSink
    {
        void report(Severity severity, String message);
    }
    
    static final class ListDiagnosticSink implements DiagnosticSink
    {
        final List<Diagnostic> diags = new ArrayList<>();
        
        @Override
        public void report(Severity severity, String message)
        {
            diags.add(new Diagnostic(severity, message));
        }
        
        boolean hasErrors()
        {
            for (Diagnostic d : diags)
                if (d.severity == Severity.ERROR)
                    return true;
            return false;
        }
        
        void printToStdErr()
        {
            for (Diagnostic d : diags)
                System.err.println(d);
        }
        
        void clear()
        {
            diags.clear();
        }
    }
    
    // ---------------- Format-level validator ----------------
    
    static final class AlixDocumentValidator
    {
        
        void validate(AlixDocument doc, DiagnosticSink out)
        {
            final int n = doc.fieldCount();
            
            // validate per-field
            for (int i = 0; i < n; i++) {
                AlixField alixField = doc.fieldAt(i);
                String value = alixField.getValueAsString();
                // INT parse check
                if (alixField.type == AlixDocument.FieldType.INT) {
                    try {
                        Integer.parseInt(value);
                    }
                    catch (Exception e) {
                        out.report(Severity.ERROR, "Field " + alixField.name + "=" + value + " type=int has non-integer content");
                    }
                }
                
                
                // source resolution: source must exist in same document scope
                if (alixField.source != null) {
                    int src = findSourceTextField(doc, alixField.source);
                    if (src < 0) {
                        out.report(Severity.ERROR,
                                "Field '" + alixField.name + "' source='" + alixField.source + "' not found in same document");
                    }
                }
            }
        }
        
        private static int findSourceTextField(AlixDocument doc, String sourceName)
        {
            // Small-N: linear scan is simplest and fast enough.
            for (int i = 0; i < doc.fieldCount(); i++) {
                AlixDocument.AlixField f = doc.fieldAt(i);
                if (f.type == AlixDocument.FieldType.TEXT && f.source == null && sourceName.equals(f.name)) {
                    return i;
                }
            }
            return -1;
        }
        
    }
    
    // ---------------- Debug consumer ----------------
    
    static final class DumpConsumer implements AlixSaxHandler.AlixDocumentConsumer
    {
        
        private final AlixDocumentValidator validator = new AlixDocumentValidator();
        private final ListDiagnosticSink diags = new ListDiagnosticSink();
        private final boolean strict;
        
        DumpConsumer(boolean strict)
        {
            this.strict = strict;
        }
        
        @Override
        public void accept(AlixDocument doc) throws SAXException
        {
            diags.clear();
            validator.validate(doc, diags);
            
            // Print header
            System.out.println("== " + doc.docType() + " id=" + doc.docId() + " ==");
            
            // Print diagnostics first (if any)
            if (!diags.diags.isEmpty()) {
                diags.printToStdErr();
                if (strict && diags.hasErrors()) {
                    throw new SAXException("Validation errors (strict mode)");
                }
            }
            
            // Dump fields in your canonical type order
            for (int i = 0; i < doc.fieldCount(); i++) {
                AlixField alixField = doc.fieldAt(i);
                
                System.out.print("- " + alixField.type + " " + alixField.name);
                if (alixField.source != null)
                    System.out.print(" source=" + alixField.source);
                System.out.println(" len=" + alixField.len);
                
                if (alixField.len > 0) {
                    System.out.println("  " + preview(alixField.getValueAsString(), 240));
                }
            }
            
            System.out.println();
        }
        
        private static String preview(String s, int max)
        {
            // Make output readable (keep content, but collapse control chars)
            String p = s.replace("\r", "\\r").replace("\n", "\\n");
            if (p.length() <= max)
                return p;
            return p.substring(0, max) + "…";
        }
    }
    
    // ---------------- Main ----------------
    
    public static void main(String[] args) throws IOException, SAXException
    {
        
        // Path file = Path.of(args[0]);
        Path path = Paths.get("src/test/test-data/ingest-alix-test.xml");
        
        AlixDocument doc = new AlixDocument();
        boolean strict = true;
        AlixSaxHandler.AlixDocumentConsumer consumer = new DumpConsumer(strict);
        
        AlixFileIngester ing = new AlixFileIngester();
        ing.ingest(path, doc, consumer); // adapt signature if your ingester differs
    }
}