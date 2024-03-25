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
		case "View Feedback":
			out.println(Util.head + viewUserFeedback() + Util.foot);
			break;
		}	
		out.println(Util.head + adminPage() + Util.foot);
	}
	
	static String adminPage() {
		StringBuffer buf = new StringBuffer(Util.head);
		buf.append("<h1>Admin Page</h1>"
				+ "<h2>Users></h2>"
				+ "Total: " + ofy().load().type(User.class).count() + "M<br/>"
				+ "Expired: " + ofy().load().type(User.class).filter("expired <",new Date()).count() + "<p>"
				+ "<h2>User Feedback</h2>"
				+ viewUserFeedback());
		return buf.toString() + Util.foot;
	}
	
	static String viewUserFeedback() {
		StringBuffer buf = new StringBuffer();
		List<UserReport> reports = ofy().load().type(UserReport.class).order("-submitted").list();		
		for (UserReport r : reports) {
			buf.append(r.view() + "<hr>");
		}
		return buf.toString();
	}

}
