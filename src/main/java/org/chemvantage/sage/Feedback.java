/*  ChemVantage - A Java web application for online learning
*   Copyright (C) 2011 ChemVantage LLC
*   
*    This program is free software: you can redistribute it and/or modify
*   it under the terms of the GNU General Public License as published by
*   the Free Software Foundation, either version 3 of the License, or
*   (at your option) any later version.
*
*   This program is distributed in the hope that it will be useful,
*   but WITHOUT ANY WARRANTY; without even the implied warranty of
*   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
*   GNU General Public License for more details.
*
*   You should have received a copy of the GNU General Public License
*   along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

package org.chemvantage.sage;

import static com.googlecode.objectify.ObjectifyService.ofy;

import java.io.IOException;
import java.io.PrintWriter;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;


@WebServlet("/feedback")
public class Feedback extends HttpServlet {

	private static final long serialVersionUID = 137L;
	
	public String getServletInfo() {
		return "This servlet uses AJAX to receive feedback from users about question items.";
	}

	public void doGet(HttpServletRequest request,HttpServletResponse response)
	throws ServletException, IOException {
		
		User user = Sage.getFromCookie(request, response);
		if (user == null) response.sendRedirect("/");
		
		String userRequest = request.getParameter("UserRequest");
		if (userRequest == null) userRequest = "";

		switch (userRequest) {  // only AJAX submissions
		case "ReportAProblem":
			reportAProblem(user, request);
			break;
		case "HelpfulHint":
			reportHelpfulHint(request);
			break;
		case "HelpfulAnswer":
			reportHelpfulAnswer(request);
		case "AjaxRating":
			//recordAjaxRating(request);
			break;
		}
	}

	public void doPost(HttpServletRequest request,HttpServletResponse response)
	throws ServletException, IOException {
		response.setContentType("text/html");
		PrintWriter out = response.getWriter();

		User user = Sage.getFromCookie(request, response);
		if (user == null) response.sendRedirect("/");
		
		String userRequest = request.getParameter("UserRequest");
		if (userRequest == null) userRequest = "";
		
		switch (userRequest ) {
		case "Submit Feedback":
			try {
				out.println(Util.head + submitFeedback(user,request) + Util.foot);
			} catch (Exception e) {}
			break;
		}
	}
	
	static String feedbackForm() {
		StringBuffer buf = new StringBuffer();
		buf.append("Please rate your Sage experience: ");
		return buf.toString();
	}
	
	static void reportAProblem(User user, HttpServletRequest request) {
		try {
			long questionId = Long.parseLong(request.getParameter("QuestionId"));
			int[] params = {0,0,0,0};
			try {
				String[] sparams = request.getParameter("Params").replace("[","").replace("]","").replaceAll("\\s", "").split(",");
				for (int i=0;i<sparams.length;i++) params[i]=Integer.parseInt(sparams[i]);
			} catch (Exception e) {}
			String notes = request.getParameter("Notes");
			String email = request.getParameter("Email");
			String studentAnswer = request.getParameter("StudentAnswer");
			UserReport r = new UserReport(user.hashedId,questionId,params,studentAnswer,notes);
			ofy().save().entity(r);
			if (email != null && !email.isEmpty()) sendEmailToAdmin(r,email);
		} catch (Exception e) {

		}
	}
	
	static void reportHelpfulAnswer(HttpServletRequest request) {
		try {
			boolean response = Boolean.parseBoolean(request.getParameter("Response"));
			Util.sageAnswerWasHelpful(response);
		} catch (Exception e) {}
	}

	static void reportHelpfulHint(HttpServletRequest request) {
		try {
			long questionId = Long.parseLong(request.getParameter("QuestionId"));
			boolean response = Boolean.parseBoolean(request.getParameter("Response"));
			Question q = ofy().load().type(Question.class).id(questionId).safe();
			q.hintWasHelpful(response);
			ofy().save().entity(q);
		} catch (Exception e) {}
	}

	static void sendEmailToAdmin(UserReport r, String email) throws Exception {
		String msgBody = r.view();
		if (!email.isEmpty()) msgBody += "Respond to " + email;
		
		if (email==null || email.isEmpty()) return;  // nowhere to send
		if (msgBody.length()==0) return;  // no reports exist
		
		try {
		Util.sendEmail("ChemVantage","admin@chemvantage.org","Sage Feedback Report",msgBody);
		} catch (Exception e) {}
	}
	
	static String submitFeedback(User user, HttpServletRequest request) throws Exception {
		StringBuffer buf = new StringBuffer(Util.head);
		
		String comments = request.getParameter("Comments");
		String nStars = request.getParameter("NStars");
		UserReport r = new UserReport(user.hashedId,nStars,comments);
		ofy().save().entity(r);
		
		String email = request.getParameter("Email");
		if (email != null && !email.isEmpty()) sendEmailToAdmin(r,email);
		
		buf.append("<h1>Thank you</h1>");
		
		return buf.toString() + Util.foot;
	}
}

