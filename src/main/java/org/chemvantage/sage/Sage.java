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
		
		HttpSession session = request.getSession(false);
		if (session == null) {
			response.sendRedirect("/");
			return;
		}
		String hashedId = (String)session.getAttribute("hashedId");
		
		try {
			Score s = getScore(hashedId);
			if (s.questionId == null) {  // user is starting a new Concept
				out.println(start(hashedId,s));
			} else {
				boolean help = Boolean.parseBoolean(request.getParameter("Help"));
				int parameter = 0;
				if (request.getParameter("p") != null) parameter = Integer.parseInt(request.getParameter("p"));
				if (help && !s.gotHelp) {
					s.gotHelp = true;
					ofy().save().entity(s).now();
				}
				out.println(poseQuestion(s,help,parameter));
			}
		} catch (Exception e) {
			response.sendRedirect("/");
			//out.println(Util.head + "Error: " + e.getMessage()==null?e.toString():e.getMessage() + Util.foot);
		}
	}
	
	public void doPost(HttpServletRequest request,HttpServletResponse response)
			throws ServletException, IOException {
		
		PrintWriter out = response.getWriter();
		response.setContentType("text/html");
	
		HttpSession session = request.getSession(false);
		if (session == null) {
			response.sendRedirect("/");
			return;
		}
		String hashedId = (String)session.getAttribute("hashedId");
		
		try {
			Score s = getScore(hashedId);
			JsonObject questionScore = scoreQuestion(request);
			if (questionScore == null) {
				doGet(request,response);
				return;
			}
			boolean level_up = s.update(questionScore);
			ofy().save().entity(s).now();
			out.println(printScore(questionScore,s,level_up));
		} catch (Exception e) {
			response.sendRedirect("/");
			//out.println(Util.head + "Error: " + e.getMessage()==null?e.toString():e.getMessage() + Util.foot);	
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
				s.questionId = getQuestionId(s);
				ofy().save().entity(s).now();
			}

			buf.append("<h1>Sage - Your AI-Powered Chemistry Tutor</h1>"
					+ "Sage will be your guide to learning more than 100 key concepts in General Chemistry.");

			buf.append("<h2>" + c.title + "</h2>"
					+ "<div style='max-width:800px'>"
					+ "<img src=/images/sage.png alt='Confucius Parrot' style='float:right;margin:20px;'>"
					+ c.summary==null?ConceptManager.getConceptSummary(c):c.summary + "<p>"
					+ "<a class=btn role=button href='/sage'>Continue</a>"
					+ "</div>");

		} catch (Exception e) {
			buf.append("<p>Error: " + e.getMessage()==null?e.toString():e.getMessage());
		}
		return buf.toString() + Util.foot;
	}
	
	static String poseQuestion(Score s, boolean help, int p) throws Exception {
		StringBuffer buf = new StringBuffer(Util.head);
		try {
			if (conceptMap == null) refreshConcepts();
			Concept c = conceptMap.get(s.conceptId);
			Question q = ofy().load().type(Question.class).id(s.questionId).now();
			if (p==0) p = new Random().nextInt();
			q.setParameters(p);

			buf.append("<h1>" + c.title + "</h1>");
			
			buf.append("<div style='width:800px; height=300px; overflow=auto; display:flex; align-items:center;'>");
			if (help) {
				buf.append("<div>"
						+ getHelp(q)
						+ "</div>"
						+ "<img src=/images/sage.png alt='Confucius Parrot' style='float:right'>");
			} else {
				buf.append("<div>"
						+ "Please submit your answer to the question below.<p>"
						+ "If you get stuck, I am here to help you, but your score will be higher if you do it by yourself.<p>"
						+ "<a id=help class=btn role=button href=/sage?Help=true&p=" + p + " onclick=waitForHelp()>Please help me with this question</a>"
						+ "</div>"
						+ "<img src=/images/sage.png alt='Confucius Parrot' style='float:right'>");
			}
			buf.append("</div>");
			
			// include some javascript to change the submit button
			buf.append("<script>"
					+ "function waitForHelp() {\n"
					+ " let a = document.getElementById('help');\n"
					+ " a.innerHTML = 'Please wait a moment for Sage to answer.';\n"
					+ "}\n"
					+ "</script>");
			
			buf.append("<hr style='width:800px;margin-left:0'>");  // break between Sage helper panel and question panel

			// Print the question for the student
			buf.append("<form method=post onsubmit='waitForScore();' >"
					+ "<input type=hidden name=QuestionId value='" + q.id + "' />"
					+ "<input type=hidden name=Parameter value='" + p + "' />"
					+ q.print()
					+ "<input id='sub" + q.id + "' type=submit class='btn' />"
					+ "</form><p>");
			
			// include some javascript to change the submit button
			buf.append("<script>"
					+ "function waitForScore() {\n"
					+ " let b = document.getElementById('sub" + q.id + "');\n"
					+ " b.disabled = true;\n"
					+ " b.value = 'Please wait a moment while we score your response.';\n"
					+ "}\n"
					+ "</script>");
		} catch (Exception e) {
			buf.append("<p>Error: " + e.getMessage()==null?e.toString():e.getMessage());
		}
		return buf.toString() + Util.foot;	
	}
	
	static Score getScore(String hashedId) {
		Score s = null;
		// Get all of the keys for existing Scores for this user (cheap query)
		List<Key<Score>> scoreKeys = ofy().load().type(Score.class).ancestor(key(User.class,hashedId)).keys().list();
		
		// Look for the next missing Score in the sequence (this forgives adding a Concept later)
		if (conceptList == null) refreshConcepts();
		Key<Score> k = null;
		for (Concept c : conceptList) {
			k = key(key(User.class,hashedId),Score.class,c.id);
			if (scoreKeys.contains(k)) continue;
			// found the first missing Score
			if (conceptList.indexOf(c)==0) { // new user 
				return new Score(hashedId,conceptList.get(0).id);  // return a new Score
			}
			// check the previous Score to see if it is complete
			k = key(key(User.class,hashedId),Score.class,conceptList.get(conceptList.indexOf(c)-1).id);
			s = ofy().load().key(k).safe();
			if (s.score < 100) return s;  // still working on the previous Concept
			else return new Score(hashedId,c.id);  // return a new Score
		}
		// at this point, all of the Concepts have been completed
		return null;
	}
	
	static Long getQuestionId(Score s) throws Exception {
		// We select the next question by calculating the user's scoreQuintile (1 - 5) and selecting
		// a question at random from those having the same degree of difficulty (1-5) so that more 
		// advanced users are offered more difficult questions as they progress through the Concept.
		
		Long currentQuestionId = s.questionId;  // don't duplicate this
		int scoreQuintile = s.score/20 + 1;
		int nConceptQuestions = ofy().load().type(Question.class).filter("conceptId",s.conceptId).count();
		if (nConceptQuestions == 0) throw new Exception("Sorry, there are no questions for this Concept.");
		
		int nQuintileQuestions =  ofy().load().type(Question.class).filter("conceptId",s.conceptId).filter("difficulty",scoreQuintile).count();
		
		// select one question index at random
		Key<Question> k = null;
		Random rand = new Random();
		if (nQuintileQuestions > 5) {
			k = ofy().load().type(Question.class).filter("conceptId",s.conceptId).filter("difficulty",scoreQuintile).offset(rand.nextInt(nQuintileQuestions)).keys().first().safe();
		} else {  // use the full range of questions for this Concept
			k = ofy().load().type(Question.class).filter("conceptId",s.conceptId).offset(rand.nextInt(nConceptQuestions)).keys().first().safe();	
		}
		
		// If this duplicates the current question, try again (recursively)
		if (k.getId() == currentQuestionId && nConceptQuestions > 1) return getQuestionId(s);
		
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
	
	static JsonObject scoreQuestion(HttpServletRequest request) throws Exception {
		/*
		 * This method scores the question and returns a JSON containing
		 *   rawScore - 0, 1 or 2 meaning incorrect, partially correct, correct
		 *   showMe - a more detailed response provided to the student
		 */
		JsonObject questionScore = new JsonObject();
		int rawScore = 0;  // result is0, 1 or 2. Tne partial score 1 is for wrong sig figs.
		StringBuffer details = new StringBuffer();  // includes correct solution or explanation of partial score
		
		Long questionId = Long.parseLong(request.getParameter("QuestionId"));
		String studentAnswer = orderResponses(request.getParameterValues(Long.toString(questionId)));
		if (studentAnswer.isEmpty()) return null;
		
		Question q = ofy().load().type(Question.class).id(questionId).safe();
		q.setParameters(Integer.parseInt(request.getParameter("Parameter")));
		
		// Get the raw score for the student's answer
		JsonObject api_score = null;
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
				api_score = JsonParser.parseString(content).getAsJsonObject();
				rawScore = api_score.get("score").getAsInt()/2; 	// scale 0-2
			} catch (Exception e) {}
			break;
		default:
		}
		
		// get the details for the student
		switch (rawScore) {
		case 2:  // correct answer
			details.append("<h1>Congratulations!</h1>"
					+ "<div style='width:800px;display:flex; align-items:center;'>"
					+ "<div>"
					+ "<b> Your answer is correct. </b><IMG SRC=/images/checkmark.gif ALT='Check mark' align=bottom /><p>"
					+ "<a id=showLink href=# onClick=document.getElementById('solution').style='display:inline';this.style='display:none';document.getElementById('polly').style='display:none';>(show me)</a>"
					+ "<div id=solution style='display:none'>");
			switch (q.getQuestionType()) {
			case 7: 
				details.append(api_score.get("feedback")+ "<p>");
				break;
			case 6:
				details.append("Thank you for your rating.");
				break;
			default:
				details.append(q.printAllToStudents(studentAnswer));
			}
			details.append("</div>"  // end of solution
					+ "</div>"    // end of left side
					+ "<img id=polly src='/images/parrot.png' alt='Fun parrot character' style='float:left; margin-left:50px'>"
					+ "</div>");
			break;
		case 1:  // partially correct answer
			details.append("<h1>Your answer is partially correct</h1>");
			switch (q.getQuestionType()) {
			case 5:  // numeric
				details.append("It appears that you've done the calculation correctly, but your answer "
						+ "does not have the correct number of significant figures appropriate for "
						+ "the data given in the question. If your answer ends in a zero, be sure "
						+ "to include a decimal point to indicate which digits are significant or "
						+ "(better!) use <a href=https://en.wikipedia.org/wiki/Scientific_notation#E_notation>"
						+ "scientific E notation</a>.<p>");
			case 7:  // short_essay
				details.append(api_score.get("feedback") + "<p>");
			default: // no other types currently offer partial credit
			}
			break;
		case 0: 
			details.append("<h1>Sorry, your answer is not correct.<IMG SRC=/images/xmark.png ALT='X mark' align=middle></h1>");
			break;
		}
		
		questionScore.addProperty("rawScore", rawScore);
		questionScore.addProperty("details", details.toString());
		
		return questionScore;
	}
	
	static String orderResponses(String[] answers) {
		if (answers==null) return "";
		Arrays.sort(answers);
		String studentAnswer = "";
		for (String a : answers) studentAnswer = studentAnswer + a;
		return studentAnswer;
	}

	static String printScore(JsonObject questionScore, Score s, boolean level_up) throws Exception {
		StringBuffer buf = new StringBuffer(Util.head);
		
		buf.append(questionScore.get("details").getAsString());
		if (s.score == 100) {
			buf.append("<h2>Congratulations! Your score is 100%. You have mastered this concept.</h2>");
		} else if (level_up) {
			buf.append("<h3>You have moved up to Level " + (s.score/20 + 1) +".</h3>"
					+ "<b>Your current score on this concept is " + s.score + "%.</b>");
		} else {
			buf.append("<b>Your current score on this concept is " + s.score + "%.</b>");
		}
		// print a button to continue
		buf.append("<p><a class=btn role=button href='/sage'>Continue</a><p>");
		return buf.toString() + Util.foot;
	}
}
