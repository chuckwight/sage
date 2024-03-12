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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.chemvantage.Subject;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.googlecode.objectify.Key;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

@WebServlet("/sage")
public class Sage extends HttpServlet {

	private static final long serialVersionUID = 1L;
	private static List<Concept> conceptList = null;
	private static Map<Long,Concept> conceptMap = null;

	public void doGet(HttpServletRequest request,HttpServletResponse response)
			throws ServletException, IOException {
		
		PrintWriter out = response.getWriter();
		response.setContentType("text/html");
		
		try {
			HttpSession session = request.getSession(false);
			if (session == null) response.sendRedirect("/");
			String hashedId = (String)session.getAttribute("hashedId");
			
			Score s = getScore(hashedId);
			if (s.questionId == null) {  // user is starting a new Concept
				out.println(start(hashedId,s));
			} else {
				boolean help = Boolean.parseBoolean(request.getParameter("Help"));
				if (help && !s.gotHelp) {
					s.gotHelp = true;
					ofy().save().entity(s).now();
				}
				out.println(poseQuestion(hashedId,s,help));
			}
		} catch (Exception e) {
			out.println(Util.head + "Error: " + e.getMessage()==null?e.toString():e.getMessage() + Util.foot);
		}
	}
	
	public void doPost(HttpServletRequest request,HttpServletResponse response)
			throws ServletException, IOException {
		
		PrintWriter out = response.getWriter();
		response.setContentType("text/html");
	
		try {
			int rawScore = scoreQuestion(request);
			
		} catch (Exception e) {
			out.println(Util.head + "Error: " + e.getMessage()==null?e.toString():e.getMessage() + Util.foot);	
		}
	}
	
	static void refreshConcepts() {
		conceptList = ofy().load().type(Concept.class).order("orderBy").list();
		conceptMap = new HashMap<Long,Concept>();
		for (Concept c : conceptList) conceptMap.put(c.id, c);
	}
	
	static String start(String hashedId) throws Exception {
		return start(hashedId,getScore(hashedId));
	}
	
	static String start(String hashedId, Score s) throws Exception {
		StringBuffer buf = new StringBuffer(Util.head);
		try {
			if (conceptMap == null) refreshConcepts();
			Concept c = conceptMap.get(s.conceptId);
			
			if (s.questionId == null) {
				s.questionId = getquestionId(c,s);
				ofy().save().entity(s).now();
			}

			buf.append("<h1>Sage - Your AI-Powered Chemistry Tutor</h1>"
					+ "Sage will be your guide to learning more than 100 key concepts in General Chemistry.");

			buf.append("<h2>" + c.title + "</h2>"
					+ "<img src=/images/sage.png alt='Confucius Parrot' style='float:right;margin:20px;'>"
					+ c.summary + "<p>"
					+ "<a class=btn role=button href='/sage'>Continue</a>");

		} catch (Exception e) {
			buf.append("<p>Error: " + e.getMessage()==null?e.toString():e.getMessage());
		}
		return buf.toString() + Util.foot;
	}
	
	static String poseQuestion(String hashedId, Score s, boolean help) throws Exception {
		StringBuffer buf = new StringBuffer(Util.head);
		try {
			if (conceptMap == null) refreshConcepts();
			Concept c = conceptMap.get(s.conceptId);
			Question q = ofy().load().type(Question.class).id(s.questionId).now();
			
			buf.append("<h1>" + c.title + "</h1>");
			
			buf.append("<div style='width:800px; height:400px; display:flex; align-items:center;'>");
			if (help) {
				buf.append("<div>"
						+ getHelp(q)
						+ "</div>"
						+ "<img src=/images/sage.png alt='Confucius Parrot' style='float:right'>");
			} else {
				buf.append("<div>"
						+ "Please submit your answer to the question below.<p>"
						+ "If you get stuck, I am here to help you, but your score will be higher if you do it by yourself.<p>"
						+ "<a class=btn role=button href=/sage?Help=true>Please help me with this question</a>"
						+ "</div>"
						+ "<img src=/images/sage.png alt='Confucius Parrot' style='float:right'>");
			}
			buf.append("</div>");
			
			buf.append("<hr style='width:800px'>");  // break between Sage helper panel and question panel

			int p = new Random().nextInt();
			q.setParameters(p);

			// Print the question for the student
			buf.append("<form method=post>"
					+ "<input type=hidden name=QuestionId value='" + q.id + "' />"
					+ "<input type=hidden name=Parameter value='" + p + "' />"
					+ q.print()
					+ "<input type=submit class='btn' onclick='this.value=\'Please wait a moment while we score your answer\';this.disabled=true;' />"
					+ "</form><p>");
			
		} catch (Exception e) {
			buf.append("<p>Error: " + e.getMessage()==null?e.toString():e.getMessage());
		}
		return buf.toString() + Util.foot;	
	}
	
