package alix.web;

import alix.lucene.search.FormEnum.Order;

public enum OptionOrder implements Option 
{
    SCORE("pertinence", null, Order.SCORE),
    FREQ("occurrences", null, Order.FREQ),
    HITS("nb de textes", null, Order.HITS),
    OCCS("Total occurrences", null, Order.OCCS),
    DOCS("Total textes", null, Order.DOCS),
    ALPHA("alphabétique", null, Order.ALPHA),
    ;
    private OptionOrder(final String label, final String hint, Order order) {    
        this.label = label;
        this.hint = hint;
        this.order = order;
    }
    final public Order order;
    final public String label;
    final public String hint;
    public String label() { return label; }
    public String hint() { return hint; }
    public Order order() { return order; }

}
