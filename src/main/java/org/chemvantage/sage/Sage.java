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
import java.util.ArrayList;
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
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@WebServlet("/sage")
public class Sage extends HttpServlet {

	private static final long serialVersionUID = 1L;
	private static List<Concept> conceptList = null;
	private static Map<Long,Concept> conceptMap = null;

	public void doGet(HttpServletRequest request,HttpServletResponse response)
			throws ServletException, IOException {
		
		PrintWriter out = response.getWriter();
		response.setContentType("text/html");
		
		String hashedId = getFromCookie(request, response);
		if (hashedId == null) response.sendRedirect("/");
		
		try {
			Score s = getScore(hashedId);
			if (s.questionId == null) {  // user is starting a new Concept
				out.println(start(hashedId));
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
			out.println(Launch.errorPage(e));
		}
	}
	
	public void doPost(HttpServletRequest request,HttpServletResponse response)
			throws ServletException, IOException {
		
		PrintWriter out = response.getWriter();
		response.setContentType("text/html");

		String hashedId = getFromCookie(request, response);
		if (hashedId == null) response.sendRedirect("/");

		String userRequest = request.getParameter("UserRequest");
		if (userRequest == null) userRequest = "";

		Score s = getScore(hashedId);
		
		switch (userRequest) {
		case "Ask Sage":
			try {
				String topic = request.getParameter("Topic");
				String userPrompt = request.getParameter("UserPrompt");
				String nonce = request.getParameter("Nonce");
				if (!Nonce.isUnique(nonce)) throw new Exception("replay attempt");
				out.println(askSage(topic,s,userPrompt));
				return;
			} catch (Exception e) {
				out.println(Util.head
						+ "<h1>Sorry, Sage only answers one question at a time.</h1>"
						+ "Your session will continue in a moment."
						+ "<script>"
						+ " setTimeout(() => { window.location.replace('/sage'); }, 2000);"  // pause, then continue
						+ "</script>"
						+ Util.foot);
			}
			break;
		default:
			try {
				JsonObject questionScore = scoreQuestion(s,request);
				if (questionScore == null) {
					doGet(request,response);
					return;
				}
				boolean level_up = s.update(questionScore);
				ofy().save().entity(s).now();
				out.println(printScore(questionScore,s,level_up));
			} catch (Exception e) {
				response.sendRedirect("/sage");
			}
		}
	}

	static String askAQuestion(String topic, String nonce) {
		StringBuffer buf = new StringBuffer();
		buf.append("<button id=askButton class=btn onClick=showAskForm(); >Ask Sage a Question</button>");
		buf.append("<div id=askForm style='display:none;' >"
				+ "If you have any question for Sage about <b>" + topic + "</b> you may ask it here:<br/>"
				+ "<form method=post action=/sage onsubmit='waitForScore();'>"
				+ "<input type=hidden name=Topic value='" + topic + "' />"
				+ "<input type=hidden name=Nonce value='" + nonce + "' />"
				+ "<input type=hidden name=UserRequest value='Ask Sage' />"
				+ "<textarea rows=4 cols=80 name=UserPrompt ></textarea><br/>"
				+ "<input id=ask type=submit class=btn value='Ask Sage' />"
				+ "</form><p>"
				+ "</div>\n");
		
		buf.append("<script>"
				+ "function showAskForm() {"
				+ " document.getElementById('askButton').style='display:none;';"
				+ " document.getElementById('askForm').style='display:inline;';"
				+ "}"
				+ "function waitForScore() {\n"
				+ " let b = document.getElementById('ask');\n"
				+ " b.disabled = true;\n"
				+ " b.value = 'Please wait a moment for Sage to answer.';\n"
				+ "}\n"
				+ "</script>"); 
				
		return buf.toString();
	}

	static String askSage(String topic, Score s, String userPrompt) {
		StringBuffer buf = new StringBuffer(Util.head);
		try {
			BufferedReader reader = null;
			JsonObject api_request = new JsonObject();  // these are used to score essay questions using ChatGPT
			api_request.addProperty("model","gpt-4");
			api_request.addProperty("max_tokens",400);
			api_request.addProperty("temperature",0.4);
			
			JsonArray messages = new JsonArray();
			JsonObject m1 = new JsonObject();  // api request message
			m1.addProperty("role", "system");
			m1.addProperty("content","You are a tutor assisting a college student taking General Chemistry. "
					+ "The student has successfully completed " + s.score + "% of the exercises on the topic of " + topic + " "
					+ "and has a question for you. You must restrict your answer to this topic in General Chemistry.");
			messages.add(m1);;
			JsonObject m2 = new JsonObject();  // api request message
			m2 = new JsonObject();  // api request message
			m2.addProperty("role", "user");
			m2.addProperty("content",userPrompt);
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
			
			buf.append("<h1>Sage Response</h1>");
			buf.append("<div style='width:800px;' >");
			buf.append("<img src=/images/sage.png alt='Confucius Parrot' style='margin-left:20px;float:right;' />" + content);	
			buf.append("</div>");
			buf.append("<div id=helpful>"
					+ "<span><b>Was this answer helpful?</b></span> " 
					+ "<a href=#  style='vertical-align:middle' onclick=wasHelpful(true);><img src=/images/thumbs_up.png alt='thumbs up' style='height:30px' /></a>&nbsp;"
					+ "<a href=#  style='vertical-align:middle' onclick=wasHelpful(false);><img src=/images/thumbs_down.png alt='thumbs down' style='height:30px' /></a>"
					+ "</div><p>");
			// include some javascript to process the response
			buf.append("<script>"
					+ "function wasHelpful(response) {"
					+ " document.getElementById('helpful').innerHTML='<br/><b>Thank you for the feedback.</b>';"
					+ " setTimeout(() => { window.location.replace('/sage'); }, 1000);"  // pause, then continue
					+ " try {"
					+ "  var xmlhttp = new XMLHttpRequest();"
					+ "  xmlhttp.open('GET','/feedback?UserRequest=HelpfulAnswer&Response=' + response,true);"
					+ "  xmlhttp.send(null);"
					+ " } catch (error) {}"
					+ "}"
					+ "</script>");
			//buf.append("<p><a class=btn role=button href='/sage'>Continue</a><p>");
			
		} catch (Exception e) {
			buf.append("<p>Error: " + (e.getMessage()==null?e.toString():e.getMessage()) + "<p>");
		}
		return buf.toString() + Util.foot;
	}
	
	static boolean finishedChapter(Score s) {
		// Identify the current chapter that might be completed
		Long chapterId = conceptMap.get(s.conceptId).chapterId;
		// identify all of the concepts assaociated with the current chapter
		List<Long> chapterConceptIds = new ArrayList<Long>();
		for (Concept c : conceptList) if (c.chapterId != null && c.chapterId.equals(chapterId)) chapterConceptIds.add(c.id);
		if (chapterConceptIds.isEmpty()) return false;
		// gather all of this user's Score entities for the chapterConcepts
		Map<Long,Score> chapterScores = ofy().load().type(Score.class).parent(s.owner).ids(chapterConceptIds);
		if (chapterScores.size() < chapterConceptIds.size()) return false; // more concepts to go
		// check to make sure all Scores are at 100%
		for (Map.Entry<Long,Score> entry : chapterScores.entrySet()) {
			if (entry.getValue().score < 100) return false;
		}
		// all checks passed, so the chapter is finished
		return true;
	}
	
	static String getFromCookie(HttpServletRequest request, HttpServletResponse response) {
		try {
			Cookie[] cookies = request.getCookies();
			for (Cookie cookie : cookies) {
				if (cookie.getName().equals("hashedId")) {
					User u = ofy().load().type(User.class).id(cookie.getValue()).safe();  // throws Exception if user does not exist
					cookie.setMaxAge(3600);
					response.addCookie(cookie);
					return u.hashedId;
				}
			}
			return null;
		} catch (Exception e) {}
		return null;
	}
	
	static String getHelp(Question q) throws Exception {
		if (q.sageAdvice != null) return q.sageAdvice; // stored AI response
		
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
				+ "in the right general direction, but do not give the correct answer to the question.");
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
		
		// Save the response for the next time a user needs help with this question
		q.sageAdvice = content;
		ofy().save().entity(q);
		
		return content;
	}

