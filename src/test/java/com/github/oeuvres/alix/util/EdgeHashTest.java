package com.github.oeuvres.alix.util;

import static org.junit.Assert.*;


import org.junit.Test;

public class EdgeHashTest {

    @Test
    public void cluster()
    {
        EdgeMap net = new EdgeMap(false);
        net.declust();
        net.clust(0);
        net.clust(1);
        net.clust(2);
        net.declust();
        net.clust(3);
        net.clust(2);
        net.declust();
        net.clust(2);
        net.clust(0);
        net.clust(1);
        net.clust(4);
        System.out.println(net);
    }

}
