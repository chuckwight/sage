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
		
		if (!Util.projectId.equals("sage-416602")) {
			out.println("Bad project configuration.");
			return;
		}
		
		out.println(viewConcepts(request));
	}
	
	public void doPost(HttpServletRequest request,HttpServletResponse response)
			throws ServletException, IOException {

		String userRequest = request.getParameter("UserRequest");
		if (userRequest == null) userRequest = "";
		
		Concept c = null;
		
		try {
			switch (userRequest) {
			case "Combine":
				combineConcepts(request);
				break;
			case "ReOrder":
				reOrderConcepts(request);
				break;
			case "Save":
				try {
					Long conceptId = Long.parseLong(request.getParameter("id"));
					c = ofy().load().type(Concept.class).id(conceptId).safe();
					c.title = request.getParameter("title").replaceAll("\'", "&#39;");
					c.orderBy = request.getParameter("orderBy");
					c.summary = request.getParameter("summary");
					ofy().save().entity(c).now();
				} catch (Exception e) {
					c = new Concept(request.getParameter("title"),request.getParameter("orderBy"),request.getParameter("summary"));
					ofy().save().entity(c).now();
				}
				break;
			}
		} catch (Exception e) {}
		response.sendRedirect("/concepts" + (c==null?"":"?ConceptId=" + c.id));
	}
	
	static void combineConcepts(HttpServletRequest request) throws Exception {
		Long fromConceptId = null;
		Long toConceptId = null;
		try {
			fromConceptId = Long.parseLong(request.getParameter("FromConceptId"));
			toConceptId = Long.parseLong(request.getParameter("ToConceptId"));
		} catch (Exception e) {
			return;
		}
		List<Question> questions = ofy().load().type(Question.class).filter("conceptId",fromConceptId).list();
		for (Question q:questions) {
			q.conceptId = toConceptId;
		}
		ofy().save().entities(questions).now();
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

	static void reOrderConcepts(HttpServletRequest request) {
		getConcepts();
		
		for (Concept c : concepts) {
			c.orderBy = request.getParameter(String.valueOf(c.id));
		}
		ofy().save().entities(concepts).now();
	}
	
	static String viewConcepts(HttpServletRequest request) {
		StringBuffer buf = new StringBuffer(Util.head);
		
		Concept c1 = null;
		try {
			Long conceptId = Long.parseLong(request.getParameter("ConceptId"));
			c1 = ofy().load().type(Concept.class).id(conceptId).safe();
		} catch (Exception e) {}
		
		buf.append("<table><tr><td colspan=2>"
				+ (c1==null?"<h1>Add a new Concept</h1>":"<h1>Edit Concept</h1>")
				+ "</td></tr>");
		
		buf.append("<tr><td style='vertical-align:top;padding-right:20px;'>"
				+ (c1==null?"Edit Existing Concept:<p>":"<a href=/concepts>Add a new Concept</a>"));
		//if (concepts == null) getConcepts();
		getConcepts();
		
		buf.append("<form method=post>"
				+ "<input type=submit name=UserRequest value=Combine />"	
				+ "<input type=submit name=UserRequest value=ReOrder />");		
		buf.append("<table><tr><th>Fr</th><th>To</th><th>Order</th><th>Title</th></tr>");
		for (Concept c : concepts) {
			buf.append("<tr>"
					+ "<td><input type=radio name=FromConceptId value='" + c.id + "' /></td>"
					+ "<td><input type=radio name=ToConceptId value='" + c.id + "' /></td>"
					+ "<td><input type=text size=5 name=" + c.id + " value='" + c.orderBy + "' /></td>"
					+ "<td><a href=/concepts?ConceptId=" + c.id + ">" + c.title + "</a></td>"
					+ "</tr>");
		}
		buf.append("</table></form>");
		
		buf.append("</td>");
		
		boolean fromAI = false;
		try { // Build a proposed AI summary, if needed
			if (c1 != null && (c1.summary == null || c1.summary.isEmpty())) {
				c1.summary = getConceptSummary(c1);
				fromAI = true;
			}
		} catch (Exception e) {}  // never mind
		
		
		buf.append("<td style='vertical-align:top;'>");  // start right side of page
		
		if (c1 != null) {  // list the numbers of questions for this Concept
			try {
				int[] nQuestions = new int[6];
				nQuestions[0] = ofy().load().type(Question.class).filter("conceptId",c1.id).count();  // total
				buf.append("Questions (Levels 1-5): ");
				for (int i=1;i<6;i++) {  // for each level of difficulty
					nQuestions[i] = ofy().load().type(Question.class).filter("conceptId",c1.id).filter("difficulty",i).count();
					buf.append(nQuestions[i] + " | ");
					nQuestions[0] -= nQuestions[i];
				}
				buf.append("Unclassified: " + nQuestions[0] + "<p>");
			} catch (Exception e) {}
		}

		buf.append("<form method=post>"  // build a Concept editing form
				+ "<table>"
				+ "<tr><td style='text-align:right;'>Title: </td><td><input size=25 name=title value='" + (c1==null?"":c1.title) + "' /></td><tr/>"
				+ "<tr><td style='text-align:right;'>OrderBy: </td><td><input size=10 name=orderBy value='" + (c1==null?"":c1.orderBy) + "' /></td></tr>"
				+ "<tr><td style='text-align:right;vertical-align:top;'>Summary: <br/><input type=submit name=UserRequest value=Save /></td>"
				+ "<td>" + (fromAI?"(generated by AI)<br/>":"")
				+ "<textarea rows=40 cols=50 name=summary />"
				+ (c1==null?"":c1.summary)
				+ "</textarea></td></tr>"
				+ "</table><p>"
				+ (c1==null?"":"<input type=hidden name=id value='" + c1.id + "' />")
				+ "</form><p>");

		if (c1 != null) {  // print a preview of the summary as it will appear in Sage.start()
			buf.append("<h2>Preview</h2>"
					+ "<div style='max-width:800px'>"
					+ "<img src=/images/sage.png alt='Confucius Parrot' style='float:right;margin:20px;'>\n"
					+ c1.summary + "<p>"
					+ "</div>");
		}

		buf.append("</td></tr></table>");
		
		return buf.toString() + Util.foot;
	}
}

