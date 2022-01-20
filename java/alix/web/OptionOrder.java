package alix.web;

import alix.lucene.search.FormEnum.Order;

public enum OptionOrder implements Option 
{
    score("Score", null, Order.score),
    freq("Occurrences", null, Order.freq),
    hits("Textes", null, Order.hits),
    occs("Total occurrences", null, Order.occs),
    docs("Total textes", null, Order.docs),
    alpha("Alphabétique", null, Order.alpha),
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
