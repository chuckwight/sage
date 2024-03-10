package org.chemvantage.sage;

import static com.googlecode.objectify.ObjectifyService.key;
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
import com.googlecode.objectify.Key;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@WebServlet("/sage")
public class Sage extends HttpServlet {

	private static final long serialVersionUID = 1L;
	private static List<Concept> concepts;

	public void doGet(HttpServletRequest request,HttpServletResponse response)
			throws ServletException, IOException {
		
		PrintWriter out = response.getWriter();
		response.setContentType("text/html");
		
		out.println("<h1>Hello World</h1>");
	}
	
	public void doPost(HttpServletRequest request,HttpServletResponse response)
			throws ServletException, IOException {
		
		PrintWriter out = response.getWriter();
		response.setContentType("text/html");
	
		out.println("<h1>Hello World</h1>");
	}
	
	static String start(String hashedId) {
		StringBuffer buf = new StringBuffer(Util.head);
		try {
		Concept c = getConcept(hashedId);
		ofy().save().entity(new Score(hashedId, c.id));  // saves a new Score with score=0
		
		buf.append("<h1>Sage - Your AI-Powered Chemistry Tutor</h1>"
				+ "Sage will be your guide to learning more than 100 key concepts in General Chemistry."
				+ "<h2>" + c.title + "</h2>");
		
		buf.append("<div style='width:800px; vertical-align:top'>\n"
				+ getConceptSummary(c)
				+ "<img src=/images/sage.png alt='Confucius Parrot' style='float:right'>"
				+ "</div>"
				+ "<a class=btn role=button href='/sage'>Continue</a>");
		} catch (Exception e) {
			buf.append("<p>Error: " + e.getMessage()==null?e.toString():e.getMessage());
		}
		return buf.toString() + Util.foot;
	}
	
	static Concept getConcept(String hashedId) throws Exception {
		if (concepts == null) concepts = ofy().load().type(Concept.class).order("orderBy").list();
		// Count the number of Scores for this user
		int n = ofy().load().type(Score.class).ancestor(key(User.class,hashedId)).count(); // inexpensive query
		/*
		 *  There are 2 possibilities:
		 *  1) The user is starting a new Concept -> get concept[n]
		 *  2) The user is working on a Concept -> get concept[n-1]
		 */
		if (n==0) return concepts.get(0);
		Key<Score> k = key(key(User.class,hashedId),Score.class,concepts.get(n-1).id);
		Score s = ofy().load().key(k).safe();
		if (s.score < 100) return concepts.get(n-1);  // user finished the previous Concept
		else return concepts.get(n);
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
