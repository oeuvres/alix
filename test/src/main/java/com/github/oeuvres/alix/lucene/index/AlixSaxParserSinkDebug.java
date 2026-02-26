package com.github.oeuvres.alix.lucene.index;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class  AlixSaxParserSinkDebug implements AlixSaxParser.AlixSink {

    @Override
    public void startUnit(AlixSaxParser.Unit unit) {
        System.out.printf("START %-7s id=%s%n", unit.kind(), unit.xmlId());
    }

    @Override
    public void field(AlixSaxParser.Unit unit, AlixSaxParser.FieldSpec f) {
        System.out.printf("  FIELD %-12s type=%-8s analyzer=%-12s",
            f.name(), f.type().name().toLowerCase(), f.analyzerHint());

        if (f.value() != null) {
            System.out.printf(" value=%s", shortStr(f.value()));
        }
        if (f.contentXml() != null) {
            System.out.printf(" contentXml=%s", shortStr(f.contentXml()));
        }
        if (f.source() != null) {
            System.out.printf(" source=%s selectors=%d", f.source(), f.selectors().size());
        }
        System.out.println();

        for (var s : f.selectors()) {
            System.out.printf("    %s element=%s attribute=%s value=%s%n",
                s.mode().name().toLowerCase(), s.element(), s.attribute(), s.value());
        }
    }

    @Override
    public void endUnit(AlixSaxParser.Unit unit) {
        System.out.printf("END   %-7s id=%s%n", unit.kind(), unit.xmlId());
    }

    private static String shortStr(String s) {
        if (s == null) return "null";
        s = s.replace('\n', ' ').replace('\r', ' ');
        return (s.length() > 90) ? s.substring(0, 87) + "..." : s;
    }
    
    public static void main(String[] args) throws Exception {
        // final String res = "/ingest/test-alix.xml";
        Path path = Paths.get("src/main/resources/ingest/test-alix.xml");
        System.out.println(path.toAbsolutePath());
        // try (InputStream in = Thread.currentThread().getContextClassLoader() .getResourceAsStream(res)) {
        try (InputStream in = Files.newInputStream(path)) {
            if (in == null) throw new IllegalStateException(path + ": resource not found");
            AlixSaxParser.parse(in, new AlixSaxParserSinkDebug());
        }
    }
}