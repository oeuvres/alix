package com.github.oeuvres.alix.web.util;

import java.io.IOException;

public class HttpParsTest
{
    public static void main(final String[] args) throws IOException
    {
        System.out.println(HttpPars.encodeQueryComponent("ab\nab"));
    }
}