	static Long getNewQuestionId(Score s) throws Exception {
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
		if (nQuintileQuestions >= 5) {
			k = ofy().load().type(Question.class).filter("conceptId",s.conceptId).filter("difficulty",scoreQuintile).offset(rand.nextInt(nQuintileQuestions)).keys().first().safe();
		} else {  // use the full range of questions for this Concept
			k = ofy().load().type(Question.class).filter("conceptId",s.conceptId).offset(rand.nextInt(nConceptQuestions)).keys().first().safe();	
		}
		
		// If this duplicates the current question, try again (recursively)
		if (k.getId().equals(currentQuestionId) && nConceptQuestions > 1) return getNewQuestionId(s);
		
		return k.getId();
	}

	static Score getScore(User user) {
		Key<User> k = key(user);
		try {
			if (user.conceptId==null) user.conceptId = ofy().load().type(Concept.class).order("orderBy").first().now().id;
			return ofy().load().type(Score.class).parent(k).id(user.conceptId).safe();
		} catch (Exception e) {
			Score s = new Score(user.hashedId,user.conceptId);
			ofy().save().entity(s).now();
			return s;
		}
	}
	
	static Score getScore(String hashedId) {
		User user = ofy().load().type(User.class).id(hashedId).now();
		return getScore(user);
	}

