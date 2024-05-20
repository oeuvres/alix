package com.github.oeuvres.alix.util;
import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.Test;

public class DirTest {

    @Test
    public void testDirGlob() throws IOException {
        String resources = new File("src/test/resources/").getAbsolutePath();
        String[] globs = {
            resources + "/globtest/a.txt",
            resources + "\\globtest\\a.txt",
            resources + "/globtest/*",
            resources + "/globtest/*.txt",
        };
        String[] expected = {
            "",
            "",
        };
        for (int i = 0; i < globs.length; i++) {
            List<Path> paths = Dir.ls(globs[i]);
            /*
            System.out.println(
                globs[i] + ":" + 
                paths.stream().map(n -> n.toString()).collect(Collectors.joining("\n    ", "\n    ", ""))
            );
            */
            // assertEquals( expected[i], result);
        }
    }

}
