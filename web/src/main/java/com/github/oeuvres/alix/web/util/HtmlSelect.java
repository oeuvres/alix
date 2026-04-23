package com.github.oeuvres.alix.web.util;

import java.io.IOException;
import java.util.List;

import com.github.oeuvres.alix.util.Markup;

public class HtmlSelect
{
    private HtmlSelect() {}

    public record Option(String value, String label) {}

    public static void options(
        Appendable out,
        String selectedValue,
        List<Option> options
    ) throws IOException {
        for (Option opt : options) {
            out.append("<option value=\"").append(Markup.escapeAttribute(opt.value())).append("\"");
            if (opt.value().equals(selectedValue)) {
                out.append(" selected");
            }
            out.append(">")
               .append(Markup.escapeAttribute(opt.label()))
               .append("</option>");
        }
    }


}
