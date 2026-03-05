package com.github.oeuvres.alix.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JUnit 5 tests for {@link Dir}.
 *
 * <p>
 * These tests focus on:
 * <ul>
 * <li>glob normalization against a base config file</li>
 * <li>include(): direct file, wildcard expansion, hidden-pruning, de-dup</li>
 * <li>exclude(): removal by glob</li>
 * <li>rm(): recursive delete</li>
 * </ul>
 *
 * <p>
 * Note: to reduce OS-specific path issues, globs are built from absolute paths and use forward slashes.
 * </p>
 */
public class DirTest
{
    
    @TempDir
    Path tmp;
    
    @Test
    void globNorm_resolvesRelativeAgainstConfigFileParent() throws Exception
    {
        Path cfgDir = Files.createDirectories(tmp.resolve("cfg"));
        Path cfgFile = Files.write(cfgDir.resolve("index.properties.xml"), "<x/>".getBytes(StandardCharsets.UTF_8));
        
        String g = Dir.globNorm("data/*.xml", cfgFile.toFile());
        assertNotNull(g);
        
        // Must be absolute and contain cfgDir + data/*.xml
        assertTrue(new File(g).isAbsolute(), "globNorm should return an absolute path");
        assertTrue(g.replace('\\', '/').contains("/cfg/data/*.xml"), "glob should be resolved against config parent");
    }
    
    @Test
    void globNorm_blankAndCommentsReturnNull() throws Exception
    {
        assertNull(Dir.globNorm("   ", tmp.toFile()));
        assertNull(Dir.globNorm("# comment", tmp.toFile()));
    }
    
    @Test
    void include_directExistingFile_isAdded() throws Exception
    {
        Path f = write(tmp.resolve("a.xml"), "<x/>");
        List<Path> out = new ArrayList<>();
        
        Dir.include(out, f.toString());
        
        assertEquals(1, out.size());
        assertEquals(f, out.get(0));
    }
    
    @Test
    void include_wildcardMatches_andDeduplicates() throws Exception
    {
        Path dir = Files.createDirectories(tmp.resolve("data"));
        Path f1 = write(dir.resolve("a.xml"), "<x/>");
        Path f2 = write(dir.resolve("b.xml"), "<x/>");
        write(dir.resolve("c.txt"), "no");
        
        String glob = (dir.toAbsolutePath().toString().replace('\\', '/') + "/*.xml");
        
        List<Path> out = new ArrayList<>();
        Dir.include(out, glob);
        Dir.include(out, glob); // second pass should not duplicate
        
        assertTrue(out.contains(f1));
        assertTrue(out.contains(f2));
        assertEquals(2, out.size(), "include() should de-duplicate results");
    }
    
    @Test
    void include_skipDotOrUnderscore_prunesDirectoriesByDefault() throws Exception
    {
        Path goodDir = Files.createDirectories(tmp.resolve("ok"));
        Path hiddenDir = Files.createDirectories(tmp.resolve(".hidden"));
        Path underDir = Files.createDirectories(tmp.resolve("_private"));
        
        Path good = write(goodDir.resolve("a.xml"), "<x/>");
        Path hidden = write(hiddenDir.resolve("b.xml"), "<x/>");
        Path under = write(underDir.resolve("c.xml"), "<x/>");
        
        String globAllXml = (tmp.toAbsolutePath().toString().replace('\\', '/') + "/**/*.xml");
        
        List<Path> out = new ArrayList<>();
        Dir.include(out, globAllXml); // default: skipDotOrUnderscore = true
        
        assertTrue(out.contains(good));
        assertFalse(out.contains(hidden), "should prune .hidden subtree by default");
        assertFalse(out.contains(under), "should prune _private subtree by default");
    }
    
    @Test
    void include_skipDotOrUnderscore_false_includesHiddenAndUnderscore() throws Exception
    {
        Path goodDir = Files.createDirectories(tmp.resolve("ok"));
        Path hiddenDir = Files.createDirectories(tmp.resolve(".hidden"));
        Path underDir = Files.createDirectories(tmp.resolve("_private"));
        
        Path good = write(goodDir.resolve("a.xml"), "<x/>");
        Path hidden = write(hiddenDir.resolve("b.xml"), "<x/>");
        Path under = write(underDir.resolve("c.xml"), "<x/>");
        
        String globAllXml = (tmp.toAbsolutePath().toString().replace('\\', '/') + "/**/*.xml");
        
        List<Path> out = new ArrayList<>();
        Dir.include(out, globAllXml, false);
        
        assertTrue(out.contains(good));
        assertTrue(out.contains(hidden));
        assertTrue(out.contains(under));
        assertEquals(3, out.size());
    }
    
    @Test
    void exclude_removesPathsMatchingGlob() throws Exception
    {
        Path dir = Files.createDirectories(tmp.resolve("data"));
        Path keep = write(dir.resolve("keep.xml"), "<x/>");
        Path drop = write(dir.resolve("drop.xml"), "<x/>");
        
        String globAll = (dir.toAbsolutePath().toString().replace('\\', '/') + "/*.xml");
        String globDrop = (dir.toAbsolutePath().toString().replace('\\', '/') + "/drop.xml");
        
        List<Path> out = new ArrayList<>();
        Dir.include(out, globAll);
        assertEquals(2, out.size());
        
        Dir.exclude(out, globDrop);
        
        assertEquals(1, out.size());
        assertTrue(out.contains(keep));
        assertFalse(out.contains(drop));
    }
    
    @Test
    void rm_deletesDirectoryTree() throws Exception
    {
        Path dir = Files.createDirectories(tmp.resolve("tree/sub"));
        write(dir.resolve("a.txt"), "x");
        write(tmp.resolve("tree/b.txt"), "y");
        
        Path root = tmp.resolve("tree");
        assertTrue(Files.exists(root));
        
        assertTrue(Dir.rm(root));
        assertFalse(Files.exists(root));
    }
    
    // -------- helpers --------
    
    private static Path write(Path path, String content) throws IOException
    {
        Files.createDirectories(path.getParent());
        return Files.write(path, content.getBytes(StandardCharsets.UTF_8));
    }
}

/*
 * public void testDirGlob() throws IOException {
 * String resources = new File("src/test/resources/").getAbsolutePath();
 * String[] globs = {
 * resources + "/globtest/a.txt",
 * resources + "\\globtest\\a.txt",
 * resources + "/globtest/*",
 * resources + "/globtest/*.txt",
 * };
 * String[] expected = {
 * "",
 * "",
 * };
 * for (int i = 0; i < globs.length; i++) {
 * List<Path> paths = Dir.ls(globs[i]);
 * System.out.println(
 * globs[i] + ":" +
 * paths.stream().map(n -> n.toString()).collect(Collectors.joining("\n    ", "\n    ", ""))
 * );
 * // assertEquals( expected[i], result);
 * }
 * }
 */
