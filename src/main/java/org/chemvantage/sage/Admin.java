package org.chemvantage.sage;

import static com.googlecode.objectify.ObjectifyService.ofy;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Date;
import java.util.List;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@WebServlet("/admin")
public class Admin extends HttpServlet {

	private static final long serialVersionUID = 1L;

	public void doGet(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		
		PrintWriter out = response.getWriter();
		response.setContentType("text/html");
		
		String userRequest = request.getParameter("UserRequest");
		if (userRequest == null) userRequest = "";
		
		switch (userRequest ) {
		}	
		out.println(adminPage());
	}
	
	public void doPost(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
	
		String userRequest = request.getParameter("UserRequest");
		if (userRequest == null) userRequest = "";
		
		switch (userRequest ) {
		case "Delete Feedback":
			deleteFeedback(request);
			break;
		}
		doGet(request,response);
	}
	static String adminPage() {
		StringBuffer buf = new StringBuffer(Util.head);
		buf.append("<h1>Admin Page</h1>"
				+ "<h2>Users</h2>"
				+ "Total: " + ofy().load().type(User.class).count() + " users<br/>"
				+ "Expired: " + ofy().load().type(User.class).filter("expired <",new Date()).count() + "<p>"
				+ "<h2>User Feedback</h2>"
				+ viewUserFeedback());
		return buf.toString() + Util.foot;
	}
	
	static String viewUserFeedback() {
		StringBuffer buf = new StringBuffer();
		List<UserReport> reports = ofy().load().type(UserReport.class).order("-submitted").list();
		if (reports.size()==0) return "(none)";
		for (UserReport r : reports) {
			buf.append(r.view() 
					+ "<a href='/questions?UserRequest=EditQuestion&QuestionId=" + r.questionId + "'>Edit Question</a>&nbsp;or&nbsp;"
					+ "<form method=post style='display: inline'>"
					+ "<input type=hidden name=ReportId value='" + r.id + "' />"
					+ "<input type=submit name=UserRequest value='Delete Feedback' />"
					+ "</form><p>"
					+ "<hr>");
		}
		return buf.toString();
	}

	static void deleteFeedback(HttpServletRequest request) {
		try {
			Long reportId = Long.parseLong(request.getParameter("ReportId"));
			ofy().delete().type(UserReport.class).id(reportId).now();
		} catch (Exception e) {}
	}
}