	static Score getScore(String hashedId) {
		if (conceptList == null) refreshConcepts();
		int nScores = ofy().load().type(Score.class).ancestor(key(User.class,hashedId)).count(); // inexpensive query
		
		if (nScores==0) return new Score(hashedId,conceptList.get(0).id);  // new user
		
		Key<Score> k = key(key(User.class,hashedId),Score.class,conceptList.get(nScores-1).id);
		Score s = ofy().load().key(k).safe();
		
		if (s.score < 100) return s;								// user is still working on a Concept
		else return new Score(hashedId,conceptList.get(nScores).id);	// user is just starting a new Concept
	}
	
	static Long getquestionId(Concept c, Score s) throws Exception {
		// We select the next question by calculating the user's scoreQuintile (1 - 5) and selecting
		// a question at random from those having the same degree of difficulty (1-5) so that more 
		// advanced users are offered more difficult questions as they progress through the Concept.
		
		Long currentQuestionId = s.questionId;  // don't duplicate this
		int scoreQuintile = s.score/20 + 1;
		int nConceptQuestions = ofy().load().type(Question.class).filter("conceptId",c.id).count();
		if (nConceptQuestions == 0) throw new Exception("Sorry, there are no questions for this Concept.");
		
		int nQuintileQuestions =  ofy().load().type(Question.class).filter("conceptId",c.id).filter("difficulty",scoreQuintile).count();
		
		// select one question index at random
		Key<Question> k = null;
		Random rand = new Random();
		if (nQuintileQuestions > 5) {
			k = ofy().load().type(Question.class).filter("conceptId",c.id).filter("difficulty",scoreQuintile).offset(rand.nextInt(nQuintileQuestions)).keys().first().safe();
		} else {  // use the full range of questions for this Concept
			k = ofy().load().type(Question.class).filter("conceptId",c.id).offset(rand.nextInt(nConceptQuestions)).keys().first().safe();	
		}
		
		// If this duplicates the current question, try again (recursively)
		if (k.getId() == currentQuestionId && nConceptQuestions > 1) return getquestionId(c,s);
		
		return k.getId();
	}
	
	static String getHelp(Question q) throws Exception {
		BufferedReader reader = null;
		JsonObject api_request = new JsonObject();  // these are used to score essay questions using ChatGPT
		api_request.addProperty("model","gpt-4");
		api_request.addProperty("max_tokens",200);
		api_request.addProperty("temperature",0.2);
		
		JsonArray messages = new JsonArray();
		JsonObject m1 = new JsonObject();  // api request message
		m1.addProperty("role", "system");
		m1.addProperty("content","You are a tutor assisting a college student taking General Chemistry. "
				+ "The student is requesting your help to answer a homework question. Guide the student "
				+ "in the right general direction, but do not give them the answer to the question.");
		messages.add(m1);;
		JsonObject m2 = new JsonObject();  // api request message
		m2 = new JsonObject();  // api request message
		m2.addProperty("role", "user");
		m2.addProperty("content","Please help me answer this General Chemistry problem: \n"
				+ q.printForSage());
		messages.add(m2);
		api_request.add("messages", messages);
		URL u = new URL("https://api.openai.com/v1/chat/completions");
		HttpURLConnection uc = (HttpURLConnection) u.openConnection();
		uc.setRequestMethod("POST");
		uc.setDoInput(true);
		uc.setDoOutput(true);
		uc.setRequestProperty("Authorization", "Bearer " + Util.getOpenAIKey());
		uc.setRequestProperty("Content-Type", "application/json");
		uc.setRequestProperty("Accept", "application/json");
		OutputStream os = uc.getOutputStream();
		byte[] json_bytes = api_request.toString().getBytes("utf-8");
		os.write(json_bytes, 0, json_bytes.length);           
		os.close();
			
		reader = new BufferedReader(new InputStreamReader(uc.getInputStream()));
		JsonObject api_response = JsonParser.parseReader(reader).getAsJsonObject();
		reader.close();
		
		String content = api_response.get("choices").getAsJsonArray().get(0).getAsJsonObject().get("message").getAsJsonObject().get("content").getAsString();
		return content;
	}
	