	static String orderResponses(String[] answers) {
		if (answers==null) return "";
		Arrays.sort(answers);
		String studentAnswer = "";
		for (String a : answers) studentAnswer = studentAnswer + a;
		return studentAnswer;
	}

	static String poseQuestion(Score s, boolean help, int p) throws Exception {
		StringBuffer buf = new StringBuffer(Util.head);
		try {
			if (conceptMap == null) refreshConcepts();
			Concept c = conceptMap.get(s.conceptId);
			Question q = ofy().load().type(Question.class).id(s.questionId).now();
			if (q==null) {
				s.questionId = getNewQuestionId(s);
				ofy().save().entity(s);
				q = ofy().load().type(Question.class).id(s.questionId).now();
			}
			if (p==0) p = new Random().nextInt();
			q.setParameters(p);
	
			buf.append("<h1>" + c.title + "</h1>");
			
			buf.append("<div style='width:800px; height=300px; overflow=auto; display:flex; align-items:center;'>");
			if (help) {
				buf.append("<div>"
						+ getHelp(q)
						+ "</div>"
						+ "<img src=/images/sage.png alt='Confucius Parrot' style='float:right'>"
						+ "</div>");
				
				buf.append("<div id=helpful>"
						+ "<span><b>Is this helpful?</b></span> " 
						+ "<a href=#  style='vertical-align:middle' onclick=wasHelpful(true);><img src=/images/thumbs_up.png alt='thumbs up' style='height:30px' /></a>&nbsp;"
						+ "<a href=#  style='vertical-align:middle' onclick=wasHelpful(false);><img src=/images/thumbs_down.png alt='thumbs down' style='height:30px' /></a>"
						+ "</div><p>");
				// include some javascript to process the response
				buf.append("<script>"
						+ "function wasHelpful(response) {"
						+ " document.getElementById('helpful').innerHTML='<br/>Thank you for the feedback. ' "
						+ "  + (response?'I&apos;m always happy to help.':'I&apos;ll try to do better next time.');"
						+ " try {"
						+ "  var xmlhttp = new XMLHttpRequest();"
						+ "  xmlhttp.open('GET','/feedback?UserRequest=HelpfulHint&QuestionId=" + q.id + "&Response=' + response,true);"
						+ "  xmlhttp.send(null);"
						+ " } catch (error) { console.error(error); }"
						+ "}"
						+ "</script>");
			} else {
				buf.append("<div>"
						+ "Please submit your answer to the question below.<p>"
						+ "If you get stuck, I am here to help you, but your score will be higher if you do it by yourself.<p>"
						+ "<a id=help class=btn role=button href=/sage?Help=true&p=" + p + " onclick=waitForHelp(); >Please help me with this question</a>"
						+ "</div>"
						+ "<img src=/images/sage.png alt='Confucius Parrot' style='float:right'>"
						+ "</div>");
				// include some javascript to change the submit button
				buf.append("<script>"
						+ "function waitForHelp() {\n"
						+ " let a = document.getElementById('help');\n"
						+ " a.innerHTML = 'Please wait a moment for Sage to answer.';\n"
						+ "}\n"
						+ "</script>");
				}
			
			buf.append("<hr style='width:800px;margin-left:0'>");  // break between Sage helper panel and question panel
	
			// Print the question for the student
			buf.append("<form method=post style='max-width:800px;' onsubmit='waitForScore();' >"
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

	static String printScore(JsonObject questionScore, Score s, boolean level_up) throws Exception {
		// Prepare a section that allows the user to ask Sage a question
		if (conceptList==null) refreshConcepts();
		User user = ofy().load().key(s.owner).now();
		String topic = conceptList.get(conceptList.indexOf(conceptMap.get(user.conceptId))).title;
		
		StringBuffer buf = new StringBuffer(Util.head);
		try {
		buf.append(questionScore.get("details").getAsString());
		if (s.score == 100) {  // move the user to the next chapter or concept
			buf.append("<h2>Your score is 100%</h2>");
			if (finishedChapter(s)) {
				Chapter ch = ofy().load().type(Chapter.class).id(conceptMap.get(s.conceptId).chapterId).now();
				buf.append("<b>You have completed Chapter " + ch.chapterNumber + ". " + ch.title + "</b>");
				buf.append("<ul>");
				for (Concept c : conceptList) if (c.chapterId != null && c.chapterId.equals(ch.id)) buf.append("<li>" + c.title + "</li>");;
				buf.append("</ul>");
				Chapter nextChapter = ofy().load().type(Chapter.class).filter("chapterNumber",ch.chapterNumber+1).first().now();
				if (nextChapter == null) buf.append("<h1>Congratulations, you finished!</h1>");
				else {
					buf.append(askAQuestion(topic,Nonce.getHexString()));
					buf.append("<br/>The next chapter is: <b>" + nextChapter.title + "</b>.<br/>");
				}
			} else {  // concept was completed
				buf.append("You have mastered the concept: <b>" + topic +"</b></br/>");
				// Calculate the number of concepts completed for this chapter
				List<Long> chapterConceptIds = new ArrayList<Long>();
				for (Concept c : conceptList) 
					if (c.chapterId != null && c.chapterId.equals(conceptMap.get(s.conceptId).chapterId)) 
						chapterConceptIds.add(c.id);
				int nChapterScores = ofy().load().type(Score.class).parent(s.owner).ids(chapterConceptIds).size();
				buf.append("You have completed " + nChapterScores + " out of " + chapterConceptIds.size() + " concepts for this chapter.<p>");
				buf.append(askAQuestion(topic,Nonce.getHexString()));
			}
			// Retrieve the next concept in the list and update the user
			int conceptIndex = conceptList.indexOf(conceptMap.get(user.conceptId));
			while (ofy().load().type(Score.class).parent(s.owner).id(conceptList.get(conceptIndex+1).id).now()!=null) conceptIndex++;
			user.conceptId = conceptList.get(conceptIndex+1).id;
			ofy().save().entity(user).now();
			buf.append("The next concept is: <b>" + conceptList.get(conceptIndex+1).title + "</b>&nbsp;");
		} else if (level_up) {
			buf.append("<h3>You have moved up to Level " + (s.score/20 + 1) +".</h3>"
					+ "<b>Your current score on this concept is " + s.score + "%.</b>&nbsp;");
			if (s.score >= 60 && s.score < 80) buf.append("<p>" + askAQuestion(topic,Nonce.getHexString()) + "Otherwise...");
		} else {
			buf.append("<p><b>Your current score on this concept is " + s.score + "%.</b>&nbsp;");
		}
		// print a button to continue
		buf.append("<a class=btn role=button href='/sage'>Continue</a><p>");
		} catch (Exception e) {
			buf.append("<p>" + e.getMessage()==null?e.toString():e.getMessage());
		}
		return buf.toString() + Util.foot;
		
	}

	static String printSolution(Question q, String studentAnswer,JsonObject api_score) throws Exception {
		StringBuffer buf = new StringBuffer();
		buf.append("<div id=solution style='display:none'>");
		switch (q.getQuestionType()) {
		case 6:  // five-star rating
			buf.append("Your rating has been recorded, thank you.");
		case 7:  // short_essay
			buf.append(api_score.get("feedback") + "<p>");
			break;
		default: // just print the solution
			buf.append(q.printAllToStudents(studentAnswer));
		}	
		buf.append("</div>");			
		buf.append("<script src='/js/report_problem.js'></script>");
		return buf.toString();
	}

	static void refreshConcepts() {
		conceptList = ofy().load().type(Concept.class).order("orderBy").list();
		conceptMap = new HashMap<Long,Concept>();
		for (Concept c : conceptList) conceptMap.put(c.id, c);
	}
	
	static JsonObject scoreQuestion(Score s, HttpServletRequest request) throws Exception {
		/*
		 * This method scores the question and returns a JSON containing
		 *   rawScore - 0, 1 or 2 meaning incorrect, partially correct, correct
		 *   showMe - a more detailed response provided to the student
		 */
		JsonObject questionScore = new JsonObject();
		int rawScore = 0;  // result is 0, 1 or 2. Tne partial score 1 is for wrong sig figs.
		StringBuffer details = new StringBuffer();  // includes correct solution or explanation of partial score
		
		Long questionId = Long.parseLong(request.getParameter("QuestionId"));
		if (!questionId.equals(s.questionId)) throw new Exception("Wrong question is being scored.");
		
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
			rawScore = q.isCorrect(studentAnswer)?2:q.agreesToRequiredPrecision(studentAnswer)?1:0;
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
		
		// Create a header section for results
		int questionType = q.getQuestionType();
		String showMeLink = "<a href=# "
				+ " onClick=\""
				+ "   document.getElementById('solution').style='display:inline';"
				+ "   document.getElementById('result').style='display:none';"
				+ "   document.getElementById('polly').style='display:none';\">"
				+ "Show me the solution"
				+ "</a>";
		
		if (questionType==6) details.append("<h1>Thank you for your rating.</h1>");
		else {
			// get the details for the student
			switch (rawScore) {
			case 2:  // correct answer
	
				details.append("<h1>Congratulations!</h1>"
						+ "<div style='width:800px;display:flex;align-items:center;'>"
						+ " <div id=result>"
						+ "  <b>Your answer is correct. </b><IMG SRC=/images/checkmark.gif ALT='Check mark' align=bottom /><p>"
						+ showMeLink
						+ " </div>"
						+ printSolution(q,studentAnswer,api_score)
						+ "<img id=polly src='/images/parrot2.png' alt='Parrot character' style='margin-left:20px;'>"
						+ "</div>");
				break;
			case 1: // partial credit			
				details.append("<h1>Your answer is partially correct</h1>"
						+ "<div style='width:800px;display:flex; align-items:center;'>"
						+ " <div id=result>"
						+ "  <b>You received half credit.</b><p>"
						+ showMeLink
						+ " </div>"
						+ printSolution(q,studentAnswer,api_score)
						+ "<img id=polly src='/images/parrot1.png' alt='Parrot character' style='margin-left:20px;'>"
						+ "</div>");
				break;
			case 0: // wrong answer
				details.append("<h1>Sorry, your answer is not correct.<IMG SRC=/images/xmark.png ALT='X mark' align=middle></h1>"
						+ "<div style='width:800px;display:flex; align-items:center;'>"
						+ " <div id=result><b>Don't give up!</b><br/>\n"
						+ "  If you feel frustrated, take a break. You can read more about this concept in a "
						+ "  <a href=https://openstax.org/details/books/chemistry-2e target=_openstax>free online chemistry textbook</a> "
						+ "  published by OpenStax. When you're refreshed, you can come back and continue your progress here.<p>"
						+ showMeLink
						+ " </div>"
						+ printSolution(q,studentAnswer,api_score)
						+ "<img id=polly src='/images/parrot0.png' alt='Parrot character' style='margin-left:20px;'>"
						+ "</div>");
				break;
			}
		}
		questionScore.addProperty("rawScore", rawScore);
		questionScore.addProperty("details", details.toString());
	
		return questionScore;
	}

	static String start(String hashedId) throws Exception {
		User user = ofy().load().type(User.class).id(hashedId).now();
		return start(user);
	}
	
	static String start(User user) throws Exception {
		StringBuffer buf = new StringBuffer(Util.head);
		try {
			Score s = getScore(user);
			user.updateConceptId(s.conceptId);
			
			if (conceptMap == null) refreshConcepts();
			Concept c = conceptMap.get(s.conceptId);
			
			if (s.questionId == null) {
				s.questionId = getNewQuestionId(s);
				ofy().save().entity(s).now();
			}

			buf.append("<h1>" + c.title + "</h1>"
					+ "<div style='max-width:800px'>"
					+ "<img src=/images/sage.png alt='Confucius Parrot' style='float:right;margin:20px;'>"
					+ (c.summary==null?ConceptManager.getConceptSummary(c):c.summary) + "<p>"
					+ "<a class=btn role=button href='/sage'>Continue</a>"
					+ "</div>");

		} catch (Exception e) {
			buf.append("<p>Error: " + e.getMessage()==null?e.toString():e.getMessage());
		}
		return buf.toString() + Util.foot;
	}
}
