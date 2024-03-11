package org.chemvantage.sage;

import static com.googlecode.objectify.ObjectifyService.ofy;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@WebServlet("/concepts")
public class ConceptManager extends HttpServlet {

	private static final long serialVersionUID = 1L;
	private static List<Concept> concepts;

	public void doGet(HttpServletRequest request,HttpServletResponse response)
			throws ServletException, IOException {
		
		PrintWriter out = response.getWriter();
		response.setContentType("text/html");
		
		StringBuffer buf = new StringBuffer(Util.head);
		
		Concept c1 = null;
		try {
			Long conceptId = Long.parseLong(request.getParameter("ConceptId"));
			c1 = ofy().load().type(Concept.class).id(conceptId).safe();
		} catch (Exception e) {}
		
		buf.append("<table><tr><td></td><td>"
				+ (c1==null?"<h1>Add a new Concept</h1>":"<h1>Edit Concept</h1>")
				+ "</td></tr>");
		
		buf.append("<tr><td style='vertical-align:top;padding-right:20px;'>"
				+ (c1==null?"Edit Existing Concept:<p>":"<a href=/concepts>Add a new Concept</a><p>"));
		if (concepts == null) getConcepts();
		for (Concept c : concepts) {
			buf.append("<a href=/concepts?ConceptId=" + c.id + ">" + c.title + "</a><br/>");
		}
		
		buf.append("</td>");
		
		try { // Build a proposed AI summary, if needed
			if (c1 != null && (c1.summary == null || c1.summary.isEmpty())) c1.summary = getConceptSummary(c1);
		} catch (Exception e) {}  // never mind
		
		buf.append("<td>"
				+ "<form method=post>"
				+ "<table>"
				+ "<tr><td style='text-align:right;'>Title: </td><td><input size=25 name=title value='" + (c1==null?"":c1.title) + "' /></td><tr/>"
				+ "<tr><td style='text-align:right;'>OrderBy: </td><td><input size=10 name=orderBy value='" + (c1==null?"":c1.orderBy) + "' /></td></tr>"
				+ "<tr><td style='text-align:right;vertical-align:top;'>Summary: </td><td><textarea rows=40 cols=80 name=summary />"
				+ (c1==null?"":c1.summary)
				+ "</textarea></td></tr>"
				+ "</table><p>"
				+ (c1==null?"":"<input type=hidden name=id value='" + c1.id + "' />")
				+ "<input type=submit />"
				+ "</form></td>");

		buf.append("</tr></table>");
		out.println(buf.toString() + Util.foot);
	}
	
	public void doPost(HttpServletRequest request,HttpServletResponse response)
			throws ServletException, IOException {
		
		Concept c = null;
		try {
			Long conceptId = Long.parseLong(request.getParameter("id"));
			c = ofy().load().type(Concept.class).id(conceptId).safe();
			c.title = request.getParameter("title");
			c.orderBy = request.getParameter("orderBy");
			c.summary = request.getParameter("summary");
			ofy().save().entity(c).now();
		} catch (Exception e) {
			c = new Concept(request.getParameter("title"),request.getParameter("orderBy"),request.getParameter("summary"));
			ofy().save().entity(c).now();
			getConcepts();
		}
		
		response.sendRedirect("/concepts");
	}
	
	static void getConcepts() {
		concepts = ofy().load().type(Concept.class).order("orderBy").list();
	}
	
	static String getConceptSummary(Concept c) throws Exception {

		JsonObject api_request = new JsonObject();  // these are used to score essay questions using ChatGPT
		api_request.addProperty("model","gpt-4");
		//api_request.addProperty("model","gpt-3.5-turbo");
		api_request.addProperty("max_tokens",200);
		api_request.addProperty("temperature",0.2);

		JsonArray messages = new JsonArray();
		JsonObject m1 = new JsonObject();
		m1.addProperty("role", "system");
		m1.addProperty("content", "You are a tutor for a student in a General Chemistry class.");
		messages.add(m1);
		JsonObject m2 = new JsonObject();  // api request message
		m2.addProperty("role", "user");
		m2.addProperty("content", "Write a brief summary to teach me the key concept: " + c.title);
		messages.add(m2);

		api_request.add("messages", messages);
		URL u = new URL("https://api.openai.com/v1/chat/completions");
		HttpURLConnection uc = (HttpURLConnection) u.openConnection();
		uc.setRequestMethod("POST");
		uc.setDoInput(true);
		uc.setDoOutput(true);
		uc.setRequestProperty("Authorization", "Bearer " + Util.getOpenAIKey());
		uc.setRequestProperty("Content-Type", "application/json");
		uc.setRequestProperty("Accept", "text/html");
		OutputStream os = uc.getOutputStream();
		byte[] json_bytes = api_request.toString().getBytes("utf-8");
		os.write(json_bytes, 0, json_bytes.length);           
		os.close();

		BufferedReader reader = new BufferedReader(new InputStreamReader(uc.getInputStream()));
		JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
		reader.close();
		
		String content = json.get("choices").getAsJsonArray().get(0).getAsJsonObject().get("message").getAsJsonObject().get("content").getAsString();
		content.replaceAll("\\n\\n", "<p>");
		
		return content;
	}

}