	static int scoreQuestion(HttpServletRequest request) throws Exception {
		int rawScore = 0;  // result is0, 1 or 2. Tne partial score 1 is for wrong sig figs.
		Long questionId = Long.parseLong(request.getParameter("QuestionId"));
		String studentAnswer = orderResponses(request.getParameterValues(Long.toString(questionId)));
		if (studentAnswer.isEmpty()) return 0;
		
		Question q = ofy().load().type(Question.class).id(questionId).safe();
		
		
		switch (q.getQuestionType()) {
		case 1:
		case 2:
		case 3:
		case 4:
		case 5:
			rawScore = q.isCorrect(studentAnswer)?2:0;
			break;
		case 6:  // Handle five-star rating response
			rawScore = 2;  // full marks for submitting a response
			break;
		case 7:  // New section for scoring essay questions with Chat GPT
			if (studentAnswer.length()>800) studentAnswer = studentAnswer.substring(0,799);
			JsonObject api_request = new JsonObject();  // these are used to score essay questions using ChatGPT
			api_request.addProperty("model","gpt-4");
			//api_request.addProperty("model","gpt-3.5-turbo");
			api_request.addProperty("max_tokens",200);
			api_request.addProperty("temperature",0.2);
			JsonObject m = new JsonObject();  // api request message
			m.addProperty("role", "user");
			String prompt = "Question: \"" + q.text +  "\"\n My response: \"" + studentAnswer + "\"\n "
					+ "Using JSON format, give a score for my response (integer in the range 0 to 5) "
					+ "and feedback for how to improve my response.";
			m.addProperty("content", prompt);
			JsonArray messages = new JsonArray();
			messages.add(m);
			api_request.add("messages", messages);
			URL u = new URL("https://api.openai.com/v1/chat/completions");
			HttpURLConnection uc = (HttpURLConnection) u.openConnection();
			uc.setRequestMethod("POST");
			uc.setDoInput(true);
			uc.setDoOutput(true);
			uc.setRequestProperty("Authorization", "Bearer " + Util.getOpenAIKey());
			uc.setRequestProperty("Content-Type", "application/json");
			uc.setRequestProperty("Accept", "application/json");
			OutputStream os = uc.getOutputStream();
			byte[] json_bytes = api_request.toString().getBytes("utf-8");
			os.write(json_bytes, 0, json_bytes.length);           
			os.close();
			
			BufferedReader reader = null;
			reader = new BufferedReader(new InputStreamReader(uc.getInputStream()));
			JsonObject api_response = JsonParser.parseReader(reader).getAsJsonObject();
			reader.close();
			
			// get the ChatGPT score from the response:
			try {
				String content = api_response.get("choices").getAsJsonArray().get(0).getAsJsonObject().get("message").getAsJsonObject().get("content").getAsString();
				JsonObject api_score = JsonParser.parseString(content).getAsJsonObject();
				rawScore = api_score.get("score").getAsInt(); 	// scale 1-5
				rawScore = rawScore/2;  						// scale 0-2
			} catch (Exception e) {}
			break;
		default:
		}
		
		return rawScore;
	}
	
	static String orderResponses(String[] answers) {
		if (answers==null) return "";
		Arrays.sort(answers);
		String studentAnswer = "";
		for (String a : answers) studentAnswer = studentAnswer + a;
		return studentAnswer;
	}


}
