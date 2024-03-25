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

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;


@WebServlet("/Feedback")
public class Feedback extends HttpServlet {

	private static final long serialVersionUID = 137L;
	
	public String getServletInfo() {
		return "This servlet uses AJAX to receive feedback from users about question items.";
	}

	public void doGet(HttpServletRequest request,HttpServletResponse response)
	throws ServletException, IOException {
		//response.setContentType("text/html");
		//PrintWriter out = response.getWriter();
		
		HttpSession session = request.getSession(false);
		if (session == null) {
			response.sendRedirect("/");
			return;
		}
		String hashedId = (String)session.getAttribute("hashedId");
		
		String userRequest = request.getParameter("UserRequest");
		if (userRequest == null) userRequest = "";

		switch (userRequest) {
		case "ReportAProblem":
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
			UserReport r = new UserReport(hashedId,questionId,params,studentAnswer,notes);
			ofy().save().entity(r);
			sendEmailToAdmin(r,email);
			} catch (Exception e) {
				
			}
			break;
		case "AjaxRating":
			//recordAjaxRating(request);
			break;
		}
	}

	public void doPost(HttpServletRequest request,HttpServletResponse response)
	throws ServletException, IOException {
	}
/*
	String viewUserFeedback(User user) {
		StringBuffer buf = new StringBuffer();
		Query<UserReport> reports = ofy().load().type(UserReport.class).order("-submitted");		
		for (UserReport r : reports) {
			String report = r.view(user);  // returns report only for ChemVantage admins, domainAdmins and report author
			if (report != null) buf.append(report + "<hr>");
		}
		return buf.toString();
	}
*/	
	private void sendEmailToAdmin(UserReport r, String email) {
		String msgBody = r.view();
		if (!email.isEmpty()) msgBody += "Respond to " + email;
		
		if (email==null || email.isEmpty()) return;  // nowhere to send
		if (msgBody.length()==0) return;  // no reports exist
		
		try {
		Util.sendEmail("ChemVantage","admin@chemvantage.org","Sage Feedback Report",msgBody);
		} catch (Exception e) {}
	}
}

