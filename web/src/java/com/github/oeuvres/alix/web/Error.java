package com.github.oeuvres.alix.web;

import org.json.JSONWriter;

/**
 * Error messages for Alix.
 */
public enum Error {
    /** Error 500 */
    BASE_NONE(500, "Alix, no base available, installation problem", null, 0),
    /** Error 404 */
    BASE_NOTFOUND(404, "Alix, base not found", "No base found for the request ?%s=%s", 2),
    /** Error 404 */
    DOC_NOTFOUND(404, "Alix, document not found", "No doc found for the request ?%s=%s", 2),
    /** Error 400 */
    FIELD_BADTYPE(400, "Alix, field with type inappropriate for this query", "Bad field type for request ?%s=%s (%s)",
            3),
    /** Error 400 */
    FIELD_BADREQUEST(400, "Alix, field inappropriate for this query", "Bad field name for request ?%s=%s", 2),
    /** Error 400 */
    FIELD_NONE(400, "Alix, field name mandatory but not given", "No field name for the waited param ?%s=…", 1),
    /** Error 404 */
    FIELD_NOTFOUND(404, "Alix, field not found for this base", "Bad field name for the request ?%s=%s — %s", 3),
    /** Error 403 */
    XSS(403, "Alix, Cross-Site Scripting (XSS) Attacks, no", null, 0),
    /** Error 400 */
    Q_NONE(400, "Alix, word to search mandatory", "Query not found for the waited param ?%s=…", 1),
    /** Error 404 */
    Q_NOTFOUND(404, "Alix, words not found in partition", "Words not found for request ?%s=%s", 2);

    final int status;
    final String title;
    final String details;
    final int argLen;

    /**
     * Build an error message.
     * 
     * @param status error number.
     * @param title general description.
     * @param details text with possible arguments, see {@link String#format(String, Object...)}.
     * @param parLen count of parameters in details.
     */
    private Error(final int status, final String title, final String details, final int parLen) {
        this.status = status;
        this.title = title;
        this.details = details;
        this.argLen = parLen;
    }

    /**
     * Get the status number.
     * 
     * @return Error status number.
     */
    public int status()
    {
        return status;
    }

    /**
     * Get the title.
     * 
     * @return Error title.
     */
    public String title()
    {
        return title;
    }

    /**
     * Format the details part of error message.
     * 
     * @param args {@link String#format(String, Object...)}.
     * @return Error detailed with arguments.
     */
    public String details(Object... args)
    {
        return String.format(details, args);
    }

    /**
     * Normalize argument count, according to argLen, to avoid exceptions
     * if count of argument is not like expected in the 
     * 
     * @param args arguments send by a jsp.
     * @return normalized array of arguments.
     */
    private Object[] norm(final Object[] args)
    {
        if (args == null)
            return args;
        if (args.length == argLen)
            return args;
        Object[] argNew = new Object[argLen];
        for (int i = 0; i < argLen; i++) {
            if (i < args.length) {
                argNew[i] = args[i];
            } else {
                argNew[i] = "";
            }
        }
        return argNew;
    }

    /**
     * Output a json view for this error
     * 
     * @param args {@link String#format(String, Object...)}.
     * @return JSON String.
     */
    public String json(Object... args)
    {
        StringBuilder sb = new StringBuilder();
        // one line error for nd-json
        sb.append("{");
        sb.append("\"status\": \"" + status + "\"");
        sb.append(", \"title\": \"" + title + "\"");
        if (details != null) {
            args = norm(args);
            sb.append(", \"detail\": " + JSONWriter.valueToString(String.format(details, args)));
        }
        sb.append("}");
        return sb.toString();
    }

    /**
     * Output an html view for this error with possible arguments.
     * 
     * @param args {@link String#format(String, Object...)}.
     * @return Displayable HTML for an error.
     */
    public String html(Object... args)
    {
        StringBuilder sb = new StringBuilder();
        // one line error for nd-html
        sb.append("<div class=\"error\">");
        sb.append("<h1 class=\"error\">" + title + "</h1>");
        if (details != null) {
            args = norm(args);
            sb.append("<p>" + String.format(details, args) + "</p>");
        }
        sb.append("</div>");
        return sb.toString();
    }
}
