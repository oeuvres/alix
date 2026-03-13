package com.github.oeuvres.alix.web;

import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;

/**
 * Embedded Jetty for development. Not shipped in any artifact.
 *
 * <pre>
 * mvn test-compile exec:java -Dalix.conf.dir=conf
 * </pre>
 */
public class DevServer
{
    public static void main(String[] args) throws Exception
    {
        int port = Integer.getInteger("port", 8888);
        Server server = new Server(port);

        ServletContextHandler ctx = new ServletContextHandler();
        ctx.setContextPath("/alix");

        ServletHolder holder = new ServletHolder(new AlixServlet());
        holder.setInitParameter("alix.conf.dir",
            System.getProperty("alix.conf.dir", "conf"));
        ctx.addServlet(holder, "/*");

        server.setHandler(ctx);
        server.start();
        System.out.println("Alix dev server: http://localhost:" + port + "/alix/");
        server.join();
    }
}
