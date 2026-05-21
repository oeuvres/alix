package com.github.oeuvres.alix.web;

import java.io.IOException;
import java.util.EnumSet;

import org.eclipse.jetty.ee10.servlet.ErrorHandler;
import org.eclipse.jetty.ee10.servlet.FilterHolder;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;

/**
 * Embedded Jetty for development. Not shipped in any artifact.
 *
 * <pre>
 * mvn test-compile exec:java -Dalix.conf.dir=conf
 * </pre>
 */
public class DevServer
{
    /**
     * Start the development server.
     *
     * @param args command line arguments, currently ignored.
     * @throws Exception if Jetty cannot start or join.
     */
    public static void main(String[] args) throws Exception
    {
        int port = Integer.getInteger("port", 8888);
        Server server = new Server(port);

        ServletContextHandler ctx = new ServletContextHandler();
        ctx.setContextPath("/alix");

        ErrorHandler errorHandler = new ErrorHandler();
        errorHandler.setShowStacks(true);
        errorHandler.setShowServlet(true);
        errorHandler.setShowMessageInTitle(true);
        ctx.setErrorHandler(errorHandler);

        ctx.addFilter(
            new FilterHolder(new DevExceptionFilter()),
            "/*",
            EnumSet.of(DispatcherType.REQUEST)
        );

        ServletHolder holder = new ServletHolder(new AlixServlet());
        holder.setInitParameter(
            "alix.conf.dir",
            System.getProperty("alix.conf.dir", "conf")
        );
        ctx.addServlet(holder, "/*");

        server.setHandler(ctx);
        server.start();
        System.out.println("Alix dev server: http://localhost:" + port + "/alix/");
        server.join();
    }

    /**
     * Development filter that prints uncaught request exceptions to stderr before
     * Jetty renders the HTTP error page.
     */
    private static final class DevExceptionFilter implements Filter
    {
        /**
         * Run the request chain and print uncaught failures to stderr.
         *
         * @param request the servlet request.
         * @param response the servlet response.
         * @param chain the remaining servlet filter chain.
         * @throws IOException if the chain fails with an I/O exception.
         * @throws ServletException if the chain fails with a servlet exception.
         */
        @Override
        public void doFilter(
            final ServletRequest request,
            final ServletResponse response,
            final FilterChain chain
        ) throws IOException, ServletException {
            try {
                chain.doFilter(request, response);
            }
            catch (final ServletException e) {
                e.printStackTrace(System.err);
                throw e;
            }
            catch (final IOException e) {
                e.printStackTrace(System.err);
                throw e;
            }
            catch (final RuntimeException e) {
                e.printStackTrace(System.err);
                throw e;
            }
            catch (final Error e) {
                e.printStackTrace(System.err);
                throw e;
            }
        }
    }
}