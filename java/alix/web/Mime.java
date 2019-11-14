package alix.web;

import java.io.IOException;

public enum Mime
{
  html("text/html; charset=UTF-8"),
  htf("text/html; charset=UTF-8"),
  json("application/json"),
  csv("text/csv"),
  ;
  public final String type;
  private Mime(final String type) 
  {  
    this.type = type ;
  }
}
