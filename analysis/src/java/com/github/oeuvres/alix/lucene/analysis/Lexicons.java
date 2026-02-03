package com.github.oeuvres.alix.lucene.analysis;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.FutureTask;

public final class Lexicons
{
    private Lexicons() {};


    /** Per-webapp (per-classloader) cache to avoid leaks on redeploy */
    private static final Map<ClassLoader, ConcurrentHashMap<String, FutureTask<Object>>> CACHES =
        Collections.synchronizedMap(new WeakHashMap<>());
    
    
}
