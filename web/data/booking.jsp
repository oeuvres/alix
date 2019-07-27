<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8"%>
<%@include file="common.jsp" %>
<%
// 
// get the current 
String corpus = getParameter(request, "corpus", "default");
Corpus bits = (Corpus)session.getAttribute(corpus);
if (bits == null) bits = new Corpus(alix, corpus);
String[] bookid = request.getParameterValues("bookid");
if (bookid != null)
String body = request.getReader().lines().collect(Collectors.joining(System.lineSeparator()));

%>
I