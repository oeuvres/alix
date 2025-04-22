package com.github.oeuvres.alix.util;

public class IntMutable
{
    private int value;
    public IntMutable(final int value) {
        this.value = value;
    }
    public void set(final int value) {
        this.value = value;
    }
    public void inc() {
        this.value++;
    }
    public void add(final int add) {
        this.value += add;
    }
    public int value() {
        return value;
    }
    @Override
    public int hashCode() {
        return value;
    }
}
