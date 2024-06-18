package com.github.oeuvres.alix.web;

import org.json.JSONWriter;

public enum Error {
    BASE_NONE(500, "Alix, no base available, installation problem", null, 0),
    BASE_NOTFOUND(404, "Alix, base not found", "No base found for the request ?%s=%s", 2),
    DOC_NOTFOUND(404, "Alix, document not found", "No doc found for the request ?%s=%s", 2),
    FIELD_BADTYPE(400, "Alix, field with type inappropriate for this query", "Bad field type for request ?%s=%s (%s)",
            3),
    FIELD_BADREQUEST(400, "Alix, field inappropriate for this query", "Bad field name for request ?%s=%s", 2),
    FIELD_NONE(400, "Alix, field name mandatory but not given", "No field name for the waited param ?%s=…", 1),
    FIELD_NOTFOUND(404, "Alix, field not found for this base", "Bad field name for the request ?%s=%s — %s", 3),
    XSS(403, "Alix, Cross-Site Scripting (XSS) Attacks, no", null, 0),
    Q_NONE(400, "Alix, word to search mandatory", "Query not found for the waited param ?%s=…", 1),
    Q_NOTFOUND(404, "Alix, words not found in partition", "Words not found for request ?%s=%s", 2);

    final int status;
    final String title;
    final String detail;
    final int argLen;

    private Error(final int status, final String title, final String detail, final int argLen) {
        this.status = status;
        this.title = title;
        this.detail = detail;
        this.argLen = argLen;
    }

    /**
     * 
     * @return Error status number.
     */
    public int status()
    {
        return status;
    }

    /**
     * 
     * @return Error title.
     */
    public String title()
    {
        return title;
    }

    /**
     * 
     * @param args {@link String#format(String, Object...)}.
     * @return Error detailed with arguments.
     */
    public String detail(Object... args)
    {
        return String.format(detail, args);
    }

    /**
     * 
     * @param args
     * @return
     */
    public Object[] norm(final Object[] args)
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
        if (detail != null) {
            args = norm(args);
            sb.append(", \"detail\": " + JSONWriter.valueToString(String.format(detail, args)));
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
        if (detail != null) {
            args = norm(args);
            sb.append("<p>" + String.format(detail, args) + "</p>");
        }
        sb.append("</div>");
        return sb.toString();
    }
}
