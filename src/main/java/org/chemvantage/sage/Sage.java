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
		
		StringBuffer debug = new StringBuffer("Debug: ");
		try {
			User user = getFromCookie(request, response);
			if (user == null) {
				response.sendRedirect("/");
				return;
			}
			
			// set the user's desired or default conceptId
			if (conceptMap == null) refreshConcepts();
			try {  // request from menuPage
				Long conceptId = Long.parseLong(request.getParameter("ConceptId"));
				if (!conceptId.equals(user.conceptId)) {
					if (conceptMap.get(conceptId) == null) throw new Exception(); // bad conceptId
					user.conceptId = conceptId;
					ofy().save().entity(user).now();
				}
			} catch (Exception e) { // no conceptId was requested
				if (user.conceptId == null) {  // set to default conceptId
					user.conceptId = conceptList.get(0).id;
				}
				// otherwise, no change to conceptId is required for this request
			}
			debug.append("2");
			
			Score s = getScore(user);
			
			String userRequest = request.getParameter("UserRequest");
			if (userRequest==null) userRequest = "";
			
			switch (userRequest) {
			case "menu":
				debug.append("a");
				out.println(menuPage(user,s));
				break;
			default:	
				debug.append("b");
				if (s.questionId == null) {  // user is starting a new Concept
					debug.append("c");
					out.println(start(user,s));
				} else {
					debug.append("d");
					boolean help = Boolean.parseBoolean(request.getParameter("Help"));
					long p = 0;
					if (request.getParameter("p") != null) p = Long.parseLong(request.getParameter("p"));
					if (help && !s.gotHelp) {
						s.gotHelp = true;
						ofy().save().entity(s).now();
					}
					debug.append("e");
					out.println(poseQuestion(s,help,p));
				}
			}
		} catch (Exception e) {
			out.println(Launch.errorPage(e) + "<br/>" + debug.toString());
		}
	}
	
	public void doPost(HttpServletRequest request,HttpServletResponse response)
			throws ServletException, IOException {
		
		PrintWriter out = response.getWriter();
		response.setContentType("text/html");

		User user = getFromCookie(request, response);
		if (user == null) {
			response.sendRedirect("/");
			return;
		}
		
		StringBuffer debug = new StringBuffer("Debug: ");
		
		try {
			
		String userRequest = request.getParameter("UserRequest");
		if (userRequest == null) userRequest = "";
		debug.append("1");
		
		Score s = getScore(user);
		debug.append("2");
		
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
		case "Score This Response":  
			try {			
				out.println(printScore(request,s));
			} catch (Exception e) {
				out.println(e.getMessage()==null?e.toString():e.getMessage());
			}
			break;
		case "Show Full Solution":
			try {	
				debug.append("3");
				out.println(printSolution(request,s));
			} catch (Exception e) {
				out.println(e.getMessage()==null?e.toString():e.getMessage());
			}
			break;
		default: response.sendError(400);
		}
		} catch (Exception e) {
			out.println(e.getMessage()==null?e.toString():e.getMessage() + "\n" + debug.toString());
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
			api_request.addProperty("model",Util.getGPTModel());
			api_request.addProperty("max_tokens",400);
			api_request.addProperty("temperature",0.4);
			
			JsonArray messages = new JsonArray();
			JsonObject m1 = new JsonObject();  // api request message
			m1.addProperty("role", "system");
			m1.addProperty("content","You are a tutor assisting a college student taking General Chemistry. "
					+ "You must restrict your response to the topic " + topic + " in General Chemistry."
					+ "Format the response in HTML and use LaTex math mode specific delimiters as follows:\n"
					+ "inline math mode : `\\(` and `\\)`\n"
					+ "display math mode: `\\[` and `\\]`\n");
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
					+ " setTimeout(() => { window.location.replace('/sage" + (s.score==100?"?UserRequest=menu":"") + "'); }, 1000);"  // pause, then continue
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
	
	static User getFromCookie(HttpServletRequest request, HttpServletResponse response) {
		try {
			Cookie[] cookies = request.getCookies();
			for (Cookie cookie : cookies) {
				if (cookie.getName().equals("hashedId")) {
					User u = ofy().load().type(User.class).id(cookie.getValue()).safe();  // throws Exception if user does not exist
					cookie.setMaxAge(3600);
					response.addCookie(cookie);
					return u;
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
		api_request.addProperty("model",Util.getGPTModel());
		api_request.addProperty("max_tokens",200);
		api_request.addProperty("temperature",0.2);
		
		JsonArray messages = new JsonArray();
		JsonObject m1 = new JsonObject();  // api request message
		m1.addProperty("role", "system");
		m1.addProperty("content","You are a tutor assisting a college student taking General Chemistry. "
				+ "The student is requesting your help to answer the following question item:\n."
				+ q.printForSage()
				+ "\nPlease guide the student in the right general direction, "
				+ "but do not give the correct answer to the question.\n"
				+ "Format the response as HTML with LaTeX for math.");
		messages.add(m1);;
		/*
		JsonObject m2 = new JsonObject();  // api request message
		m2 = new JsonObject();  // api request message
		m2.addProperty("role", "user");
		m2.addProperty("content","Please help me answer this General Chemistry problem: \n"
				+ q.printForSage());
		messages.add(m2);
		*/
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
		// We select the next question by calculating the user's scoreQuintile (1-5) and selecting
		// a question at random from those having a similar degree of difficulty (1-5). The selection 
		// process is random, but a bias is imposed to ensure that there is a 50% chance of selecting 
		// a question where the difficulty is the same as the user's scoreQuintile.
		int[][] qSelCutoff = { {10,17,20,20},{4,14,18,20},{1,5,15,19},{0,2,6,16},{0,0,3,10} };
		
		Long currentQuestionId = s.questionId;  // don't duplicate this
		int scoreQuintile = s.score==100?4:s.score/20;			// ranges from 0-4
		int nConceptQuestions = ofy().load().type(Question.class).filter("conceptId",s.conceptId).count();
		if (nConceptQuestions == 0) throw new Exception("Sorry, there are no questions for this Concept.");
		
		// select a level of difficulty between 0-4 based on user's scoreQuintile
		Random rand = new Random();
		int r = rand.nextInt(20);
		int difficulty = 4;
		for (int i=0;i<4;i++) {
			if (r < qSelCutoff[scoreQuintile][i]) {
				difficulty = i;
				break;
			}
		}
		int nQuintileQuestions =  ofy().load().type(Question.class).filter("conceptId",s.conceptId).filter("difficulty",difficulty).count();
		
		// select one question index at random
		Key<Question> k = null;
		if (nQuintileQuestions >= 5) {
			k = ofy().load().type(Question.class).filter("conceptId",s.conceptId).filter("difficulty",difficulty).offset(rand.nextInt(nQuintileQuestions)).keys().first().safe();
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
			return ofy().load().type(Score.class).parent(k).id(user.conceptId).safe();
		} catch (Exception e) {
			Score s = new Score(user.hashedId,user.conceptId);
			ofy().save().entity(s).now();
			return s;
		}
	}

	static String menuPage (User user, Score currentScore) {
		StringBuffer buf = new StringBuffer(Util.head);
		buf.append("<h1>Select a Key Concept</h1>"
				+ "Click on any numbered chapter to view the associated key concepts. Then click on a key concept to start the tutorial.<br/>"
				+ "You may start anywhere, but Sage has indicated a good starting point based on your current scores.<p>");
		return buf.toString() + conceptsMenu(user, currentScore);
	}
	
	static String conceptsMenu (User user, Score currentScore) {
		StringBuffer buf = new StringBuffer(Util.head);
		try {
			if (conceptMap == null) refreshConcepts();
			List<Key<Chapter>> chapterKeys = ofy().load().type(Chapter.class).order("chapterNumber").keys().list();
			Map<Key<Chapter>,Chapter> chapters = ofy().load().keys(chapterKeys);
			Map<Long,List<Long>> chapterMap = new HashMap<Long,List<Long>>();
			for (Concept con : conceptList) {
				if (con.chapterId == null) continue;
				if (chapterMap.get(con.chapterId)==null) chapterMap.put(con.chapterId, new ArrayList<Long>());
				chapterMap.get(con.chapterId).add(con.id);
			}

			// make a HashMap of this user's Score entities
			List<Score> userScores = ofy().load().type(Score.class).ancestor(user).list();
			Map<Long,Score> userScoresMap = new HashMap<Long,Score>();
			for (Score s : userScores) if (s.conceptId != null) userScoresMap.put(s.conceptId, s);

			Concept nextConcept = conceptMap.get(user.conceptId);
			int i = conceptList.indexOf(nextConcept);
			while(userScoresMap.get(nextConcept.id) != null && userScoresMap.get(nextConcept.id).score==100) {
				nextConcept = conceptList.get(i+1);
				i++;
			}
			
			// Add style elements to menu
			buf.append("<style>"
					+ "ul {"
					+ "  list-style-type: none;"
					+ "}"
					+ "#topUL {"
					+ "  margin: 0;"
					+ "  padding: 0;"
					+ "}"
					+ ".caret {"
					+ "  -webkit-user-select: none; /* Safari 3.1+ */"
					+ "  -moz-user-select: none; /* Firefox 2+ */"
					+ "  -ms-user-select: none; /* IE 10+ */"
					+ "  cursor: pointer;"
					+ "  user-select: none;"
					+ "}"
					+ ".caret::before {"
					+ "  content: '\\25B6';"
					+ "  color: black;"
					+ "  display: inline-block;"
					+ "  margin-right: 6px;"
					+ "}"
					+ ".caret-down::before {"
					+ "  -ms-transform: rotate(90deg); /* IE 9 */"
					+ "  -webkit-transform: rotate(90deg); /* Safari */"
					+ "  transform: rotate(90deg);"
					+ "}"
					+ ".nested {"
					+ "  display: none;"
					+ "}"
					+ ".active {"
					+ "  display: block;"
					+ "}"
					+ "</style>");

			// construct an unordered list of chapters with a nested inner list of related concepts
			buf.append("<ul>");
			int chapterNumber = 0;
			for (Key<Chapter> chKey : chapterKeys) {  // outer loop
				Chapter ch = chapters.get(chKey);
				if (ch == null) continue;
				chapterNumber++;
				// construct inner nested list:
				StringBuffer innerUL = new StringBuffer("<ul class=" + (ch.id.equals(nextConcept.chapterId)?"'active'":"'nested'") + ">\n");
				boolean chapterCompleted = true;
				for (Long conId : chapterMap.get(ch.id)) {
					Score userScore = userScoresMap.get(conId);
					if (userScore == null || userScore.score < 100) chapterCompleted = false;
					innerUL.append("<li><a href=/sage?ConceptId=" + conId + ">" + conceptMap.get(conId).title + "</a>" + (userScore==null?"":" (" + userScore.score + "%)") + (conId.equals(nextConcept.id)?"<span style='font-weight:bold;'> <mark>&#x21e6; Sage suggests that you start here</mark></span>":"") + "</li>\n");
				}
				innerUL.append("</ul>");
				buf.append("<li><span class='caret'>" + chapterNumber + ". " + ch.title + (chapterCompleted?"<span style='color:red;font-weight:bold'> &#x2713;</span>":"") + "</span>"
						+ innerUL.toString()
						+ "</li>");
			}
			buf.append("</ul>");

			buf.append("<script>"
					+ "var toggler = document.getElementsByClassName('caret');"
					+ "var i;"
					+ "for (i = 0; i < toggler.length; i++) {"
					+ "  toggler[i].addEventListener('click', function() {"
					+ "    this.parentElement.querySelector('.nested').classList.toggle('active');"
					+ "    this.classList.toggle('caret-down');"
					+ "  });"
					+ "}"
					+ "</script>");		
		} catch (Exception e) {
			buf.append(e.getMessage());
		}
		return buf.toString() + Util.foot;
	}

	static String orderResponses(String[] answers) {
		if (answers==null) return "";
		Arrays.sort(answers);
		String studentAnswer = "";
		for (String a : answers) studentAnswer = studentAnswer + a;
		return studentAnswer;
	}

	static String poseQuestion(Score s, boolean help, long p) throws Exception {
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
			if (p == 0L) p = new Random().nextLong(Long. MAX_VALUE);
			q.setParameters(p);
	
			buf.append("<h1>" + c.title + "</h1>");
			
			buf.append("<div style='width:800px; height=300px; overflow=auto; display:flex; align-items:center;'>");
			if (help) {
				buf.append("<div>"
						+ getHelp(q)
						+ "</div>"
						+ "<img src=/images/sage.png alt='Confucius Parrot' style='float:right'>"
						+ "</div>");
				
				buf.append("<script id='MathJax-script' async src='https://cdn.jsdelivr.net/npm/mathjax@3/es5/tex-mml-chtml.js'></script>\n");
				
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
						+ "function waitForHelp() {"
						+ " let a = document.getElementById('help');"
						+ " a.innerHTML = 'Please wait a moment for Sage to answer.';"
						+ "}"
						+ "</script>");
				}
			
			buf.append("<hr style='width:800px;margin-left:0'>");  // break between Sage helper panel and question panel
	
			// Print the question for the student
			buf.append("<form method=post style='max-width:800px;' onsubmit='waitForScore();' >"
					+ "<input type=hidden name=QuestionId value='" + q.id + "' />"
					+ "<input type=hidden name=Parameter value='" + p + "' />"
					+ "<input type=hidden name=UserRequest value='Score This Response' />"
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

	static String printScore(HttpServletRequest request, Score s) throws Exception {
		// Prepare a section that allows the user to ask Sage a question
		if (conceptList==null) refreshConcepts();
		User user = ofy().load().key(s.owner).now();
		String topic = conceptList.get(conceptList.indexOf(conceptMap.get(user.conceptId))).title;

		StringBuffer buf = new StringBuffer(Util.head);
		
		int rawScore = 0;  // result is 0, 1 or 2. Tne partial score 1 is for wrong sig figs.
		
		Long questionId = Long.parseLong(request.getParameter("QuestionId"));
		if (!questionId.equals(s.questionId)) throw new Exception("Wrong question is being scored.");
		
		String[] responses = request.getParameterValues(Long.toString(questionId));
		String studentAnswer = orderResponses(responses);
		if (studentAnswer == null || studentAnswer.isEmpty()) return null;
		
		Question q = ofy().load().type(Question.class).id(questionId).safe();
		long p = 0L;
		if (q.requiresParser()) {
			p = Long.parseLong(request.getParameter("Parameter"));
			q.setParameters(p);
		}
		
		// Construct a link that either reveals the static solution or submits a request to generate a full AI solution for parameterized questions
		StringBuffer showMeLink = new StringBuffer("<div>");
				
		// Get the raw score for the student's answer
		switch (q.getQuestionType()) {
		case 1:
		case 2:
		case 3:
		case 4:
		case 5:
			rawScore = q.isCorrect(studentAnswer)?2:q.agreesToRequiredPrecision(studentAnswer)?1:0;
			if (q.requiresParser()) {  // offer a POST form to get an AI response
				// include some javascript to change the submit button
				showMeLink.append("<script>"
						+ "function waitForScore() {\n"
						+ " let b = document.getElementById('showFullSolution');\n"
						+ " b.disabled = true;\n"
						+ " b.value = 'Please wait a moment for Sage to respond.';\n"
						+ "}\n"
						+ "</script>");
				showMeLink.append("<form method=post action=/sage onsubmit='waitForScore();'>"
						+ "<input type=hidden name=QuestionId value=" + q.id + " />"
						+ "<input type=hidden name=Parameter value=" + p + " />");
				for (String r : responses) showMeLink.append("<input type=hidden name=" + q.id + " value='" + r + "' />");
				showMeLink.append("<input type=hidden name=RawScore value=" + rawScore + " />"
						+ "<input type=hidden name=UserRequest value='Show Full Solution' />"
						+ "<input id=showFullSolution type=submit class=btn value='Show me' />"
						+ "</form>");
			} else {  // offer the static solution
				showMeLink.append("<script>"
						+ "function showSolution() {"
						+ " document.getElementById('link').style.display='none';"
						+ " document.getElementById('solution').style.display='inline';"
						+ "}"
						+ "</script>");
				showMeLink.append("<div id=link><a href=# class=btn role=button onclick='showSolution();'>Show me</a></div>"
						+ "<div id=solution style='display: none'>" 
						+ q.printAllToStudents(studentAnswer) 
						+ "</div>");
			}
			break;
		case 6:  // Handle five-star rating response
			try {
				if (Integer.parseInt(studentAnswer) > 0) rawScore = 2;  // full marks for submitting a response
			} catch (Exception e) {}
			break;
		case 7:  // New section for scoring essay questions with Chat GPT
			JsonObject api_score = scoreEssayQuestion(q.text,studentAnswer);  // these are used to score essay questions using ChatGPT
			rawScore = api_score.get("score").getAsInt()/2; 	// scale 0-2
			showMeLink.append(api_score.get("feedback").getAsString());  // displays feedback in lieu of link
			break;
		default: throw new Exception("Unable to determine question type");
		}

		showMeLink.append("</div>");
		
		// Update and save the Score object
		boolean level_up = s.update(rawScore);
		ofy().save().entity(s);  // asynchronous save takes a second or 2; should be OK
		if (level_up) user.addTokens(20);  // add 20 tokens to the user's account for earning a higher level
		
		try {
			if (q.getQuestionType() == 6) {
				buf.append(rawScore==2?"<h1>Thank you for your rating.</h1><b>You received full credit for this question.</b>":"<h1>No rating was submitted.</h1><b>You did not receive credit for this question.</b>");
			} else {
				switch (rawScore) {  // 0, 1 or 2
				case 2:  // correct answer
					buf.append("<h1>Congratulations!</h1>"
							+ "<b>Your answer is correct. </b><IMG SRC=/images/checkmark.gif ALT='Check mark' align=bottom /><p>"
							+ "<div style='width:800px;display:flex;align-items:center;'>"
							
							+ showMeLink
							+ "<img id=polly src='/images/parrot2.png' alt='Parrot character' style='margin-left:20px;'>"
							+ "</div>");
					break;
				case 1: // partial credit			
					buf.append("<h1>Your answer is partially correct</h1>"
							+ "<b>You received half credit.</b><p>"
							+ "<div style='width:800px;display:flex; align-items:center;'>"
							+ showMeLink
							+ "<img id=polly src='/images/parrot1.png' alt='Parrot character' style='margin-left:20px;'>"
							+ "</div>");
					break;
				case 0: // wrong answer
					buf.append("<h1>Sorry, your answer is not correct.<IMG SRC=/images/xmark.png ALT='X mark' align=middle></h1>"
							+ "<div style='width:800px;display:flex; align-items:center;'>"
							+ showMeLink
							+ "<img id=polly src='/images/parrot0.png' alt='Parrot character' style='margin-left:20px;'>\n"
							+ "</div>");
					break;
				}
			}
			boolean completed = false;
			if (s.score == 100) {  // move the user to the next chapter or concept
				buf.append("<h2>Your score is 100%</h2>");
				completed = true;
				if (finishedChapter(s)) {
					Chapter ch = ofy().load().type(Chapter.class).id(conceptMap.get(s.conceptId).chapterId).now();
					buf.append("<b>You have completed Chapter " + ch.chapterNumber + ". " + ch.title + " and earned 20 more tokens.</b>");
					buf.append("<ul>");
					for (Concept c : conceptList) if (c.chapterId != null && c.chapterId.equals(ch.id)) buf.append("<li>" + c.title + "</li>");;
					buf.append("</ul>");
					buf.append("Your Sage account now has " + user.tokensRemaining() + " tokens.<br/>");
					Chapter nextChapter = ofy().load().type(Chapter.class).filter("chapterNumber",ch.chapterNumber+1).first().now();
					if (nextChapter == null) buf.append("<h1>Congratulations, you finished!</h1>");
					else {
						buf.append(askAQuestion(topic,Nonce.getHexString()));
						buf.append("<br/>The next chapter is: <b>" + nextChapter.title + "</b>.<br/>");
					}
				} else {  // concept was completed
					buf.append("You have mastered the concept: <b>" + topic +"</b>" + (level_up?" and earned 20 more tokens":"") + ".</br/>");
					buf.append("Your Sage account now has " + user.tokensRemaining() + " tokens.<br/>");
					buf.append(askAQuestion(topic,Nonce.getHexString()));
				}
			} else if (level_up) {
				buf.append("<h3>You have moved up to Level " + (s.score/20 + 1) +" and earned 20 tokens.</h3>"
						+ "<b>Your current score on this concept is " + s.score + "%.</b>&nbsp;");
				if (s.score >= 60 && s.score < 80) buf.append("<p>" + askAQuestion(topic,Nonce.getHexString()) + "Otherwise...");
			} else {
				buf.append("<p><b>Your current score on this concept is " + s.score + "%.</b>&nbsp;");
			}
			// print a button to continue
			buf.append("<a class=btn role=button href='/sage" + (completed?"?UserRequest=menu":"") + "'>Continue</a><p>");
		} catch (Exception e) {
			buf.append("<p>" + e.getMessage()==null?e.toString():e.getMessage());
		}
		return buf.toString() + Util.foot;
	}

	static String printSolution(HttpServletRequest request, Score s) throws Exception {
		StringBuffer buf = new StringBuffer(Util.head);
		int rawScore = Integer.parseInt(request.getParameter("RawScore"));
		switch (rawScore) {
		case 2:
			buf.append("<h1>Congratulations!</h1>"
					+ "<b>Your answer is correct. </b><IMG SRC=/images/checkmark.gif ALT='Check mark' align=bottom /><p>");
			break;
		case 1:
			buf.append("<h1>Your answer is partially correct</h1>"
					+ "<b>You received half credit.</b><p>");
			break;
		case 0:
			buf.append("<h1>Sorry, your answer is not correct.<IMG SRC=/images/xmark.png ALT='X mark' align=middle></h1>");
			break;
		default:
		}
		long questionId = Long.parseLong(request.getParameter("QuestionId"));
		long p = Long.parseLong(request.getParameter("Parameter"));
		String[] responses = request.getParameterValues(Long.toString(questionId));
		String studentAnswer = orderResponses(responses);
		Question q = ofy().load().type(Question.class).id(questionId).safe();
		q.setParameters(p);
		buf.append("<div style='width:800px;display:flex;align-items:center;'>"
				+ "<div style='width:600px'>" + q.printAllToStudents(studentAnswer) + "</div><p>");
		switch (rawScore) {
		case 2:
			buf.append("<img id=polly src='/images/parrot2.png' alt='Parrot character' style='margin-left:20px;'>");
			break;
		case 1:
			buf.append("<img id=polly src='/images/parrot1.png' alt='Parrot character' style='margin-left:20px;'>");
			break;
		case 0:
			buf.append("<img id=polly src='/images/parrot0.png' alt='Parrot character' style='margin-left:20px;'>");
			break;
		}
		buf.append("</div>");			
		
		// print a button to continue
		buf.append("<a class=btn role=button href='/sage" + (s.score==100?"?UserRequest=menu":"") + "'>Continue</a><p>");
		return buf.toString() + Util.foot;
	}

	static void refreshConcepts() {
		conceptList = ofy().load().type(Concept.class).order("orderBy").list();
		conceptMap = new HashMap<Long,Concept>();
		for (Concept c : conceptList) conceptMap.put(c.id, c);
	}

	static JsonObject scoreEssayQuestion(String questionText, String studentAnswer) throws Exception {
		if (studentAnswer.length()>800) studentAnswer = studentAnswer.substring(0,799);
		JsonObject api_request = new JsonObject();  // these are used to score essay questions using ChatGPT
		api_request.addProperty("model",Util.getGPTModel());
		api_request.addProperty("max_tokens",200);
		api_request.addProperty("temperature",0.2);
		JsonObject m = new JsonObject();  // api request message
		m.addProperty("role", "user");
		String prompt = "Question: \"" + questionText +  "\"\n My response: \"" + studentAnswer + "\"\n "
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
		JsonObject api_score = null;
		try {
			String content = api_response.get("choices").getAsJsonArray().get(0).getAsJsonObject().get("message").getAsJsonObject().get("content").getAsString();
			api_score = JsonParser.parseString(content).getAsJsonObject();
			return api_score;
		} catch (Exception e) {
			api_score = new JsonObject();
			api_score.addProperty("score", 0);
			api_score.addProperty("feedback", "Sorry, an error occurred: " + e.getMessage()==null?e.toString():e.getMessage());
		}
		return api_score;
	}
	
	static String start(User user, Score s) throws Exception {
		StringBuffer buf = new StringBuffer(Util.head);
		try {
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
			
			buf.append("<script id='MathJax-script' async src='https://cdn.jsdelivr.net/npm/mathjax@3/es5/tex-mml-chtml.js'></script>\n");
		} catch (Exception e) {
			buf.append("<p>Error: " + e.getMessage()==null?e.toString():e.getMessage());
		}
		return buf.toString() + Util.foot;
	}
}
