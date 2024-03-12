package org.chemvantage.sage;

import static com.googlecode.objectify.ObjectifyService.key;
import static com.googlecode.objectify.ObjectifyService.ofy;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.googlecode.objectify.Key;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@WebServlet("/questions")
public class QuestionManager extends HttpServlet {

	private static final long serialVersionUID = 1L;
	private static List<Concept> concepts;

	public void doGet(HttpServletRequest request,HttpServletResponse response)
			throws ServletException, IOException {
		
		PrintWriter out = response.getWriter();
		response.setContentType("text/html");
		
		StringBuffer buf = new StringBuffer(Util.head);
		
		try {
			buf.append("<h1>Manage Question Items</h1>");
			
			Long conceptId = null;
			Concept concept = null;
			try {
				conceptId = Long.parseLong(request.getParameter("ConceptId"));
				concept = ofy().load().type(Concept.class).id(conceptId).safe();
			} catch (Exception e) {}
			
			if (concepts == null) concepts = ofy().load().type(Concept.class).order("orderBy").list();;
			
			buf.append("\n<form id='conceptselector' method=get>Select a concept: "
					+ "<select name=ConceptId onchange='submit();' >"
					+ "<option>Select a concept</option>");
			for (Concept c : concepts) buf.append("<option value='" + c.id + (c.id.equals(conceptId)?"' selected >":"' >") + c.title + "</option>\n");
			buf.append("</select></form>");
			
			if (concept != null) {
				buf.append("<h4>" + concept.title + "</h4>");
				List<Question> questions = ofy().load().type(Question.class).filter("conceptId",conceptId).list();
				buf.append("This concept has " + questions.size() + " question items.<p>");
				
				buf.append("<form method=post>"
						+ "<input type=submit name=UserRequest value='Save Difficulty'/>"
						+ "<table>");
				for (Question q : questions) {
					buf.append("<tr><td style='width:400px;'>"
							+ q.printAll()
							+ "</td><td style='vertical-align:top;'>easy"
							+ "<span" + (q.difficulty!=null&&q.difficulty==1?" style='background-color:#90EE90'":"") + "><input type=radio name='difficulty" + q.id + "' value=1> </span>"
							+ "<span" + (q.difficulty!=null&&q.difficulty==2?" style='background-color:#90EE90'":"") + "><input type=radio name='difficulty" + q.id + "' value=2> </span>"
							+ "<span" + (q.difficulty!=null&&q.difficulty==3?" style='background-color:#90EE90'":"") + "><input type=radio name='difficulty" + q.id + "' value=3> </span>"
							+ "<span" + (q.difficulty!=null&&q.difficulty==4?" style='background-color:#90EE90'":"") + "><input type=radio name='difficulty" + q.id + "' value=4> </span>"
							+ "<span" + (q.difficulty!=null&&q.difficulty==5?" style='background-color:#90EE90'":"") + "><input type=radio name='difficulty" + q.id + "' value=5> </span>"
							+ "hard</td></tr>"
							+ "<tr><td colspan=2><hr></td</tr>");
				}
				buf.append("</table>"
						+ "<input type=submit name=UserRequest value='Save Difficulty'/>"
						+ "</form>");
			}
		} catch (Exception e) {
			buf.append("<p>Error: " + e.getMessage()==null?e.toString():e.getMessage());
		}
		
		out.println(Util.head + buf.toString() + Util.foot);
	}
		
	public void doPost(HttpServletRequest request,HttpServletResponse response)
			throws ServletException, IOException {
		
		String userRequest = request.getParameter("UserRequest");
		if (userRequest==null) return;
		
		try {
			switch (userRequest) {
			case "Save Difficulty":
				saveQuestions(request);
				doGet(request,response);
				break;
			}
		} catch (Exception e) {
			response.getWriter().println(e.getMessage()==null?e.toString():e.getMessage());
		}
	}
	
	static void saveQuestions(HttpServletRequest request) throws Exception {
		
		// Make a List of question keys for the submission and a Map of the difficulty values
		List<Key<Question>> questionKeys = new ArrayList<Key<Question>>();
		Map<Key<Question>,Integer> difficulties = new HashMap<Key<Question>,Integer>();
		
		Enumeration<String> params = request.getParameterNames();
		while (params.hasMoreElements()) {
			String name = params.nextElement();
			if (name.startsWith("difficulty")) {
				Long qId = Long.parseLong(name.substring(10));
				Key<Question> k = key(Question.class,qId);
				questionKeys.add(k);
				difficulties.put(k, Integer.parseInt(request.getParameter(name)));
			}
		}
		
		// Retrieve the questions
		Map<Key<Question>,Question> questions = ofy().load().keys(questionKeys);
		
		// Apply the changes
		for (Key<Question> k : questionKeys) {
			questions.get(k).difficulty = difficulties.get(k);
		}
		
		// Save the questions
		ofy().save().entities(questions.values()).now();
	}
}
