package org.chemvantage.sage;

import static com.googlecode.objectify.ObjectifyService.key;
import static com.googlecode.objectify.ObjectifyService.ofy;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.Random;

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
	private static List<Concept> concepts;

	public void doGet(HttpServletRequest request,HttpServletResponse response)
			throws ServletException, IOException {
		
		PrintWriter out = response.getWriter();
		response.setContentType("text/html");
		
		try {
			HttpSession session = request.getSession(false);
			if (session == null) response.sendRedirect("/");
			String hashedId = (String)session.getAttribute("hashedId");
			out.println(start(hashedId));
		} catch (Exception e) {
			out.println(Util.head + "Error: " + e.getMessage()==null?e.toString():e.getMessage() + Util.foot);
		}
	}
	
	public void doPost(HttpServletRequest request,HttpServletResponse response)
			throws ServletException, IOException {
		
		PrintWriter out = response.getWriter();
		response.setContentType("text/html");
	
		out.println("<h1>Hello World</h1>");
	}
	
	static String start(String hashedId) throws Exception {
		StringBuffer buf = new StringBuffer(Util.head);
		try {
		Concept c = getConcept(hashedId);
		Key<Score> k = key(key(User.class,hashedId),Score.class,c.id);
		Score s = null;
		try {
			s = ofy().load().key(k).safe();
		} catch (Exception e) {
			s = new Score(hashedId,c.id);
		}
		if (s.nextQuestionId == null) {
			s.nextQuestionId = getNextQuestionId(c,s);
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
		if (s.score < 100) return concepts.get(n-1);  // still working on this concept
		else {  // user finished the previous Concept
			ofy().save().entity(new Score(hashedId,concepts.get(n).id)).now();
			return concepts.get(n);
		}
	}
	
	static Long getNextQuestionId(Concept c, Score s) throws Exception {
		Long currentQuestionId = s.nextQuestionId;  // don't duplicate this
		int scoreQuintile = s.score/20 + 1;
		int nConceptQuestions = ofy().load().type(Question.class).filter("conceptId",c.id).order("-pctSuccess").count();
		/*
		 * For N questions in this concept, we divide into quintiles according to pctSuccess (100 = easy; 0 = hardest)
		 * The question indices for quintile q range from N*(q-1)/5 to N*q/5-1
		 */
		int offset = nConceptQuestions*(scoreQuintile-1)/5;
		int nQuintileQuestions = (nConceptQuestions*scoreQuintile/5) - (nConceptQuestions*(scoreQuintile-1)/5);
		
		// Perform bulletproofing checks in case there are few or no questions
		if (nQuintileQuestions == 0) {
			if (nConceptQuestions == 0) throw new Exception("Sorry, there are no questions for this Concept.");
			// there are fewer than 5 questions, so use the entire range
			offset = 0;
			nQuintileQuestions = nConceptQuestions;
		}
		
		// select one at random
		int index = new Random().nextInt(nQuintileQuestions) + offset;
		
		// We don't need the Question itself, only the id
		Key<Question> k = ofy().load().type(Question.class).filter("conceptId",c.id).order("-pctSuccess").offset(index).keys().first().safe();
		
		// If this duplicates the current question, try again (recursively)
		if (k.getId() == currentQuestionId && nQuintileQuestions > 1) return getNextQuestionId(c,s);
		
		return k.getId();
	}
	
}
