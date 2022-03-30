package alix.web;

import org.json.JSONWriter;

public enum Error
{
    BASE_NONE(500,  "Alix, no base available, installation problem", null),
    BASE_NOTFOUND(404,  "Alix, base not found", "No base found for param ?%s=%s"),
    DOC_NOTFOUND(404,  "Alix, document not found", "No doc found for param ?%s=%s"),
    FIELD_NOTFOUND(400, "Alix, field inappropriate for this query", "Field not found for param ?%s=%s"),
    XSS(403, "Alix, Cross-Site Scripting (XSS) Attacks, no", null),
    Q_NONE(400, "Alix, no word to search", "Query not found for param ?%s"),
    Q_NOWORD(400, "Alix, no word found", "No word found for param ?%s=%s"),
    Q_NOTFOUND(404, "Alix, words not found in partition", "Words not found for param ?%s=%s"),
    ;
    final int status;
    final String title;
    final String detail;
    
    private Error(final int status, final String title, final String detail)
    {
        this.status = status;
        this.title = title;
        this.detail = detail;
    }
    
    public int status()
    {
        return status;
    }
    public String title()
    {
        return title;
    }
    public String detail(Object... pars)
    {
        return String.format(detail, pars);
    }
    
    /**
     * Output a json view for this error
     * @param pars
     * @return
     */
    public String json(Object... pars)
    {
        StringBuilder sb = new StringBuilder();
        // one line error for nd-json
        sb.append("{");
        sb.append("\"status\": \""+ status +"\"");
        sb.append(", \"title\": \"" + title + "\"");
        if (detail != null) {
            sb.append(", \"detail\": " + JSONWriter.valueToString(String.format(detail, pars)));
        }
        sb.append("}");
        return sb.toString();
    }
    /**
     * Output an html view for this error
     * @param pars
     * @return
     */
    public String html(Object... pars)
    {
        StringBuilder sb = new StringBuilder();
        // one line error for nd-html
        sb.append("<div class=\"error\">");
        sb.append("<h1 class=\"error\">"+ title +"</h1>");
        if (detail != null) {
            sb.append("<p>" + String.format(detail, pars) + "</p>");
        }
        sb.append("</div>");
        return sb.toString();
    }
}
