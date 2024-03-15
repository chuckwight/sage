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
	private static List<Concept> conceptList;
	private static Map<Long,Concept> conceptMap = null;

	public void doGet(HttpServletRequest request,HttpServletResponse response)
			throws ServletException, IOException {
		
		PrintWriter out = response.getWriter();
		response.setContentType("text/html");
		
		String userRequest = request.getParameter("UserRequest");
		if (userRequest == null) userRequest = "";
		
		try {
			switch (userRequest) {
			case "EditQuestion":
				Long questionId = null;
				try { 
					questionId = Long.parseLong(request.getParameter("QuestionId")); 
					out.println(editQuestion(questionId));
				} catch (Exception e) {}
				break;
			case "NewQuestion": 
				out.println(newQuestionForm(request)); 
				break;
			default:
				Long conceptId = null;
				try { 
					conceptId = Long.parseLong(request.getParameter("ConceptId")); 
				} catch (Exception e) {}
				out.println(viewQuestions(conceptId));
			}

		} catch (Exception e) {
			response.getWriter().println(e.getMessage()==null?e.toString():e.getMessage());
		}
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
			case "Save New Question":
				createQuestion(request);
				break;
			case "Update Question":
				updateQuestion(request);
				break;
			case "Delete Question":
				deleteQuestion(request);
				break;
			}
		} catch (Exception e) {
			response.getWriter().println(e.getMessage()==null?e.toString():e.getMessage());
		}
		doGet(request,response);
	}
	
	static Question assembleQuestion(HttpServletRequest request) {
		try {
			int questionType = Integer.parseInt(request.getParameter("QuestionType"));
			return assembleQuestion(request,new Question(questionType)); 
		} catch (Exception e) {
			return null;
		}
	}
	
	static Question assembleQuestion(HttpServletRequest request,Question q) {
		long conceptId = 0;
		try {
			conceptId = Long.parseLong(request.getParameter("ConceptId"));
		} catch (Exception e) {}
		int type = q.getQuestionType();
		try {
			type = Integer.parseInt(request.getParameter("QuestionType"));
		}catch (Exception e) {}
		String questionText = request.getParameter("QuestionText");
		ArrayList<String> choices = new ArrayList<String>();
		int nChoices = 0;
		char choice = 'A';
		for (int i=0;i<5;i++) {
			String choiceText = request.getParameter("Choice"+ choice +"Text");
			if (choiceText==null) choiceText = "";
			if (choiceText.length() > 0) {
				choices.add(choiceText);
				nChoices++;
			}
			choice++;
		}
		double requiredPrecision = 0.; // percent
		int significantFigures = 0;
		int pointValue = 1;
		try {
			pointValue = Integer.parseInt(request.getParameter("PointValue"));
		} catch (Exception e) {
		}
		try {
			requiredPrecision = Double.parseDouble(request.getParameter("RequiredPrecision"));
		} catch (Exception e) {
		}
		try {
			significantFigures = Integer.parseInt(request.getParameter("SignificantFigures"));
		} catch (Exception e) {
		}
		String correctAnswer = "";
		try {
			String[] allAnswers = request.getParameterValues("CorrectAnswer");
			for (int i = 0; i < allAnswers.length; i++) correctAnswer += allAnswers[i];
		} catch (Exception e) {
			correctAnswer = request.getParameter("CorrectAnswer");
		}
		String parameterString = request.getParameter("ParameterString");
		if (parameterString == null) parameterString = "";
		
		q.conceptId = conceptId;
		q.setQuestionType(type);
		q.text = questionText;
		q.nChoices = nChoices;
		q.choices = choices;
		q.requiredPrecision = requiredPrecision;
		q.significantFigures = significantFigures;
		q.correctAnswer = correctAnswer;
		q.tag = request.getParameter("QuestionTag");
		q.pointValue = pointValue;
		q.parameterString = parameterString;
		q.solution = request.getParameter("Solution");
		q.notes = "";
		q.authorId = request.getParameter("AuthorId");
		q.editorId = request.getParameter("EditorId");
		q.scrambleChoices = Boolean.parseBoolean(request.getParameter("ScrambleChoices"));
		q.strictSpelling = Boolean.parseBoolean(request.getParameter("StrictSpelling"));
		q.validateFields();
		return q;
	}
	
	
	static String conceptSelectBox(Long conceptId) {
		StringBuffer buf = new StringBuffer("<select name=ConceptId>");
		if (conceptList == null) refreshConcepts();
		for (Concept c : conceptList) buf.append("<option value=" + c.id + (c.id.equals(conceptId)?" selected>":">") + c.title + "</option>");
		buf.append("</select>");
		return buf.toString();
	}
	
	static void createQuestion(HttpServletRequest request) { //previously type long
		try {
			Question q = assembleQuestion(request);
			q.isActive = true;
			ofy().save().entity(q).now();
	} catch (Exception e) {}
	}

	static void deleteQuestion(HttpServletRequest request) {
		long questionId = 0;
		try {
			questionId = Long.parseLong(request.getParameter("QuestionId"));
			Key<Question> k = key(Question.class,questionId);
			ofy().delete().key(k).now();
		} catch (Exception e) {
			return;
		}
	}

	static String editQuestion(Long questionId) throws Exception {
		StringBuffer buf = new StringBuffer(Util.head);
		
		Question q = ofy().load().type(Question.class).id(questionId).safe();
		
		if (conceptMap == null) refreshConcepts();
		Concept c = conceptMap.get(q.conceptId);
		
		if (q.requiresParser()) q.setParameters();
		buf.append("<h1>Edit</h1><h2>Current Question</h2>");
		buf.append("Concept: " + (c==null?"n/a":c.title) + "<br/>");
		buf.append("Author: " + q.authorId + "<br>");
		buf.append("Editor: " + q.editorId + "<br>");
		
		buf.append("Success Rate: " + q.getSuccess() + "<p>");
		
		buf.append("<FORM Action=/Edit METHOD=POST>");
		
		buf.append(q.printAll());
		
		if (q.authorId==null) q.authorId="";
		if (q.editorId==null) q.editorId="";
		buf.append("<INPUT TYPE=HIDDEN NAME=AuthorId VALUE='" + q.authorId + "' />");
		buf.append("<INPUT TYPE=HIDDEN NAME=EditorId VALUE='" + q.editorId + "' />");
		buf.append("<INPUT TYPE=HIDDEN NAME=QuestionId VALUE=" + questionId + " />");
		buf.append("<INPUT TYPE=SUBMIT NAME=UserRequest VALUE='Delete Question' />");
		buf.append("<INPUT TYPE=SUBMIT NAME=UserRequest VALUE='Quit' />");
		
		buf.append("<hr><h2>Edit This Question</h2>");
		
		buf.append("Concept:" + conceptSelectBox(q.conceptId) + "<br/>");
		
		buf.append("Question Type:" + questionTypeDropDownBox(q.getQuestionType()));
		
		buf.append(q.edit());
		
		buf.append("<INPUT TYPE=SUBMIT NAME=UserRequest VALUE=Preview />");
		buf.append("</FORM>");
	
		return buf.toString() + Util.foot;
	}

	static String newQuestionForm(HttpServletRequest request) {
		StringBuffer buf = new StringBuffer("<h1>Edit</h1><h2>New Question</h2>");
		Long conceptId = null;
		try {
			conceptId = Long.parseLong(request.getParameter("ConceptId"));
		} catch (Exception e) {}		
		
		int questionType = 0;
		try {
			questionType = Integer.parseInt(request.getParameter("QuestionType"));
			switch (questionType) {
			case (1): buf.append("<h3>Multiple-Choice Question</h3>");
			buf.append("Fill in the question text and the possible answers "
					+ "(up to a maximum of 5). Be sure to select the single best "
					+ "answer to the question."); break;
			case (2): buf.append("<h3>True-False Question</h3>");
			buf.append("Write the question as an affirmative statement. Then "
					+ "indicate below whether the statement is true or false."); break;
			case (3): buf.append("<h3>Select-Multiple Question</h3>");
			buf.append("Fill in the question text and the possible answers "
					+ "(up to a maximum of 5). Be sure to "
					+ "select all of the correct answers to the question."); break;
			case (4): buf.append("<h3>Fill-in-Word Question</h3>");
			buf.append("Start the question text in the upper textarea box. Indicate "
					+ "the correct answer (and optionally, an alternative correct answer) in "
					+ "the middle boxes, and the end of the question text below that.  The answers "
					+ "are not case-sensitive or punctuation-sensitive, but spelling must "
					+ "be exact."); break;
			case (5): buf.append("<h3>Numeric Question</h3>");
			buf.append("Fill in the question text in the upper textarea box and "
					+ "the correct numeric answer below. Also indicate the required precision "
					+ "of the student's response in percent (default = 2%). Use the bottom "
					+ "textarea box to finish the question text and/or to indicate the "
					+ "expected dimensions or units of the student's answer."); break;
			case (6): buf.append("<h3>Five Star Question</h3>");
			buf.append("Fill in the question text. The user will be asked to provide a rating "
					+ "from 1 to 5 stars."); break;
			case (7): buf.append("<h3>EssayQuestion</h3>");
			buf.append("Fill in the question text. The user will be asked to provide a short "
					+ "essay response."); break;
			default: buf.append("An unexpected error occurred. Please try again.");
			}
			Question question = new Question(questionType);
			buf.append("<p><FORM METHOD=POST ACTION=Edit>");
			buf.append("<INPUT TYPE=HIDDEN NAME=QuestionType VALUE=" + questionType + ">");
			
			buf.append("Concept: " + conceptSelectBox(conceptId) + "<br>");
			
			buf.append(question.edit());
			buf.append("<INPUT TYPE=SUBMIT NAME=UserRequest VALUE='Preview'></FORM>");
		} catch (Exception e) {
			return buf.toString() + "<br>" + e.getMessage();
		}
		return buf.toString();
	}

	static 	String questionTypeDropDownBox(int questionType) {
		StringBuffer buf = new StringBuffer();
		buf.append("\n<SELECT NAME=QuestionType>"
				+ "<OPTION VALUE=1" + (questionType==1?" SELECTED>":">") + "Multiple Choice</OPTION>"
				+ "<OPTION VALUE=2" + (questionType==2?" SELECTED>":">") + "True/False</OPTION>"
				+ "<OPTION VALUE=3" + (questionType==3?" SELECTED>":">") + "Select Multiple</OPTION>"
				+ "<OPTION VALUE=4" + (questionType==4?" SELECTED>":">") + "Fill in word/phrase</OPTION>"
				+ "<OPTION VALUE=5" + (questionType==5?" SELECTED>":">") + "Numeric</OPTION>"
				+ "<OPTION VALUE=6" + (questionType==6?" SELECTED>":">") + "Five Star</OPTION>"
				+ "<OPTION VALUE=7" + (questionType==7?" SELECTED>":">") + "Essay</OPTION>"
				+ "</SELECT>");
		return buf.toString();
	}
	
	static void refreshConcepts() {
		conceptList = ofy().load().type(Concept.class).order("orderBy").list();
		conceptMap = new HashMap<Long,Concept>();
		for (Concept c : conceptList) conceptMap.put(c.id, c);
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

	static void updateQuestion(HttpServletRequest request) {
		long questionId = 0;
		try {
			questionId = Long.parseLong(request.getParameter("QuestionId"));	
			Question q = ofy().load().type(Question.class).id(questionId).safe();
			q = assembleQuestion(request,q);
			q.isActive = true;
			ofy().save().entity(q).now();
		} catch (Exception e) {
			return;
		}
	}

	static String viewQuestions(Long conceptId) throws Exception {
		StringBuffer buf = new StringBuffer(Util.head);
	
		buf.append("<h1>Manage Question Items</h1>");
		
		if (conceptMap == null) refreshConcepts();		
		Concept concept = conceptMap.get(conceptId);
		
		buf.append("\n<form id='conceptselector' method=get>Select a concept: "
				+ "<select name=ConceptId onchange='submit();' >"
				+ "<option>Select a concept</option>");
		for (Concept c : conceptList) buf.append("<option value='" + c.id + (c.id.equals(conceptId)?"' selected >":"' >") + c.title + "</option>\n");
		buf.append("</select></form>");
		
		if (concept != null) {
			buf.append("<h4>" + concept.title + "</h4>");
			List<Question> questions = ofy().load().type(Question.class).filter("conceptId",conceptId).list();
			
			buf.append("This concept has " + questions.size() + " question items. ");
			int[] nQuestions = new int[6];
			nQuestions[0] = questions.size();  // total
			buf.append("Difficulty Levels (1-5):&nbsp;<span style='border-style:solid;'>");
			for (int i=1;i<6;i++) {  // for each level of difficulty
				nQuestions[i] = ofy().load().type(Question.class).filter("conceptId",concept.id).filter("difficulty",i).count();
				buf.append(nQuestions[i] + (i<5?"&nbsp;|&nbsp;":"&nbsp;"));
				nQuestions[0] -= nQuestions[i];
			}
			buf.append("</span>&nbsp;Unclassified: " + nQuestions[0]);
			
			// Add a button to create a new question item:
			buf.append("&nbsp;<a href=/?UserRequest=NewQuestion&ConceptId=" + concept.id + "><button>Create a New Question</button></a><p>");
			
			buf.append("<form method=post>"
					+ "<input type=submit name=UserRequest value='Save Difficulty'/>"
					+ "<table>");
			for (Question q : questions) {
				q.setParameters();
				buf.append("<tr><td style='width:400px;'>"
						+ q.printAll()
						+ "</td><td style='text-align:center;vertical-align:top;'>"
						+ "<div style='border-style:solid'> easy "
						+ "<span" + (q.difficulty!=null&&q.difficulty==1?" style='background-color:#90EE90'":"") + "><input type=radio name='difficulty" + q.id + "' value=1> </span>"
						+ "<span" + (q.difficulty!=null&&q.difficulty==2?" style='background-color:#90EE90'":"") + "><input type=radio name='difficulty" + q.id + "' value=2> </span>"
						+ "<span" + (q.difficulty!=null&&q.difficulty==3?" style='background-color:#90EE90'":"") + "><input type=radio name='difficulty" + q.id + "' value=3> </span>"
						+ "<span" + (q.difficulty!=null&&q.difficulty==4?" style='background-color:#90EE90'":"") + "><input type=radio name='difficulty" + q.id + "' value=4> </span>"
						+ "<span" + (q.difficulty!=null&&q.difficulty==5?" style='background-color:#90EE90'":"") + "><input type=radio name='difficulty" + q.id + "' value=5> </span>"
						+ " hard "
						+ "</div><p>"
						+ "<a href=/?UserRequest=EditQuestion&QuestionId=" + q.id + "><button>Edit</button></a>&nbsp;"
						+ "</td></tr>"
						+ "<tr><td colspan=2><hr></td</tr>");
			}
			buf.append("</table>"
					+ "<input type=submit name=UserRequest value='Save Difficulty'/>"
					+ "</form>");
		}
		
		return buf.toString() + Util.foot;
	}
}
