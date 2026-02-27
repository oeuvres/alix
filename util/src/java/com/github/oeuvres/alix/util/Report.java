package com.github.oeuvres.alix.util;

public interface Report
{
    void debug(String msg);
    void info(String msg);
    void warn(String msg);
    void error(String msg);


    public final class ReportNull implements Report
    {
        public static final ReportNull INSTANCE = new ReportNull();
        private ReportNull() {}    
        @Override public void debug(String msg) {}
        @Override public void info(String msg)  {}
        @Override public void warn(String msg)  {}
        @Override public void error(String msg) {}
    }
    
    
    public final class ReportConsole implements Report
    {
        @Override
        public void debug(String msg)
        {
            System.out.println(msg);
        }
        
        @Override
        public void info(String msg)
        {
            System.out.println(msg);
        }
        
        @Override
        public void warn(String msg)
        {
            System.err.println(msg);
        }
        
        @Override
        public void error(String msg)
        {
            System.err.println(msg);
        }
    }
}