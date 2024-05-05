package org.chemvantage.sage;

import static com.googlecode.objectify.ObjectifyService.ofy;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
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
		
		try {
			boolean showGraph = Boolean.parseBoolean(request.getParameter("ShowGraph"));
			out.println(adminPage(showGraph));
		} catch (Exception e) {}
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
	static String adminPage(boolean showGraph) throws Exception {
		StringBuffer buf = new StringBuffer(Util.head);
		
		buf.append("<h1>Admin Page</h1>");
		
		buf.append("<h2>Users</h2>"
				+ ofy().load().type(User.class).count() + " users (" + ofy().load().type(User.class).filter("expired",new Date()).count() + " expired)<br/>"
				+ ofy().load().type(Score.class).count() + " scores<br/>");
		
		if (showGraph) {
			// Make a graph of number of scores for each Concept
			List<Concept> concepts = ofy().load().type(Concept.class).order("orderBy").list();
			List<Integer> nScores = new ArrayList<Integer>();
			int maxCount = 0;
			for (Concept c : concepts) {
				int count = ofy().load().type(Score.class).filter("conceptId",c.id).count();
				if (count==0) break;
				nScores.add(concepts.indexOf(c),count);
				if (count > maxCount) maxCount = count;
			}
			int cellWidth = 600/nScores.size();
			if (cellWidth>10) cellWidth=10;

			buf.append("<table><tr>");
			for (int count : nScores) {
				buf.append("<td style='vertical-align:bottom;'><div style='background-color:blue;width:" + cellWidth + "px;height:" + 200*count/maxCount + "px;'></div></td>");
			}
			buf.append("</tr></table>");
		} else {
			buf.append("<a href=/admin?ShowGraph=true>show graph</a>");
		}
		
		buf.append("<h2>Launch Statistics</h2>"
				+ "The following table contains the number of launches having the indicated "
				+ "reCaptcha scores (0.0 - 1.0) and outcomes. Note that 'Invalid Email' is "
				+ "potentially serious because it circumvents email validation on index.html "
				+ "and may indicate a direct attack.<p>" + Launch.getLaunchStats());
		
		
		buf.append("<h2>User Feedback</h2>" + viewUserFeedback());
		
		return buf.toString() + Util.foot;
	}
	
	static String viewUserFeedback() throws Exception {
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

