package com.github.oeuvres.alix.lucene.search;

import static org.junit.Assert.*;

import org.junit.Test;

import com.github.oeuvres.alix.lucene.search.FormIterator.Order;

public class FormCollectorTest
{

    public void test()
    {
        FormCollector nodes = new FormCollector();
        nodes.put(10, 1, 1);
        System.out.println(nodes);
        nodes.put(2, 2, 2);
        nodes.put(7, 3, 3);
        System.out.println(nodes);
        nodes.sort(Order.SCORE);
        System.out.println(nodes);
        nodes.sort(Order.SCORE, 1);
        System.out.println(nodes);
        nodes.sort(Order.INSERTION, 2);
        System.out.println(nodes);
    }

}
